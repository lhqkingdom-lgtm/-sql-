package com.slowsql.api;

import com.slowsql.capture.CapturedSql;
import com.slowsql.capture.CapturedSqlRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final CapturedSqlRepository repository;

    public MonitorController(CapturedSqlRepository repository) { this.repository = repository; }

    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(required = false) String projectCode,
                                      @RequestParam(required = false) String instanceId,
                                      @RequestParam(required = false) String severity,
                                      @RequestParam(required = false) String startTime,
                                      @RequestParam(required = false) String endTime) {
        List<CapturedSql> list;
        boolean hasFilter = (projectCode != null && !projectCode.isEmpty())
                         || (instanceId != null && !instanceId.isEmpty())
                         || (severity != null && !severity.isEmpty())
                         || (startTime != null && !startTime.isEmpty())
                         || (endTime != null && !endTime.isEmpty());
        if (hasFilter) {
            list = repository.findByFilters(projectCode, instanceId, severity, startTime, endTime, limit);
        } else {
            list = repository.findAll(limit);
        }
        return ResponseEntity.ok(list.stream().map(this::toMap).toList());
    }

    /** 指纹聚合视图 */
    @GetMapping("/aggregated")
    public ResponseEntity<?> aggregated(@RequestParam(required = false) String projectCode,
                                         @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(repository.aggregatedByFingerprint(projectCode, limit));
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
        m.put("queryTimeSec", c.getQueryTimeSec());
        m.put("instanceId", c.getInstanceId());
        m.put("projectCode", c.getProjectCode());
        m.put("occurrence", c.getOccurrenceCount());
        m.put("severity", c.getSeverity());
        m.put("diagnosed", c.getDiagnosisReport() != null);
        m.put("capturedAt", c.getCapturedAt() != null ? c.getCapturedAt().toString() : null);
        return m;
    }
}
