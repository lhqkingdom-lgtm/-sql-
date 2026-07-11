package com.slowsql.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent HTTP 客户端——手动诊断直发 Agent /diagnose，不走 RMQ。
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String agentUrl;

    public AgentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        // 可通过环境变量覆盖，默认 localhost:8000
        this.agentUrl = System.getenv().getOrDefault("AGENT_URL", "http://localhost:8000");
    }

    /**
     * 同步调用 Agent /diagnose，返回诊断报告。
     */
    public DiagnoseResult diagnose(String sql, String instanceId, String projectCode) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("sql", sql);
            body.put("instanceId", instanceId);
            body.put("projectCode", projectCode != null ? projectCode : "");

            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(agentUrl + "/diagnose"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return objectMapper.readValue(resp.body(), DiagnoseResult.class);
            }
            log.warn("Agent /diagnose 返回非200: {}", resp.statusCode());
            return DiagnoseResult.failed("Agent 返回 HTTP " + resp.statusCode());
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("Agent /diagnose 超时", e);
            return DiagnoseResult.failed("诊断超时，请重试");
        } catch (Exception e) {
            log.error("Agent /diagnose 调用失败", e);
            return DiagnoseResult.failed("Agent 不可达: " + e.getMessage());
        }
    }

    /**
     * 诊断结果 DTO——与 Python Agent DiagnoseResponse 对齐。
     */
    public static class DiagnoseResult {
        private String taskId;
        private String status;
        private String report;
        private String error;
        private int durationMs;
        private int toolCallCount;

        public DiagnoseResult() {}

        public static DiagnoseResult failed(String error) {
            DiagnoseResult r = new DiagnoseResult();
            r.status = "failed";
            r.error = error;
            return r;
        }

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getReport() { return report; }
        public void setReport(String report) { this.report = report; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public int getDurationMs() { return durationMs; }
        public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
        public int getToolCallCount() { return toolCallCount; }
        public void setToolCallCount(int toolCallCount) { this.toolCallCount = toolCallCount; }
        public boolean isCompleted() { return "completed".equals(status); }
    }
}
