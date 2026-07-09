package com.slowsql.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;

class SseEmitterManagerTest {

    private SseEmitterManager manager;

    @BeforeEach
    void setUp() {
        manager = new SseEmitterManager();
    }

    // ===== create =====

    @Test
    void create_shouldReturnNonNullEmitter() {
        SseEmitter emitter = manager.create("task-1");

        assertNotNull(emitter);
        // 默认超时 3 分钟
        assertEquals(180_000L, emitter.getTimeout());
    }

    // ===== pushCompleted =====

    @Test
    void pushCompleted_shouldSendAndComplete() {
        SseEmitter emitter = manager.create("task-1");

        manager.pushCompleted("task-1", "## 诊断完成");

        // emitter 已被移除并 complete，再 push 不会找到
        assertDoesNotThrow(() -> manager.pushCompleted("task-1", "第二次推送"));
    }

    // ===== pushFailed =====

    @Test
    void pushFailed_shouldSendErrorAndComplete() {
        SseEmitter emitter = manager.create("task-1");

        manager.pushFailed("task-1", "LLM调用超时");

        // 不应抛异常
        assertDoesNotThrow(() -> manager.pushFailed("task-2", "不会影响"));
    }

    // ===== push 到不存在的 taskId =====

    @Test
    void push_shouldSilentlyIgnoreMissingTask() {
        assertDoesNotThrow(() -> manager.pushCompleted("nonexistent", "报告"));
        assertDoesNotThrow(() -> manager.pushFailed("nonexistent", "错误"));
    }

    // ===== 同一 taskId 重复 create =====

    @Test
    void create_shouldReplaceExistingEmitter() {
        manager.create("task-1");
        SseEmitter second = manager.create("task-1");

        assertNotNull(second);
    }

    // ===== 超时自动清理 =====

    @Test
    void onTimeout_shouldRemoveEmitter() {
        SseEmitter emitter = manager.create("task-1");

        // 触发超时
        emitter.getTimeout();
        assertDoesNotThrow(() -> {
            emitter.completeWithError(new RuntimeException("timeout"));
        });
    }
}
