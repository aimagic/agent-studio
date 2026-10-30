# coding: utf-8
"""进程内代码执行器 - 使用 exec() 在当前进程内执行代码

对齐旧版 custom_code.py 的 _execute_code_safely() 实现。
无子进程开销，适用于 exec_env=local 且 LOCAL_CODE_EXEC_MODE=inprocess 的场景。

注意：进程内模式（inprocess）的超时中断为 best-effort：
- 纯 Python 字节码（循环、IO 等）可被中断；
- 若用户代码进入不可中断的 C 扩展或吞掉 BaseException，超时注入可能不生效。
  如需可靠硬超时，请使用 LOCAL_CODE_EXEC_MODE=subprocess。
"""

import asyncio
import copy
import ctypes
import logging
import sys
import threading
import traceback
from dataclasses import dataclass
from typing import Any, Optional
from unittest import mock

from agent_runtime.extension.workflow_node.code_runner.base_code_runner import CodeRunner
from openjiuwen.core.common.exception.codes import StatusCode
from openjiuwen.core.common.exception.errors import build_error

_logger = logging.getLogger(__name__)


@dataclass
class _ExecResult:
    """线程执行结果容器"""

    result: Any = None
    exception: Optional[BaseException] = None
    error_log: Optional[str] = None
    function_log: str = ""


class _ThreadInterrupt(BaseException):
    """用于 PyThreadState_SetAsyncExc 注入工作线程以中断 exec 的内部异常。

    继承 BaseException 而非 Exception，避免被用户代码的 except Exception 吞掉。
    """


# 进程级锁：sys.stdout 重定向和 os.system patch 都是进程级全局操作，
# bpmn 会并发调度组件（MAX_PARALLEL_COMPONENT_NUM），必须串行化避免交错破坏全局状态。
# 带超时获取：前一次执行卡死时，后续调用不永久阻塞，而是抛出明确错误。
_exec_process_lock = threading.Lock()
_LOCK_ACQUIRE_TIMEOUT = 30


