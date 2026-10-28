# -*- coding: UTF-8 -*-
"""规划/理解/StepExecute LLM 调用遥测载荷单测（!2524 覆盖面补齐）。

task_plan_module / task_understanding_module 此前不经过 TraceManager，
PlanExecute StepExecute 的 _stream_llm_response 绕过 stream_function_call，
qwen._chat（ainvoke 落点）不打双时间戳——这些路径的 LLM span 均拿不到
用量/时延/finish_reason。本文件锁住补齐后的契约：

- task_plan / task_understanding：镜像 intention_detect 接线，on_llm_end
  载荷按 core handler 约定键名携带 usage_metadata / finish_reason；
- PlanExecute StepExecute：astream finish 块的 usage_metadata 进入载荷；
- qwen._chat：requests_start_time / first_token_time 双时间戳打点，
  批式语义下 TTFT ≈ 整体请求时延（可换算、非负）。
"""

import asyncio
from types import SimpleNamespace

import pytest

from jiuwen.common.llm_service.messages import AIMessage, HumanMessage, UsageMetadata
from jiuwen.planner.common import stream_post_utils as spu
from jiuwen.planner.common.stream_post_utils import (
    FINISH_REASON,
    LATENCY,
    MODEL_USAGE,
    USAGE_METADATA,
    _iso_delta_ms,
)


def _usage(**overrides):
    """构造带完整字段的 UsageMetadata，按需覆盖。"""
    base = dict(
        model_name="qwen-max",
        finish_reason="stop",
        input_tokens=10,
        output_tokens=20,
        total_tokens=30,
        first_token_time="2026-10-23T10:00:00.200+08:00",
        requests_start_time="2026-10-23T10:00:00+08:00",
        cache_tokens=5,
        reasoning_tokens=7,
        input_cost=0.01,
        output_cost=0.02,
        total_cost=0.03,
    )
    base.update(overrides)
    return UsageMetadata(**base)


class StubManager:
    """记录 TraceManager 事件的桩。"""

    def __init__(self):
        self.events = []

    async def on_llm_start(self, messages):
        self.events.append(("start", messages))

    async def on_llm_end(self, payload):
        self.events.append(("end", payload))

    async def on_llm_error(self, error):
        self.events.append(("error", error))


class TestTaskPlanEmit:
    @staticmethod
    def test_plan_emits_usage_latency_finish_reason(monkeypatch):
        """plan() 经 TraceManager 发射：trace_handlers 取自 runtime_data，
        on_llm_end 载荷带约定键名的 usage_metadata / finish_reason。"""
        from jiuwen.controller.task_planner.planning_modules import (
            task_plan_module as tpm,
        )

        stub_manager = StubManager()
        captured = {}

        def fake_create(*args, **kwargs):
            captured.update(kwargs)
            return stub_manager

        monkeypatch.setattr(tpm, "create_trace_manager", fake_create)
        monkeypatch.setattr(tpm, "TemplateManager", lambda: None)

        class StubLLM:
            async def ainvoke(self, messages):
                return AIMessage(
                    content='{"steps": [{"step_idx": 1, "step_name": "s1"}]}',
                    usage_metadata=_usage(),
                )

        stub_llm = StubLLM()
        module = tpm.TaskPlanModule(llm=stub_llm, context_manager=None)
        module._build_system_prompt = lambda: "sys-prompt"
        module._build_user_prompt = (
            lambda query, matched_scene, filtered_tools: "usr-prompt"
        )

        handlers = object()
        plan = asyncio.run(
            module.plan(
                query="q",
                chat_history=[],
                matched_scene=None,
                filtered_tools=[],
                task_id="t1",
                runtime_data={"trace_handlers": handlers},
            )
        )

        # 接线参数：trace_handlers 从 runtime_data 透传
        assert captured["llm"] is stub_llm
        assert captured["functions"] == []
        assert captured["trace_handlers"] is handlers

        # 事件序列与载荷形状
        assert [e[0] for e in stub_manager.events] == ["start", "end"]
        start_msgs = stub_manager.events[0][1]
        assert start_msgs[0] == {"role": "system", "content": "sys-prompt"}
        payload = stub_manager.events[1][1]
        assert payload["content"].startswith('{"steps"')
        assert payload[LATENCY]["total_latency"] >= 0
        assert payload[MODEL_USAGE]["total_tokens"] == 30
        assert payload[USAGE_METADATA]["first_token_time"] == pytest.approx(0.2)
        assert payload[FINISH_REASON] == "stop"

        # 业务行为不因接线改变
        assert len(plan.steps) == 1

    @staticmethod
    def test_plan_without_runtime_data_passes_none(monkeypatch):
        """runtime_data 缺省（调用方兜底为 None）→ trace_handlers=None，正常发射。"""
        from jiuwen.controller.task_planner.planning_modules import (
            task_plan_module as tpm,
        )

        stub_manager = StubManager()
        captured = {}

        def fake_create(*args, **kwargs):
            captured.update(kwargs)
            return stub_manager

        monkeypatch.setattr(tpm, "create_trace_manager", fake_create)
        monkeypatch.setattr(tpm, "TemplateManager", lambda: None)

        class StubLLM:
            async def ainvoke(self, messages):
                return AIMessage(content='{"steps": []}', usage_metadata=_usage())

        module = tpm.TaskPlanModule(llm=StubLLM(), context_manager=None)
        module._build_system_prompt = lambda: "sys-prompt"
        module._build_user_prompt = (
            lambda query, matched_scene, filtered_tools: "usr-prompt"
        )

        asyncio.run(
            module.plan(
                query="q",
                chat_history=[],
                matched_scene=None,
                filtered_tools=[],
            )
        )
        assert captured["trace_handlers"] is None
        assert [e[0] for e in stub_manager.events] == ["start", "end"]

    @staticmethod
    def test_plan_llm_error_emits_error_event(monkeypatch):
        """LLM 调用失败 → on_llm_error 发射 + 原有 raise 行为保留。"""
        from jiuwen.controller.task_planner.planning_modules import (
            task_plan_module as tpm,
        )

        stub_manager = StubManager()
        monkeypatch.setattr(
            tpm, "create_trace_manager", lambda *args, **kwargs: stub_manager
        )
        monkeypatch.setattr(tpm, "TemplateManager", lambda: None)

        class StubLLM:
            async def ainvoke(self, messages):
                raise RuntimeError("llm down")

        module = tpm.TaskPlanModule(llm=StubLLM(), context_manager=None)
        module._build_system_prompt = lambda: "sys-prompt"
        module._build_user_prompt = (
            lambda query, matched_scene, filtered_tools: "usr-prompt"
        )

        with pytest.raises(RuntimeError):
            asyncio.run(
                module.plan(query="q", chat_history=[], matched_scene=None, filtered_tools=[])
            )
        kinds = [e[0] for e in stub_manager.events]
        assert kinds == ["start", "error"]
        assert isinstance(stub_manager.events[-1][1], RuntimeError)


