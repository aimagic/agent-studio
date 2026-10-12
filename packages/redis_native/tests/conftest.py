# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""redis_native 单测公共 fixture。"""

import pytest


@pytest.fixture(autouse=True)
def _isolate_cwd(tmp_path, monkeypatch):
    """隔离 CWD：.env 按 CWD 相对解析，避免开发目录的 .env 泄入 Settings。"""
    monkeypatch.chdir(tmp_path)
