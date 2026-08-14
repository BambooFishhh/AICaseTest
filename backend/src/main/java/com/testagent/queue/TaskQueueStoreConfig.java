package com.testagent.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * v5.3: 任务队列存储选择。
 */
@Configuration
public class TaskQueueStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
    public TaskQueueStore redisTaskQueueStore(StringRedisTemplate redisTemplate) {
        return new RedisTaskQueueStore(redisTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
    public TaskQueueStore memoryTaskQueueStore() {
        return new MemoryTaskQueueStore();
    }
}
