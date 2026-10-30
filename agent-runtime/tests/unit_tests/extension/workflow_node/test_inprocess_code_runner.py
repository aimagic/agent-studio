#!/usr/bin/env python
# -*- coding: UTF-8 -*-
"""InprocessCodeRunner 单元测试"""

import pytest

from agent_runtime.extension.workflow_node.code_runner.inprocess_code_runner import (
    InprocessCodeRunner,
)
from openjiuwen.core.common.exception.errors import BaseError


class TestInprocessCodeRunnerBasicExecution:
    """基本执行测试"""

    @staticmethod
    @pytest.mark.asyncio
    async def test_returns_dict():
        runner = InprocessCodeRunner()
        code = "def main(args): return {'key': args.get('input', '')}"
        result = await runner.run(user_code=code, inputs={"input": "hello"})
        assert result == {"key": "hello"}

    @staticmethod
    @pytest.mark.asyncio
    async def test_returns_empty_dict():
        runner = InprocessCodeRunner()
        code = "def main(args): return {}"
        result = await runner.run(user_code=code, inputs={})
        assert result == {}

    @staticmethod
    @pytest.mark.asyncio
    async def test_passes_all_inputs():
        runner = InprocessCodeRunner()
        code = "def main(args): return args"
        inputs = {"a": 1, "b": "two", "c": [3, 4]}
        result = await runner.run(user_code=code, inputs=inputs)
        assert result == inputs


class TestInprocessCodeRunnerConsoleLog:
    """console_log（function_log）捕获测试"""

    @staticmethod
    @pytest.mark.asyncio
    async def test_print_captured_to_function_log():
        runner = InprocessCodeRunner()
        code = "def main(args):\n    print('hello from code')\n    return {}"
        await runner.run(user_code=code, inputs={})
        assert "hello from code" in runner.function_log

    @staticmethod
    @pytest.mark.asyncio
    async def test_multiple_prints_captured():
        runner = InprocessCodeRunner()
        code = "def main(args):\n    print('line1')\n    print('line2')\n    return {}"
        await runner.run(user_code=code, inputs={})
        assert "line1" in runner.function_log
        assert "line2" in runner.function_log

    @staticmethod
    @pytest.mark.asyncio
    async def test_no_print_gives_empty_function_log():
        runner = InprocessCodeRunner()
        code = "def main(args): return {}"
        await runner.run(user_code=code, inputs={})
        assert runner.function_log == ""


class TestInprocessCodeRunnerReturnValueValidation:
    """返回值类型校验测试"""

    @staticmethod
    @pytest.mark.asyncio
    async def test_non_dict_return_raises_error():
        runner = InprocessCodeRunner()
        code = "def main(args): return 'not a dict'"
        with pytest.raises(BaseError):
            await runner.run(user_code=code, inputs={})

    @staticmethod
    @pytest.mark.asyncio
    async def test_list_return_raises_error():
        runner = InprocessCodeRunner()
        code = "def main(args): return [1, 2, 3]"
        with pytest.raises(BaseError):
            await runner.run(user_code=code, inputs={})

    @staticmethod
    @pytest.mark.asyncio
    async def test_none_return_raises_error():
        runner = InprocessCodeRunner()
        code = "def main(args): return None"
        with pytest.raises(BaseError):
            await runner.run(user_code=code, inputs={})


class TestInprocessCodeRunnerExceptionPropagation:
    """异常传播测试"""

    @staticmethod
    @pytest.mark.asyncio
    async def test_runtime_error_propagated():
        runner = InprocessCodeRunner()
        code = "def main(args): raise RuntimeError('test error')"
        with pytest.raises(RuntimeError, match="test error"):
            await runner.run(user_code=code, inputs={})

    @staticmethod
    @pytest.mark.asyncio
    async def test_value_error_propagated():
        runner = InprocessCodeRunner()
        code = "def main(args): raise ValueError('bad value')"
        with pytest.raises(ValueError, match="bad value"):
            await runner.run(user_code=code, inputs={})

    @staticmethod
    @pytest.mark.asyncio
    async def test_exception_traceback_in_function_log():
        runner = InprocessCodeRunner()
        code = "def main(args): raise RuntimeError('oops')"
        with pytest.raises(RuntimeError):
            await runner.run(user_code=code, inputs={})
        assert "oops" in runner.function_log


class TestInprocessCodeRunnerInputsIsolation:
    """inputs 不被污染测试"""

    @staticmethod
    @pytest.mark.asyncio
    async def test_inputs_not_mutated():
        runner = InprocessCodeRunner()
        code = "def main(args):\n    args['new_key'] = 'mutated'\n    return args"
        original_inputs = {"a": 1}
        await runner.run(user_code=code, inputs=original_inputs)
        assert "new_key" not in original_inputs
        assert original_inputs == {"a": 1}

    @staticmethod
    @pytest.mark.asyncio
    async def test_nested_dict_not_mutated():
        runner = InprocessCodeRunner()
        code = "def main(args):\n    args['nested']['x'] = 999\n    return args"
        original_inputs = {"nested": {"x": 1}}
        await runner.run(user_code=code, inputs=original_inputs)
        assert original_inputs == {"nested": {"x": 1}}


class TestInprocessCodeRunnerOsSystemMock:
    """os.system mock 测试"""

    @staticmethod
    @pytest.mark.asyncio
    async def test_os_system_returns_negative_one():
        runner = InprocessCodeRunner()
        code = "import os\ndef main(args):\n    ret = os.system('echo test')\n    return {'ret': ret}"
        result = await runner.run(user_code=code, inputs={})
        assert result == {"ret": -1}


class TestInprocessCodeRunnerTimeout:
    """超时控制测试"""

    @staticmethod
    @pytest.mark.asyncio
    async def test_timeout_raises_timeout_error():
        """死循环代码在超时后被中断并抛出 TimeoutError"""
        runner = InprocessCodeRunner()
        code = "def main(args):\n    while True:\n        pass\n    return {}"
        with pytest.raises(TimeoutError, match="代码执行超时"):
            await runner.run(user_code=code, inputs={}, timeout=1)

    @staticmethod
    @pytest.mark.asyncio
    async def test_normal_execution_within_timeout():
        """正常代码在超时时间内完成不受影响"""
        runner = InprocessCodeRunner()
        code = "def main(args): return {'ok': True}"
        result = await runner.run(user_code=code, inputs={}, timeout=10)
        assert result == {"ok": True}

    @staticmethod
    @pytest.mark.asyncio
    async def test_stdout_restored_after_timeout():
        """超时后 sys.stdout 恢复为执行前的值"""
        import sys
        runner = InprocessCodeRunner()
        original_stdout = sys.stdout
        code = "def main(args):\n    while True:\n        pass\n    return {}"
        with pytest.raises(TimeoutError):
            await runner.run(user_code=code, inputs={}, timeout=1)
        assert sys.stdout is original_stdout
