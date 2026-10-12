# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""types.py 值对象契约测试。"""

import pytest

from redis_spi.types import (
    Duration,
    GeoMember,
    GeoUnit,
    ScanBatch,
    SetCondition,
)

TEST_CASE = "redis_spi/types"


class TestDuration:
    def test_seconds_to_milliseconds(self):
        assert Duration.seconds(30).milliseconds == 30000

    def test_half_second(self):
        assert Duration.seconds(0.5).milliseconds == 500

    def test_sub_millisecond_rounds_up(self):
        # 0.5ms 向上取整为 1ms：避免正 TTL 被截断为 0
        assert Duration.seconds(0.0005).milliseconds == 1

    @pytest.mark.parametrize("bad", [-1, 0, -0.0005])
    def test_seconds_non_positive_rejected(self, bad):
        # 含亚毫秒级负数：禁止 ceil 后翻成正时长
        with pytest.raises(ValueError):
            Duration.seconds(bad)

    @pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf")])
    def test_seconds_non_finite_rejected(self, bad):
        with pytest.raises(ValueError):
            Duration.seconds(bad)

    def test_milliseconds_factory(self):
        assert Duration.milliseconds(500).milliseconds == 500

    def test_zero_rejected(self):
        with pytest.raises(ValueError):
            Duration(milliseconds=0)

    def test_negative_rejected(self):
        with pytest.raises(ValueError):
            Duration(milliseconds=-5)

    @pytest.mark.parametrize("bad", [True, "100", 1.5, None])
    def test_non_int_rejected(self, bad):
        with pytest.raises(TypeError):
            Duration(milliseconds=bad)

    def test_frozen(self):
        duration = Duration.seconds(10)
        with pytest.raises(Exception):
            duration.milliseconds = 20  # type: ignore[misc]

    def test_str_for_logging(self):
        assert str(Duration.seconds(2)) == "2000ms"


class TestScanBatch:
    def test_freezes_items_to_tuple(self):
        batch = ScanBatch(cursor=0, items=["a", "b"])
        assert batch.items == ("a", "b")
        assert isinstance(batch.items, tuple)

    def test_negative_cursor_rejected(self):
        with pytest.raises(ValueError):
            ScanBatch(cursor=-1, items=())


class TestGeoMember:
    def test_valid(self):
        member = GeoMember(longitude=116.4, latitude=39.9, member="beijing")
        assert member.member == "beijing"

    def test_longitude_out_of_range(self):
        with pytest.raises(ValueError):
            GeoMember(longitude=200.0, latitude=0.0, member="x")

    def test_latitude_out_of_range(self):
        with pytest.raises(ValueError):
            GeoMember(longitude=0.0, latitude=-91.0, member="x")


class TestEnums:
    def test_set_condition_values(self):
        assert SetCondition.NX.value == "nx"
        assert SetCondition.XX.value == "xx"

    def test_geo_units(self):
        assert {unit.value for unit in GeoUnit} == {"m", "km", "ft", "mi"}
