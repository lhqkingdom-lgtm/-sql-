package com.slowsql.api;

import com.slowsql.persistence.DiagnosisRecord;
import com.slowsql.persistence.DiagnosisRecordRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisHistoryController {

    private final DiagnosisRecordRepository repository;

    public DiagnosisHistoryController(DiagnosisRecordRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(required = false) String projectCode,
                                      @RequestParam(required = false) String instanceId,
                                      @RequestParam(required = false) String startTime,
                                      @RequestParam(required = false) String endTime,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        int offset = (page - 1) * size;
        List<DiagnosisRecord> records = repository.findHistory(projectCode, instanceId,
                startTime, endTime, source, keyword, offset, size);
        int total = repository.countHistory(projectCode, instanceId, startTime, endTime, source, keyword);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records.stream().map(this::toSummary).toList());
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history/{taskId}")
    public ResponseEntity<?> detail(@PathVariable String taskId) {
        DiagnosisRecord r = repository.findByTaskId(taskId);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toDetail(r));
    }

    @DeleteMapping("/history/{taskId}")
    public ResponseEntity<?> delete(@PathVariable String taskId) {
        int deleted = repository.deleteByTaskId(taskId);
        return ResponseEntity.ok(Map.of("ok", deleted > 0));
    }

    @PostMapping("/history/clear")
    public ResponseEntity<?> clear(@RequestBody(required = false) Map<String, String> body) {
        String projectCode = body != null ? body.get("projectCode") : null;
        int deleted = repository.deleteAll(projectCode);
        return ResponseEntity.ok(Map.of("ok", true, "deleted", deleted));
    }

    private Map<String, Object> toSummary(DiagnosisRecord r) {
        String displaySql = r.getCleanSql() != null ? r.getCleanSql() : r.getOriginalSql();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", r.getTaskId());
        m.put("projectCode", r.getProjectCode());
        m.put("instanceId", r.getInstanceId());
        m.put("sqlPreview", displaySql != null && displaySql.length() > 100
                ? displaySql.substring(0, 100) + "..." : displaySql);
        m.put("originalSql", r.getOriginalSql());
        m.put("cleanSql", r.getCleanSql());
        m.put("status", r.getStatus());
        m.put("source", r.getSource());
        m.put("fingerprint", r.getFingerprint());
        m.put("durationMs", r.getDurationMs());
        m.put("toolCallCount", r.getToolCallCount());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toDetail(DiagnosisRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", r.getTaskId());
        m.put("projectCode", r.getProjectCode());
        m.put("instanceId", r.getInstanceId());
        m.put("originalSql", r.getOriginalSql());
        m.put("cleanSql", r.getCleanSql());
        m.put("report", r.getReport());
        m.put("status", r.getStatus());
        m.put("errorMessage", r.getErrorMessage());
        m.put("durationMs", r.getDurationMs());
        m.put("toolCallCount", r.getToolCallCount());
        m.put("source", r.getSource());
        m.put("fingerprint", r.getFingerprint());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }
}