class InprocessCodeRunner(CodeRunner):
    """进程内代码执行器 - 使用 exec() 在当前进程内执行代码

    无子进程开销，适用于 exec_env=local 且 LOCAL_CODE_EXEC_MODE=inprocess 的场景。

    超时机制：exec() 在独立守护线程中执行，主协程在 event loop 上轮询完成状态；
    超时后通过 PyThreadState_SetAsyncExc 向工作线程注入 _ThreadInterrupt 以中断 exec。
    注意：若用户代码进入不可中断的 C 扩展，注入的异常会延迟到下一次 Python 字节码调度时生效。
    """

    _PROLOGUE = (
        "import sys\n"
        "import io\n"
        "buffer = io.StringIO()\n"
        "original_stdout = sys.stdout\n"
        "sys.stdout = buffer\n"
    )
    _EPILOGUE = (
        "\ntry:\n"
        "    res = main(args)\n"
        "finally:\n"
        "    sys.stdout = original_stdout\n"
        "    console_log = buffer.getvalue()\n"
    )

    def __init__(self):
        self.function_log: str = ""

    async def run(self, user_code: str, inputs: dict, timeout: int = 900) -> dict:
        """在进程内执行用户代码（带超时控制）

        Args:
            user_code: 用户源代码（包含 main 函数定义）
            inputs: 输入参数字典，传递给 main 函数
            timeout: 超时时间（秒），超时后向执行线程注入 _ThreadInterrupt 中断

        Returns:
            dict: main 函数的返回值

        Raises:
            TimeoutError: 执行超时
            Exception: 用户代码抛出的原始异常
            BuildError: 返回值非 dict 时抛出
        """
        saved_stdout = sys.stdout

        container = _ExecResult()
        done_event = threading.Event()

        def _worker():
            try:
                self._exec_sync(user_code, inputs, container)
            except BaseException as e:
                container.exception = e
                container.error_log = traceback.format_exc()
            finally:
                done_event.set()

        thread = threading.Thread(target=_worker, daemon=True)
        thread.start()

        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        try:
            while not done_event.is_set():
                remaining = deadline - loop.time()
                if remaining <= 0:
                    break
                await asyncio.sleep(min(0.05, remaining))
        except asyncio.CancelledError:
            self._interrupt_thread(thread)
            await self._wait_done(done_event, loop, grace=2)
            if thread.is_alive():
                self._force_cleanup(saved_stdout)
            raise

        if not done_event.is_set():
            self._interrupt_thread(thread)
            await self._wait_done(done_event, loop, grace=2)
            if thread.is_alive():
                self._force_cleanup(saved_stdout)
            raise TimeoutError(
                f"代码执行超时（已超过 {timeout} 秒限制），"
                f"请优化代码或调整超时配置"
            )

        self.function_log = container.function_log
        if container.error_log:
            self.function_log += container.error_log[
                container.error_log.find('local_namespace') + 17:
            ]
        if container.exception is not None:
            raise container.exception

        res = container.result
        if not isinstance(res, dict):
            raise build_error(
                StatusCode.WORKFLOW_COMPONENT_SCHEMA_INVALID,
                comp_id="flow_code",
                reason="main() must return a dict",
                workflow="n/a",
            )
        return res

    @staticmethod
    async def _wait_done(
        done_event: threading.Event,
        loop: asyncio.AbstractEventLoop,
        grace: float,
    ) -> None:
        """有限等待 done_event 置位（轮询，不占线程池线程）。"""
        deadline = loop.time() + grace
        while not done_event.is_set() and loop.time() < deadline:
            await asyncio.sleep(0.05)

    @staticmethod
    def _force_cleanup(saved_stdout: Any) -> None:
        """worker 无法中断时恢复全局状态。

        恢复 sys.stdout 为执行前保存的值（不用 sys.__stdout__，避免破坏宿主自定义 stdout）。
        _exec_process_lock 可能仍被 worker 持有，后续调用会在 _LOCK_ACQUIRE_TIMEOUT 秒后报错。
        """
        try:
            if sys.stdout is not saved_stdout:
                sys.stdout = saved_stdout
        except Exception as e:
            _logger.warning(f"Failed to restore sys.stdout in _force_cleanup: {e}")

    @staticmethod
    def _interrupt_thread(thread: threading.Thread) -> None:
        """向工作线程注入 _ThreadInterrupt 以中断 exec。"""
        if not thread.is_alive():
            return
        tid = thread.ident
        if tid is None:
            return
        ret = ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(tid), ctypes.py_object(_ThreadInterrupt)
        )
        if ret > 1:
            ctypes.pythonapi.PyThreadState_SetAsyncExc(
                ctypes.c_ulong(tid), ctypes.c_long(0)
            )

    def _exec_sync(
        self, user_code: str, inputs: dict, container: _ExecResult
    ) -> None:
        """同步执行用户代码（在工作线程中调用，结果写入 container）"""
        inputs = copy.deepcopy(inputs)
        exec_code = self._PROLOGUE + user_code + self._EPILOGUE

        env = {
            "args": inputs,
            "__builtins__": __builtins__,
            "__name__": "__main__",
        }
        global_namespace = env
        local_namespace = env
        exec_exception = None
        error_log = None

        os_system_patcher = mock.patch('os.system', return_value=-1)
        lock_acquired = _exec_process_lock.acquire(timeout=_LOCK_ACQUIRE_TIMEOUT)
        if not lock_acquired:
            raise RuntimeError(
                "代码执行锁获取超时：前一次 inprocess 执行可能因不可中断的 C 扩展"
                "而未释放锁。建议改用 LOCAL_CODE_EXEC_MODE=subprocess 获取可靠硬超时。"
            )
        try:
            os_system_patcher.start()
            try:
                exec(exec_code, global_namespace, local_namespace)
            except BaseException as e:
                error_log = traceback.format_exc()
                exec_exception = e
            finally:
                os_system_patcher.stop()
        except BaseException as e:
            error_log = traceback.format_exc()
            exec_exception = e
            try:
                os_system_patcher.stop()
            except Exception as stop_err:
                _logger.warning(f"Failed to stop os.system patcher: {stop_err}")
        finally:
            _exec_process_lock.release()

        res = local_namespace.get("res", {})
        console_log = local_namespace.get("console_log", "")

        container.result = res
        container.function_log = console_log
        if error_log is not None:
            container.error_log = error_log
        if exec_exception is not None:
            container.exception = exec_exception
