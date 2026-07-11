package com.slowsql.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slowsql.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 诊断任务投递器。
 * 正常路径：RabbitMQ；降级路径：Redis List。
 */
@Component
public class DiagnosisTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisTaskProducer.class);

    private static final String FALLBACK_KEY = "diagnosis:fallback:queue";
    private static final Duration FALLBACK_TTL = Duration.ofHours(2);

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DiagnosisTaskProducer(RabbitTemplate rabbitTemplate, StringRedisTemplate redis,
                                  ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 投递诊断任务到队列（自动采集）。
     */
    public void sendNormal(Map<String, Object> taskMsg) {
        send(taskMsg, "task.normal");
    }

    private void send(Map<String, Object> taskMsg, String routingKey) {
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, taskMsg);
            log.debug("诊断任务已投递: routing={}", routingKey);
        } catch (AmqpException e) {
            log.warn("RabbitMQ 不可达，降级到 Redis: {}", e.getMessage());
            try {
                fallbackToRedis(objectMapper.writeValueAsString(taskMsg));
            } catch (JsonProcessingException ex) {
                log.error("降级序列化失败: {}", ex.getMessage());
            }
        }
    }

    private void fallbackToRedis(String json) {
        try {
            redis.opsForList().rightPush(FALLBACK_KEY, json);
            redis.expire(FALLBACK_KEY, FALLBACK_TTL);
            log.info("诊断任务已写入降级队列, 当前积压: {}", redis.opsForList().size(FALLBACK_KEY));
        } catch (Exception e) {
            log.error("Redis降级队列写入失败: {}", e.getMessage());
        }
    }

    /** 降级队列积压数量 */
    public long getFallbackSize() {
        try {
            Long size = redis.opsForList().size(FALLBACK_KEY);
            return size != null ? size : 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
