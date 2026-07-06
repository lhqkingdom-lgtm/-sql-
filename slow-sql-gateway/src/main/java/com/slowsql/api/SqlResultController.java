package com.slowsql.api;

import com.slowsql.gateway.SseEmitterManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 结果推送 + 轮询兜底。
 */
@RestController
@RequestMapping("/api/sql")
public class SqlResultController {

    private final SseEmitterManager sseEmitterManager;
    private final StringRedisTemplate redis;

    public SqlResultController(SseEmitterManager sseEmitterManager, StringRedisTemplate redis) {
        this.sseEmitterManager = sseEmitterManager;
        this.redis = redis;
    }

    /** SSE 长连接——等诊断完成时服务端主动推送 */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        return sseEmitterManager.create(taskId);
    }

    /** 轮询兜底——查 Redis 已有结果 */
    @GetMapping("/result/{taskId}")
    public SqlAnalyzeResponse getResult(@PathVariable String taskId) {
        try {
            String status = (String) redis.opsForHash().get("diagnosis:task:" + taskId, "status");
            if (status == null) return new SqlAnalyzeResponse(null, "not_found", null, null, null);
            if ("completed".equals(status)) {
                String report = redis.opsForValue().get("diagnosis:result:" + taskId);
                return new SqlAnalyzeResponse(taskId, "completed", report, null, null);
            }
            if ("failed".equals(status)) {
                String error = (String) redis.opsForHash().get("diagnosis:task:" + taskId, "error");
                return new SqlAnalyzeResponse(taskId, "failed", null, error, null);
            }
            return new SqlAnalyzeResponse(taskId, status, null, null, null);
        } catch (Exception e) {
            return new SqlAnalyzeResponse(taskId, "error", null, e.getMessage(), null);
        }
    }
}
