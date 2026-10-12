# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""converters 单测：Duration 换算 / kwargs 翻译 / 前置校验。"""

from __future__ import annotations

import pytest

from redis_native.converters import (
    blocking_timeout,
    condition_options,
    duration_to_seconds,
    set_options,
    validate_hset_args,
    validate_range_paired,
    validate_set_ttl,
)

from redis_spi.exceptions import RedisCommandError
from redis_spi.types import Duration, SetCondition


class TestDurationToSeconds:
    @pytest.mark.parametrize(
        ("milliseconds", "expected"),
        [
            (1000, 1),
            (1500, 2),  # 向上取整：TTL 不截短
            (999, 1),
            (1, 1),
            (60000, 60),
        ],
    )
    def test_ceil(self, milliseconds, expected):
        assert duration_to_seconds(Duration.milliseconds(milliseconds)) == expected


class TestSetOptions:
    def test_empty(self):
        assert set_options(condition=None, duration=None, keep_ttl=False) == {}

    def test_nx(self):
        options = set_options(
            condition=SetCondition.NX, duration=None, keep_ttl=False
        )
        assert options == {"nx": True}

    def test_xx(self):
        options = set_options(
            condition=SetCondition.XX, duration=None, keep_ttl=False
        )
        assert options == {"xx": True}

    def test_duration_uses_px(self):
        options = set_options(
            condition=None, duration=Duration.milliseconds(1500), keep_ttl=False
        )
        assert options == {"px": 1500}

    def test_keep_ttl(self):
        options = set_options(condition=None, duration=None, keep_ttl=True)
        assert options == {"keepttl": True}

    def test_nx_with_duration(self):
        options = set_options(
            condition=SetCondition.NX,
            duration=Duration.seconds(30),
            keep_ttl=False,
        )
        assert options == {"nx": True, "px": 30000}


class TestConditionOptions:
    def test_none(self):
        assert condition_options(None) == {}

    def test_nx(self):
        assert condition_options(SetCondition.NX) == {"nx": True}

    def test_xx(self):
        assert condition_options(SetCondition.XX) == {"xx": True}


class TestBlockingTimeout:
    def test_none_blocks_forever(self):
        assert blocking_timeout(None) == 0.0

    def test_seconds(self):
        assert blocking_timeout(Duration.seconds(2)) == 2.0

    def test_sub_second(self):
        assert blocking_timeout(Duration.milliseconds(500)) == 0.5


class TestValidateRangePaired:
    def test_both_none_ok(self):
        validate_range_paired("bitcount", None, None)

    def test_both_given_ok(self):
        validate_range_paired("bitcount", 0, 7)

    def test_start_only_rejected(self):
        with pytest.raises(RedisCommandError, match="成对"):
            validate_range_paired("bitcount", 0, None)

    def test_end_only_rejected(self):
        with pytest.raises(RedisCommandError, match="成对"):
            validate_range_paired("bitcount", None, 7)


class TestValidateHsetArgs:
    def test_mapping_ok(self):
        validate_hset_args({"f": b"v"}, None, None)

    def test_field_value_ok(self):
        validate_hset_args(None, "f", b"v")

    def test_empty_mapping_treated_as_absent(self):
        # mapping={} 且 field/value 给了 → 走 field/value 路径
        validate_hset_args({}, "f", b"v")

    def test_both_rejected(self):
        with pytest.raises(RedisCommandError, match="二选一"):
            validate_hset_args({"f": b"v"}, "f", b"v")

    def test_neither_rejected(self):
        with pytest.raises(RedisCommandError, match="不可同时为空"):
            validate_hset_args(None, None, None)


class TestValidateSetTtl:
    def test_duration_only_ok(self):
        validate_set_ttl(Duration.seconds(1), False)

    def test_conflict_rejected(self):
        with pytest.raises(RedisCommandError, match="互斥"):
            validate_set_ttl(Duration.seconds(1), True)

    def test_neither_ok(self):
        validate_set_ttl(None, False)
