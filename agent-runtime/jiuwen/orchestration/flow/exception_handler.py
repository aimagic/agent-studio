#  Copyright (c) Huawei Technologies Co., Ltd. 2024-2024. All rights reserved.

import os

from jiuwen.orchestration.common.runtime_env import (
    REQUEST_TIMEOUT_MCP,
    REQUEST_TIMEOUT_WORKFLOW,
)
from jiuwen.orchestration.flow.base_workflow import Node
from jiuwen.orchestration.flow.constant import (
    DEFAULT_MCP_TIME_OUT_SECONDS,
    ENVIRONMENT_VARIABLES,
)
from jiuwen.orchestration.flow.enum import NodeType
from jiuwen.orchestration.flow.runtime_context import RuntimeContext

MS_P_S = 1000


def rewrite_timeout(runtime: RuntimeContext, invokable: Node, timeout: float) -> float:
    """
    Rewrite the timeout value based on the node type and environment variables.

    Args:
        runtime (RuntimeContext): The runtime context containing environment variables.
        invokable (Node): The node for which the timeout is being set.
        timeout (float): The default timeout value in seconds.

    Returns:
        float: The rewritten timeout value in seconds. If conditions are not met, the default timeout is returned.
    """

    # 如果节点类型不是 MCP 或 SUB_WORKFLOW，直接返回默认超时时间
    if invokable.type not in {NodeType.MCP.value, NodeType.SUB_WORKFLOW.value}:
        return timeout

    # 获取环境变量
    env = runtime.get(ENVIRONMENT_VARIABLES)
    if env is None:
        return timeout
    # 获取 REQUEST_TIMEOUT_MCP 的值
    if invokable.type == NodeType.MCP.value:
        return _get_mcp_timeout(env, REQUEST_TIMEOUT_MCP, timeout)
    if invokable.type == NodeType.SUB_WORKFLOW.value:
        return _get_timeout(env, REQUEST_TIMEOUT_WORKFLOW, timeout)
    return timeout


def _get_timeout(env: dict, key: str, timeout: float) -> float:
    val = env.get(key)
    if val is None or not isinstance(val, int):
        return timeout

    # 返回转换后的超时时间（毫秒转秒）
    new_timeout = val / MS_P_S
    return max(new_timeout, timeout)


def _parse_mcp_time_out_seconds() -> float:
    """读取 MCP_TIME_OUT_SECONDS 环境变量，非数字或缺失时回退到默认值。"""
    raw = os.getenv("MCP_TIME_OUT_SECONDS")
    if raw is None:
        return DEFAULT_MCP_TIME_OUT_SECONDS
    try:
        return float(raw)
    except (TypeError, ValueError):
        return DEFAULT_MCP_TIME_OUT_SECONDS


def _get_mcp_timeout(env: dict, key: str, timeout: float) -> float:
    """MCP 节点超时：节点配置超时与环境变量超时取小，再以 MCP_TIME_OUT_SECONDS 兜底取小。

    Args:
        env: 环境变量字典。
        key: 环境变量中保存请求超时的键。
        timeout: 节点 exception_config 配置的超时时间（秒）。

    Returns:
        最终超时时间（秒）。三者取小：节点超时、keep-alive 超时、MCP_TIME_OUT_SECONDS 兜底。
        timeout <= 0 时视为无限制，直接返回兜底值。
    """
    # MCP_TIME_OUT_SECONDS 作为兜底上限，默认 900s
    mcp_cap = _parse_mcp_time_out_seconds()

    # timeout <= 0 视为无限制，跳过 min 直接使用兜底值
    if timeout <= 0:
        return mcp_cap

    candidates = [timeout]

    val = env.get(key)
    if val is not None and isinstance(val, int) and val > 0:
        # keep-alive 超时（毫秒转秒）
        candidates.append(val / MS_P_S)

    candidates.append(mcp_cap)

    return min(candidates)
