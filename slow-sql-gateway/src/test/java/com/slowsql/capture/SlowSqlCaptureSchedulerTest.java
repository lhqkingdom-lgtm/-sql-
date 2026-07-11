package com.slowsql.capture;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import com.slowsql.gateway.DiagnosisTaskProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlowSqlCaptureSchedulerTest {

    private SlowSqlCaptureScheduler scheduler;
    private DataSourceManager dataSourceManager;
    private SqlMonitorProperties properties;
    private FingerprintDedupService dedupService;
    private CapturedSqlRepository repository;
    private DiagnosisTaskProducer taskProducer;
    private ImNotificationService notifier;
    private EventNormalizer normalizer;
    private HttpCaptureController httpCapture;
    private CaptureStatusController captureStatus;

    @BeforeEach
    void setUp() {
        dataSourceManager = mock(DataSourceManager.class);
        properties = new SqlMonitorProperties();
        dedupService = mock(FingerprintDedupService.class);
        repository = mock(CapturedSqlRepository.class);
        taskProducer = mock(DiagnosisTaskProducer.class);
        notifier = mock(ImNotificationService.class);
        normalizer = new EventNormalizer();
        httpCapture = mock(HttpCaptureController.class);
        captureStatus = mock(CaptureStatusController.class);

        scheduler = new SlowSqlCaptureScheduler(
                dataSourceManager, properties, dedupService, repository,
                taskProducer, notifier, normalizer, httpCapture, captureStatus);
    }

    // ===== 采集开关关闭 =====

    @Test
    void poll_shouldSkipWhenCaptureDisabled() {
        properties.getCapture().setEnabled(false);

        scheduler.poll();

        verifyNoInteractions(dataSourceManager);
    }

    // ===== 无就绪实例 =====

    @Test
    void poll_shouldSkipWhenNoReadyInstances() {
        properties.getCapture().setEnabled(true);
        when(dataSourceManager.getReadyInstanceIds()).thenReturn(Set.of());

        scheduler.poll();

        verify(dataSourceManager).getReadyInstanceIds();
    }

    // ===== 慢日志无数据 =====

    @Test
    @SuppressWarnings("unchecked")
    void poll_shouldHandleEmptySlowLog() {
        properties.getCapture().setEnabled(true);
        when(dataSourceManager.getReadyInstanceIds()).thenReturn(Set.of("tc-dev-mysql"));
        when(dataSourceManager.findProjectCode("tc-dev-mysql")).thenReturn("tongcheng-club");

        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(dataSourceManager.getMonitoringTemplate("tc-dev-mysql")).thenReturn(jt);
        doReturn(List.of()).when(jt).queryForList(anyString(), any(Timestamp.class));

        scheduler.poll();

        verify(taskProducer, never()).sendNormal(anyMap());
    }

    // ===== 噪声 SQL 过滤 =====

    @Test
    @SuppressWarnings("unchecked")
    void poll_shouldFilterNoiseSql() {
        properties.getCapture().setEnabled(true);
        when(dataSourceManager.getReadyInstanceIds()).thenReturn(Set.of("tc-dev-mysql"));
        when(dataSourceManager.findProjectCode("tc-dev-mysql")).thenReturn("tongcheng-club");

        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(dataSourceManager.getMonitoringTemplate("tc-dev-mysql")).thenReturn(jt);

        // 返回一条 SHOW 语句（噪声），一条 SELECT（正常）
        Map<String, Object> noiseRow = Map.of(
                "sql_text", "SHOW GLOBAL VARIABLES LIKE 'innodb_buffer_pool_size'",
                "query_time", 0.5, "lock_time", 0.0, "rows_examined", 0L,
                "rows_sent", 10L, "start_time", Timestamp.valueOf("2026-07-06 10:00:00"));
        Map<String, Object> validRow = Map.of(
                "sql_text", "SELECT * FROM orders WHERE status = 'pending'",
                "query_time", 5.0, "lock_time", 0.1, "rows_examined", 100000L,
                "rows_sent", 100L, "start_time", Timestamp.valueOf("2026-07-06 10:01:00"));

        doReturn(List.of(noiseRow, validRow)).when(jt).queryForList(anyString(), any(Timestamp.class));
        when(dedupService.tryRegister(anyString())).thenReturn(true);

        scheduler.poll();

        // 噪声被跳过，只投递了 validRow 对应的任务
        verify(taskProducer, times(1)).sendNormal(anyMap());
    }

    // ===== 指纹去重——命中缓存跳过诊断 =====

    @Test
    @SuppressWarnings("unchecked")
    void poll_shouldSkipDuplicateFingerprint() {
        properties.getCapture().setEnabled(true);
        when(dataSourceManager.getReadyInstanceIds()).thenReturn(Set.of("tc-dev-mysql"));
        when(dataSourceManager.findProjectCode("tc-dev-mysql")).thenReturn("tongcheng-club");

        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(dataSourceManager.getMonitoringTemplate("tc-dev-mysql")).thenReturn(jt);

        Map<String, Object> row = Map.of(
                "sql_text", "SELECT * FROM orders WHERE id = 1",
                "query_time", 5.0, "lock_time", 0.0, "rows_examined", 100000L,
                "rows_sent", 10L, "start_time", Timestamp.valueOf("2026-07-06 10:00:00"));
        doReturn(List.of(row)).when(jt).queryForList(anyString(), any(Timestamp.class));

        // 指纹已存在 → 跳过诊断
        when(dedupService.tryRegister(anyString())).thenReturn(false);
        when(repository.findByFingerprint(anyString())).thenReturn(mock(CapturedSql.class));

        scheduler.poll();

        // 只更新出现次数，不投递新任务
        verify(repository, times(1)).upsert(any(CapturedSql.class));
        verify(taskProducer, never()).sendNormal(anyMap());
    }

    // ===== 采集异常不崩溃 =====

    @Test
    void poll_shouldSurviveException() {
        properties.getCapture().setEnabled(true);
        when(dataSourceManager.getReadyInstanceIds()).thenReturn(Set.of("tc-dev-mysql"));
        when(dataSourceManager.findProjectCode("tc-dev-mysql")).thenThrow(new RuntimeException("DB down"));

        // 不应抛异常
        assertDoesNotThrow(() -> scheduler.poll());
    }
}
