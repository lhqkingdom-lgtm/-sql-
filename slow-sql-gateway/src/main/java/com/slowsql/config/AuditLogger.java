package com.slowsql.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditLogger {

    public static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    public void log(String taskId, String sessionId, String instanceId,
                    int sqlLength, boolean masked, String tableNames,
                    long durationMs, String status) {
        log.info(AUDIT, "taskId={} | sessionId={} | instanceId={} | sqlLen={} | masked={} | tables={} | durationMs={} | status={}",
                taskId, sessionId, instanceId, sqlLength, masked, tableNames, durationMs, status);
    }
}
