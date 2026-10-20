/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 */

package com.openjiuwen.studio.agent.common.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate 第二栈装配（Lettuce 栈）。
 *
 * Q11 拍板：保留一版兜底，业务侧已零直用（会话/SAML 已收口到 RedisClient 单栈），
 * 后续版本移除。
 *
 * RedisClient 主路径（Provider 单栈，缺省 Redisson）的装配在
 * studio-redis-redisson 模块的 RedisProviderAutoConfig。
 */
@Configuration
@Lazy
public class RedisClientAutoConfig {

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /**
     * RedisTemplate Bean（保留一版兜底，业务侧已零直用）。
     * 依赖 spring.data.redis（Lettuce）连接工厂，与 RedisClient 主路径相互独立。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        applyJsonSerialization(template, buildRedisObjectMapper());
        return template;
    }

    /**
     * 值序列化专用 ObjectMapper：时间类型按 ISO 文本落库、全字段可见、
     * 多态类型信息保留。
     *
     * <ul>
     *   <li>{@code JavaTimeModule} + 关闭时间戳写出：{@code LocalDateTime} 等
     *       JSR-310 类型序列化为可读文本，回读不依赖应用时区；</li>
     *   <li>多态激活取 {@code NON_FINAL}（而非 EVERYTHING）：兼容既有缓存值
     *       以 {@code @class} 属性落库的历史数据——关闭会丢失具体类型、
     *       范围过宽则扩大反序列化攻击面；类型校验器用 mapper 自带的
     *       {@code LaissezFaire} 口径，仅放行已注册/基础类型。</li>
     * </ul>
     */
    private ObjectMapper buildRedisObjectMapper() {
        ObjectMapper mapper = objectMapper != null ? objectMapper.copy() : new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    /**
     * 键（含哈希键）统一字符串序列化——key 可读、与 redis-cli 运维直查兼容；
     * 值与哈希值共用同一 JSON 序列化器保证同构。显式 {@code afterPropertiesSet}
     * 让序列化器缺失等问题在 Bean 装配期即失败，而非首次读写时。
     */
    private void applyJsonSerialization(RedisTemplate<String, Object> template, ObjectMapper mapper) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<Object> valueSerializer =
            new Jackson2JsonRedisSerializer<>(mapper, Object.class);
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
    }
}
