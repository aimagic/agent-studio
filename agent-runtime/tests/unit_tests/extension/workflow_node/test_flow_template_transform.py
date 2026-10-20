import pytest

from agent_runtime.extension.workflow_node.template_jinja import (
    TemplateRenderError,
    render_jinja,
)


def test_render_object_get_and_literal_text():
    text = render_jinja(
        "用户名称：{{ data.get('customerName') }}，户号：{{ data.get('customerCode') }}",
        {"data": {"customerName": "张三", "customerCode": "10086"}},
    )
    assert text == "用户名称：张三，户号：10086"


def test_render_for_and_if():
    text = render_jinja(
        "{% for item in items %}{{ item }}{% if not loop.last %},{% endif %}{% endfor %}",
        {"items": ["a", "b"]},
    )
    assert text == "a,b"


def test_block_dunder_attribute():
    with pytest.raises(TemplateRenderError, match="forbidden dunder access"):
        render_jinja("{{ ''.__class__ }}", {})


def test_block_include_import_extends():
    for template in ("{% include 'x' %}", "{% import 'x' as y %}", "{% extends 'x' %}", "{% from 'x' import y %}"):
        with pytest.raises(TemplateRenderError, match="forbidden jinja tag"):
            render_jinja(template, {})


def test_empty_template_rejected():
    with pytest.raises(TemplateRenderError):
        render_jinja("   ", {})
