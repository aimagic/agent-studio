# -*- coding: utf-8 -*-
"""SwitchNode 分支表达式安全检查回归用例。

覆盖两类问题：
1) 字符串字面量不应被误判（'open' / 'a@b.com' 等业务取值）；
2) 危险标识符必须继续被拦截（不引入安全回归）。
"""
import pytest

from jiuwen.common.exception import JiuWenBaseException
from jiuwen.orchestration.flow.base_workflow import SwitchNode
from jiuwen.orchestration.flow.runtime_context import RuntimeContext


def _build(expression: str):
    node_info = {
        "id": "node_branch",
        "name": "开与关判断",
        "type": "Branch",
        "inputs": {},
        "outputs": {},
        "configs": {"branches": [{"id": "branch_0", "boolExpression": expression}]},
    }
    return SwitchNode(node_info, RuntimeContext())


@pytest.mark.parametrize(
    "expression",
    [
        "(${node_x.userFields.action} == 'open')",
        "(${node_x.userFields.action} == 'OPEN')",
        "(${node_x.userFields.action} == 'close')",
        "(${node_x.userFields.action} == 'os')",
        "(${node_x.userFields.action} == 'input')",
        "(${node_x.userFields.mail} == 'a@b.com')",
        "(${node_x.userFields.note} == 'x#y')",
        "(${node_x.userFields.t} == '2026-10-16 21:00')",
        "((${node_x.userFields.mode} == 'startup') and (is_not_empty(${node_x.userFields.valve_id})))",
        "(length(${node_x.userFields.code}) > 3)",
    ],
)
def test_string_literal_should_pass(expression):
    """字符串字面量的内容不应参与危险词/非法字符判定。"""
    _build(expression)  # 不抛异常即通过


@pytest.mark.parametrize(
    "expression",
    [
        "(os.system('id'))",
        "(__import__('os').system('id'))",
        "(getattr(node_x, 'system')())",
        "(eval('1+1'))",
        "(open('/etc/passwd'))",
        "(vars())",
        "(globals())",
        "(() .__class__.__bases__[0].__subclasses__())",
        "(node_x.__dict__)",
        "(chr(47))",
        "(lambda: 1)",
    ],
)
def test_dangerous_identifier_should_be_blocked(expression):
    """危险标识符仍须被拦截，且异常应携带节点定位信息。"""
    with pytest.raises(JiuWenBaseException) as exc_info:
        _build(expression)
    assert exc_info.value.error_code == 101623
    assert getattr(exc_info.value, "node_id", None) == "node_branch"
    assert "开与关判断" in str(exc_info.value)
