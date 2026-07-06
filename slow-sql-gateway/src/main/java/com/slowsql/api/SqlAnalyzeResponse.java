package com.slowsql.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SqlAnalyzeResponse(
        String taskId,
        String status,
        String report,
        String error,
        String errorCode) {

    public static SqlAnalyzeResponse pending(String taskId) {
        return new SqlAnalyzeResponse(taskId, "pending", null, null, null);
    }

    public static SqlAnalyzeResponse completed(String report) {
        return new SqlAnalyzeResponse(null, "completed", report, null, null);
    }
}
