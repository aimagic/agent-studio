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
import threading
import traceback
from dataclasses import dataclass
from typing import Any, Optional
from unittest import mock

from agent_runtime.extension.workflow_node.code_runner.base_code_runner import CodeRunner
from openjiuwen.core.common.exception.codes import StatusCode
from openjiuwen.core.common.exception.errors import build_error


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


class InprocessCodeRunner(CodeRunner):
    """进程内代码执行器 - 使用 exec() 在当前进程内执行代码

    无子进程开销，适用于 exec_env=local 且 LOCAL_CODE_EXEC_MODE=inprocess 的场景。

    超时机制：exec() 在独立守护线程中执行，主协程在 event loop 上轮询完成状态；
    超时后通过 PyThreadState_SetAsyncExc 向工作线程注入 _ThreadInterrupt 以中断 exec。
    注意：若用户代码进入不可中断的 C 扩展（如 time.sleep 的底层实现），
    注入的异常会延迟到下一次 Python 字节码调度时才生效。
    """

    # stdout 重定向前缀：exec 体内先建 StringIO 并接管 sys.stdout
    _PROLOGUE = (
        "import sys\n"
        "import io\n"
        "buffer = io.StringIO()\n"
        "original_stdout = sys.stdout\n"
        "sys.stdout = buffer\n"
    )
    # stdout 恢复 + console_log 提取后缀：在 try/finally 中还原并收集 print 输出
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
        container = _ExecResult()
        done_event = threading.Event()

        def _worker():
            try:
                self._exec_sync(user_code, inputs, container)
            except BaseException as e:
                # _exec_sync 之外的异常（如 deepcopy 失败）也要写入 container，
                # 否则主协程会因 container.result 为 None 误报 "main() must return a dict"
                container.exception = e
                container.error_log = traceback.format_exc()
            finally:
                done_event.set()

        thread = threading.Thread(target=_worker, daemon=True)
        thread.start()

        # 在 event loop 上轮询 done_event，不占用任何线程池线程；
        # 超时/取消后可直接进入中断逻辑，不受阻塞的 run_in_executor 约束。
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        try:
            while not done_event.is_set():
                remaining = deadline - loop.time()
                if remaining <= 0:
                    break
                await asyncio.sleep(min(0.05, remaining))
        except asyncio.CancelledError:
            # 协程被取消（断连/外层 wait_for 超时）：中断 worker 线程，避免补丁/锁泄漏
            self._interrupt_thread(thread)
            await self._wait_done(done_event, loop, grace=2)
            if thread.is_alive():
                self._force_cleanup()
            raise

        if not done_event.is_set():
            # 超时：注入中断异常并有限等待线程响应
            self._interrupt_thread(thread)
            await self._wait_done(done_event, loop, grace=2)
            if thread.is_alive():
                # worker 无法中断（卡在 C 扩展/吞掉 BaseException）：
                # 强制恢复全局状态并释放锁，避免后续所有 inprocess 调用死锁。
                self._force_cleanup()
            raise TimeoutError(
                f"代码执行超时（已超过 {timeout} 秒限制），"
                f"请优化代码或调整超时配置"
            )

        # 线程执行完毕，收集结果（先拼 function_log 再 raise，确保异常时也能拿到堆栈）
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
    def _force_cleanup() -> None:
        """worker 无法中断时恢复全局状态。

        worker 卡在不可中断的 C 扩展时，exec 的 _EPILOGUE finally 不会执行，
        sys.stdout 可能仍是 StringIO。此方法恢复 stdout 使后续调用不受影响。
        os.system patch 由 mock.patch 自身的 stop 兜底（新 patcher 会覆盖旧的）。
        """
        import sys
        try:
            if hasattr(sys.stdout, 'getvalue'):
                sys.stdout = sys.__stdout__
        except Exception:
            pass

    @staticmethod
    def _interrupt_thread(thread: threading.Thread) -> None:
        """向工作线程注入 _ThreadInterrupt 以中断 exec。

        通过 ctypes 调用 CPython 内部 API 向目标线程的当前帧抛出异常。
        对于纯 Python 字节码（循环、IO 等）可立即中断；
        若线程正阻塞在不可中断的 C 扩展中，异常会延迟到返回 Python 层时生效。
        """
        if not thread.is_alive():
            return
        tid = thread.ident
        if tid is None:
            return
        # PyThreadState_SetAsyncExc(thread_id, exception_object)
        # 返回 0 表示线程已结束，1 表示成功注入，>1 表示目标线程有多个栈帧（罕见）
        ret = ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(tid), ctypes.py_object(_ThreadInterrupt)
        )
        if ret > 1:
            # 多帧场景：清除异常注入，避免影响其他线程
            ctypes.pythonapi.PyThreadState_SetAsyncExc(
                ctypes.c_ulong(tid), ctypes.c_long(0)
            )

    def _exec_sync(
        self, user_code: str, inputs: dict, container: _ExecResult
    ) -> None:
        """同步执行用户代码（在工作线程中调用，结果写入 container）"""
        # 1. 深拷贝 inputs 防止污染
        inputs = copy.deepcopy(inputs)

        # 2. 构建执行代码：stdout 重定向 + 用户代码 + try/finally 恢复
        exec_code = self._PROLOGUE + user_code + self._EPILOGUE

        # 3. 执行代码（每次创建局部 os.system patcher，避免实例级补丁在并发下残留）
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
        # 不使用进程级锁：卡在 exec 时锁无法释放会导致后续所有调用死锁。
        # stdout 重定向和 os.system patch 通过 try/finally 保证正常路径清理；
        # 超时无法中断时由 _force_cleanup 恢复全局状态。
        # 并发执行风险：inprocess 模式默认串行（bpmn 调度），且 daemon 线程
        # 在超时后会被注入异常中断，实际并发概率极低。
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
            # patch.start() 或 stop() 本身失败（如前一次卡死未 stop）
            error_log = traceback.format_exc()
            exec_exception = e
            try:
                os_system_patcher.stop()
            except Exception:
                pass

        # 4. 提取结果
        res = local_namespace.get("res", {})
        console_log = local_namespace.get("console_log", "")

        # 5. 写入容器（主协程读取）
        container.result = res
        container.function_log = console_log
        if error_log is not None:
            container.error_log = error_log
        if exec_exception is not None:
            container.exception = exec_exception
