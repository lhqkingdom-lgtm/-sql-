package com.slowsql.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/rmq")
public class RmqStatusController {

    @Value("${rabbitmq-management.url}")
    private String rmqApi;

    @Value("${rabbitmq-management.username}")
    private String rmqUser;

    @Value("${rabbitmq-management.password}")
    private String rmqPass;

    @GetMapping("/queues")
    public ResponseEntity<?> queues() {
        try {
            String json = httpGet(rmqApi + "/queues");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> all = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> q : all) {
                String name = (String) q.get("name");
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

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        try {
            String json = httpGet(rmqApi + "/overview");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("node", data.getOrDefault("node", "unknown"));
            @SuppressWarnings("unchecked")
            Map<String, Object> queueTotals = (Map<String, Object>) data.getOrDefault("queue_totals", Map.of());
            result.put("total_messages", queueTotals.getOrDefault("messages", 0));
            result.put("total_messages_ready", queueTotals.getOrDefault("messages_ready", 0));
            result.put("total_messages_unacknowledged", queueTotals.getOrDefault("messages_unacknowledged", 0));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", "RMQ管理API不可达: " + e.getMessage()));
        }
    }

    private String httpGet(String url) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build();
        String auth = java.util.Base64.getEncoder().encodeToString((rmqUser + ":" + rmqPass).getBytes());
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Basic " + auth)
                .timeout(java.time.Duration.ofSeconds(5))
                .GET().build();
        return client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }
}
