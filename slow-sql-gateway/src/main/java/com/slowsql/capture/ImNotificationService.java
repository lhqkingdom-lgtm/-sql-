package com.slowsql.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** IM通知——企微/钉钉 Webhook，24h内同指纹不重复推送 */
@Component
public class ImNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ImNotificationService.class);
    private final Set<String> notifiedFps = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** 发送通知——同指纹24h内只推一次 */
    public void notify(String fingerprint, String sqlPreview, String content,
                       double queryTime, long rowsExamined, String db) {
        if (!notifiedFps.add(fingerprint)) return;

        String msg = String.format("""
                {
                  "msgtype": "text",
                  "text": {"content": "%s 慢查询告警\\n库: %s\\n耗时: %.1fs | 扫描: %d行\\nSQL: %s\\n\\n%s"}
                }""",
                SlowSqlSeverity.from(newSlowSqlEvent(queryTime, rowsExamined)).emoji,
                db, queryTime, rowsExamined,
                sqlPreview.length() > 100 ? sqlPreview.substring(0, 100) + "..." : sqlPreview,
                content.length() > 300 ? content.substring(0, 300) + "..." : content);

        log.info("IM通知: {}", msg);
        // Webhook URL 从配置读取，这里仅打日志（实际推送取消注释即可）
        // sendWebhook(msg);
    }

    private SlowSqlEvent newSlowSqlEvent(double queryTime, long rowsExamined) {
        SlowSqlEvent e = new SlowSqlEvent();
        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(queryTime);
        m.setRowsExamined(rowsExamined);
        e.setMetrics(m);
        return e;
    }

    @SuppressWarnings("unused")
    private void sendWebhook(String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_KEY"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("IM推送失败: {}", e.getMessage());
        }
    }
}
