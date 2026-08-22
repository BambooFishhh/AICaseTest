package com.testagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v6.8: Redis Streams 任务事件总线。事件只是快速触发信号，
 * agent_task（DB）仍是任务事实源；Redis 不可用时 DB 轮询兜底。
 */
@Component
public class TaskEventStreamService {

    private static final Logger log = LoggerFactory.getLogger(TaskEventStreamService.class);
    private static final String STREAM = "aicasetest:task:events";
    private static final String GROUP = "ha-workers";

    @Autowired(required = false)
    private StringRedisTemplate redis;

    public boolean publish(String taskId) {
        try {
            if (redis == null) {
                return false;
            }
            ensureGroup();
            redis.opsForStream().add(STREAM, Map.of("taskId", taskId));
            return true;
        } catch (Exception e) {
            log.warn("Task event publish failed for {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    public List<String> consume(int batchSize) {
        List<String> taskIds = new ArrayList<>();
        try {
            if (redis == null) {
                return taskIds;
            }
            ensureGroup();
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(GROUP, "worker-" + UUID.randomUUID().toString().substring(0, 8)),
                    StreamReadOptions.empty().count(batchSize).block(Duration.ofSeconds(1)),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
            if (records != null) {
                for (MapRecord<String, Object, Object> record : records) {
                    Object value = record.getValue().get("taskId");
                    if (value != null) {
                        taskIds.add(String.valueOf(value));
                    }
                    // 事件丢失可接受：DB 轮询会兜底，因此读取后立即确认
                    redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Task event consume failed: {}", e.getMessage());
        }
        return taskIds;
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, GROUP);
        } catch (Exception ignored) {
            // 组已存在或 Redis 不可用
        }
    }
}
