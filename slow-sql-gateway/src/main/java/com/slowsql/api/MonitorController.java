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
    private final List<Map<String, Object>> recentSqls = Collections.synchronizedList(new ArrayList<>());

    public MonitorController(CapturedSqlRepository repository) { this.repository = repository; }

    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestParam(defaultValue = "50") int limit) {
        List<CapturedSql> list = repository.findAll(limit);
        return ResponseEntity.ok(list.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("sqlText", c.getSqlText());
            m.put("queryTimeSec", c.getQueryTimeSec());
            m.put("instanceId", c.getInstanceId());
            m.put("occurrence", c.getOccurrenceCount());
            m.put("severity", c.getSeverity());
            m.put("diagnosed", c.getDiagnosisReport() != null);
            m.put("capturedAt", c.getCapturedAt() != null ? c.getCapturedAt().toString() : null);
            return m;
        }).toList());
    }

    @PostMapping("/records/delete")
    public ResponseEntity<?> delete(@RequestBody Map<String, Long> body) {
        repository.deleteById(body.getOrDefault("id", 0L));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/records/clear")
    public ResponseEntity<?> clear() {
        repository.deleteAll();
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
