package com.slowsql.capture;

import com.slowsql.config.SqlMonitorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FingerprintDedupServiceTest {

    private FingerprintDedupService service;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        SqlMonitorProperties props = new SqlMonitorProperties();
        props.getCapture().setDedupWindowMinutes(30);

        service = new FingerprintDedupService(redis, props);
    }

    // ===== tryRegister — 新指纹 =====

    @Test
    void tryRegister_shouldReturnTrueForNewFingerprint() {
        when(valueOps.setIfAbsent(eq("diagnosis:dedup:abc123"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        assertTrue(service.tryRegister("abc123"));
    }

    // ===== tryRegister — 已存在 =====

    @Test
    void tryRegister_shouldReturnFalseForExistingFingerprint() {
        when(valueOps.setIfAbsent(eq("diagnosis:dedup:abc123"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        assertFalse(service.tryRegister("abc123"));
    }

    // ===== tryRegister — Redis 挂了→保守放行 =====

    @Test
    void tryRegister_shouldReturnTrueWhenRedisFails() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis down"));

        // 保守策略：都诊断
        assertTrue(service.tryRegister("abc123"));
    }

    // ===== getCachedReport =====

    @Test
    void getCachedReport_shouldReturnCachedValue() {
        when(valueOps.get("diagnosis:result:fp:abc123")).thenReturn("## 诊断结果");

        assertEquals("## 诊断结果", service.getCachedReport("abc123"));
    }

    @Test
    void getCachedReport_shouldReturnNullWhenNotFound() {
        when(valueOps.get("diagnosis:result:fp:abc123")).thenReturn(null);

        assertNull(service.getCachedReport("abc123"));
    }

    @Test
    void getCachedReport_shouldReturnNullWhenRedisFails() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertNull(service.getCachedReport("abc123"));
    }

    // ===== cacheReport =====

    @Test
    void cacheReport_shouldSetWithTtl() {
        service.cacheReport("abc123", "## 报告");

        verify(valueOps).set(eq("diagnosis:result:fp:abc123"), eq("## 报告"), any(Duration.class));
    }

    @Test
    void cacheReport_shouldSurviveRedisFailure() {
        doThrow(new RuntimeException("Redis down")).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));

        // 不应抛异常
        assertDoesNotThrow(() -> service.cacheReport("abc123", "## 报告"));
    }
}
