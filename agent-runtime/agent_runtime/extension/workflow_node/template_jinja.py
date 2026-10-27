# coding: utf-8
# Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.

"""Jinja2 沙箱渲染。不依赖工作流引擎，方便单测直接覆盖 SSTI 拦截与资源护栏。"""

from __future__ import annotations

import os
import threading
import time
from collections.abc import Mapping
from typing import Any, Callable, Iterable, Iterator

from jinja2 import nodes
from jinja2.exceptions import SecurityError, TemplateError
from jinja2.runtime import Context
from jinja2.sandbox import SandboxedEnvironment

_FORBIDDEN_TAGS = (nodes.Include, nodes.Import, nodes.FromImport, nodes.Extends)

# 内部会自行迭代的过滤器：入参包进 CountingIterable，使预算/deadline 覆盖 |list|sort|join 等
_ITERATING_FILTERS = frozenset(
    {
        "list",
        "sort",
        "join",
        "map",
        "select",
        "reject",
        "selectattr",
        "rejectattr",
        "unique",
        "dictsort",
        "groupby",
        "sum",
        "min",
        "max",
        "batch",
        "slice",
        "reverse",
        "first",
        "last",
        "random",
    }
)


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
# 含超时后仍存活的 orphan worker；槽位由 worker finally 释放，避免无限堆积线程
MAX_CONCURRENT_RENDERS = _positive_int_env("TEMPLATE_JINJA_MAX_CONCURRENT", 8)

_render_slots = threading.BoundedSemaphore(MAX_CONCURRENT_RENDERS)


class TemplateRenderError(ValueError):
    """模板语法、安全策略、资源护栏或渲染失败。"""


class _RenderBudget:
    """渲染期共享计数：循环迭代次数 + 墙钟截止 + 调用方取消信号。"""

    __slots__ = ("iters", "max_iters", "deadline", "cancel")

    def __init__(
        self,
        max_iters: int,
        deadline: float | None = None,
        cancel: threading.Event | None = None,
    ) -> None:
        self.iters = 0
        self.max_iters = max_iters
        self.deadline = deadline
        self.cancel = cancel

    def check_deadline(self) -> None:
        if self.cancel is not None and self.cancel.is_set():
            raise TemplateRenderError(
                f"template render timed out after {RENDER_TIMEOUT_SEC}s"
            )
        if self.deadline is not None and time.monotonic() >= self.deadline:
            raise TemplateRenderError(
                f"template render timed out after {RENDER_TIMEOUT_SEC}s"
            )

    def consume(self, n: int = 1) -> None:
        self.check_deadline()
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


def _prepare_filter_value(value: Any, budget: _RenderBudget) -> Any:
    """过滤器入参：可迭代对象包进计数器；Mapping 只查 deadline，避免破坏 dict 协议。"""
    if isinstance(value, _CountingIterable):
        return value
    if isinstance(value, Mapping):
        budget.check_deadline()
        return value
    if isinstance(value, (str, bytes, bytearray)):
        return _CountingIterable(value, budget)
    if isinstance(value, Iterable):
        return _CountingIterable(value, budget)
    budget.check_deadline()
    return value


def _wrap_filter_with_budget(original: Callable[..., Any], budget: _RenderBudget) -> Callable[..., Any]:
    pass_arg = getattr(original, "jinja_pass_arg", None)

    def wrapped(*args: Any, **kwargs: Any) -> Any:
        budget.check_deadline()
        # pass_eval_context / pass_environment 时首参是 ctx，值在第二位
        if pass_arg is not None:
            if len(args) < 2:
                return original(*args, **kwargs)
            ctx, value, *rest = args
            return original(ctx, _prepare_filter_value(value, budget), *rest, **kwargs)
        if not args:
            return original(*args, **kwargs)
        value, *rest = args
        return original(_prepare_filter_value(value, budget), *rest, **kwargs)

    wrapped.__name__ = getattr(original, "__name__", "guarded_filter")
    wrapped.__doc__ = getattr(original, "__doc__", None)
    if pass_arg is not None:
        wrapped.jinja_pass_arg = pass_arg  # type: ignore[attr-defined]
    return wrapped


def _install_filter_guards(env: SandboxedEnvironment, budget: _RenderBudget) -> None:
    for name in _ITERATING_FILTERS:
        original = env.filters.get(name)
        if original is None:
            continue
        env.filters[name] = _wrap_filter_with_budget(original, budget)


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


def _const_str(node: nodes.Node) -> str | None:
    if isinstance(node, nodes.Const) and isinstance(node.value, str):
        return node.value
    return None


def _const_int(node: nodes.Node) -> int | None:
    if isinstance(node, nodes.Const) and isinstance(node.value, int):
        return node.value
    return None


