# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""SyncCompatClient（sync-over-async 线程桥）与 RedisClientManager 同步面接线测试。

覆盖：redis-py 调用形态的同步往返（String/List/Key）、bytes 透传语义、
装配循环线程内调用的死锁防护、委托层 get_sync_client 注册优先与 reset 清理。
"""

import asyncio
import threading

import pytest

from common_utils.redis_manager import RedisClientManager
from common_utils.redis_sync_compat import SyncCompatClient


class StubAsyncClient:
    """SPI 协议面最小替身（内存实现，bytes 语义）。"""

    def __init__(self):
        self.strings = {}
        self.lists = {}

    async def get(self, key):
        return self.strings.get(key)

    async def set(self, key, value, *, condition=None, duration=None, keep_ttl=False):
        self.strings[key] = value if isinstance(value, bytes) else str(value).encode()
        return b"OK"

    async def delete(self, *keys):
        removed = 0
        for key in keys:
            if key in self.strings:
                self.strings.pop(key)
                removed += 1
            if key in self.lists:
                self.lists.pop(key)
                removed += 1
        return removed

    async def expire(self, key, duration):
        return key in self.strings or key in self.lists

    async def exists(self, *keys):
        return sum(
            1 for key in keys if key in self.strings or key in self.lists
        )

    async def rpush(self, key, *values):
        bucket = self.lists.setdefault(key, [])
        bucket.extend(
            v if isinstance(v, bytes) else str(v).encode() for v in values
        )
        return len(bucket)

    async def lrange(self, key, start, stop):
        values = self.lists.get(key, [])
        if stop < 0:
            stop = len(values) + stop
        return values[start:stop + 1]

    async def llen(self, key):
        return len(self.lists.get(key, []))


@pytest.fixture
def loop_thread():
    """独立事件循环线程（模拟进程主循环）。"""
    loop = asyncio.new_event_loop()
    thread = threading.Thread(target=loop.run_forever, daemon=True)
    thread.start()
    yield loop
    loop.call_soon_threadsafe(loop.stop)
    thread.join(timeout=5)
    loop.close()


def _make_bridge_on_loop(loop, client):
    """在循环线程内构造桥（对齐 lifespan 装配场景，死锁防护才能生效）。"""
    ready = threading.Event()
    holder = {}

    def _construct():
        holder["bridge"] = SyncCompatClient(client, asyncio.get_running_loop())
        ready.set()

    loop.call_soon_threadsafe(_construct)
    assert ready.wait(timeout=5), "bridge construction timed out"
    return holder["bridge"]


@pytest.fixture(autouse=True)
def _reset_manager():
    RedisClientManager.reset()
    yield
    RedisClientManager.reset()


class TestSyncCompatClient:
    def test_string_get_set_ex_roundtrip(self, loop_thread):
        bridge = _make_bridge_on_loop(loop_thread, StubAsyncClient())
        assert bridge.set("k1", "v1", ex=60) == b"OK"
        assert bridge.get("k1") == b"v1"
        assert bridge.get("missing") is None

    def test_list_rpush_lrange_llen(self, loop_thread):
        bridge = _make_bridge_on_loop(loop_thread, StubAsyncClient())
        assert bridge.rpush("hist", "a", "b", "c") == 3
        assert bridge.lrange("hist", 0, -1) == [b"a", b"b", b"c"]
        assert bridge.lrange("hist", 1, 1) == [b"b"]
        assert bridge.llen("hist") == 3
        assert bridge.llen("missing") == 0

    def test_key_delete_expire_exists(self, loop_thread):
        bridge = _make_bridge_on_loop(loop_thread, StubAsyncClient())
        bridge.set("k", "v")
        assert bridge.exists("k") == 1
        assert bridge.expire("k", 30) is True
        assert bridge.delete("k") == 1
        assert bridge.exists("k") == 0
        assert bridge.delete("k") == 0
        assert bridge.expire("k", 30) is False

    def test_deadlock_guard_on_loop_thread(self, loop_thread):
        bridge = _make_bridge_on_loop(loop_thread, StubAsyncClient())
        done = threading.Event()
        holder = {}

        def _call_on_loop_thread():
            try:
                bridge.get("k")
                holder["error"] = None
            except RuntimeError as exc:
                holder["error"] = exc
            finally:
                done.set()

        loop_thread.call_soon_threadsafe(_call_on_loop_thread)
        assert done.wait(timeout=5)
        assert isinstance(holder["error"], RuntimeError)
        assert "死锁" in str(holder["error"])


class TestManagerSyncCompatWiring:
    def test_get_sync_client_prefers_registered_compat(self, loop_thread):
        bridge = _make_bridge_on_loop(loop_thread, StubAsyncClient())
        RedisClientManager.set_sync_compat(bridge)
        assert RedisClientManager.get_instance().get_sync_client() is bridge

    def test_reset_clears_registered_compat(self, loop_thread):
        bridge = _make_bridge_on_loop(loop_thread, StubAsyncClient())
        RedisClientManager.set_sync_compat(bridge)
        RedisClientManager.reset()
        assert RedisClientManager._sync_compat is None
