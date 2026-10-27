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


def test_literal_double_underscore_text_allowed():
    text = render_jinja("Markdown __bold__ and field __init__ ok", {})
    assert text == "Markdown __bold__ and field __init__ ok"


def test_dict_key_with_dunder_name_allowed():
    text = render_jinja("{{ data.get('__init__') }}", {"data": {"__init__": "ok"}})
    assert text == "ok"


def test_block_dunder_attribute():
    with pytest.raises(TemplateRenderError, match="forbidden dunder access"):
        render_jinja("{{ ''.__class__ }}", {})


def test_block_private_attribute():
    with pytest.raises(TemplateRenderError, match="forbidden dunder access"):
        render_jinja("{{ obj._secret }}", {"obj": type("O", (), {"_secret": "x"})()})


def test_block_include_import_extends():
    for template in ("{% include 'x' %}", "{% import 'x' as y %}", "{% extends 'x' %}", "{% from 'x' import y %}"):
        with pytest.raises(TemplateRenderError, match="forbidden jinja tag"):
            render_jinja(template, {})


def test_empty_template_rejected():
    with pytest.raises(TemplateRenderError):
        render_jinja("   ", {})


def test_nested_huge_range_rejected_before_render():
    """工单复现：嵌套 range(10000)×range(10000) 应在解析阶段拒绝，不拖死进程。"""
    template = (
        "{% for i in range(10000) %}"
        "{% for j in range(10000) %}AAAAA{% endfor %}"
        "{% endfor %}"
    )
    with pytest.raises(TemplateRenderError, match="iteration limit|range\\(\\) size"):
        render_jinja(template, {})


def test_single_range_over_limit_rejected(monkeypatch):
    import agent_runtime.extension.workflow_node.template_jinja as tj

    monkeypatch.setattr(tj, "MAX_RANGE_SIZE", 100)
    with pytest.raises(TemplateRenderError, match="range\\(\\) size"):
        render_jinja("{% for i in range(101) %}x{% endfor %}", {})


def test_loop_iteration_budget_runtime(monkeypatch):
    import agent_runtime.extension.workflow_node.template_jinja as tj

    # range 参数来自变量时静态无法估乘积；运行时计数应拦住
    monkeypatch.setattr(tj, "MAX_RANGE_SIZE", 1000)
    monkeypatch.setattr(tj, "MAX_LOOP_ITERATIONS", 50)
    with pytest.raises(TemplateRenderError, match="loop iterations exceeded"):
        render_jinja(
            "{% for i in range(outer) %}{% for j in range(inner) %}x{% endfor %}{% endfor %}",
            {"outer": 2, "inner": 80},
        )


def test_output_length_limit(monkeypatch):
    import agent_runtime.extension.workflow_node.template_jinja as tj

    monkeypatch.setattr(tj, "MAX_OUTPUT_CHARS", 20)
    monkeypatch.setattr(tj, "MAX_LOOP_ITERATIONS", 10_000)
    with pytest.raises(TemplateRenderError, match="output length"):
        render_jinja("{% for i in range(10) %}abcdefghij{% endfor %}", {})


def test_moderate_nested_loops_still_ok():
    text = render_jinja(
        "{% for i in range(3) %}{% for j in range(2) %}{{ i }}{{ j }};{% endfor %}{% endfor %}",
        {},
    )
    assert text == "00;01;10;11;20;21;"
