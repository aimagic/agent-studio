# coding: utf-8
"""进程内代码执行器 - 使用 exec() 在当前进程内执行代码

对齐旧版 custom_code.py 的 _execute_code_safely() 实现。
无子进程开销，适用于 exec_env=local 且 LOCAL_CODE_EXEC_MODE=inprocess 的场景。
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


class InprocessCodeRunner(CodeRunner):
    """进程内代码执行器 - 使用 exec() 在当前进程内执行代码

    无子进程开销，适用于 exec_env=local 且 LOCAL_CODE_EXEC_MODE=inprocess 的场景。

    超时机制：exec() 在独立守护线程中执行，主协程通过 asyncio.wait_for 控制超时；
    超时后通过 PyThreadState_SetAsyncExc 向工作线程注入 SystemExit 以中断 exec。
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
        # 复用 os.system patcher：实例级单例，避免每次 run() 重建 _patch 对象。
        # os.system 为进程级全局属性，patch 期间影响全进程；用 _exec_lock 串行
        # start/stop，使并发调用下全局补丁不竞争，并保持与旧版同步 exec 等价的串行语义。
        self._os_system_patcher = mock.patch('os.system', return_value=-1)
        self._exec_lock = threading.Lock()

    async def run(self, user_code: str, inputs: dict, timeout: int = 300) -> dict:
        """在进程内执行用户代码（带超时控制）

        Args:
            user_code: 用户源代码（包含 main 函数定义）
            inputs: 输入参数字典，传递给 main 函数
            timeout: 超时时间（秒），超时后向执行线程注入 SystemExit 中断

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
            finally:
                done_event.set()

        thread = threading.Thread(target=_worker, daemon=True)
        thread.start()

        loop = asyncio.get_running_loop()
        try:
            await asyncio.wait_for(
                loop.run_in_executor(None, done_event.wait),
                timeout=timeout,
            )
        except asyncio.TimeoutError:
            self._interrupt_thread(thread)
            # 给被中断的线程一点时间响应注入的异常（放线程池避免阻塞 event loop）
            await loop.run_in_executor(None, lambda: done_event.wait(timeout=2))
            raise TimeoutError(
                f"代码执行超时（已超过 {timeout} 秒限制），"
                f"请优化代码或调整超时配置"
            )

        # 正常路径：线程已结束。防御性检查 —— 若线程仍存活（注入异常未生效，
        # 如卡在不可中断的 C 扩展），container 未写入，不应读取到脏数据。
        if thread.is_alive():
            raise TimeoutError(
                f"代码执行超时（已超过 {timeout} 秒限制），"
                f"且无法中断执行线程，请优化代码或调整超时配置"
            )

        # 线程执行完毕，收集结果
        self.function_log = container.function_log
        if container.exception is not None:
            raise container.exception
        if container.error_log:
            self.function_log += container.error_log[
                container.error_log.find('local_namespace') + 17:
            ]

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
    def _interrupt_thread(thread: threading.Thread) -> None:
        """向工作线程注入 SystemExit 以中断 exec。

        通过 ctypes 调用 CPython 内部 API 向目标线程的当前帧抛出异常。
        对于纯 Python 字节码（循环、IO 等）可立即中断；
        若线程正阻塞在不可中断的 C 扩展中，异常会延迟到返回 Python 层时生效。
        """
        if not thread.is_alive():
            return
        tid = thread.ident
        if tid is None:
            return
        exc = SystemExit
        # PyThreadState_SetAsyncExc(thread_id, exception_object)
        # 返回 0 表示线程已结束，1 表示成功注入，>1 表示目标线程有多个栈帧（罕见）
        ret = ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(tid), ctypes.py_object(exc)
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

        # 3. 执行代码（复用 os.system patcher）
        env = {
            "args": inputs,
            "__builtins__": __builtins__,
            "__name__": "__main__",
        }
        global_namespace = env
        local_namespace = env
        exec_exception = None
        error_log = None

        with self._exec_lock:
            self._os_system_patcher.start()
            try:
                exec(exec_code, global_namespace, local_namespace)
            except BaseException as e:
                error_log = traceback.format_exc()
                exec_exception = e
            finally:
                self._os_system_patcher.stop()

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
