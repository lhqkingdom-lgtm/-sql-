package com.slowsql.capture;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 采集控制API——启用/停用/查询采集状态。
 */
@RestController
@RequestMapping("/api/capture")
public class CaptureStatusController {

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final SqlMonitorProperties properties;
    private final DataSourceManager dsManager;
    private final HttpCaptureController httpController;

    /** 每个实例的采集启用状态（内存，重启恢复默认启用） */
    private final Map<String, Boolean> instanceEnabled = new java.util.concurrent.ConcurrentHashMap<>();

    /** 采集统计 */
    private final Map<String, CaptureStats> statsMap = new java.util.concurrent.ConcurrentHashMap<>();

    public CaptureStatusController(SqlMonitorProperties properties,
                                    DataSourceManager dsManager,
                                    HttpCaptureController httpController) {
        this.properties = properties;
        this.dsManager = dsManager;
        this.httpController = httpController;
    }

    /** 查询所有项目/实例的采集状态 */
    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam(required = false) String projectCode) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SqlMonitorProperties.ProjectConfig proj : properties.getProjects()) {
            if (projectCode != null && !projectCode.equals(proj.getCode())) continue;
            Map<String, Object> pStatus = new LinkedHashMap<>();
            pStatus.put("projectCode", proj.getCode());
            pStatus.put("projectName", proj.getName());
            List<Map<String, Object>> instances = new ArrayList<>();
            for (String iid : proj.getInstanceIds()) {
                Map<String, Object> is = new LinkedHashMap<>();
                is.put("instanceId", iid);
                is.put("enabled", instanceEnabled.getOrDefault(iid, true));
                is.put("reachable", checkReachableWithTimeout(iid));
                CaptureStats stats = statsMap.get(iid);
                is.put("lastCollectAt", stats != null ? stats.lastCollectAt : null);
                is.put("totalCollected", stats != null ? stats.totalCollected : 0);
                is.put("lastError", stats != null ? stats.lastError : null);
                instances.add(is);
            }
            pStatus.put("instances", instances);
            result.add(pStatus);
        }
        return ResponseEntity.ok(result);
    }

    /** 启用某实例的采集 */
    @PostMapping("/{instanceId}/enable")
    public ResponseEntity<?> enable(@PathVariable String instanceId) {
        instanceEnabled.put(instanceId, true);
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "enabled", true));
    }

    /** 停用某实例的采集 */
    @PostMapping("/{instanceId}/disable")
    public ResponseEntity<?> disable(@PathVariable String instanceId) {
        instanceEnabled.put(instanceId, false);
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "enabled", false));
    }

    /** 获取实例的采集方式配置 */
    @GetMapping("/{instanceId}/sources")
    public ResponseEntity<?> getSources(@PathVariable String instanceId) {
        List<String> sources = instanceSources.getOrDefault(instanceId, List.of("all"));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "sources", sources));
    }

    /** 设置实例的采集方式 */
    @PutMapping("/{instanceId}/sources")
    public ResponseEntity<?> setSources(@PathVariable String instanceId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) body.getOrDefault("sources", List.of("all"));
        instanceSources.put(instanceId, sources);
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "sources", sources));
    }

    /** 检查某采集方式是否对该实例启用 */
    public boolean isSourceEnabled(String instanceId, String sourceType) {
        List<String> sources = instanceSources.getOrDefault(instanceId, List.of("all"));
        return sources.contains("all") || sources.contains(sourceType);
    }

    /** 实例采集方式配置（内存） */
    private final Map<String, List<String>> instanceSources = new ConcurrentHashMap<>();

    /** 记录一次采集（由 Scheduler 调用） */
    public void recordCollect(String instanceId, int count) {
        statsMap.computeIfAbsent(instanceId, k -> new CaptureStats()).record(count);
    }

    public void recordError(String instanceId, String error) {
        statsMap.computeIfAbsent(instanceId, k -> new CaptureStats()).recordError(error);
    }

    public boolean isEnabled(String instanceId) {
        return instanceEnabled.getOrDefault(instanceId, true);
    }

    private boolean checkReachableWithTimeout(String instanceId) {
        Future<Boolean> future = executor.submit(() -> {
            try { return dsManager.validateConnection(instanceId); } catch (Exception e) { return false; }
        });
        try { return future.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return false; }
    }

    private boolean checkReachable(String instanceId) {
        try { return dsManager.validateConnection(instanceId); } catch (Exception e) { return false; }
    }

    static class CaptureStats {
        volatile long totalCollected = 0;
        volatile LocalDateTime lastCollectAt;
        volatile String lastError;

        void record(int count) {
            totalCollected += count;
            lastCollectAt = LocalDateTime.now();
            lastError = null;
        }
        void recordError(String err) { lastError = err; lastCollectAt = LocalDateTime.now(); }
    }
}
