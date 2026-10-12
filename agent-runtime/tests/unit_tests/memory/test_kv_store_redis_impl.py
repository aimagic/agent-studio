# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""KVStoreRedisImpl 单测（Phase 4 第五批迁移回归锁）。

背景：本类重写自 jiuwen 老 store（accessor 装配）；旧实现的 delete_by_prefix
调用老 wrapper 上不存在的方法（一调即 AttributeError），scan 语义在本文件
首次得到验证。锁住的语义：默认 TTL、exclusive_set（行为对齐旧实现的非 NX
口径）、前缀读写删、mget。
"""

import asyncio
from unittest.mock import patch

import fakeredis.aioredis

from redis_native.client import NativeRedisClient

from agent_runtime.memory.kv_store_redis_impl import KVStoreRedisImpl

#: 与实现一致的默认 TTL 上限（MEMORY_REDIS_TTL 默认 180 天）。
_DEFAULT_TTL_SECONDS = 180 * 24 * 3600


def _make_kv():
    """经 accessor 装配 KVStoreRedisImpl（fakeredis 底座 + 真实 SPI 客户端）。"""
    raw = fakeredis.aioredis.FakeRedis(decode_responses=False)
    client = NativeRedisClient(raw)
    with patch("redis_spi.accessor.get_redis", return_value=client):
        kv = KVStoreRedisImpl()
    return kv, raw


class TestKVStoreRedisImpl:
    def test_set_get_roundtrip_with_default_ttl(self):
        kv, raw = _make_kv()
        asyncio.run(kv.set("m:u1", "v1"))
        # bytes 语义：老 jiuwen store 客户端同为 decode_responses=False
        assert asyncio.run(kv.get("m:u1")) == b"v1"
        # 默认 TTL 生效（180 天兜底，避免记忆数据永存）
        ttl = asyncio.run(raw.ttl("m:u1"))
        assert 0 < ttl <= _DEFAULT_TTL_SECONDS
        assert asyncio.run(kv.get("m:none")) is None

    def test_exclusive_set_plain_and_with_expiry(self):
        kv, raw = _make_kv()
        # 行为对齐旧实现：exclusive_set 非 NX 语义（方法名与行为的历史差异保留）
        assert asyncio.run(kv.exclusive_set("m:x", "v1")) is True
        asyncio.run(kv.exclusive_set("m:x", "v2"))  # 覆写不拒绝
        assert asyncio.run(kv.get("m:x")) == b"v2"
        assert asyncio.run(kv.exclusive_set("m:y", "v3", expiry=60)) is True
        assert 0 < asyncio.run(raw.ttl("m:y")) <= 60

    def test_exists_delete_mget(self):
        kv, _ = _make_kv()
        asyncio.run(kv.set("m:a", "1"))
        asyncio.run(kv.set("m:b", "2"))
        assert asyncio.run(kv.exists("m:a")) is True
        assert asyncio.run(kv.exists("m:zz")) is False
        assert asyncio.run(kv.mget(["m:a", "m:zz", "m:b"])) == [b"1", None, b"2"]
        assert asyncio.run(kv.mget([])) == []
        asyncio.run(kv.delete("m:a"))
        assert asyncio.run(kv.exists("m:a")) is False

    def test_get_by_prefix(self):
        kv, _ = _make_kv()
        asyncio.run(kv.set("pfx:a", "va"))
        asyncio.run(kv.set("pfx:b", "vb"))
        asyncio.run(kv.set("oth:c", "vc"))
        result = asyncio.run(kv.get_by_prefix("pfx:"))
        assert result == {"pfx:a": "va", "pfx:b": "vb"}

    def test_delete_by_prefix(self):
        """旧实现此路径一调即 AttributeError；SCAN 语义在此首次验证。"""
        kv, _ = _make_kv()
        asyncio.run(kv.set("pfx:a", "va"))
        asyncio.run(kv.set("pfx:b", "vb"))
        asyncio.run(kv.set("oth:c", "vc"))
        asyncio.run(kv.delete_by_prefix("pfx:"))
        assert asyncio.run(kv.exists("pfx:a")) is False
        assert asyncio.run(kv.exists("pfx:b")) is False
        assert asyncio.run(kv.exists("oth:c")) is True  # 前缀外不受影响
