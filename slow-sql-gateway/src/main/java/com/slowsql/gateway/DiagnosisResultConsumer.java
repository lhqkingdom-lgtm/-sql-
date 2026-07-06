package com.slowsql.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.slowsql.config.RabbitMqConfig;
import com.slowsql.persistence.DiagnosisRecord;
import com.slowsql.persistence.DiagnosisRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 诊断结果消费者——从 done.queue 拿 Python Agent 的结果，
 * 写入 Redis（前端 SSE 读取）并异步落库。
 */
@Component
public class DiagnosisResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisResultConsumer.class);

    private static final String RESULT_KEY_PREFIX = "diagnosis:result:";
    private static final String TASK_KEY_PREFIX = "diagnosis:task:";
    private static final Duration RESULT_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final DiagnosisRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public DiagnosisResultConsumer(StringRedisTemplate redis,
                                    DiagnosisRecordRepository recordRepository,
                                    ObjectMapper objectMapper) {
        this.redis = redis;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_DONE, ackMode = "MANUAL")
    public void onResult(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(body, Map.class);
            String taskId = (String) result.get("taskId");
            String status = (String) result.get("status");

            if (taskId == null) {
                log.warn("结果消息缺少 taskId，丢弃");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 写入 Redis（前端 SSE 读取）
            if ("completed".equals(status)) {
                String report = (String) result.getOrDefault("report", "");
                redis.opsForValue().set(RESULT_KEY_PREFIX + taskId, report, RESULT_TTL);
                redis.opsForHash().put(TASK_KEY_PREFIX + taskId, "status", "completed");
                redis.opsForHash().put(TASK_KEY_PREFIX + taskId, "updatedAt", LocalDateTime.now().toString());
                redis.expire(TASK_KEY_PREFIX + taskId, RESULT_TTL);
            } else {
                String error = (String) result.getOrDefault("error", "未知错误");
                redis.opsForValue().set(RESULT_KEY_PREFIX + taskId, "诊断失败: " + error, RESULT_TTL);
                redis.opsForHash().put(TASK_KEY_PREFIX + taskId, "status", "failed");
                redis.opsForHash().put(TASK_KEY_PREFIX + taskId, "error", error);
            }

            // 异步落库
            updateRecord(taskId, result);

            channel.basicAck(deliveryTag, false);
            log.info("诊断结果已处理: taskId={}, status={}", taskId, status);

        } catch (Exception e) {
            log.error("处理诊断结果失败，requeue: {}", e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void updateRecord(String taskId, Map<String, Object> result) {
        try {
            DiagnosisRecord record = recordRepository.findByTaskId(taskId);
            if (record != null) {
                record.setStatus((String) result.getOrDefault("status", DiagnosisRecord.STATUS_FAILED));
                record.setReport((String) result.get("report"));
                record.setErrorMessage((String) result.get("error"));
                if (result.get("durationMs") instanceof Number n) {
                    record.setDurationMs(n.longValue());
                }
                if (result.get("toolCallCount") instanceof Number n) {
                    record.setToolCallCount(n.intValue());
                }
                record.setUpdatedAt(LocalDateTime.now());
                recordRepository.save(record);
            }
        } catch (Exception e) {
            log.warn("更新诊断记录失败 (不影响主流程): {}", e.getMessage());
        }
    }
}
