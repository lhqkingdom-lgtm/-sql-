package com.slowsql.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** IM通知——企微/钉钉 Webhook，24h内同指纹不重复推送（Redis SETEX防内存泄漏） */
@Component
public class ImNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ImNotificationService.class);
    private static final String DEDUP_PREFIX = "im:notified:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public ImNotificationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 发送通知——同指纹24h内只推一次 */
    public void notify(String fingerprint, String sqlPreview, String content,
                       double queryTime, long rowsExamined, String db) {
        try {
            Boolean isNew = redis.opsForValue()
                    .setIfAbsent(DEDUP_PREFIX + fingerprint, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(isNew)) return;
        } catch (Exception e) {
            // Redis挂了 → 保守：允许推送
        }

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
