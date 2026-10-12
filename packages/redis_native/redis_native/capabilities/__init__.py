# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""能力组实现注册表：SUPPORTED_CAPABILITIES + capability_view 视图工厂。

redis_native 支持全部 8 个可选能力组（redis-py 全命令面委托）；
``supports()`` 探测与 ``as_capability()`` 取视图都经由本模块。
"""

from __future__ import annotations

from typing import Any

from redis_native.capabilities.bitmap import NativeBitmapCapability
from redis_native.capabilities.blocking import NativeBlockingCapability
from redis_native.capabilities.counter import NativeCounterCapability
from redis_native.capabilities.geo import NativeGeoCapability
from redis_native.capabilities.hll import NativeHyperLogLogCapability
from redis_native.capabilities.pubsub import NativePubSubCapability
from redis_native.capabilities.scripting import NativeScriptingCapability
from redis_native.capabilities.sortedset import NativeSortedSetCapability

from redis_spi.capabilities import (
    BitmapCapability,
    BlockingCommands,
    CounterCapability,
    GeoCapability,
    HyperLogLogCapability,
    PubSubCapability,
    ScriptingCapability,
    SortedSetCapability,
)

#: 本实现支持的完整能力组集合（supports 探测依据）。
SUPPORTED_CAPABILITIES = frozenset(
    {
        BitmapCapability,
        BlockingCommands,
        CounterCapability,
        GeoCapability,
        HyperLogLogCapability,
        PubSubCapability,
        ScriptingCapability,
        SortedSetCapability,
    }
)


def capability_view(client: Any, capability: type) -> Any:
    """按能力组为 NativeRedisClient 构造委托视图；不支持返回 None。

    视图与客户端共享底层连接池与生命周期（契约要求）——访问 client 的包内
    部属性（``_redis`` / ``_subscriber`` / ``_blocking``）是包内约定，不构成
    对外接口。
    """
    if capability is CounterCapability:
        return NativeCounterCapability(client._redis)
    if capability is SortedSetCapability:
        return NativeSortedSetCapability(client._redis)
    if capability is BitmapCapability:
        return NativeBitmapCapability(client._redis)
    if capability is HyperLogLogCapability:
        return NativeHyperLogLogCapability(client._redis)
    if capability is GeoCapability:
        return NativeGeoCapability(client._redis)
    if capability is ScriptingCapability:
        return NativeScriptingCapability(client._redis)
    if capability is PubSubCapability:
        return NativePubSubCapability(client._redis, client._subscriber)
    if capability is BlockingCommands:
        return NativeBlockingCapability(client._blocking)
    return None
