package com.slowsql.capture;

import com.slowsql.config.SqlMonitorProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis 指纹去重——30min窗口内同指纹只诊断一次 */
@Component
public class FingerprintDedupService {

    private final StringRedisTemplate redis;
    private final int dedupMinutes;

    public FingerprintDedupService(StringRedisTemplate redis, SqlMonitorProperties props) {
        this.redis = redis;
        this.dedupMinutes = props.getCapture().getDedupWindowMinutes();
    }

    /** 尝试注册指纹，成功=新指纹需诊断，失败=已有缓存跳过 */
    public boolean tryRegister(String fingerprint) {
        try {
            Boolean ok = redis.opsForValue()
                    .setIfAbsent("diagnosis:dedup:" + fingerprint, "1",
                            Duration.ofMinutes(dedupMinutes));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return true; // Redis挂了→保守：都诊断
        }
    }

    public String getCachedReport(String fingerprint) {
        try {
            return redis.opsForValue().get("diagnosis:result:fp:" + fingerprint);
        } catch (Exception e) {
            return null;
        }
    }

    public void cacheReport(String fingerprint, String report) {
        try {
            redis.opsForValue().set("diagnosis:result:fp:" + fingerprint, report,
                    Duration.ofMinutes(dedupMinutes));
        } catch (Exception e) { /* ignore */ }
    }
}
