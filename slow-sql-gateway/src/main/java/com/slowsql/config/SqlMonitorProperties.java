package com.slowsql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V5.0 配置模型：projects + instances 两层。
 * 按 MySQL 实例管理连接，一个实例一个 HikariCP 连接池。
 */
@Component
@ConfigurationProperties(prefix = "sql-monitor")
public class SqlMonitorProperties {

    private CaptureConfig capture = new CaptureConfig();
    private RateLimitConfig rateLimit = new RateLimitConfig();
    private List<ProjectConfig> projects = new ArrayList<>();
    private List<InstanceConfig> instances = new ArrayList<>();

    // ===== getters/setters =====
    public CaptureConfig getCapture() { return capture; }
    public void setCapture(CaptureConfig capture) { this.capture = capture; }

    public RateLimitConfig getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimitConfig rateLimit) { this.rateLimit = rateLimit; }

    public List<ProjectConfig> getProjects() { return projects; }
    public void setProjects(List<ProjectConfig> projects) { this.projects = projects; }

    public List<InstanceConfig> getInstances() { return instances; }
    public void setInstances(List<InstanceConfig> instances) { this.instances = instances; }

    // ===== 采集配置 =====
    public static class CaptureConfig {
        private boolean enabled = true;
        private int dedupWindowMinutes = 30;
        private int maxPerRound = 50;
        private FilterConfig filter = new FilterConfig();
        private List<SourceConfig> sources = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDedupWindowMinutes() { return dedupWindowMinutes; }
        public void setDedupWindowMinutes(int dedupWindowMinutes) { this.dedupWindowMinutes = dedupWindowMinutes; }
        public int getMaxPerRound() { return maxPerRound; }
        public void setMaxPerRound(int maxPerRound) { this.maxPerRound = maxPerRound; }
        public FilterConfig getFilter() { return filter; }
        public void setFilter(FilterConfig filter) { this.filter = filter; }
        public List<SourceConfig> getSources() { return sources; }
        public void setSources(List<SourceConfig> sources) { this.sources = sources; }

        public static class FilterConfig {
            private double minQueryTimeSec;
            private long minRowsExamined;
            private List<String> ignoreDatabases = new ArrayList<>();

            public double getMinQueryTimeSec() { return minQueryTimeSec; }
            public void setMinQueryTimeSec(double minQueryTimeSec) { this.minQueryTimeSec = minQueryTimeSec; }
            public long getMinRowsExamined() { return minRowsExamined; }
            public void setMinRowsExamined(long minRowsExamined) { this.minRowsExamined = minRowsExamined; }
            public List<String> getIgnoreDatabases() { return ignoreDatabases; }
            public void setIgnoreDatabases(List<String> ignoreDatabases) { this.ignoreDatabases = ignoreDatabases; }
        }

        public static class SourceConfig {
            private String type;
            private int intervalSeconds = 60;
            private double minQueryTimeSec;
            private long minRowsExamined;
            private int lookbackSeconds = 600;
            private String path;
            private String instanceId;

            public String getType() { return type; }
            public void setType(String type) { this.type = type; }
            public int getIntervalSeconds() { return intervalSeconds; }
            public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
            public double getMinQueryTimeSec() { return minQueryTimeSec; }
            public void setMinQueryTimeSec(double minQueryTimeSec) { this.minQueryTimeSec = minQueryTimeSec; }
            public long getMinRowsExamined() { return minRowsExamined; }
            public void setMinRowsExamined(long minRowsExamined) { this.minRowsExamined = minRowsExamined; }
            public int getLookbackSeconds() { return lookbackSeconds; }
            public void setLookbackSeconds(int lookbackSeconds) { this.lookbackSeconds = lookbackSeconds; }
            public String getPath() { return path; }
            public void setPath(String path) { this.path = path; }
            public String getInstanceId() { return instanceId; }
            public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        }
    }

    // ===== 限流配置 =====
    public static class RateLimitConfig {
        private int maxPerMinute = 100;

        public int getMaxPerMinute() { return maxPerMinute; }
        public void setMaxPerMinute(int maxPerMinute) { this.maxPerMinute = maxPerMinute; }
    }

    // ===== 项目配置 =====
    public static class ProjectConfig {
        private String code;
        private String name;
        private List<String> instanceIds = new ArrayList<>();

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getInstanceIds() { return instanceIds; }
        public void setInstanceIds(List<String> instanceIds) { this.instanceIds = instanceIds; }
    }

    // ===== 实例配置 =====
    public static class InstanceConfig {
        private String id;
        private String host;
        private int port = 3306;
        private String username;
        private String password;
        private String defaultDatabase;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDefaultDatabase() { return defaultDatabase; }
        public void setDefaultDatabase(String defaultDatabase) { this.defaultDatabase = defaultDatabase; }

        public String getJdbcUrl() {
            String db = defaultDatabase != null ? defaultDatabase : "";
            return String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                    host, port, db);
        }

        public String getHostKey() {
            return host + ":" + port;
        }
    }
}
