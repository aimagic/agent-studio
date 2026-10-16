# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""model_service UT 共享 path 修正。

pytest 的 AssertionRewritingHook 会盖住 .pth 装的 editable finder，
导致 packages/ 下的 editable 包在测试里 import 不到（收集阶段即
``ModuleNotFoundError: No module named 'model_service.client'; 'model_service'
is not a package``）。这里手动把三个共享包源码目录插回 sys.path，
model_service.client / dispatch / policy / resolver 等模块才能正常 init。
"""

import os
import sys

_REPO_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "..")
)

for _pkg in ("common_utils", "storage", "model_service"):
    _pkg_path = os.path.join(_REPO_ROOT, "packages", _pkg)
    if os.path.isdir(_pkg_path) and _pkg_path not in sys.path:
        sys.path.insert(0, _pkg_path)
