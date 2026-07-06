package com.slowsql.api;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.DdlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据访问网关——13 个 REST 端点，仅供 Python Agent 内部调用。
 * <p>
 * 安全守卫：X-Internal-Token 认证 + 表名正则 + SQL 黑名单 + 变量白名单。
 */
@RestController
@RequestMapping(value = "/api/data", produces = "text/plain;charset=UTF-8")
public class DataAccessController {

    private static final Logger log = LoggerFactory.getLogger(DataAccessController.class);

    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final Pattern DANGEROUS_SQL_KEYWORD = Pattern.compile(
            "\\b(update|delete|drop|insert|truncate|alter)\\b", Pattern.CASE_INSENSITIVE);

    private static final Set<String> ALLOWED_VARIABLES = Set.of(
            "innodb_buffer_pool_size", "max_connections", "thread_cache_size",
            "query_cache_size", "tmp_table_size", "max_heap_table_size",
            "sort_buffer_size", "join_buffer_size");

    private final DataSourceManager dataSourceManager;
    private final DdlCache ddlCache;
    private final String internalToken;

    public DataAccessController(DataSourceManager dataSourceManager,
                                DdlCache ddlCache,
                                @Value("${gateway.internal-token}") String internalToken) {
        this.dataSourceManager = dataSourceManager;
        this.ddlCache = ddlCache;
        this.internalToken = internalToken;
    }

    // ==================== 安全守卫 ====================

    private void checkToken(String token) {
        if (token == null || !token.equals(internalToken)) {
            log.warn("数据API认证失败");
        }
    }

    private boolean isAuthorized(String token) {
        return token != null && token.equals(internalToken);
    }

    private String cleanTableName(String tableName) {
        return tableName.replace("`", "").trim();
    }

    private String validateTableName(String tableName) {
        String clean = cleanTableName(tableName);
        if (!SAFE_TABLE_NAME.matcher(clean).matches()) {
            return "错误：tableName 只能包含字母、数字和下划线。";
        }
        return null;
    }

    private String validateSql(String sql) {
        if (DANGEROUS_SQL_KEYWORD.matcher(sql).find()) {
            return "错误：权限拒绝！该 SQL 包含敏感的写入或 DDL 关键字。";
        }
        return null;
    }

    private JdbcTemplate getTemplate(String instanceId) {
        return dataSourceManager.getTemplate(instanceId);
    }

    // ==================== 1. GET /ddl ====================

    @GetMapping("/{instanceId}/ddl")
    public ResponseEntity<String> getTableDdl(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "") String table,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");
        if (!StringUtils.hasText(table)) return ResponseEntity.ok("错误：tableName 不能为空。");

        String clean = cleanTableName(table);
        String err = validateTableName(clean);
        if (err != null) return ResponseEntity.ok(err);

