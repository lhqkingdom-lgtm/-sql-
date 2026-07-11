package com.slowsql.api;

import com.slowsql.capture.CapturedSql;
import com.slowsql.capture.CapturedSqlRepository;
import com.slowsql.config.DataSourceManager;
import com.slowsql.gateway.AgentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private final CapturedSqlRepository repository;
    private final AgentClient agentClient;
    private final DataSourceManager dataSourceManager;

    public MonitorController(CapturedSqlRepository repository, AgentClient agentClient,
                              DataSourceManager dataSourceManager) {
        this.repository = repository;
        this.agentClient = agentClient;
        this.dataSourceManager = dataSourceManager;
    }

    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String projectCode,
                                      @RequestParam(required = false) String instanceId,
                                      @RequestParam(required = false) String databaseName,
                                      @RequestParam(required = false) String severity,
                                      @RequestParam(required = false) String startTime,
                                      @RequestParam(required = false) String endTime) {
        int offset = (page - 1) * size;
        List<CapturedSql> list = repository.findByFilters(projectCode, instanceId, databaseName, severity,
                startTime, endTime, offset, size);
        int total = repository.countByFilters(projectCode, instanceId, databaseName, severity, startTime, endTime);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", list.stream().map(this::toMap).toList());
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(result);
    }

    /** 实例的数据库列表 */
    @GetMapping("/databases")
    public ResponseEntity<?> databases(@RequestParam String instanceId) {
        try {
            JdbcTemplate jt = dataSourceManager.getTemplate(instanceId);
            List<Map<String, Object>> dbs = jt.queryForList("SHOW DATABASES");
            List<String> names = new ArrayList<>();
            for (var db : dbs) {
                String name = (String) db.get("Database");
                if (name != null && !isSystemDb(name)) names.add(name);
            }
            return ResponseEntity.ok(names);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    private boolean isSystemDb(String name) {
        return name.equals("information_schema") || name.equals("mysql")
            || name.equals("performance_schema") || name.equals("sys");
    }

    /** 指纹聚合视图 */
    @GetMapping("/aggregated")
    public ResponseEntity<?> aggregated(@RequestParam(required = false) String projectCode,
                                         @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(repository.aggregatedByFingerprint(projectCode, limit));
    }

    /** 前端按钮触发单条诊断——直发 Agent HTTP */
    @PostMapping("/records/{id}/diagnose")
    public ResponseEntity<?> diagnose(@PathVariable Long id) {
        CapturedSql cs = repository.findById(id);
        if (cs == null) {
            return ResponseEntity.notFound().build();
        }
        if (cs.getSqlText() == null || cs.getSqlText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sql is empty"));
        }

        log.info("手动触发诊断: id={}, sql={}", id, cs.getSqlText().substring(0, Math.min(50, cs.getSqlText().length())));

        AgentClient.DiagnoseResult result = agentClient.diagnose(
                cs.getSqlText(), cs.getInstanceId(), cs.getProjectCode());

        if (result.isCompleted()) {
            cs.setDiagnosisReport(result.getReport());
            cs.setSeverity(cs.getSeverity() != null ? cs.getSeverity() : "P2");
            repository.updateReport(cs);
        }

        return ResponseEntity.ok(Map.of(
                "id", id,
                "taskId", result.getTaskId(),
                "status", result.getStatus(),
                "report", result.getReport() != null ? result.getReport() : "",
                "error", result.getError() != null ? result.getError() : ""
        ));
    }

    @PostMapping("/records/delete")
    public ResponseEntity<?> delete(@RequestBody Map<String, Long> body) {
        repository.deleteById(body.getOrDefault("id", 0L));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/records/clear")
    public ResponseEntity<?> clear(@RequestBody(required = false) Map<String, String> body) {
        String projectCode = body != null ? body.get("projectCode") : null;
        if (projectCode != null && !projectCode.isEmpty()) {
            repository.findByProjectCode(projectCode, Integer.MAX_VALUE)
                    .forEach(c -> repository.deleteById(c.getId()));
        } else {
            repository.deleteAll();
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Map<String, Object> toMap(CapturedSql c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("sqlText", c.getSqlText());
        m.put("databaseName", c.getDatabaseName());
        m.put("queryTimeSec", c.getQueryTimeSec());
        m.put("instanceId", c.getInstanceId());
        m.put("projectCode", c.getProjectCode());
        m.put("occurrence", c.getOccurrenceCount());
        m.put("severity", c.getSeverity());
        m.put("diagnosisReport", c.getDiagnosisReport());
        m.put("diagnosed", c.getDiagnosisReport() != null && !c.getDiagnosisReport().equals("N/A"));
        m.put("capturedAt", c.getCapturedAt() != null ? c.getCapturedAt().toString() : null);
        return m;
    }
}
