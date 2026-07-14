package com.slowsql.capture;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 采集控制API——启用/停用/查询采集状态。
 * 启动时零定时任务，前端开启后才动态调度轮询。
 */
@RestController
@RequestMapping("/api/capture")
public class CaptureStatusController {

    private static final Logger log = LoggerFactory.getLogger(CaptureStatusController.class);
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final SqlMonitorProperties properties;
    private final DataSourceManager dsManager;
    private final HttpCaptureController httpController;
    private final SlowSqlCaptureScheduler captureScheduler;

    /** 每个实例的采集启用状态（内存，重启默认关闭——需前端显式开启） */
    private final Map<String, Boolean> instanceEnabled = new ConcurrentHashMap<>();

    /** 采集统计 */
    private final Map<String, CaptureStats> statsMap = new ConcurrentHashMap<>();

    /** 每实例选定的采集源（三选一）：slow_log_table / slow_log_file / http_endpoint */
    private final Map<String, String> instanceSources = new ConcurrentHashMap<>();

    /** 每实例轮询参数 */
    private final Map<String, Map<String, Object>> pollingConfigs = new ConcurrentHashMap<>();

    /** 动态轮询调度器 + 每实例的线程 */
    private final Map<String, Thread> pollingThreads = new ConcurrentHashMap<>();

    /** 每实例的轮询开始时间（用于倒计时计算） */
    private final Map<String, LocalDateTime> pollingStartedAt = new ConcurrentHashMap<>();

