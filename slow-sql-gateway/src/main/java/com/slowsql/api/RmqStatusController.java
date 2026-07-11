package com.slowsql.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RabbitMQ 队列状态——前端可视化，知道任务是排队还是卡住。
 * 通过 RMQ Management HTTP API (localhost:15672) 获取数据。
 */
@RestController
@RequestMapping("/api/rmq")
public class RmqStatusController {

    private static final String RMQ_API = "http://localhost:15672/api";
    private static final String RMQ_USER = "guest";
    private static final String RMQ_PASS = "guest";

    /** 获取所有队列状态 */
    @GetMapping("/queues")
    public ResponseEntity<?> queues() {
        try {
            String json = httpGet(RMQ_API + "/queues");
            // Parse relevant fields
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> all = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> q : all) {
                String name = (String) q.get("name");
                // 只返回本项目 diagnosis.* 和 Redis 降级队列
                if (name == null || (!name.startsWith("diagnosis.") && !name.equals("diagnosis:fallback:queue")))
                    continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", name);
                s.put("messages", q.getOrDefault("messages", 0));
                s.put("messages_ready", q.getOrDefault("messages_ready", 0));
                s.put("messages_unacknowledged", q.getOrDefault("messages_unacknowledged", 0));
                s.put("consumers", q.getOrDefault("consumers", 0));
                s.put("state", q.get("state"));
                result.add(s);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", "RMQ管理API不可达: " + e.getMessage()));
        }
    }

    /** 获取概览（连接数、消息速率等） */
    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        try {
            String json = httpGet(RMQ_API + "/overview");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("node", data.getOrDefault("node", "unknown"));
            @SuppressWarnings("unchecked")
            Map<String, Object> queueTotals = (Map<String, Object>) data.getOrDefault("queue_totals", Map.of());
            result.put("total_messages", queueTotals.getOrDefault("messages", 0));
            result.put("total_messages_ready", queueTotals.getOrDefault("messages_ready", 0));
            result.put("total_messages_unacknowledged", queueTotals.getOrDefault("messages_unacknowledged", 0));
            @SuppressWarnings("unchecked")
            Map<String, Object> msgStats = (Map<String, Object>) data.getOrDefault("message_stats", Map.of());
            result.put("publish_rate", msgStats.getOrDefault("publish_details", Map.of()).getClass().getSimpleName());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", "RMQ管理API不可达: " + e.getMessage()));
        }
    }

    private String httpGet(String url) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build();
        String auth = java.util.Base64.getEncoder().encodeToString((RMQ_USER + ":" + RMQ_PASS).getBytes());
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Basic " + auth)
                .timeout(java.time.Duration.ofSeconds(5))
                .GET().build();
        return client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }
}
