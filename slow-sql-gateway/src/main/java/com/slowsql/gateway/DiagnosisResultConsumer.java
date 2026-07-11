package com.slowsql.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.slowsql.capture.CapturedSql;
import com.slowsql.capture.CapturedSqlRepository;
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
    private final CapturedSqlRepository capturedRepo;
    private final ObjectMapper objectMapper;

    public DiagnosisResultConsumer(StringRedisTemplate redis,
                                    DiagnosisRecordRepository recordRepository,
                                    CapturedSqlRepository capturedRepo,
                                    ObjectMapper objectMapper) {
        this.redis = redis;
        this.recordRepository = recordRepository;
        this.capturedRepo = capturedRepo;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_DONE, ackMode = "MANUAL")
    public void onResult(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(body, Map.class);
            Object tidObj = result.get("taskId");
            String taskId = tidObj != null ? String.valueOf(tidObj) : null;
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

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 消息格式错误 → 不可恢复 → ACK丢弃 + 记日志
            log.error("诊断结果消息格式错误，丢弃: {}", e.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // DB写入失败等其他异常 → 可重试 → NACK requeue
            log.warn("处理诊断结果异常，requeue: {}", e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void updateRecord(String taskId, Map<String, Object> result) {
        try {
            String finger = result.get("fingerprint") instanceof String s ? s : "";
            String iid = result.get("instanceId") instanceof String s ? s : "";
            String pcode = result.get("projectCode") instanceof String s ? s : "";
            String src = result.get("source") instanceof String s ? s : "auto";
            String report = result.get("report") instanceof String s ? s : null;

            DiagnosisRecord record = recordRepository.findByTaskId(taskId);
            if (record == null) {
                record = new DiagnosisRecord();
                record.setTaskId(taskId);
                record.setSessionId(taskId);
                record.setInstanceId(iid);
                record.setProjectCode(pcode);
                record.setSource(src);
                record.setFingerprint(finger);
                record.setOriginalSql("");
                record.setCleanSql("");
                record.setCreatedAt(LocalDateTime.now());
            } else {
                if (!iid.isEmpty()) record.setInstanceId(iid);
                if (!pcode.isEmpty()) record.setProjectCode(pcode);
                if (!src.isEmpty()) record.setSource(src);
                if (!finger.isEmpty()) record.setFingerprint(finger);
            }
            record.setStatus((String) result.getOrDefault("status", DiagnosisRecord.STATUS_FAILED));
            if (report != null) record.setReport(report);
            if (result.get("error") instanceof String s) record.setErrorMessage(s);
            if (result.get("durationMs") instanceof Number n) record.setDurationMs(n.longValue());
            if (result.get("toolCallCount") instanceof Number n) record.setToolCallCount(n.intValue());
            record.setUpdatedAt(LocalDateTime.now());
            recordRepository.save(record);

            // 回写 captured_sql.diagnosis_report
            if (!finger.isEmpty() && report != null) {
                try {
                    com.slowsql.capture.CapturedSql cs = capturedRepo.findByFingerprint(finger);
                    if (cs != null) {
                        cs.setDiagnosisReport(report);
                        capturedRepo.upsert(cs);
                        log.info("诊断报告已回写 captured_sql: fingerprint={}", finger.substring(0, 8));
                    }
                } catch (Exception e) {
                    log.debug("回写 captured_sql 失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("更新诊断记录失败 (不影响主流程): {}", e.getMessage());
        }
    }
}
