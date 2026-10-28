# coding: utf-8
# Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.

"""AgentCoreMcpLayer options / legacy kwargs 兼容契约最小覆盖。"""

from __future__ import annotations

import pytest

from jiuwen.integration.agent_core_mcp_new import (
    AgentCoreMcpLayer,
    AgentCoreMcpLayerOptions,
)


class _DummyRuntime:
    """避免构造时拉起真实 MCP ToolWrapper。"""


def test_legacy_kwargs_construct_mcp_api():
    layer = AgentCoreMcpLayer(
        runtime=_DummyRuntime(),
        name="tool-a",
        description="desc-a",
        server_url="http://example",
        server_name="srv",
        transport_type="sse",
        parameters={"p": 1},
    )
    assert layer.mcp_api.name == "tool-a"
    assert layer.mcp_api.description == "desc-a"
    assert layer.mcp_api.server_url == "http://example"
    assert layer.mcp_api.server_name == "srv"
    assert layer.mcp_api.transport_type == "sse"
    assert layer.mcp_api.parameters == {"p": 1}


def test_options_only_construct_mcp_api():
    opts = AgentCoreMcpLayerOptions(
        name="from-opts",
        description="opts-desc",
        server_url="http://opts",
    )
    layer = AgentCoreMcpLayer(runtime=_DummyRuntime(), options=opts)
    assert layer.mcp_api.name == "from-opts"
    assert layer.mcp_api.description == "opts-desc"
    assert layer.mcp_api.server_url == "http://opts"


def test_legacy_kwargs_override_options():
    """options 与 legacy 同时传入时，显式 legacy 关键字优先。"""
    opts = AgentCoreMcpLayerOptions(
        name="from-opts",
        description="opts-desc",
        server_url="http://opts",
    )
    layer = AgentCoreMcpLayer(
        runtime=_DummyRuntime(),
        options=opts,
        name="from-legacy",
        description="legacy-desc",
    )
    assert layer.mcp_api.name == "from-legacy"
    assert layer.mcp_api.description == "legacy-desc"
    # 未在 legacy 中覆盖的字段仍来自 options
    assert layer.mcp_api.server_url == "http://opts"


def test_unknown_legacy_kwargs_raise_type_error():
    with pytest.raises(TypeError, match="unexpected keyword argument"):
        AgentCoreMcpLayer(runtime=_DummyRuntime(), foo=1, bar=2)


def test_merge_options_legacy_wins_unit():
    """直接覆盖 _merge_options 优先级，不依赖 mcp_api 副作用。"""
    opts = AgentCoreMcpLayerOptions(name="opts", description="d-opts", server_url="u")
    merged = AgentCoreMcpLayer._merge_options(opts, {"name": "legacy"})
    assert merged.name == "legacy"
    assert merged.description == "d-opts"
    assert merged.server_url == "u"
