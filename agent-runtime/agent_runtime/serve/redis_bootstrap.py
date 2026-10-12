# -*- coding: UTF-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Redis Provider 适配层启动装配（Phase 2 过渡接线，设计文档 §十）。

平台装配层职责（redis_spi.config 约定「平台装配层 = Phase 2 的 server lifespan」）：
  - 读取 common_utils.RedisSettings（password 已在其 field_validator 层解密）；
  - 映射为 redis_spi.RedisProviderSettings：连接字段同名搬运，Provider 装配字段
    （REDIS_PROVIDER / REDIS_PROVIDER_CLASS / 池尺寸等）由其自身 env 别名读取；
  - 经 redis_spi.accessor 完成进程级装配（RedisProviderFactory 内置 ping fail-fast）；
  - 过渡期向既有注入点（init_ltm / checkpointer / FastRedisCheckpointer）提供
    redis-py 原生视图（as_native_client，Q3）——Phase 4 业务全量迁移后移除。

行为对齐（Phase 2 验证口径：行为零变化）：
  - 未配置（REDIS_HOST / REDIS_CLUSTER_NODES 全空）：返回 None，由 lifespan 抛出
    与旧实现一致的 RuntimeError；
  - 连接/命令类装配失败：抛 RuntimeError("Redis connection check failed: ...")
    （对齐旧 lifespan 文案）；配置类错误（RedisConfigError）原样上抛
    （对齐旧 init() 对配置异常的透传）；
  - 缺省 native（REDIS_PROVIDER 未配置）= redis-py 直连，连接参数与旧
    RedisClientManager 逐字段一致。
"""

from __future__ import annotations

import logging
from typing import Any

from common_utils.common_config import RedisSettings
from redis_native.compat import as_native_client
from redis_spi import RedisMode, RedisProviderSettings, accessor
from redis_spi.exceptions import RedisConfigError, RedisError

logger = logging.getLogger(__name__)

#: 两包 Settings 的同名连接字段（password 已在 RedisSettings 层解密后搬运）。
#: mode 不在此列：两包各有一套同名同值枚举，按 value 重导出（不做类身份耦合）。
_SHARED_CONNECTION_FIELDS = (
    "host",
    "port",
    "db",
    "password",
    "redis_transaction_enabled",
    "cluster_nodes",
    "sentinel_master",
    "sentinel_nodes",
    "max_connections",
    "socket_timeout",
    "socket_connect_timeout",
    "ssl_enabled",
    "ssl_ca_cert",
    "ssl_cert_file",
    "ssl_key_file",
    "datasource_ttl_seconds",
)


def build_provider_settings(
    legacy: RedisSettings | None = None,
) -> RedisProviderSettings:
    """common_utils.RedisSettings → redis_spi.RedisProviderSettings。

    - 连接字段同名搬运（socket_timeout int→float 由 pydantic 宽化，数值不变）；
    - Provider 装配字段不搬运：由 RedisProviderSettings 自身 env 别名读取，
      REDIS_PROVIDER 未配置时缺省 native（redis-py 直连）。
    """
    legacy = legacy if legacy is not None else RedisSettings()
    overrides: dict[str, Any] = {
        field: getattr(legacy, field) for field in _SHARED_CONNECTION_FIELDS
    }
    overrides["mode"] = RedisMode(legacy.mode.value)
    return RedisProviderSettings(_env_file=None, **overrides)


async def init_redis_provider(settings: RedisSettings | None = None) -> Any:
    """装配 Redis Provider，返回 redis-py 原生视图（过渡期注入面）。

    - settings 缺省时按环境构造（与旧 RedisClientManager.init() 同源同判据）；
    - 未配置返回 None（由 lifespan 转为旧口径 RuntimeError）；
    - 幂等（accessor.initialize 语义）：重复调用复用既有装配；
    - 返回值（主池客户端）与 common_utils.get_redis_client() 过渡委托返回
      同一对象，保持旧「全局单客户端身份」不变量。
    """
    legacy = settings if settings is not None else RedisSettings()
    if not legacy.host and not legacy.cluster_nodes:
        logger.warning("Redis not configured, provider not initialized.")
        return None

    provider_settings = build_provider_settings(legacy)
    try:
        client = await accessor.initialize(provider_settings)
    except RedisConfigError:
        # 配置类错误原样上抛（对齐旧 init() 对配置异常的透传行为）
        raise
    except RedisError as exc:
        # 连接/命令类失败对齐旧 lifespan 口径（RuntimeError 文案不变）
        raise RuntimeError(f"Redis connection check failed: {exc}") from exc

    native = as_native_client(client)
    logger.info(
        "Redis client initialized: provider=%s, mode=%s, host=%s:%s",
        provider_settings.provider,
        provider_settings.mode.value,
        provider_settings.host,
        provider_settings.port,
    )
    return native
