package com.slowsql.api;

public record SqlAnalyzeRequest(
        String sessionId,
        String projectCode,
        String instanceId,
        String sql,
        String type) {
}
