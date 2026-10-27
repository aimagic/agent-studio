# coding: utf-8
# Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.

"""Jinja2 沙箱渲染。不依赖工作流引擎，方便单测直接覆盖 SSTI 拦截与资源护栏。"""

from __future__ import annotations

import os
import threading
import time
from typing import Any, Iterable, Iterator

from jinja2 import nodes
from jinja2.exceptions import SecurityError, TemplateError
from jinja2.sandbox import SandboxedEnvironment

_FORBIDDEN_TAGS = (nodes.Include, nodes.Import, nodes.FromImport, nodes.Extends)


def _positive_int_env(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


# 可被环境变量覆盖，便于现场调参；默认挡住工单里 1e4×1e4 这类拖死进程的模板。
MAX_RANGE_SIZE = _positive_int_env("TEMPLATE_JINJA_MAX_RANGE", 10_000)
MAX_LOOP_ITERATIONS = _positive_int_env("TEMPLATE_JINJA_MAX_LOOP_ITERS", 100_000)
MAX_OUTPUT_CHARS = _positive_int_env("TEMPLATE_JINJA_MAX_OUTPUT_CHARS", 1_000_000)
RENDER_TIMEOUT_SEC = _positive_int_env("TEMPLATE_JINJA_TIMEOUT_SEC", 10)


class TemplateRenderError(ValueError):
    """模板语法、安全策略、资源护栏或渲染失败。"""


class _RenderBudget:
    """渲染期共享计数：循环迭代次数 + 可选墙钟截止时间。"""

    __slots__ = ("iters", "max_iters", "deadline")

    def __init__(self, max_iters: int, deadline: float | None = None) -> None:
        self.iters = 0
        self.max_iters = max_iters
        self.deadline = deadline

    def consume(self, n: int = 1) -> None:
        if self.deadline is not None and time.monotonic() >= self.deadline:
            raise TemplateRenderError(
                f"template render timed out after {RENDER_TIMEOUT_SEC}s"
            )
        self.iters += n
        if self.iters > self.max_iters:
            raise TemplateRenderError(
                f"template loop iterations exceeded limit {self.max_iters}"
            )


class _CountingIterable:
    """对 for 可迭代对象计数，超限即抛错（含 list/dict 与 range）。"""

    def __init__(self, iterable: Iterable[Any], budget: _RenderBudget) -> None:
        self._iterable = iterable
        self._budget = budget

    def __iter__(self) -> Iterator[Any]:
        for item in self._iterable:
            self._budget.consume()
            yield item

    def __len__(self) -> int:
        return len(self._iterable)  # type: ignore[arg-type]


def _make_guard_iter(budget: _RenderBudget):
    """包装任意 for 目标；已是 _CountingIterable 则不重复计数。"""

    def guard_iter(obj: Any) -> Any:
        if isinstance(obj, _CountingIterable):
            return obj
        return _CountingIterable(obj, budget)

    return guard_iter


def _make_guarded_range(budget: _RenderBudget):
    def guarded_range(*args: int):
        try:
            r = range(*args)
        except TypeError as exc:
            raise TemplateRenderError(f"invalid range(): {exc}") from exc
        size = len(r)
        if size > MAX_RANGE_SIZE:
            raise TemplateRenderError(
                f"range() size {size} exceeds limit {MAX_RANGE_SIZE}"
            )
        return _CountingIterable(r, budget)

    return guarded_range


def _make_guarded_concat():
    """渲染拼接时累计长度，避免只在最终结果上做事后检查。"""

    def guarded_concat(pieces: Iterable[Any]) -> str:
        parts: list[str] = []
        total = 0
        for piece in pieces:
            if piece is None:
                continue
            text = piece if isinstance(piece, str) else str(piece)
            total += len(text)
            if total > MAX_OUTPUT_CHARS:
                raise TemplateRenderError(
                    f"template output length {total} exceeds limit {MAX_OUTPUT_CHARS}"
                )
            parts.append(text)
        return "".join(parts)

    return guarded_concat


def _make_guarded_finalize():
    """每个输出表达式落地前检查长度，尽早拒绝超大中间产物。"""

    def guarded_finalize(value: Any) -> Any:
        if isinstance(value, str) and len(value) > MAX_OUTPUT_CHARS:
            raise TemplateRenderError(
                f"template output length {len(value)} exceeds limit {MAX_OUTPUT_CHARS}"
            )
        return value

    return guarded_finalize


def _reject_static_huge_string_mul(parsed: nodes.Template) -> None:
    """解析期拒绝字面量字符串重复（如 'x' * 1e7），避免渲染时先撑爆内存。"""

    def _mul_len(left: nodes.Node, right: nodes.Node) -> int | None:
        if (
            isinstance(left, nodes.Const)
            and isinstance(left.value, str)
            and isinstance(right, nodes.Const)
            and isinstance(right.value, int)
        ):
            if right.value <= 0:
                return 0
            return len(left.value) * right.value
        if (
            isinstance(right, nodes.Const)
            and isinstance(right.value, str)
            and isinstance(left, nodes.Const)
            and isinstance(left.value, int)
        ):
            if left.value <= 0:
                return 0
            return len(right.value) * left.value
        return None

    for node in parsed.find_all(nodes.Mul):
        size = _mul_len(node.left, node.right)
        if size is not None and size > MAX_OUTPUT_CHARS:
            raise TemplateRenderError(
                f"template output length {size} exceeds limit {MAX_OUTPUT_CHARS}"
            )


def _wrap_for_iters_with_guard(parsed: nodes.Template) -> None:
    """把每个 for 的可迭代表达式包进 guard_iter(...)，list/dict 等也会计入预算。"""
    for for_node in list(parsed.find_all(nodes.For)):
        for_node.iter = nodes.Call(
            nodes.Name("guard_iter", "load"),
            [for_node.iter],
            [],
            None,
            None,
        )


def _has_forbidden_attr_access(parsed: nodes.Template) -> bool:
    """拒绝 AST 中对 _ / __ 开头属性的访问，避免误杀正文里的双下划线文本。"""
    for node in parsed.find_all(nodes.Getattr):
        attr = getattr(node, "attr", None)
        if isinstance(attr, str) and attr.startswith("_"):
            return True
    return False


def _const_range_len(call: nodes.Call) -> int | None:
    """若 call 是 range(常量...)，返回长度；否则 None。"""
    node = call.node
    if not isinstance(node, nodes.Name) or node.name != "range":
        return None
    args: list[int] = []
    for arg in call.args:
        if not isinstance(arg, nodes.Const) or not isinstance(arg.value, int):
            return None
        args.append(arg.value)
    if call.kwargs or call.dyn_args or call.dyn_kwargs:
        return None
    try:
        return len(range(*args))
    except (TypeError, ValueError):
        return None


def _reject_static_nested_range_blowup(parsed: nodes.Template) -> None:
    """嵌套 for + 常量 range 时，若乘积超过迭代上限则在解析阶段拒绝。"""

    def walk(node: nodes.Node, product: int) -> None:
        if isinstance(node, nodes.For):
            iter_node = node.iter
            size = 1
            if isinstance(iter_node, nodes.Call):
                const_len = _const_range_len(iter_node)
                if const_len is not None:
                    size = const_len
                    if size > MAX_RANGE_SIZE:
                        raise TemplateRenderError(
                            f"range() size {size} exceeds limit {MAX_RANGE_SIZE}"
                        )
            next_product = product * size if product else size
            if next_product > MAX_LOOP_ITERATIONS:
                raise TemplateRenderError(
                    f"nested range loops would exceed iteration limit {MAX_LOOP_ITERATIONS}"
                )
            for child in node.iter_child_nodes():
                walk(child, next_product)
            return
        for child in node.iter_child_nodes():
            walk(child, product)

    walk(parsed, 1)


def _render_with_guards(
    template: str,
    variables: dict[str, Any],
    deadline: float | None = None,
) -> str:
    env = SandboxedEnvironment(autoescape=False)
    try:
        parsed = env.parse(template)
    except TemplateError as exc:
        raise TemplateRenderError(str(exc)) from exc

    forbidden = next(parsed.find_all(_FORBIDDEN_TAGS), None)
    if forbidden is not None:
        raise TemplateRenderError(f"forbidden jinja tag: {type(forbidden).__name__}")

    if _has_forbidden_attr_access(parsed):
        raise TemplateRenderError("forbidden dunder access")

    # 静态检查必须在 AST 改写之前（改写后 for 目标变成 guard_iter(...)）
    _reject_static_nested_range_blowup(parsed)
    _reject_static_huge_string_mul(parsed)
    _wrap_for_iters_with_guard(parsed)

    budget = _RenderBudget(MAX_LOOP_ITERATIONS, deadline=deadline)
    env.globals["range"] = _make_guarded_range(budget)
    env.globals["guard_iter"] = _make_guard_iter(budget)
    env.concat = _make_guarded_concat()
    env.finalize = _make_guarded_finalize()

    try:
        code = env.compile(parsed)
        tmpl = env.template_class.from_code(env, code, env.make_globals(None), None)
        rendered = tmpl.render(**variables)
    except (SecurityError, TemplateError) as exc:
        # TemplateRenderError（护栏主动抛出）不在此捕获，原样向上传播
        raise TemplateRenderError(str(exc)) from exc

    if not isinstance(rendered, str):
        raise TemplateRenderError("template render result must be a string")
    # concat 已在渲染中拦截；此处兜底单表达式直接产出超长字符串的路径
    if len(rendered) > MAX_OUTPUT_CHARS:
        raise TemplateRenderError(
            f"template output length {len(rendered)} exceeds limit {MAX_OUTPUT_CHARS}"
        )
    return rendered


def render_jinja(template: str, variables: dict[str, Any]) -> str:
    """用 SandboxedEnvironment 渲染。

    - 拒绝 include/import/extends，并按 AST 拦住私有属性访问（SSTI）
    - 限制 range 大小、所有 for 迭代次数（含 list/dict）、输出长度与渲染超时
    - 超时用 daemon 线程 + join(timeout)：调用方按时返回，不 wait 工作线程；
      循环内靠 budget.deadline / 迭代上限协作退出（Python 无法强杀线程；
      不做进程隔离，避免本次改动过重）
    """
    if not isinstance(template, str) or not template.strip():
        raise TemplateRenderError("template must be a non-empty string")
    if not isinstance(variables, dict):
        raise TemplateRenderError("template variables must be a dict")

    deadline = time.monotonic() + RENDER_TIMEOUT_SEC
    box: dict[str, Any] = {}

    def _worker() -> None:
        try:
            box["result"] = _render_with_guards(template, variables, deadline)
        except Exception as exc:  # noqa: BLE001 — 传回调用线程统一处理
            box["error"] = exc

    thread = threading.Thread(
        target=_worker,
        name="template-jinja-render",
        daemon=True,
    )
    thread.start()
    thread.join(timeout=RENDER_TIMEOUT_SEC)
    if thread.is_alive():
        # 不 join 到底：避免 ThreadPoolExecutor shutdown(wait=True) 同类卡死
        raise TemplateRenderError(
            f"template render timed out after {RENDER_TIMEOUT_SEC}s"
        )
    if "error" in box:
        raise box["error"]
    return box["result"]
