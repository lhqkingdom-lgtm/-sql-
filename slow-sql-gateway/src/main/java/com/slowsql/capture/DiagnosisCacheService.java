package com.slowsql.capture;

import com.slowsql.config.DataSourceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 诊断结果缓存——同 fingerprint + 同 EXPLAIN(type/key/Extra) → 复用报告。
 * P0 不缓存，TTL 7 天。
 */
@Component
public class DiagnosisCacheService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisCacheService.class);
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final DataSourceManager dsManager;

    public DiagnosisCacheService(StringRedisTemplate redis, DataSourceManager dsManager) {
        this.redis = redis;
        this.dsManager = dsManager;
    }

    /** 查缓存，命中返回报告，否则返回 null */
    public String checkCache(String instanceId, String sql, String fingerprint) {
        try {
            String explainKey = extractExplainKey(instanceId, sql);
            if (explainKey == null || explainKey.isEmpty()) return null;
            String cacheKey = "diagnosis:cache:" + fingerprint + ":" + explainKey;
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                log.info("诊断缓存命中: fingerprint={}", fingerprint.substring(0, Math.min(8, fingerprint.length())));
            }
            return cached;
        } catch (Exception e) {
            log.debug("缓存查询异常(降级继续): {}", e.getMessage());
            return null;
        }
    }

    /** 诊断完成后写缓存 */
    public void storeCache(String instanceId, String sql, String fingerprint, String report) {
        try {
            if (report == null || report.isEmpty()) return;
            String explainKey = extractExplainKey(instanceId, sql);
            if (explainKey == null || explainKey.isEmpty()) return;
            String cacheKey = "diagnosis:cache:" + fingerprint + ":" + explainKey;
            redis.opsForValue().set(cacheKey, report, CACHE_TTL);
            log.info("诊断缓存已写入: fingerprint={}", fingerprint.substring(0, Math.min(8, fingerprint.length())));
        } catch (Exception e) {
            log.debug("缓存写入异常(降级继续): {}", e.getMessage());
        }
    }

    /** 强制清除缓存（重诊时用） */
    public void clearCache(String instanceId, String sql, String fingerprint) {
        try {
            String explainKey = extractExplainKey(instanceId, sql);
            if (explainKey != null && !explainKey.isEmpty()) {
                redis.delete("diagnosis:cache:" + fingerprint + ":" + explainKey);
            }
        } catch (Exception e) { /* ignore */ }
    }

    /** 提取 EXPLAIN 关键字段: type|key|Extra */
    String extractExplainKey(String instanceId, String sql) {
        try {
            var jt = dsManager.getTemplate(instanceId);
            String plan = jt.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class);
            if (plan == null) return null;
            JsonNode root = mapper.readTree(plan);
            JsonNode steps = root.path("query_block");
            if (steps.isMissingNode()) steps = root;
            // 递归取第一个表的 EXPLAIN
            JsonNode table = findFirstTable(steps);
            if (table.isMissingNode()) return null;
            String type = table.path("access_type").asText("");
            String key = table.path("key").asText("");
            String extra = table.path("Extra").asText("");
            return type + "|" + key + "|" + extra;
        } catch (Exception e) {
            log.debug("EXPLAIN提取异常: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode findFirstTable(JsonNode node) {
        if (node.has("table_name")) return node;  // 叶子节点：有 table_name 就是表
        if (node.has("table")) {
            JsonNode tbl = node.get("table");
            if (tbl != null && tbl.has("table_name")) return tbl;
        }
        if (node.has("nested_loop")) {
            for (JsonNode child : node.path("nested_loop")) {
                JsonNode found = findFirstTable(child);
                if (!found.isMissingNode()) return found;
            }
        }
        if (node.has("grouping_operation")) {
            for (JsonNode child : node.path("grouping_operation")) {
                JsonNode found = findFirstTable(child);
                if (!found.isMissingNode()) return found;
            }
        }
        return JsonNodeFactory.instance.missingNode(); // 没找到
    }
}
