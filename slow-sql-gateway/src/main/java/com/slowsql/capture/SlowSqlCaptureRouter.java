package com.slowsql.capture;

/** 采集路由工具方法 */
public final class SlowSqlCaptureRouter {
    private SlowSqlCaptureRouter() {}

    public static String buildDiagnosisContext(SlowSqlEvent event) {
        StringBuilder ctx = new StringBuilder("【自动采集信息】\n");
        if (event.getMetrics() != null) {
            ctx.append(String.format("- 耗时: %.2f 秒\n", event.getMetrics().getQueryTimeSec()));
            ctx.append(String.format("- 扫描: %d 行\n", event.getMetrics().getRowsExamined()));
        }
        ctx.append(String.format("- 来源: %s\n", event.getSource()));
        ctx.append(String.format("- 库: %s\n", event.getDbName() != null ? event.getDbName() : "unknown"));
        ctx.append("\n【待分析SQL】\n").append(event.getSqlText());
        return ctx.toString();
    }
}
