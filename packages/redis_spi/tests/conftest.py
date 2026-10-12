# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""redis_spi 单测公共 fixture：每用例隔离 accessor / registry 全局态。"""

import pytest

import redis_spi.accessor as accessor
import redis_spi.registry as registry


@pytest.fixture(autouse=True)
def _isolate_spi_state():
    """用例前后清空进程级单例，避免用例间串扰。"""
    accessor.reset()
    registry.reset()
    yield
    accessor.reset()
    registry.reset()


@pytest.fixture(autouse=True)
def _isolate_cwd(tmp_path, monkeypatch):
    """隔离 CWD：.env 按 CWD 相对解析，避免开发目录的 .env 泄入 Settings。"""
    monkeypatch.chdir(tmp_path)