class TestTaskUnderstandingEmit:
    @staticmethod
    def test_invoke_emits_usage_latency_finish_reason(monkeypatch):
        """invoke() 经 TraceManager 发射，复杂意图判断行为不变。"""
        from jiuwen.controller.task_planner.planning_modules import (
            task_understanding_module as tum,
        )

        stub_manager = StubManager()
        captured = {}

        def fake_create(*args, **kwargs):
            captured.update(kwargs)
            return stub_manager

        monkeypatch.setattr(tum, "create_trace_manager", fake_create)
        monkeypatch.setattr(tum, "TemplateManager", lambda: None)

        class StubLLM:
            async def ainvoke(self, messages):
                return AIMessage(content="1", usage_metadata=_usage())

        module = tum.TaskUnderstandingModule(llm=StubLLM(), context_manager=None)
        module._build_system_prompt = lambda: "sys-prompt"
        module._build_user_prompt = (
            lambda query, chat_history, available_intents: "usr-prompt"
        )

        handlers = object()
        result = asyncio.run(
            module.invoke(
                query="还信用卡账单",
                chat_history=[],
                available_intents=["查余额"],
                task_id="t2",
                runtime_data={"trace_handlers": handlers},
            )
        )

        assert captured["trace_handlers"] is handlers
        assert [e[0] for e in stub_manager.events] == ["start", "end"]
        payload = stub_manager.events[1][1]
        assert payload["content"] == "1"
        assert payload[USAGE_METADATA]["first_token_time"] == pytest.approx(0.2)
        assert payload[MODEL_USAGE]["input_tokens"] == 10
        assert payload[FINISH_REASON] == "stop"
        # 业务行为不因接线改变
        assert result.is_complex is True

    @staticmethod
    def test_invoke_llm_error_emits_error_and_falls_back(monkeypatch):
        """LLM 调用失败 → on_llm_error 发射 + 原有兜底（is_complex=False）保留。"""
        from jiuwen.controller.task_planner.planning_modules import (
            task_understanding_module as tum,
        )

        stub_manager = StubManager()
        monkeypatch.setattr(
            tum, "create_trace_manager", lambda *args, **kwargs: stub_manager
        )
        monkeypatch.setattr(tum, "TemplateManager", lambda: None)

        class StubLLM:
            async def ainvoke(self, messages):
                raise RuntimeError("llm down")

        module = tum.TaskUnderstandingModule(llm=StubLLM(), context_manager=None)
        module._build_system_prompt = lambda: "sys-prompt"
        module._build_user_prompt = (
            lambda query, chat_history, available_intents: "usr-prompt"
        )

        result = asyncio.run(
            module.invoke(query="q", chat_history=[], available_intents=[])
        )
        assert result.is_complex is False
        kinds = [e[0] for e in stub_manager.events]
        assert kinds == ["start", "error"]