        // 优先缓存
        String cached = ddlCache.get(instanceId, clean);
        if (cached != null) return ResponseEntity.ok(cached);

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            Map<String, Object> row = jt.queryForMap("SHOW CREATE TABLE " + clean);
            Object ddl = row.get("Create Table");
            String result = ddl != null ? ddl.toString() : "错误：未返回建表语句。";
            ddlCache.put(instanceId, clean, result);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取DDL失败 [{}] {}: {}", instanceId, clean, e.getMessage());
            return ResponseEntity.ok("错误：获取DDL失败 - " + e.getMessage());
        }
    }

    // ==================== 2. POST /explain ====================

    @PostMapping("/{instanceId}/explain")
    public ResponseEntity<String> getExecutionPlan(
            @PathVariable String instanceId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        String sql = body.get("sql");
        if (!StringUtils.hasText(sql)) return ResponseEntity.ok("错误：sql 不能为空。");
        String err = validateSql(sql);
        if (err != null) return ResponseEntity.ok(err);

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            String plan = jt.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class);
            return ResponseEntity.ok(plan);
        } catch (Exception e) {
            log.error("EXPLAIN失败 [{}]: {}", instanceId, e.getMessage());
            return ResponseEntity.ok("错误：EXPLAIN失败 - " + e.getMessage());
        }
    }

    // ==================== 3. GET /stats/{table} ====================

    @GetMapping("/{instanceId}/stats/{table}")
    public ResponseEntity<String> getTableStatistics(
            @PathVariable String instanceId,
            @PathVariable String table,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        String clean = cleanTableName(table);
        String err = validateTableName(clean);
        if (err != null) return ResponseEntity.ok(err);

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            Map<String, Object> status = jt.queryForMap("SHOW TABLE STATUS LIKE '" + clean + "'");
            String rows = String.valueOf(status.get("Rows"));

            List<Map<String, Object>> indexes = jt.queryForList("SHOW INDEX FROM " + clean);
            StringBuilder sb = new StringBuilder();
            sb.append("表预估总行数: ").append(rows).append("\n");
            sb.append("索引基数:\n");
            for (Map<String, Object> idx : indexes) {
                sb.append("- ").append(idx.get("Key_name"))
                  .append(" (").append(idx.get("Column_name")).append(")")
                  .append(" Cardinality: ").append(idx.get("Cardinality")).append("\n");
            }
            return ResponseEntity.ok(sb.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：获取统计信息失败 - " + e.getMessage());
        }
    }

    // ==================== 4. GET /locks ====================

    @GetMapping("/{instanceId}/locks")
    public ResponseEntity<String> checkActiveLocks(
            @PathVariable String instanceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            List<Map<String, Object>> trx = jt.queryForList(
                    "SELECT trx_id, trx_state, trx_query, TIMESTAMPDIFF(SECOND, trx_started, NOW()) as wait_seconds " +
                    "FROM information_schema.innodb_trx " +
                    "WHERE trx_state = 'LOCK WAIT' OR TIMESTAMPDIFF(SECOND, trx_started, NOW()) > 5");
            if (trx.isEmpty()) return ResponseEntity.ok("当前无锁等待或长事务。");
            return ResponseEntity.ok("检测到锁/长事务：\n" + trx.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：检查锁失败 - " + e.getMessage());
        }
    }

    // ==================== 5. GET /innodb ====================

    @GetMapping("/{instanceId}/innodb")
    public ResponseEntity<String> getInnodbStatus(
            @PathVariable String instanceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            Map<String, Object> result = jt.queryForMap("SHOW ENGINE INNODB STATUS");
            Object status = result.get("Status");
            if (status == null) return ResponseEntity.ok("错误：未获取到InnoDB状态。");
            String full = status.toString();
            if (full.length() > 10000) {
                full = full.substring(0, 10000) + "\n...(已截断，完整共" + full.length() + "字符)";
            }
            return ResponseEntity.ok(full);
        } catch (Exception e) {
            return ResponseEntity.ok("错误：获取InnoDB状态失败 - " + e.getMessage());
        }
    }

    // ==================== 6. GET /variable ====================

    @GetMapping("/{instanceId}/variable")
    public ResponseEntity<String> getGlobalVariable(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "") String name,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        if (!StringUtils.hasText(name)) {
            return ResponseEntity.ok("可查询的变量: " + String.join(", ", ALLOWED_VARIABLES));
        }
        String lower = name.trim().toLowerCase();
        if (!ALLOWED_VARIABLES.contains(lower)) {
            return ResponseEntity.ok("错误：变量 '" + name + "' 不在可查询范围内。");
        }

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            Map<String, Object> result = jt.queryForMap("SHOW GLOBAL VARIABLES LIKE '" + lower + "'");
            return ResponseEntity.ok(result.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：查询变量失败 - " + e.getMessage());
        }
    }

    // ==================== 7. GET /indexes/redundant ====================

    @GetMapping("/{instanceId}/indexes/redundant")
    public ResponseEntity<String> checkRedundantIndexes(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "") String table,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        String clean = cleanTableName(table);
        String err = validateTableName(clean);
        if (err != null) return ResponseEntity.ok(err);

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            List<Map<String, Object>> indexes = jt.queryForList(
                    "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS COLS " +
                    "FROM information_schema.statistics " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                    "GROUP BY INDEX_NAME ORDER BY INDEX_NAME", clean);

            if (indexes.isEmpty()) return ResponseEntity.ok("表 " + clean + " 没有索引。");
            if (indexes.size() < 3) return ResponseEntity.ok("表 " + clean + " 仅有 " + indexes.size() + " 个索引，未达到冗余检测阈值(≥3)。");

            StringBuilder report = new StringBuilder("表 " + clean + " 索引分析（共" + indexes.size() + "个）：\n");
            List<String> redundants = new ArrayList<>();

            for (Map<String, Object> idx : indexes) {
                String cols = (String) idx.get("COLS");
                report.append("- ").append(idx.get("INDEX_NAME")).append(": (").append(cols).append(")\n");
                if (cols != null) {
                    String[] parts = cols.split(",");
                    if (parts.length == 1) {
                        for (Map<String, Object> other : indexes) {
                            String otherCols = (String) other.get("COLS");
                            if (otherCols != null && otherCols.startsWith(cols + ",")) {
                                redundants.add("索引 [" + idx.get("INDEX_NAME") + "] (" + cols +
                                        ") 可能被 [" + other.get("INDEX_NAME") + "] (" + otherCols + ") 覆盖");
                            }
                        }
                    }
                }
            }
            if (redundants.isEmpty()) report.append("\n未发现明显冗余索引。");
            else redundants.forEach(r -> report.append("\n⚠️ ").append(r));
            return ResponseEntity.ok(report.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：检查冗余索引失败 - " + e.getMessage());
        }
    }

    // ==================== 8. POST /explain/compare ====================

    @PostMapping("/{instanceId}/explain/compare")
    public ResponseEntity<String> compareExecutionPlan(
            @PathVariable String instanceId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        String original = body.get("originalSql");
        String optimized = body.get("optimizedSql");
        if (!StringUtils.hasText(original) || !StringUtils.hasText(optimized)) {
            return ResponseEntity.ok("错误：两条SQL都不能为空。");
        }
        if (validateSql(original) != null || validateSql(optimized) != null) {
            return ResponseEntity.ok("错误：SQL包含敏感关键字。");
        }

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            String p1 = jt.queryForObject("EXPLAIN FORMAT=JSON " + original, String.class);
            String p2 = jt.queryForObject("EXPLAIN FORMAT=JSON " + optimized, String.class);
            return ResponseEntity.ok("【原始SQL】\n" + p1 + "\n\n【优化SQL】\n" + p2);
        } catch (Exception e) {
            return ResponseEntity.ok("错误：EXPLAIN对比失败 - " + e.getMessage());
        }
    }

    // ==================== 9. GET /slowlog ====================

    @GetMapping("/{instanceId}/slowlog")
    public ResponseEntity<String> getSlowLogStats(
            @PathVariable String instanceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            List<Map<String, Object>> tables = jt.queryForList("SHOW TABLES FROM mysql LIKE 'slow_log'");
            if (tables.isEmpty()) return ResponseEntity.ok("mysql.slow_log 表不存在或未启用。");

            List<Map<String, Object>> stats = jt.queryForList(
                    "SELECT sql_text, COUNT(*) AS times, AVG(query_time) AS avg_time, MAX(query_time) AS max_time, " +
                    "AVG(rows_examined) AS avg_rows " +
                    "FROM mysql.slow_log WHERE start_time > DATE_SUB(NOW(), INTERVAL 24 HOUR) AND sql_text IS NOT NULL " +
                    "GROUP BY sql_text ORDER BY times DESC LIMIT 20");
            if (stats.isEmpty()) return ResponseEntity.ok("最近24小时无慢SQL记录。");

            StringBuilder sb = new StringBuilder("最近24小时慢SQL Top" + stats.size() + "：\n\n");
            for (int i = 0; i < stats.size(); i++) {
                Map<String, Object> r = stats.get(i);
                sb.append(String.format("%d. 出现%s次 | 平均%.2fs | 最大%.2fs | 平均扫描%s行\n   %s\n\n",
                        i + 1, r.get("times"), r.get("avg_time"), r.get("max_time"),
                        r.get("avg_rows"), ((String) r.get("sql_text")).replace('\n', ' ')));
            }
            return ResponseEntity.ok(sb.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：慢日志统计失败 - " + e.getMessage());
        }
    }

    // ==================== 10. POST /indexes/missing ====================
    //         11. POST /type-mismatch ====================
    //         12. GET /bufferpool ====================
    //         13. GET /processlist + POST /actual-rows ====================
    // 这4个端点复用旧项目 JSqlParser 逻辑，写在一个辅助方法里

    @PostMapping("/{instanceId}/indexes/missing")
    public ResponseEntity<String> checkMissingIndexes(
            @PathVariable String instanceId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");
        String sql = body.get("sql");
        if (!StringUtils.hasText(sql)) return ResponseEntity.ok("错误：sql不能为空。");
        if (validateSql(sql) != null) return ResponseEntity.ok(validateSql(sql));

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            Set<String> tables = extractTables(sql);
            StringBuilder report = new StringBuilder("【索引覆盖检查】\n\n");
            for (String table : tables) {
                if (!SAFE_TABLE_NAME.matcher(table).matches()) continue;
                List<Map<String, Object>> indexes = jt.queryForList("SHOW INDEX FROM " + table);
                Set<String> indexed = new HashSet<>();
                for (Map<String, Object> idx : indexes) indexed.add((String) idx.get("Column_name"));
                report.append("表 ").append(table).append(":\n");
                report.append("  已有索引列: ").append(indexed).append("\n");
            }
            return ResponseEntity.ok(report.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：索引检查失败 - " + e.getMessage());
        }
    }

    @PostMapping("/{instanceId}/type-mismatch")
    public ResponseEntity<String> checkTypeMismatch(
            @PathVariable String instanceId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");
        String sql = body.get("sql");
        if (!StringUtils.hasText(sql)) return ResponseEntity.ok("错误：sql不能为空。");

        // 简化版：依赖 JSqlParser 从旧项目复用完整逻辑，这里返回引导信息
        try {
            Set<String> tables = extractTables(sql);
            StringBuilder report = new StringBuilder("【隐式类型转换检查】\n\n");
            for (String table : tables) {
                if (!SAFE_TABLE_NAME.matcher(table).matches()) continue;
                JdbcTemplate jt = getTemplate(instanceId);
                Map<String, Object> ddl = jt.queryForMap("SHOW CREATE TABLE " + table);
                report.append("表 ").append(table).append(" DDL已获取，请检查WHERE条件中的值类型是否与字段类型匹配。\n");
            }
            return ResponseEntity.ok(report.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：类型检查失败 - " + e.getMessage());
        }
    }

    @GetMapping("/{instanceId}/bufferpool")
    public ResponseEntity<String> getBufferPoolHitRate(
            @PathVariable String instanceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            List<Map<String, Object>> rows = jt.queryForList("SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%'");
            long reads = 0, requests = 0;
            for (Map<String, Object> r : rows) {
                String name = (String) r.get("Variable_name");
                long val = ((Number) r.get("Value")).longValue();
                if ("Innodb_buffer_pool_reads".equals(name)) reads = val;
                if ("Innodb_buffer_pool_read_requests".equals(name)) requests = val;
            }
            if (requests == 0) return ResponseEntity.ok("尚无足够统计数据。");
            double hitRate = (1.0 - (double) reads / requests) * 100;
            return ResponseEntity.ok(String.format("Buffer Pool命中率: %.1f%% | 磁盘读: %,d | 总请求: %,d",
                    hitRate, reads, requests));
        } catch (Exception e) {
            return ResponseEntity.ok("错误：查询失败 - " + e.getMessage());
        }
    }

    @GetMapping("/{instanceId}/processlist")
    public ResponseEntity<String> getProcessList(
            @PathVariable String instanceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            List<Map<String, Object>> list = jt.queryForList("SHOW FULL PROCESSLIST");
            Map<String, Integer> byCommand = new LinkedHashMap<>();
            for (Map<String, Object> r : list) {
                String cmd = r.get("Command") != null ? r.get("Command").toString() : "Unknown";
                byCommand.merge(cmd, 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder("连接总数: " + list.size() + "\n按状态分布:\n");
            byCommand.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            return ResponseEntity.ok(sb.toString());
        } catch (Exception e) {
            return ResponseEntity.ok("错误：查询失败 - " + e.getMessage());
        }
    }

    @PostMapping("/{instanceId}/actual-rows")
    public ResponseEntity<String> checkActualRowCount(
            @PathVariable String instanceId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isAuthorized(token)) return ResponseEntity.status(403).body("Forbidden");
        String sql = body.get("sql");
        if (!StringUtils.hasText(sql)) return ResponseEntity.ok("错误：sql不能为空。");

        try {
            JdbcTemplate jt = getTemplate(instanceId);
            Long actual = jt.queryForObject("SELECT COUNT(*) FROM (" + sql + ") AS _cnt", Long.class);
            return ResponseEntity.ok("实际行数: " + actual);
        } catch (Exception e) {
            return ResponseEntity.ok("错误：查询失败 - " + e.getMessage());
        }
    }

    // ==================== 全局异常处理 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body("[ERROR] 错误：" + e.getMessage());
    }

    // ==================== 辅助 ====================

    private Set<String> extractTables(String sql) {
        try {
            net.sf.jsqlparser.parser.CCJSqlParserUtil.parseStatements(sql)
                    .getStatements().forEach(s -> {});
            net.sf.jsqlparser.util.TablesNamesFinder finder = new net.sf.jsqlparser.util.TablesNamesFinder();
            return new HashSet<>(finder.getTableList(
                    net.sf.jsqlparser.parser.CCJSqlParserUtil.parseStatements(sql).getStatements().get(0)));
        } catch (Exception e) {
            return Set.of();
        }
    }
}
