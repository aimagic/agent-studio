# coding: utf-8
# Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.

"""Jinja2 沙箱渲染。不依赖工作流引擎，方便单测直接覆盖 SSTI 拦截。"""

from __future__ import annotations

from typing import Any

from jinja2 import nodes
from jinja2.exceptions import SecurityError, TemplateError
from jinja2.sandbox import SandboxedEnvironment

_FORBIDDEN_TAGS = (nodes.Include, nodes.Import, nodes.FromImport, nodes.Extends)


class TemplateRenderError(ValueError):
    """模板语法、安全策略或渲染失败。"""


def _has_forbidden_attr_access(parsed: nodes.Template) -> bool:
    """拒绝 AST 中对 _ / __ 开头属性的访问，避免误杀正文里的双下划线文本。"""
    for node in parsed.find_all(nodes.Getattr):
        attr = getattr(node, "attr", None)
        if isinstance(attr, str) and attr.startswith("_"):
            return True
    return False


def render_jinja(template: str, variables: dict[str, Any]) -> str:
    """用 SandboxedEnvironment 渲染。拒绝 include/import/extends，并按 AST 拦住私有属性访问。"""
    if not isinstance(template, str) or not template.strip():
        raise TemplateRenderError("template must be a non-empty string")
    if not isinstance(variables, dict):
        raise TemplateRenderError("template variables must be a dict")

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

    try:
        rendered = env.from_string(template).render(**variables)
    except (SecurityError, TemplateError) as exc:
        raise TemplateRenderError(str(exc)) from exc

    if not isinstance(rendered, str):
        raise TemplateRenderError("template render result must be a string")
    return rendered
