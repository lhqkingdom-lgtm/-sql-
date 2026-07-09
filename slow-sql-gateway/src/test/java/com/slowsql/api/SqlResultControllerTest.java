package com.slowsql.api;

import com.slowsql.gateway.SseEmitterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SqlResultControllerTest {

    private MockMvc mvc;
    private SseEmitterManager sseEmitterManager;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private ValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        sseEmitterManager = mock(SseEmitterManager.class);
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForValue()).thenReturn(valueOps);

        SqlResultController controller = new SqlResultController(sseEmitterManager, redis);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ===== SSE stream =====

    @Test
    void stream_shouldDelegateToSseEmitterManager() throws Exception {
        when(sseEmitterManager.create("task-1")).thenReturn(new SseEmitter());

        mvc.perform(get("/api/sql/stream/task-1"))
                .andExpect(status().isOk());
    }

    // ===== GET /result — completed =====

    @Test
    void getResult_shouldReturnCompletedWhenDone() throws Exception {
        when(hashOps.get("diagnosis:task:task-1", "status")).thenReturn("completed");
        when(valueOps.get("diagnosis:result:task-1")).thenReturn("## 诊断报告\n优化建议...");

        mvc.perform(get("/api/sql/result/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.report").value("## 诊断报告\n优化建议..."));
    }

    // ===== GET /result — failed =====

    @Test
    void getResult_shouldReturnFailedWithError() throws Exception {
        when(hashOps.get("diagnosis:task:task-1", "status")).thenReturn("failed");
        when(hashOps.get("diagnosis:task:task-1", "error")).thenReturn("LLM调用超时");

        mvc.perform(get("/api/sql/result/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error").value("LLM调用超时"));
    }

    // ===== GET /result — pending =====

    @Test
    void getResult_shouldReturnPendingStatus() throws Exception {
        when(hashOps.get("diagnosis:task:task-1", "status")).thenReturn("pending");

        mvc.perform(get("/api/sql/result/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.report").doesNotExist());
    }

    // ===== GET /result — running =====

    @Test
    void getResult_shouldReturnRunningStatus() throws Exception {
        when(hashOps.get("diagnosis:task:task-1", "status")).thenReturn("running");

        mvc.perform(get("/api/sql/result/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }

    // ===== GET /result — not found =====

    @Test
    void getResult_shouldReturnNotFoundWhenNoStatus() throws Exception {
        when(hashOps.get("diagnosis:task:task-1", "status")).thenReturn(null);

        mvc.perform(get("/api/sql/result/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_found"));
    }

    // ===== GET /result — Redis 异常 =====

    @Test
    void getResult_shouldHandleRedisException() throws Exception {
        when(hashOps.get("diagnosis:task:task-1", "status"))
                .thenThrow(new RuntimeException("Redis连接超时"));

        mvc.perform(get("/api/sql/result/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value(containsString("Redis")));
    }
}
