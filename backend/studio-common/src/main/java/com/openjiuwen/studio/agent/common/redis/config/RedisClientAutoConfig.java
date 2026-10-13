/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 */

package com.openjiuwen.studio.agent.common.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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

        ObjectMapper redisObjectMapper =
            objectMapper != null ? objectMapper.copy() : new ObjectMapper();

        redisObjectMapper.registerModule(new JavaTimeModule());
        redisObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        redisObjectMapper.setVisibility(
            com.fasterxml.jackson.annotation.PropertyAccessor.ALL,
            JsonAutoDetect.Visibility.ANY);
        redisObjectMapper.activateDefaultTyping(redisObjectMapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        Jackson2JsonRedisSerializer<Object> serializer =
            new Jackson2JsonRedisSerializer<>(redisObjectMapper, Object.class);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}
