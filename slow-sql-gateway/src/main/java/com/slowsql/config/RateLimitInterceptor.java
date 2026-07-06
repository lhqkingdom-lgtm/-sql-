package com.slowsql.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "ratelimit:ip:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final int maxPerMinute;

    public RateLimitInterceptor(StringRedisTemplate redis, SqlMonitorProperties props) {
        this.redis = redis;
        this.maxPerMinute = props.getRateLimit().getMaxPerMinute();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) throws Exception {
        if (maxPerMinute <= 0) return true;

        String ip = request.getRemoteAddr();
        String key = PREFIX + ip;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, WINDOW);
            if (count != null && count > maxPerMinute) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"请求过于频繁，请稍后重试\"}");
                return false;
            }
        } catch (Exception e) {
            // Redis挂了→放行
        }
        return true;
    }
}
