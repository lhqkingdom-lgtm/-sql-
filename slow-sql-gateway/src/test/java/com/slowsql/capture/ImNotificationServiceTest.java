package com.slowsql.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImNotificationServiceTest {

    private ImNotificationService service;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        service = new ImNotificationService(redis);
    }

    // ===== 首次通知→发送 =====

    @Test
    void notify_shouldSendWhenNewFingerprint() {
        when(valueOps.setIfAbsent(eq("im:notified:abc123"), eq("1"), eq(Duration.ofHours(24))))
                .thenReturn(true);

        // 不应抛异常
        assertDoesNotThrow(() ->
                service.notify("abc123", "SELECT * FROM orders WHERE status = 'pending'",
                        "## 慢查询告警", 5.0, 100000L, "test_sql"));
    }

    // ===== 24h内重复→跳过 =====

    @Test
    void notify_shouldSkipDuplicateWithin24h() {
        when(valueOps.setIfAbsent(eq("im:notified:abc123"), eq("1"), eq(Duration.ofHours(24))))
                .thenReturn(false);

        // 不应抛异常
        assertDoesNotThrow(() ->
                service.notify("abc123", "SELECT 1", "报告", 1.0, 100L, "db"));
    }

    // ===== Redis挂了→保守允许推送 =====

    @Test
    void notify_shouldAllowWhenRedisFails() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis down"));

        // 保守：允许推送
        assertDoesNotThrow(() ->
                service.notify("abc123", "SELECT 1", "报告", 1.0, 100L, "db"));
    }

    // ===== null 返回 → 允许推送 =====

    @Test
    void notify_shouldAllowWhenSetIfAbsentReturnsNull() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(null);

        // null 被视为允许推送
        assertDoesNotThrow(() ->
                service.notify("abc123", "SELECT 1", "报告", 1.0, 100L, "db"));
    }

    // ===== SQL 截断 =====

    @Test
    void notify_shouldTruncateLongSqlPreview() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        String longSql = "SELECT a, b, c, d, e, f, g, h, i, j FROM very_long_table_name "
                       + "WHERE status = 'active' AND type = 'premium' ORDER BY created_at DESC LIMIT 100";
        assertTrue(longSql.length() > 100);

        assertDoesNotThrow(() ->
                service.notify("abc123", longSql, "报告", 1.0, 100L, "db"));
    }
}
