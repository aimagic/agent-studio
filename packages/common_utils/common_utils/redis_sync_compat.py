# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""SPI 异步客户端的 redis-py 同步兼容面（sync-over-async 线程桥）。

背景：Redis Provider 适配层（redis_spi）只有异步协议面；builder 的同步存储类
（nl_to_agent/storage/history.py 的 ImRedisConversation / SessionStorage）以
redis-py 同步调用形态工作（get / set(ex=) / delete / expire / exists / rpush /
lrange / llen），且其上层接口（BaseConversationMemory）约束同步签名，无法改异步。

方案：把同步调用经 asyncio.run_coroutine_threadsafe 调度回 SPI 客户端所属的
事件循环（装配循环，即进程主循环），在调用线程阻塞等待结果——与 builder 既有
的 llm_bridge._run_async 桥接模式同构。

语义约定：
  - 返回值透传 SPI 语义（native 实现默认 bytes，与 legacy decode_responses=False
    一致，调用方零感知）；
  - 在装配循环线程内直接调用会死锁，此处 fail-fast 抛 RuntimeError；
  - close 为 no-op：客户端生命周期归 accessor（进程级装配，随 aclose 释放）。
"""

from __future__ import annotations

import asyncio
import threading
from concurrent.futures import TimeoutError as FutureTimeoutError
from typing import Any

from redis_spi.types import Duration


class SyncCompatClient:
    """redis-py 同步调用形态的 SPI 客户端桥（须在 SPI 客户端所属事件循环内构造）。"""

    def __init__(self, client: Any, loop: asyncio.AbstractEventLoop,
                 *, call_timeout: float = 60.0):
        self._client = client
        self._loop = loop
        self._call_timeout = call_timeout
        # 构造发生在装配循环线程（lifespan），记录用于死锁防护
        self._loop_thread = threading.current_thread()

    # ---------------------------------------------------------------- internal

    def _call(self, coro_factory: Any, method: str) -> Any:
        """把协程调度回装配循环执行，调用线程阻塞等待结果。"""
        if threading.current_thread() is self._loop_thread:
            raise RuntimeError(
                f"SyncCompatClient.{method} 在装配事件循环线程内被调用会死锁；"
                "异步上下文请直接使用 redis_spi.accessor 的异步客户端"
            )
        future = asyncio.run_coroutine_threadsafe(coro_factory(), self._loop)
        try:
            return future.result(timeout=self._call_timeout)
        except FutureTimeoutError as exc:
            future.cancel()
            raise RuntimeError(
                f"Redis sync compat call '{method}' timed out "
                f"after {self._call_timeout}s"
            ) from exc

    # ---------------------------------------------------------------- String

    def get(self, key: str) -> bytes | None:
        """GET。"""
        return self._call(lambda: self._client.get(key), "get")

    def set(self, key: str, value: str | bytes, ex: int | None = None) -> Any:
        """SET（redis-py 兼容签名：ex 秒 → SPI duration）。"""
        duration = Duration.seconds(ex) if ex is not None else None

        async def _do() -> Any:
            return await self._client.set(key, value, duration=duration)

        return self._call(_do, "set")

    # ---------------------------------------------------------------- Key

    def delete(self, *keys: str) -> int:
        """DEL，返回删除数量。"""
        return self._call(lambda: self._client.delete(*keys), "delete")

    def expire(self, key: str, ttl_seconds: int) -> bool:
        """EXPIRE（秒）。"""
        duration = Duration.seconds(ttl_seconds)

        async def _do() -> bool:
            return await self._client.expire(key, duration)

        return self._call(_do, "expire")

    def exists(self, *keys: str) -> int:
        """EXISTS，返回存在的 key 数量。"""
        return self._call(lambda: self._client.exists(*keys), "exists")

    # ---------------------------------------------------------------- List

    def rpush(self, key: str, *values: str | bytes) -> int:
        """RPUSH，返回列表长度。"""
        return self._call(lambda: self._client.rpush(key, *values), "rpush")

    def lrange(self, key: str, start: int, stop: int) -> list[bytes]:
        """LRANGE（stop=-1 表示到末尾）。"""
        return self._call(lambda: self._client.lrange(key, start, stop), "lrange")

    def llen(self, key: str) -> int:
        """LLEN。"""
        return self._call(lambda: self._client.llen(key), "llen")

    # ---------------------------------------------------------------- lifecycle

    def close(self) -> None:
        """no-op：客户端生命周期归 accessor（进程级装配，随 aclose 释放）。"""

    def keys(self, pattern: str) -> list[bytes]:  # noqa: D401 - redis-py 兼容面
        """KEYS（谨慎使用，仅小键空间场景）。"""
        async def _do() -> list[bytes]:
            found = [key async for key in self._client.scan_iter(match=pattern)]
            return list(found)

        return self._call(_do, "keys")
