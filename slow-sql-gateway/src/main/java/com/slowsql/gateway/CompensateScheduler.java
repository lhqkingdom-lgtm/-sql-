package com.slowsql.gateway;

import com.slowsql.persistence.DiagnosisRecord;
import com.slowsql.persistence.DiagnosisRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 定时补偿任务——每5分钟执行。
 */
@Component
public class CompensateScheduler {

    private static final Logger log = LoggerFactory.getLogger(CompensateScheduler.class);

    private final StringRedisTemplate redis;
    private final DiagnosisRecordRepository recordRepository;
    private final DiagnosisTaskProducer taskProducer;
    private final SseEmitterManager sseEmitterManager;

    public CompensateScheduler(StringRedisTemplate redis,
                                DiagnosisRecordRepository recordRepository,
                                DiagnosisTaskProducer taskProducer,
                                SseEmitterManager sseEmitterManager) {
        this.redis = redis;
        this.recordRepository = recordRepository;
        this.taskProducer = taskProducer;
        this.sseEmitterManager = sseEmitterManager;
    }

    /** 1. 超时检测：running > 180s 的任务 → 标记 FAILED */
    @Scheduled(fixedDelay = 300_000)
    public void detectStaleTasks() {
        try {
            List<DiagnosisRecord> stale = recordRepository.findStaleRunningTasks(180);
            for (DiagnosisRecord r : stale) {
                r.setStatus(DiagnosisRecord.STATUS_FAILED);
                r.setErrorMessage("诊断超时（Agent可能异常退出）");
                r.setUpdatedAt(LocalDateTime.now());
                recordRepository.save(r);
                sseEmitterManager.pushFailed(r.getTaskId(), r.getErrorMessage());
                log.warn("超时任务已标记FAILED: {}", r.getTaskId());
            }
        } catch (Exception e) {
            log.warn("超时检测异常: {}", e.getMessage());
        }
    }

    /** 2. 补偿补写：Redis有结果但DB缺失的记录 */
    @Scheduled(fixedDelay = 300_000)
    public void compensateMissingRecords() {
        try {
            Set<String> redisKeys = redis.keys("diagnosis:result:*");
            if (redisKeys == null || redisKeys.isEmpty()) return;

            List<String> dbTaskIds = recordRepository.findCompletedTaskIds(30);
            for (String key : redisKeys) {
                String taskId = key.replace("diagnosis:result:", "");
                if (!dbTaskIds.contains(taskId)) {
                    String report = redis.opsForValue().get(key);
                    if (report != null) {
                        DiagnosisRecord record = recordRepository.findByTaskId(taskId);
                        if (record != null) {
                            record.setReport(report);
                            record.setStatus(DiagnosisRecord.STATUS_COMPLETED);
                            record.setUpdatedAt(LocalDateTime.now());
                            recordRepository.save(record);
                            log.info("补偿补写: {}", taskId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("补偿任务异常: {}", e.getMessage());
        }
    }

    /** 3. 指纹缓存重建 */
    @Scheduled(fixedDelay = 300_000)
    public void rebuildFingerprintCache() {
        try {
            Set<String> fpKeys = redis.keys("diagnosis:result:fp:*");
            List<String> dbTaskIds = recordRepository.findCompletedTaskIds(30);
            // 重建缺失的指纹缓存
            for (String taskId : dbTaskIds) {
                String fpKey = "diagnosis:result:fp:" + taskId.hashCode();
                if (fpKeys == null || !fpKeys.contains(fpKey)) {
                    DiagnosisRecord r = recordRepository.findByTaskId(taskId);
                    if (r != null && r.getFingerprint() != null && r.getReport() != null) {
                        redis.opsForValue().set("diagnosis:result:fp:" + r.getFingerprint(),
                                r.getReport(), java.time.Duration.ofMinutes(30));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("缓存重建异常: {}", e.getMessage());
        }
    }

    /** 4. 清理过期记录 */
    @Scheduled(cron = "0 0 3 * * ?") // 每天凌晨3点
    public void cleanupOldRecords() {
        int deleted = recordRepository.deleteOlderThan(90);
        if (deleted > 0) log.info("清理过期诊断记录: {} 条", deleted);
    }
}
