# coding: utf-8
# Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.

"""Jinja2 沙箱渲染。不依赖工作流引擎，方便单测直接覆盖 SSTI 拦截与资源护栏。"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturesTimeout
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
    """渲染期共享计数：循环迭代次数。"""

    __slots__ = ("iters", "max_iters")

    def __init__(self, max_iters: int) -> None:
        self.iters = 0
        self.max_iters = max_iters

    def consume(self, n: int = 1) -> None:
        self.iters += n
        if self.iters > self.max_iters:
            raise TemplateRenderError(
                f"template loop iterations exceeded limit {self.max_iters}"
            )


class _CountingIterable:
    """对 for 可迭代对象计数，超限即抛错，避免嵌套大 range 拖死进程。"""

    def __init__(self, iterable: Iterable[Any], budget: _RenderBudget) -> None:
        self._iterable = iterable
        self._budget = budget

    def __iter__(self) -> Iterator[Any]:
        for item in self._iterable:
            self._budget.consume()
            yield item

    def __len__(self) -> int:
        return len(self._iterable)  # type: ignore[arg-type]


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


def _render_with_guards(template: str, variables: dict[str, Any]) -> str:
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

    _reject_static_nested_range_blowup(parsed)

    budget = _RenderBudget(MAX_LOOP_ITERATIONS)
    env.globals["range"] = _make_guarded_range(budget)

    try:
        rendered = env.from_string(template).render(**variables)
    except TemplateRenderError:
        raise
    except (SecurityError, TemplateError) as exc:
        raise TemplateRenderError(str(exc)) from exc

    if not isinstance(rendered, str):
        raise TemplateRenderError("template render result must be a string")
    if len(rendered) > MAX_OUTPUT_CHARS:
        raise TemplateRenderError(
            f"template output length {len(rendered)} exceeds limit {MAX_OUTPUT_CHARS}"
        )
    return rendered


def render_jinja(template: str, variables: dict[str, Any]) -> str:
    """用 SandboxedEnvironment 渲染。

    - 拒绝 include/import/extends，并按 AST 拦住私有属性访问（SSTI）
    - 限制 range 大小、总循环迭代次数、输出长度与渲染超时，避免拖死 runtime
    """
    if not isinstance(template, str) or not template.strip():
        raise TemplateRenderError("template must be a non-empty string")
    if not isinstance(variables, dict):
        raise TemplateRenderError("template variables must be a dict")

    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(_render_with_guards, template, variables)
        try:
            return future.result(timeout=RENDER_TIMEOUT_SEC)
        except FuturesTimeout as exc:
            raise TemplateRenderError(
                f"template render timed out after {RENDER_TIMEOUT_SEC}s"
            ) from exc
        except TemplateRenderError:
            raise
        except Exception as exc:  # noqa: BLE001 — 统一成 TemplateRenderError
            raise TemplateRenderError(str(exc)) from exc
