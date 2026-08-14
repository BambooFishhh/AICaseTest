package com.testagent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * v5.2: 运行态存储选择。APP_REDIS_ENABLED=true 时使用 Redis，否则使用内存。
 */
@Configuration
public class RuntimeStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
    public RuntimeStore redisRuntimeStore(StringRedisTemplate redisTemplate) {
        return new RedisRuntimeStore(redisTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
    public RuntimeStore memoryRuntimeStore() {
        return new MemoryRuntimeStore();
    }
}