class TestPlanExecuteStreamLlmEmit:
    @staticmethod
    def _make_mode(monkeypatch, stub_manager, model):
        """绕过重量级 __init__，只装配 _stream_llm_response 依赖的属性。

        方法内部 from stream_post_utils import create_trace_manager 在
        调用时解析模块属性，故打桩打在 spu 命名空间。
        """
        from jiuwen.controller.agent.control_mode.plan_execute_mode import (
            PlanExecuteMode,
        )

        monkeypatch.setattr(
            spu, "create_trace_manager", lambda *args, **kwargs: stub_manager
        )
        mode = object.__new__(PlanExecuteMode)
        mode.task_id = "t-pe"
        mode.model = model
        mode._trace_handlers = None
        return mode

    @staticmethod
    def test_stream_llm_response_emits_payload(monkeypatch):
        """astream finish 块的 usage_metadata 进入 on_llm_end 载荷。"""

        class StubModel:
            # 生产链路 self.model.astream 同步返回异步生成器
            def astream(self, inputs, **kwargs):
                async def gen():
                    yield AIMessage(content="he", usage_metadata=UsageMetadata())
                    yield AIMessage(content="llo", usage_metadata=UsageMetadata())
                    yield AIMessage(content="done", usage_metadata=_usage())

                return gen()

        stub_manager = StubManager()
        mode = TestPlanExecuteStreamLlmEmit._make_mode(
            monkeypatch, stub_manager, StubModel()
        )

        llm_output = asyncio.run(
            mode._stream_llm_response(
                [{"role": "user", "content": "hi"}], []
            )
        )

        # 业务行为不变：finish 块内容覆盖拼装内容
        assert llm_output["content"] == "done"
        assert [e[0] for e in stub_manager.events] == ["start", "end"]
        payload = stub_manager.events[1][1]
        assert payload["content"] == "done"
        assert payload[LATENCY]["total_latency"] >= 0
        assert payload[MODEL_USAGE]["total_tokens"] == 30
        assert payload[USAGE_METADATA]["first_token_time"] == pytest.approx(0.2)
        assert payload[FINISH_REASON] == "stop"

    @staticmethod
    def test_stream_llm_response_error_emits_error_event(monkeypatch):
        """流式调用失败 → on_llm_error 发射 + 原有吞错（error 键）保留。"""

        class StubModel:
            def astream(self, inputs, **kwargs):
                raise RuntimeError("stream down")

        stub_manager = StubManager()
        mode = TestPlanExecuteStreamLlmEmit._make_mode(
            monkeypatch, stub_manager, StubModel()
        )

        llm_output = asyncio.run(
            mode._stream_llm_response(
                [{"role": "user", "content": "hi"}], []
            )
        )
        assert llm_output["content"] == ""
        assert llm_output["error"] == "stream down"
        kinds = [e[0] for e in stub_manager.events]
        assert kinds == ["start", "error"]


class TestQwenChatTimestamps:
    @staticmethod
    def test_chat_stamps_dual_timestamps(monkeypatch):
        """qwen._chat（ainvoke 落点）打双时间戳：批式语义下首 token 即
        完整响应到达，TTFT 可换算且非负（≈ 整体请求时延）。"""
        from jiuwen.common.llm_service.language_model import qwen as qwen_module
        from jiuwen.common.llm_service.language_model.base import BaseChatModel

        # 假 self：_request_params 直接挂在实例上，绕开 Crypt/TemplateManager；
        # token 抽取用基类真实现（静态方法），api_base/model_name 供
        # requests.post 的 url 与 dict.get 默认值引用
        fake_self = SimpleNamespace(
            api_base="http://unit-test",
            model_name="qwen-max",
            _request_params=lambda messages, tools: ({"model": "qwen-max"}, {}),
            _extract_tokens_from_response=BaseChatModel._extract_tokens_from_response,
        )

        class FakeResponse:
            status_code = 200

            @staticmethod
            def json():
                return {
                    "id": "task-1",
                    "model": "qwen-max",
                    "choices": [
                        {"finish_reason": "stop", "message": {"content": "hi"}}
                    ],
                    "usage": {
                        "prompt_tokens": 3,
                        "completion_tokens": 4,
                        "total_tokens": 7,
                    },
                }

        captured = {}

        def fake_post(**kwargs):
            captured.update(kwargs)
            return FakeResponse()

        monkeypatch.setattr(qwen_module.requests, "post", fake_post)

        result = qwen_module.Qwen._chat(
            fake_self, [HumanMessage(content="hi")]
        )

        assert captured["json"]["model"] == "qwen-max"
        assert isinstance(result, AIMessage)
        usage = result.usage_metadata
        # 既有行为不回退：token 抽取与 finish_reason 保持
        assert (usage.input_tokens, usage.output_tokens, usage.total_tokens) == (3, 4, 7)
        assert usage.finish_reason == "stop"
        # 新增：双时间戳在位且可换算
        assert usage.requests_start_time
        assert usage.first_token_time
        delta_ms = _iso_delta_ms(usage.first_token_time, usage.requests_start_time)
        assert delta_ms is not None
        assert delta_ms >= 0
