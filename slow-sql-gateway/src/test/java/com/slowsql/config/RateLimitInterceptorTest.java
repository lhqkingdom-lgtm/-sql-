package com.slowsql.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitInterceptorTest {

    private RateLimitInterceptor interceptor;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter responseWriter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        SqlMonitorProperties props = new SqlMonitorProperties();
        props.getRateLimit().setMaxPerMinute(100);

        interceptor = new RateLimitInterceptor(redis, props);

        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        response = mock(HttpServletResponse.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    // ===== 正常：未超限 =====

    @Test
    void preHandle_shouldAllowWhenBelowLimit() throws Exception {
        when(valueOps.increment("ratelimit:ip:192.168.1.1")).thenReturn(50L);

        assertTrue(interceptor.preHandle(request, response, null));
    }

    // ===== 正常：首次请求设置过期 =====

    @Test
    void preHandle_shouldSetExpireOnFirstRequest() throws Exception {
        when(valueOps.increment("ratelimit:ip:192.168.1.1")).thenReturn(1L);

        assertTrue(interceptor.preHandle(request, response, null));
        verify(redis).expire(eq("ratelimit:ip:192.168.1.1"), any());
    }

    // ===== 超限 =====

    @Test
    void preHandle_shouldBlockWhenOverLimit() throws Exception {
        when(valueOps.increment("ratelimit:ip:192.168.1.1")).thenReturn(101L);

        assertFalse(interceptor.preHandle(request, response, null));
        verify(response).setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        assertTrue(responseWriter.toString().contains("请求过于频繁"));
    }

    @Test
    void preHandle_shouldAllowWhenExactlyAtLimit() throws Exception {
        when(valueOps.increment("ratelimit:ip:192.168.1.1")).thenReturn(100L);

        assertTrue(interceptor.preHandle(request, response, null));
    }

    // ===== maxPerMinute <= 0 时禁用限流 =====

    @Test
    void preHandle_shouldAllowWhenRateLimitDisabled() throws Exception {
        SqlMonitorProperties props = new SqlMonitorProperties();
        props.getRateLimit().setMaxPerMinute(0);  // 禁用

        RateLimitInterceptor disabled = new RateLimitInterceptor(redis, props);

        assertTrue(disabled.preHandle(request, response, null));
        verifyNoInteractions(valueOps);
    }

    @Test
    void preHandle_shouldAllowWhenMaxPerMinuteNegative() throws Exception {
        SqlMonitorProperties props = new SqlMonitorProperties();
        props.getRateLimit().setMaxPerMinute(-1);

        RateLimitInterceptor disabled = new RateLimitInterceptor(redis, props);

        assertTrue(disabled.preHandle(request, response, null));
    }

    // ===== Redis 挂了 → 放行 =====

    @Test
    void preHandle_shouldAllowWhenRedisFails() throws Exception {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("Redis down"));

        // 不应抛异常，应放行
        assertTrue(interceptor.preHandle(request, response, null));
    }

    // ===== 不同 IP 独立计数 =====

    @Test
    void preHandle_shouldTrackDifferentIpsIndependently() throws Exception {
        when(valueOps.increment("ratelimit:ip:192.168.1.1")).thenReturn(101L);
        when(valueOps.increment("ratelimit:ip:10.0.0.1")).thenReturn(1L);

        // IP1 超限
        assertFalse(interceptor.preHandle(request, response, null));

        // IP2 正常
        HttpServletRequest request2 = mock(HttpServletRequest.class);
        when(request2.getRemoteAddr()).thenReturn("10.0.0.1");
        HttpServletResponse response2 = mock(HttpServletResponse.class);
        when(response2.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        assertTrue(interceptor.preHandle(request2, response2, null));
    }
}
