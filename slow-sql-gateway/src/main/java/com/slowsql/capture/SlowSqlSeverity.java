package com.slowsql.capture;

public enum SlowSqlSeverity {
    P0("严重", "🔴", 10.0, 1_000_000L),
    P1("警告", "🟡", 2.0, 100_000L),
    P2("关注", "🟢", 0, 0);

    public final String label;
    public final String emoji;
    private final double minQueryTime;
    private final long minRowsExamined;

    SlowSqlSeverity(String label, String emoji, double minQueryTime, long minRowsExamined) {
        this.label = label;
        this.emoji = emoji;
        this.minQueryTime = minQueryTime;
        this.minRowsExamined = minRowsExamined;
    }

    public static SlowSqlSeverity from(SlowSqlEvent event) {
        if (event.getMetrics() == null) return P2;
        double t = event.getMetrics().getQueryTimeSec();
        long r = event.getMetrics().getRowsExamined();
        if (t >= P0.minQueryTime || r >= P0.minRowsExamined) return P0;
        if (t >= P1.minQueryTime || r >= P1.minRowsExamined) return P1;
        return P2;
    }
}
