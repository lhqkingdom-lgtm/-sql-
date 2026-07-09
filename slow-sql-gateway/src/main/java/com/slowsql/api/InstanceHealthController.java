package com.slowsql.api;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/instances")
public class InstanceHealthController {

    private final DataSourceManager dsManager;
    private final SqlMonitorProperties properties;

    public InstanceHealthController(DataSourceManager dsManager, SqlMonitorProperties properties) {
        this.dsManager = dsManager;
        this.properties = properties;
    }

    @GetMapping("/health")
    public ResponseEntity<?> allHealth() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SqlMonitorProperties.InstanceConfig inst : properties.getInstances()) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("instanceId", inst.getId());
            status.put("host", inst.getHost() + ":" + inst.getPort());
            status.put("projectCode", dsManager.findProjectCode(inst.getId()));
            try {
                // 2s 超时检查，不可达实例不阻塞整体响应
                status.put("reachable", dsManager.validateConnection(inst.getId()));
            } catch (Exception e) {
                status.put("reachable", false);
            }
            result.add(status);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{instanceId}/health")
    public ResponseEntity<?> singleHealth(@PathVariable String instanceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        try {
            result.put("reachable", dsManager.validateConnection(instanceId));
        } catch (Exception e) {
            result.put("reachable", false);
        }
        result.put("projectCode", dsManager.findProjectCode(instanceId));
        return ResponseEntity.ok(result);
    }
}
