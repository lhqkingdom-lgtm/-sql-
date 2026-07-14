package com.slowsql.capture;

import com.slowsql.config.SqlMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 慢日志文件采集源。RandomAccessFile + 字节偏移增量读取，
 * 支持文件轮转检测（Windows 用文件大小，Linux 用 inode）。
 */
public class SlowLogFileSource implements CaptureSource {

    private static final Logger log = LoggerFactory.getLogger(SlowLogFileSource.class);

    private static final Pattern TIME_PATTERN =
            Pattern.compile("^# Time: (\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern QUERY_TIME_PATTERN =
            Pattern.compile("Query_time: (\\d+\\.?\\d*)");
    private static final Pattern ROWS_EXAMINED_PATTERN =
            Pattern.compile("Rows_examined: (\\d+)");

    private final SqlMonitorProperties.CaptureConfig.SourceConfig config;
    private final Path logFile;
    private long lastOffset = 0;
    private long lastInode = 0;

    public SlowLogFileSource(SqlMonitorProperties.CaptureConfig.SourceConfig config) {
        this.config = config;
        this.logFile = config.getPath() != null ? Path.of(config.getPath()) : null;
        // 首次启动从当前文件末尾开始，不采集历史数据
        if (logFile != null && Files.exists(logFile)) {
            try {
                this.lastOffset = Files.size(logFile);
            } catch (IOException e) { /* ignore, keep 0 */ }
        }
    }

    @Override public String name() { return "slow_log_file"; }

    /** 获取此文件源绑定的实例 ID（来自 yml 配置）。 */
    public String getInstanceId() { return config.getInstanceId(); }

    @Override
    public boolean isConfigured() {
        return config != null && "slow_log_file".equals(config.getType())
                && config.getPath() != null && !config.getPath().isBlank()
                && Files.exists(Path.of(config.getPath()));
    }

    @Override
    public List<SlowSqlEvent> collect() {
        List<CapturedSql> rawResult = new ArrayList<>();
        if (logFile == null || !Files.exists(logFile)) return List.of();

        try {
            long currentInode = detectInode();
            if (lastInode != 0 && currentInode != lastInode) {
                log.info("慢日志文件已轮转，重置偏移量");
                lastOffset = 0;
            }
            lastInode = currentInode;

            long fileSize = Files.size(logFile);
            if (fileSize <= lastOffset) { lastOffset = 0; }

            try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
                raf.seek(lastOffset);
                StringBuilder block = new StringBuilder();
                String line;
                CapturedSql current = null;
                String currentDb = null;

                while ((line = raf.readLine()) != null) {
                    if (line.startsWith("# Time:")) {
                        if (current != null && current.getSqlText() != null) {
                            rawResult.add(current);
                        }
                        current = new CapturedSql();
                        current.setSource(name());
                        if (config.getInstanceId() != null && !config.getInstanceId().isBlank()) {
                            current.setInstanceId(config.getInstanceId());
                        }
                        if (currentDb != null) current.setDatabaseName(currentDb);
                        Matcher tm = TIME_PATTERN.matcher(line);
                        if (tm.find()) {
                            current.setCapturedAt(LocalDateTime.parse(
                                    tm.group(1), DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        }
                    } else if (line.startsWith("# Query_time:") && current != null) {
                        Matcher qm = QUERY_TIME_PATTERN.matcher(line);
                        if (qm.find()) current.setQueryTimeSec(Double.parseDouble(qm.group(1)));
                        Matcher rm = ROWS_EXAMINED_PATTERN.matcher(line);
                        if (rm.find()) current.setRowsExamined(Long.parseLong(rm.group(1)));
                    } else if (line.startsWith("use ") || line.startsWith("USE ")) {
                        currentDb = line.substring(4).trim().replace(";", "");
                    } else if (line.startsWith("SET timestamp")) {
                        // skip
                    } else if (!line.startsWith("#") && current != null && current.getSqlText() == null) {
                        current.setSqlText(line.trim());
                        current.setFingerprint(CapturedSql.fingerprint(line.trim()));
                    }
                }
                if (current != null && current.getSqlText() != null) {
                    rawResult.add(current);
                }
                lastOffset = raf.getFilePointer();
            }
        } catch (IOException e) {
            log.warn("读取慢日志文件失败: {}", e.getMessage());
        }

        double minTime = config.getMinQueryTimeSec();
        long minRows = config.getMinRowsExamined();
        rawResult.removeIf(c -> c.getQueryTimeSec() < minTime || c.getRowsExamined() < minRows);

        if (!rawResult.isEmpty()) log.info("慢日志文件采集到 {} 条", rawResult.size());
        return rawResult.stream().map(this::toEvent).toList();
    }

    private long detectInode() {
        try {
            return (long) Files.getAttribute(logFile, "unix:ino");
        } catch (UnsupportedOperationException e) {
            try {
                long size = Files.size(logFile);
                if (lastInode > 0 && size < lastInode) {
                    log.info("慢日志文件可能已轮转（文件变小）");
                    lastOffset = 0;
                }
                return size;
            } catch (IOException ex) { return 0; }
        } catch (IOException e) { return 0; }
    }

    public boolean healthCheck() {
        return logFile != null && Files.isReadable(logFile);
    }

    private SlowSqlEvent toEvent(CapturedSql cs) {
        SlowSqlEvent e = new SlowSqlEvent();
        e.setSqlText(cs.getSqlText());
        e.setSource(cs.getSource());
        e.setDbName(cs.getDatabaseName());
        e.setInstanceId(cs.getInstanceId() != null ? cs.getInstanceId() : "");
        e.setFingerprint(cs.getFingerprint());
        if (cs.getCapturedAt() != null) e.setCapturedAt(cs.getCapturedAt());
        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(cs.getQueryTimeSec());
        m.setRowsExamined(cs.getRowsExamined());
        e.setMetrics(m);
        e.computeFingerprint();
        return e;
    }
}
