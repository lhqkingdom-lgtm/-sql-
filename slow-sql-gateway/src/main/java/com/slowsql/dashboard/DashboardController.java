package com.slowsql.dashboard;

import com.slowsql.capture.CapturedSqlRepository;
import com.slowsql.persistence.DiagnosisRecordRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final CapturedSqlRepository capturedRepo;
    private final DiagnosisRecordRepository diagnosisRepo;

    public DashboardController(CapturedSqlRepository capturedRepo,
                               DiagnosisRecordRepository diagnosisRepo) {
        this.capturedRepo = capturedRepo;
        this.diagnosisRepo = diagnosisRepo;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@RequestParam(required = false) String projectCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("todayCount", capturedRepo.countToday(projectCode));
        data.put("totalCount", capturedRepo.countTotal(projectCode));
        data.put("sourceDistribution", capturedRepo.countBySource(projectCode));
        data.put("topFrequent", capturedRepo.findTopFrequent(projectCode, 5));
        data.put("diagnosisCount", diagnosisRepo.countByProject(projectCode));
        data.put("pendingCount", diagnosisRepo.countPending(projectCode));
        data.put("p0Count", capturedRepo.countP0(projectCode));
        return ResponseEntity.ok(data);
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestParam(required = false) String projectCode,
                                     @RequestParam(defaultValue = "10") int limit) {
        List<com.slowsql.capture.CapturedSql> recent;
        if (projectCode != null && !projectCode.isEmpty())
            recent = capturedRepo.findByProjectCode(projectCode, limit);
        else
            recent = capturedRepo.findAll(limit);
        return ResponseEntity.ok(recent);
    }

    @GetMapping("/trend")
    public ResponseEntity<?> trend(@RequestParam(required = false) String projectCode,
                                    @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(capturedRepo.dailyTrend(projectCode, days));
    }
}
