/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 */

package com.openjiuwen.studio.agent.common.redis.spi;

import java.util.List;
import java.util.Map;

/**
 * Redis Provider 配置上下文——工厂创建客户端时的完整配置载体。
 *
 * 框架负责从配置文件读取并解密通用连接参数，以本对象传给 Provider 工厂；
 * 企业专属参数（如 NOS 的 appId/passport/maxAttempts）经 getRawConfig() 透传，
 * 框架不解析其内部字段。
 */
public class RedisProviderContext {

    /** 主机地址 */
    private final String host;
    /** 端口 */
    private final int port;
    /** 已解密的密码 */
    private final String password;
    /** 数据库编号（单机模式兼容配置，集群模式忽略） */
    private final int database;
    /** 连接池最小空闲数 */
    private final int connectionMinimumIdleSize;
    /** 连接池最大连接数 */
    private final int connectionPoolSize;
    /** 集群节点地址列表 */
    private final List<String> clusterNodeList;
    /** 哨兵 master 名称 */
    private final String sentinelMasterName;
    /** 哨兵节点地址列表 */
    private final List<String> sentinelNodeList;
    /** 哨兵目标端口列表 */
    private final List<String> sentinelTargetPorts;
    /** 企业专属参数透传（来自 redis.provider-config / redis.provider-config-file） */
    private final Map<String, Object> rawConfig;

    public RedisProviderContext(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.password = builder.password;
        this.database = builder.database;
        this.connectionMinimumIdleSize = builder.connectionMinimumIdleSize;
        this.connectionPoolSize = builder.connectionPoolSize;
        this.clusterNodeList = builder.clusterNodeList;
        this.sentinelMasterName = builder.sentinelMasterName;
        this.sentinelNodeList = builder.sentinelNodeList;
        this.sentinelTargetPorts = builder.sentinelTargetPorts;
        this.rawConfig = builder.rawConfig;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getPassword() {
        return password;
    }

    public int getDatabase() {
        return database;
    }

    public int getConnectionMinimumIdleSize() {
        return connectionMinimumIdleSize;
    }

    public int getConnectionPoolSize() {
        return connectionPoolSize;
    }

    public List<String> getClusterNodeList() {
        return clusterNodeList;
    }

    public String getSentinelMasterName() {
        return sentinelMasterName;
    }

    public List<String> getSentinelNodeList() {
        return sentinelNodeList;
    }

    public List<String> getSentinelTargetPorts() {
        return sentinelTargetPorts;
    }

    /**
     * 获取企业专属参数。
     *
     * @param key 参数名
     * @return 参数值；未配置返回 null
     */
    public Object getRawConfig(String key) {
        return rawConfig != null ? rawConfig.get(key) : null;
    }

    /**
     * 获取企业专属参数（带类型检查与默认值）。
     *
     * @param key 参数名
     * @param defaultValue 默认值（同时是类型锚点：非 null 时按其类型校验）
     * @param <T> 目标类型
     * @return 参数值；未配置或类型不匹配返回默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getRawConfig(String key, T defaultValue) {
        Object value = getRawConfig(key);
        if (value == null || defaultValue == null) {
            return defaultValue;
        }
        // 泛型擦除下 (T) value 不会在本方法内抛 CCE（会延迟到调用方 checkcast），
        // 必须显式按 defaultValue 的运行时类型校验——如 JSON 数字 Integer 传给 Long 锚点。
        if (!defaultValue.getClass().isInstance(value)) {
            return defaultValue;
        }
        return (T) value;
    }

    /**
     * 获取全部企业专属参数（只读视图）。
     *
     * @return 不可修改的参数 Map；未配置返回空 Map（JSON null 值项被剔除）
     */
    public Map<String, Object> getRawConfigMap() {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> nonNull = new java.util.HashMap<>();
        rawConfig.forEach((k, v) -> {
            if (v != null) {
                nonNull.put(k, v);
            }
        });
        return Map.copyOf(nonNull);
    }

    /**
     * Builder 模式构造上下文。
     */
    public static class Builder {
        private String host;
        private int port;
        private String password;
        private int database = 0;
        private int connectionMinimumIdleSize = 24;
        private int connectionPoolSize = 64;
        private List<String> clusterNodeList = List.of();
        private String sentinelMasterName;
        private List<String> sentinelNodeList = List.of();
        private List<String> sentinelTargetPorts = List.of();
        private Map<String, Object> rawConfig = Map.of();

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder connectionMinimumIdleSize(int size) {
            this.connectionMinimumIdleSize = size;
            return this;
        }

        public Builder connectionPoolSize(int size) {
            this.connectionPoolSize = size;
            return this;
        }

        public Builder clusterNodeList(List<String> nodeList) {
            this.clusterNodeList = nodeList != null ? List.copyOf(nodeList) : List.of();
            return this;
        }

        public Builder sentinelMasterName(String masterName) {
            this.sentinelMasterName = masterName;
            return this;
        }

        public Builder sentinelNodeList(List<String> nodeList) {
            this.sentinelNodeList = nodeList != null ? List.copyOf(nodeList) : List.of();
            return this;
        }

        public Builder sentinelTargetPorts(List<String> ports) {
            this.sentinelTargetPorts = ports != null ? List.copyOf(ports) : List.of();
            return this;
        }

        public Builder rawConfig(Map<String, Object> rawConfig) {
            if (rawConfig != null) {
                // Map.copyOf 拒绝 null 值（JSON "key": null 场景），此处过滤为显式契约
                Map<String, Object> nonNull = new java.util.HashMap<>();
                rawConfig.forEach((k, v) -> {
                    if (v != null) {
                        nonNull.put(k, v);
                    }
                });
                this.rawConfig = Map.copyOf(nonNull);
            } else {
                this.rawConfig = Map.of();
            }
            return this;
        }

        public RedisProviderContext build() {
            return new RedisProviderContext(this);
        }
    }
}
