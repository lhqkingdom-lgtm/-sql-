package com.slowsql.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL 规则引擎——根据 EXPLAIN 结果 + SQL 特征精确匹配规则。
 * 替代关键词 RAG，规则从 rules.yaml 加载。
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private List<Rule> rules = List.of();

    @PostConstruct
    public void init() {
        try {
            InputStream in = new ClassPathResource("rules.yaml").getInputStream();
            RuleConfig config = yamlMapper.readValue(in, RuleConfig.class);
            rules = config.rules != null ? config.rules : List.of();
            log.info("规则引擎加载完成: {} 条规则", rules.size());
        } catch (Exception e) {
            log.warn("规则加载失败，降级为空规则集: {}", e.getMessage());
            rules = List.of();
        }
    }

    /**
     * 仅匹配 SQL 文本规则（不依赖 EXPLAIN）。
     */
    /** 去除 SQL 中的块注释和行注释 */
    private static String stripComments(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("/\\*.*?\\*/", "")   // /* ... */
                  .replaceAll("--[^\n]*", "")      // -- ...
                  .replaceAll("\\s+", " ").trim();
    }

    public String match(String sql) {
        return matchAndFormat(stripComments(sql), null);
    }

    /**
     * 匹配规则（含 EXPLAIN），返回格式化的 markdown 文本。
     */
    public String matchAndFormat(String sql, JsonNode explainJson) {
        if (rules.isEmpty() || sql == null) return "";

        String upperSql = sql.toUpperCase();
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : rules) {
            if (allConditionsMatch(rule.conditions, upperSql, explainJson)) {
                matched.add(rule);
            }
        }

        if (matched.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n【企业知识库匹配规则】\n");
        int i = 1;
        for (Rule r : matched) {
            sb.append("**规则 #").append(r.id).append("** [").append(r.category)
              .append("]: ").append(r.title)
              .append("\n> ").append(r.suggestion).append("\n\n");
            i++;
        }
        log.info("规则引擎命中 {} 条", matched.size());
        return sb.toString();
    }

    private boolean allConditionsMatch(List<Map<String, String>> conditions,
                                        String upperSql, JsonNode explain) {
        if (conditions == null) return true;
        for (Map<String, String> cond : conditions) {
            for (Map.Entry<String, String> e : cond.entrySet()) {
                if (!matchSingle(e.getKey(), e.getValue(), upperSql, explain))
                    return false;
            }
        }
        return true;
    }

    private boolean matchSingle(String key, String value,
                                 String upperSql, JsonNode explain) {
        return switch (key) {
            case "sql_contains" -> upperSql.contains(value.toUpperCase());
            case "sql_not_contains" -> !upperSql.contains(value.toUpperCase());
            case "sql_pattern" -> Pattern.compile(value, Pattern.CASE_INSENSITIVE)
                    .matcher(upperSql).find();
            case "explain_access_type" ->
                    explain != null && value.equalsIgnoreCase(explain.path("access_type").asText(""));
            case "explain_key_is_null" ->
                    explain != null && "true".equals(value) && explain.path("key").isNull();
            case "explain_rows" -> explain != null && compareOp(
                    explain.path("rows_examined_per_scan").asInt(0), value);
            case "explain_filtered" -> explain != null && compareOp(
                    explain.path("filtered").asDouble(0), value);
            case "explain_extra_contains" ->
                    explain != null && explain.path("Extra").asText("").contains(value);
            default -> false;
        };
    }

    /** 比较运算符: "> 1000", "< 20", ">= 5000" */
    private boolean compareOp(double actual, String expr) {
        try {
            expr = expr.trim();
            if (expr.startsWith(">=")) return actual >= Double.parseDouble(expr.substring(2).trim());
            if (expr.startsWith("<=")) return actual <= Double.parseDouble(expr.substring(2).trim());
            if (expr.startsWith(">"))  return actual > Double.parseDouble(expr.substring(1).trim());
            if (expr.startsWith("<"))  return actual < Double.parseDouble(expr.substring(1).trim());
            return actual == Double.parseDouble(expr);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    // ---- 数据类 ----

    @SuppressWarnings("unused")
    static class RuleConfig {
        public List<Rule> rules;
    }

    @SuppressWarnings("unused")
    static class Rule {
        public String id;
        public String category;
        public String title;
        public List<Map<String, String>> conditions;
        public String suggestion;
    }
}
