package com.slowsql.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 MySQL 实例管理连接池。
 * 同一 host:port 只建一个池，多个 instanceId 可映射到同一个池。
 */
@Component
public class DataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DataSourceManager.class);

    private final SqlMonitorProperties properties;

    /** host:port → HikariDataSource（物理去重） */
    private final Map<String, HikariDataSource> poolMap = new ConcurrentHashMap<>();

    /** instanceId → HikariDataSource（快速路由） */
    private final Map<String, HikariDataSource> instancePoolMap = new ConcurrentHashMap<>();

    /** instanceId → JdbcTemplate */
    private final Map<String, JdbcTemplate> templateMap = new ConcurrentHashMap<>();

    /** instanceId → JdbcTemplate（监测专用，轻量池） */
    private final Map<String, JdbcTemplate> monitorTemplateMap = new ConcurrentHashMap<>();

    public DataSourceManager(SqlMonitorProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        List<SqlMonitorProperties.InstanceConfig> instances = properties.getInstances();
        if (instances == null || instances.isEmpty()) {
            log.info("未配置任何 MySQL 实例，跳过连接池初始化");
            return;
        }

        int createdCount = 0;
        int reusedCount = 0;

        for (SqlMonitorProperties.InstanceConfig inst : instances) {
            String hostKey = inst.getHostKey();

            // 去重：同 host:port 复用已有池
            HikariDataSource ds = poolMap.get(hostKey);
            if (ds != null) {
                instancePoolMap.put(inst.getId(), ds);
                templateMap.put(inst.getId(), new JdbcTemplate(ds));
                // 监测池也复用或新建（每个实例独立监测池以便隔离）
                reusedCount++;
                log.info("实例 {} 复用连接池 {} (共 {} 个实例共享)",
                        inst.getId(), hostKey, countInstancesForPool(hostKey));
                createMonitoringPool(inst);
                continue;
            }

            // 新建池
            try {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(inst.getJdbcUrl());
                config.setUsername(inst.getUsername());
                config.setPassword(inst.getPassword());
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setMinimumIdle(2);
                config.setMaximumPoolSize(15);
                config.setIdleTimeout(300000);          // 5min 空闲缩到 minIdle
                config.setConnectionTimeout(10000);     // 10s 超时
                config.setPoolName("diag-" + inst.getId());

                HikariDataSource newDs = new HikariDataSource(config);
                poolMap.put(hostKey, newDs);
                instancePoolMap.put(inst.getId(), newDs);
                templateMap.put(inst.getId(), new JdbcTemplate(newDs));

                createMonitoringPool(inst);
                createdCount++;
                log.info("连接池已创建: {} → {}", inst.getId(), hostKey);

            } catch (Exception e) {
                log.error("创建连接池失败 [{}] {}: {} —— 跳过此实例，不影响其他实例",
                        inst.getId(), hostKey, e.getMessage());
                // 不放入 map，getTemplate() 查不到会抛 IllegalArgumentException
            }
        }

        log.info("连接池初始化完成: 新建 {} 个, 复用 {} 个, 共管理 {} 个实例",
                createdCount, reusedCount, instances.size());
    }

    /**
     * 根据 instanceId 获取 JdbcTemplate（用于诊断工具调用）。
     */
    public JdbcTemplate getTemplate(String instanceId) {
        JdbcTemplate jt = templateMap.get(instanceId);
        if (jt == null) {
            throw new IllegalArgumentException("未找到实例配置: " + instanceId);
        }
        return jt;
    }

    /**
     * 获取监测专用 JdbcTemplate（采集调度用，与诊断池隔离）。
     */
    public JdbcTemplate getMonitoringTemplate(String instanceId) {
        JdbcTemplate jt = monitorTemplateMap.get(instanceId);
        if (jt == null) {
            throw new IllegalArgumentException("未找到实例配置: " + instanceId);
        }
        return jt;
    }

    /**
     * 连接预检：SELECT 1 探活。
     */
    public boolean validateConnection(String instanceId) {
        JdbcTemplate jt = getTemplate(instanceId);
        if (jt == null) return false;
        try {
            jt.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("连接预检失败 [{}]: {}", instanceId, e.getMessage());
            return false;
        }
    }

    /**
     * 根据 instanceId 查找所属的 projectCode。
     */
    public String findProjectCode(String instanceId) {
        for (SqlMonitorProperties.ProjectConfig project : properties.getProjects()) {
            if (project.getInstanceIds() != null
                    && project.getInstanceIds().contains(instanceId)) {
                return project.getCode();
            }
        }
        return null;
    }

    /**
     * 获取所有已成功初始化的实例 ID。
     */
    public java.util.Set<String> getReadyInstanceIds() {
        java.util.Set<String> ready = new java.util.HashSet<>();
        templateMap.forEach((id, jt) -> {
            if (jt != null) ready.add(id);
        });
        return ready;
    }

    private void createMonitoringPool(SqlMonitorProperties.InstanceConfig inst) {
        com.zaxxer.hikari.HikariConfig monConfig = new com.zaxxer.hikari.HikariConfig();
        monConfig.setJdbcUrl(inst.getJdbcUrl());
        monConfig.setUsername(inst.getUsername());
        monConfig.setPassword(inst.getPassword());
        monConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        monConfig.setMaximumPoolSize(3);
        monConfig.setConnectionTimeout(10000);
        monConfig.setPoolName("mon-" + inst.getId());
        com.zaxxer.hikari.HikariDataSource monDs = new com.zaxxer.hikari.HikariDataSource(monConfig);
        monitorTemplateMap.put(inst.getId(), new JdbcTemplate(monDs));
    }

    private int countInstancesForPool(String hostKey) {
        DataSource pool = poolMap.get(hostKey);
        return (int) instancePoolMap.values().stream().filter(pool::equals).count();
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭诊断连接池...", poolMap.size());
        poolMap.forEach((key, ds) -> {
            try { ds.close(); log.info("诊断池已关闭: {}", key); }
            catch (Exception e) { log.warn("关闭诊断池失败 [{}]: {}", key, e.getMessage()); }
        });
        log.info("正在关闭监测连接池...");
        monitorTemplateMap.forEach((key, jt) -> {
            try {
                if (jt != null && jt.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource ds) {
                    ds.close();
                    log.info("监测池已关闭: {}", key);
                }
            } catch (Exception e) { log.warn("关闭监测池失败 [{}]: {}", key, e.getMessage()); }
        });
        poolMap.clear();
        instancePoolMap.clear();
        templateMap.clear();
        monitorTemplateMap.clear();
    }
}