    public CaptureStatusController(SqlMonitorProperties properties,
                                    DataSourceManager dsManager,
                                    HttpCaptureController httpController,
                                    @Lazy SlowSqlCaptureScheduler captureScheduler) {
        this.properties = properties;
        this.dsManager = dsManager;
        this.httpController = httpController;
        this.captureScheduler = captureScheduler;
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
                is.put("enabled", instanceEnabled.getOrDefault(iid, false));
                is.put("reachable", checkReachableWithTimeout(iid));
                CaptureStats stats = statsMap.get(iid);
                is.put("lastCollectAt", stats != null ? stats.lastCollectAt : null);
                is.put("totalCollected", stats != null ? stats.totalCollected : 0);
                is.put("lastError", stats != null ? stats.lastError : null);
                // 距下次采集剩余秒数（PS/文件源），HTTP 返回 null
                String src = instanceSources.getOrDefault(iid, "");
                is.put("nextPollSec", pollingThreads.containsKey(iid)
                        && ("slow_log_table".equals(src) || "slow_log_file".equals(src))
                        ? computeNextPollSec(iid, stats)
                        : null);
                instances.add(is);
            }
            pStatus.put("instances", instances);
            result.add(pStatus);
        }
        return ResponseEntity.ok(result);
    }

    /** 启用某实例的采集——若采集源需要轮询则启动定时任务 */
    @PostMapping("/{instanceId}/enable")
    public ResponseEntity<?> enable(@PathVariable String instanceId) {
        instanceEnabled.put(instanceId, true);
        startPollingIfNeeded(instanceId);
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "enabled", true));
    }

    /** 停用某实例的采集——停止该实例的定时轮询 */
    @PostMapping("/{instanceId}/disable")
    public ResponseEntity<?> disable(@PathVariable String instanceId) {
        instanceEnabled.put(instanceId, false);
        stopPolling(instanceId);
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "enabled", false));
    }

    /** 获取实例的采集方式（单个源，三选一） */
    @GetMapping("/{instanceId}/sources")
    public ResponseEntity<?> getSources(@PathVariable String instanceId) {
        String source = instanceSources.getOrDefault(instanceId, "");
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "source", source));
    }

    /** 设置实例的采集方式（三选一）。若当前已启用且新源需轮询，则重启调度。 */
    @PutMapping("/{instanceId}/sources")
    public ResponseEntity<?> setSources(@PathVariable String instanceId, @RequestBody Map<String, Object> body) {
        String newSource = (String) body.getOrDefault("source", "");
        String oldSource = instanceSources.getOrDefault(instanceId, "");

        // 兼容旧版 API（传 sources 数组）→ 取第一个
        if (newSource.isEmpty() && body.containsKey("sources")) {
            Object s = body.get("sources");
            if (s instanceof List<?> list && !list.isEmpty()) {
                newSource = list.contains("all") ? "" : (String) list.get(0);
            }
        }

        instanceSources.put(instanceId, newSource);

        // 采集源变了 → 停旧启新
        if (!Objects.equals(oldSource, newSource)) {
            stopPolling(instanceId);
            if (isInstanceEnabled(instanceId)) {
                startPollingIfNeeded(instanceId);
            }
        }
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "source", newSource));
    }

    /** 检查某采集方式是否对该实例启用 */
    public boolean isSourceEnabled(String instanceId, String sourceType) {
        String current = instanceSources.getOrDefault(instanceId, "");
        return sourceType.equals(current);
    }

    private boolean isInstanceEnabled(String instanceId) {
        return instanceEnabled.getOrDefault(instanceId, false);
    }

    /** 估算距下一次轮询的剩余秒数 */
    private long computeNextPollSec(String instanceId, CaptureStats stats) {
        int interval = ((Number) getPollingConfigForInstance(instanceId)
                .getOrDefault("intervalSeconds", 60)).intValue();
        // 优先用上次采集时间，否则用线程启动时间
        LocalDateTime base = (stats != null && stats.lastCollectAt != null)
                ? stats.lastCollectAt
                : pollingStartedAt.getOrDefault(instanceId, LocalDateTime.now());
        long elapsed = java.time.Duration.between(base, LocalDateTime.now()).getSeconds();
        return Math.max(0, interval - (elapsed % interval));
    }

    /** 读取 MySQL 端的慢日志参数 */
    @GetMapping("/{instanceId}/mysql-settings")
    public ResponseEntity<?> getMysqlSettings(@PathVariable String instanceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var jt = dsManager.getMonitoringTemplate(instanceId);
            var rows = jt.queryForList("SHOW GLOBAL VARIABLES LIKE 'long_query_time'");
            result.put("long_query_time", rows.isEmpty() ? "?" : rows.get(0).get("Value"));
            rows = jt.queryForList("SHOW GLOBAL VARIABLES LIKE 'min_examined_row_limit'");
            result.put("min_examined_row_limit", rows.isEmpty() ? "?" : rows.get(0).get("Value"));
        } catch (Exception e) {
            result.put("error", "无法读取MySQL参数: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /** 获取实例的轮询参数 */
    @GetMapping("/{instanceId}/polling-config")
    public ResponseEntity<?> getPollingConfig(@PathVariable String instanceId) {
        Map<String, Object> cfg = pollingConfigs.getOrDefault(instanceId, Map.of(
                "intervalSeconds", 60, "minQueryTimeSec", 0.5));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "config", cfg));
    }

    /** 设置实例的轮询参数。若当前正在轮询，按新间隔重启。 */
    @PutMapping("/{instanceId}/polling-config")
    public ResponseEntity<?> setPollingConfig(@PathVariable String instanceId,
                                               @RequestBody Map<String, Object> body) {
        pollingConfigs.put(instanceId, body);
        // 若正在轮询 → 重启以应用新间隔
        if (pollingThreads.containsKey(instanceId)) {
            stopPolling(instanceId);
            startPollingIfNeeded(instanceId);
        }
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "config", body));
    }

    /** 获取实例的轮询参数（供 Scheduler 使用） */
    public Map<String, Object> getPollingConfigForInstance(String instanceId) {
        return pollingConfigs.getOrDefault(instanceId, Map.of(
                "intervalSeconds", 60, "minQueryTimeSec", 0.5));
    }

    /** 记录一次采集（由 Scheduler 调用） */
    public void recordCollect(String instanceId, int count) {
        statsMap.computeIfAbsent(instanceId, k -> new CaptureStats()).record(count);
    }

    public void recordError(String instanceId, String error) {
        statsMap.computeIfAbsent(instanceId, k -> new CaptureStats()).recordError(error);
    }

    public boolean isEnabled(String instanceId) {
        return instanceEnabled.getOrDefault(instanceId, false);
    }

    // ---- 动态调度 ----

    /** 若实例已启用且采集源需要轮询（PS / 文件），启动轮询线程 */
    private void startPollingIfNeeded(String instanceId) {
        if (!isInstanceEnabled(instanceId)) return;
        String source = instanceSources.getOrDefault(instanceId, "");
        if (source.isEmpty() || source.equals("http_endpoint")) return;

        stopPolling(instanceId); // 先停旧的
        final String iid = instanceId;
        final String src = source;
        final int intervalSec = ((Number) getPollingConfigForInstance(instanceId)
                .getOrDefault("intervalSeconds", 60)).intValue();

        Thread t = new Thread(() -> {
            log.info("轮询线程启动 [{}] source={} interval={}s", iid, src, intervalSec);
            try {
                // 首次等待
                Thread.sleep(intervalSec * 1000L);
                while (isInstanceEnabled(iid)
                        && src.equals(instanceSources.getOrDefault(iid, ""))) {
                    try {
                        if ("slow_log_table".equals(src)) {
                            captureScheduler.pollPsInstance(iid);
                        } else if ("slow_log_file".equals(src)) {
                            captureScheduler.pollFileInstance(iid);
                        }
                    } catch (Exception e) {
                        log.error("轮询异常 [{}]: {}", iid, e.getMessage(), e);
                    }
                    Thread.sleep(intervalSec * 1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("轮询线程退出 [{}]", iid);
        }, "poll-" + iid);
        t.setDaemon(true);
        t.start();
        pollingThreads.put(instanceId, t);
        pollingStartedAt.put(instanceId, LocalDateTime.now());
        log.info("轮询已启动 [{}] source={} interval={}s", instanceId, source, intervalSec);
    }

    /** 停止该实例的轮询线程 */
    private void stopPolling(String instanceId) {
        Thread t = pollingThreads.remove(instanceId);
        pollingStartedAt.remove(instanceId);
        if (t != null) {
            t.interrupt();
            log.info("轮询已停止 [{}]", instanceId);
        }
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
