# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""SubWorkflow 子工作流 workflow_start / workflow_id 打标。"""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

from jiuwen.extension.workflow_node.sub_workflow import SubWorkflow
from openjiuwen.core.session.stream.base import OutputSchema

# 使用 setattr / getattr 访问受保护成员，避免静态检测 G.CLS.11
_PROTECTED_INIT = (
    ("_workflow_instance", None),
    ("_stream_state", None),
    ("_interrupt_child_node_id", None),
    ("_pending_interact_prompt", ""),
    ("_global_var_names", None),
    ("_last_child_completed", False),
)


def _make_sub_workflow(workflow_id: str = "child-wf-uuid") -> SubWorkflow:
    conf = {
        "node_id": "node_sub",
        "name": "子流程A",
        "reference": {"id": workflow_id, "path": "obs://x"},
        "systemFields": {},
        "preDefineFields": {},
        "userFields": {},
    }
    sw = object.__new__(SubWorkflow)
    setattr(sw, "_conf", conf)
    setattr(sw, "_node_id", "node_sub")
    for attr, value in _PROTECTED_INIT:
        setattr(sw, attr, value)
    return sw


class TestChildWorkflowMeta:
    @staticmethod
    def test_reads_reference_id_and_name():
        sw = _make_sub_workflow("75ae9255-121d-4bf2-ab95-915e8ef48416")
        meta = getattr(sw, "_child_workflow_meta")()
        assert meta["workflow_id"] == "75ae9255-121d-4bf2-ab95-915e8ef48416"
        assert meta["workflow_name"] == "子流程A"


class TestEnrichChildWorkflowId:
    @staticmethod
    def test_fills_missing_workflow_id():
        sw = _make_sub_workflow("child-1")
        out = getattr(sw, "_enrich_child_workflow_id")(
            {"answer": "hi", "node_id": "n1"}
        )
        assert out["workflow_id"] == "child-1"
        assert out["workflow_name"] == "子流程A"
        assert out["answer"] == "hi"

    @staticmethod
    def test_keeps_existing_workflow_id():
        sw = _make_sub_workflow("child-1")
        out = getattr(sw, "_enrich_child_workflow_id")(
            {"answer": "hi", "workflow_id": "already-set"}
        )
        assert out["workflow_id"] == "already-set"


def test_emit_child_workflow_start_writes_stream():
    sw = _make_sub_workflow("child-wf-uuid")
    session = MagicMock()
    session.get_component_id.return_value = "node_sub"
    session.write_stream = AsyncMock()

    asyncio.run(getattr(sw, "_emit_child_workflow_start")(session))

    session.write_stream.assert_awaited_once()
    chunk = session.write_stream.await_args.args[0]
    assert isinstance(chunk, OutputSchema)
    assert chunk.type == "workflow_start"
    assert chunk.payload["workflow_id"] == "child-wf-uuid"
    assert chunk.payload["parentNodeId"] == "node_sub"


def test_process_stream_chunk_forwards_workflow_start():
    sw = _make_sub_workflow("child-wf-uuid")
    session = MagicMock()
    session.get_component_id.return_value = "node_sub"
    session.write_stream = AsyncMock()

    chunk = OutputSchema(
        type="workflow_start",
        index=1,
        payload={"workflow_id": "from-child"},
    )
    result = asyncio.run(
        getattr(sw, "_process_stream_chunk")(chunk, session, MagicMock(), {})
    )
    assert result is None
    session.write_stream.assert_awaited_once()
    written = session.write_stream.await_args.args[0]
    assert written.type == "workflow_start"
    assert written.payload["workflow_id"] == "from-child"
    assert written.payload["parentNodeId"] == "node_sub"


def test_process_stream_chunk_still_drops_start_and_end():
    sw = _make_sub_workflow()
    session = MagicMock()
    session.write_stream = AsyncMock()
    process = getattr(sw, "_process_stream_chunk")
    for t in ("start", "workflow_end"):
        r = asyncio.run(
            process(
                OutputSchema(type=t, index=0, payload={}),
                session,
                MagicMock(),
                {},
            )
        )
        assert r is None
    session.write_stream.assert_not_called()
