package com.slowsql.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 长连接管理器——按 taskId 索引，诊断完成后推送结果。
 */
@Component
public class SseEmitterManager {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterManager.class);
    private static final long SSE_TIMEOUT = 180_000L; // 3min

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter create(String taskId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.put(taskId, emitter);

        emitter.onCompletion(() -> emitters.remove(taskId));
        emitter.onTimeout(() -> emitters.remove(taskId));
        emitter.onError(e -> {
            emitters.remove(taskId);
            log.debug("SSE连接异常 taskId={}: {}", taskId, e.getMessage());
        });

        log.debug("SSE连接已建立: taskId={}", taskId);
        return emitter;
    }

    public void pushCompleted(String taskId, String report) {
        push(taskId, SseEvent.completed(taskId, report));
    }

    public void pushFailed(String taskId, String error) {
        push(taskId, SseEvent.failed(taskId, error));
    }

    private void push(String taskId, SseEvent event) {
        SseEmitter emitter = emitters.remove(taskId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.name)
                        .data(event.data, org.springframework.http.MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE推送失败 taskId={}: {}", taskId, e.getMessage());
            }
        }
    }

    record SseEvent(String name, Object data) {
        static SseEvent completed(String taskId, String report) {
            return new SseEvent("diagnosis-complete",
                    java.util.Map.of("taskId", taskId, "status", "completed", "report", report));
        }
        static SseEvent failed(String taskId, String error) {
            return new SseEvent("diagnosis-failed",
                    java.util.Map.of("taskId", taskId, "status", "failed", "error", error));
        }
    }
}