def _raise_output_too_long(size: int) -> None:
    raise TemplateRenderError(
        f"template output length {size} exceeds limit {MAX_OUTPUT_CHARS}"
    )


def _product_len_or_raise(base_len: int, times: int) -> int:
    """计算 base_len * times；超限则抛错，避免先分配再检查。"""
    if times <= 0 or base_len <= 0:
        return 0
    if base_len > MAX_OUTPUT_CHARS:
        _raise_output_too_long(base_len)
    # times > MAX // base_len ⇔ base_len * times > MAX（整数安全）
    if times > MAX_OUTPUT_CHARS // base_len:
        # 仅用于错误信息；可能极大，用近似描述避免真乘爆 int 展示
        _raise_output_too_long(MAX_OUTPUT_CHARS + 1)
    return base_len * times


def _guard_string_repeat(left: Any, right: Any) -> None:
    """字符串重复乘法执行前检查最终长度。"""
    if isinstance(left, str) and isinstance(right, int):
        _product_len_or_raise(len(left), right)
        return
    if isinstance(right, str) and isinstance(left, int):
        _product_len_or_raise(len(right), left)


class _GuardedSandboxedEnvironment(SandboxedEnvironment):
    """拦截 *，在 str*int 分配前做输出长度护栏。"""

    intercepted_binops = frozenset(["*"])

    def call_binop(
        self, context: Context, operator: str, left: Any, right: Any
    ) -> Any:
        if operator == "*":
            _guard_string_repeat(left, right)
        return super().call_binop(context, operator, left, right)


def _fold_static_str_len(node: nodes.Node) -> int | None:
    """静态折叠字符串表达式长度；支持链式 Mul(Mul(...), ...)。无法折叠则 None。"""
    text = _const_str(node)
    if text is not None:
        return len(text)
    if not isinstance(node, nodes.Mul):
        return None
    left_len = _fold_static_str_len(node.left)
    right_times = _const_int(node.right)
    if left_len is not None and right_times is not None:
        return _product_len_or_raise(left_len, right_times)
    right_len = _fold_static_str_len(node.right)
    left_times = _const_int(node.left)
    if right_len is not None and left_times is not None:
        return _product_len_or_raise(right_len, left_times)
    return None


def _reject_static_huge_string_mul(parsed: nodes.Template) -> None:
    """解析期拒绝可静态折叠的超大字符串重复（含链式乘法）。"""
    for node in parsed.find_all(nodes.Mul):
        _fold_static_str_len(node)


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
            # 必须用乘法：range(0) 的 size=0 是合法的「零次迭代」，不能当 falsy 丢掉
            next_product = product * size
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
    cancel: threading.Event | None = None,
) -> str:
    env = _GuardedSandboxedEnvironment(autoescape=False)
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

    budget = _RenderBudget(MAX_LOOP_ITERATIONS, deadline=deadline, cancel=cancel)
    env.globals["range"] = _make_guarded_range(budget)
    env.globals["guard_iter"] = _make_guard_iter(budget)
    _install_filter_guards(env, budget)
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
    - 限制 range 大小、for / 迭代类过滤器次数、输出长度与渲染超时
    - 超时用 daemon 线程 + join(timeout)：调用方按时返回；超时后 set cancel，
      for/过滤器路径在 consume 时协作退出；worker 持有并发槽直至结束
    """
    if not isinstance(template, str) or not template.strip():
        raise TemplateRenderError("template must be a non-empty string")
    if not isinstance(variables, dict):
        raise TemplateRenderError("template variables must be a dict")

    slots = _render_slots
    if not slots.acquire(blocking=False):
        raise TemplateRenderError(
            f"template render concurrency limit exceeded ({MAX_CONCURRENT_RENDERS})"
        )

    deadline = time.monotonic() + RENDER_TIMEOUT_SEC
    cancel = threading.Event()
    box: dict[str, Any] = {}

    def _worker() -> None:
        try:
            box["result"] = _render_with_guards(
                template, variables, deadline, cancel
            )
        except Exception as exc:  # noqa: BLE001 — 传回调用线程统一处理
            box["error"] = exc
        finally:
            slots.release()

    thread = threading.Thread(
        target=_worker,
        name="template-jinja-render",
        daemon=True,
    )
    try:
        thread.start()
    except RuntimeError as exc:
        slots.release()
        raise TemplateRenderError(
            f"template render failed to start worker: {exc}"
        ) from exc

    thread.join(timeout=RENDER_TIMEOUT_SEC)
    if thread.is_alive():
        # 通知 worker 在下一次 consume/check_deadline 时退出；不 join 到底
        cancel.set()
        raise TemplateRenderError(
            f"template render timed out after {RENDER_TIMEOUT_SEC}s"
        )
    if "error" in box:
        raise box["error"]
    return box["result"]
