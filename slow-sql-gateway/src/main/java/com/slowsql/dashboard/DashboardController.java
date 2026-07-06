package com.slowsql.dashboard;
import com.slowsql.capture.CapturedSqlRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final CapturedSqlRepository repository;

    public DashboardController(CapturedSqlRepository repository) { this.repository = repository; }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("todayCount", repository.countToday());
        data.put("totalCount", repository.countTotal());
        data.put("sourceDistribution", repository.countBySource());
        data.put("topFrequent", repository.findTopFrequent(5));
        return ResponseEntity.ok(data);
    }
}
