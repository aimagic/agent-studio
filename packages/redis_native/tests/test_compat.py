# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""as_native_client 单测：duck-typed 过渡视图（Q3；Phase 4 移除）。"""

from __future__ import annotations

import pytest

from redis_native.client import NativeRedisClient
from redis_native.compat import as_native_client

from redis_spi.exceptions import RedisConfigError

from fakes import FakeRedisClient


class _WithNativeView:
    def native_client(self):
        return "raw-view"


class _Plain:
    pass


class _NonCallable:
    native_client = "not-a-method"


class TestAsNativeClient:
    def test_duck_typed_getter_used(self):
        assert as_native_client(_WithNativeView()) == "raw-view"

    def test_native_client_integration(self):
        fake = FakeRedisClient()
        client = NativeRedisClient(fake)
        assert as_native_client(client) is fake

    def test_plain_object_rejected(self):
        with pytest.raises(RedisConfigError, match="_Plain"):
            as_native_client(_Plain())

    def test_non_callable_attr_rejected(self):
        with pytest.raises(RedisConfigError, match="_NonCallable"):
            as_native_client(_NonCallable())

    def test_error_mentions_transition_scope(self):
        # 自描述：仅 native provider 支持 + 过渡期 API（引导 Phase 4 收口）
        with pytest.raises(RedisConfigError, match="native provider"):
            as_native_client(_Plain())
