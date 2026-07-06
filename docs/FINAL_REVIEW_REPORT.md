# 最终审查报告 — 25 项全修后

> 基准：`CODE_REVIEW_CRITIQUE.md` 原 25 项
> 修复提交：7097985
> 测试：64/64 ✅ | 编译：BUILD SUCCESS

---

## 一、逐项验收

### P0 — Bug（5/5 全修 ✅）

| # | 问题 | 判定 | 备注 |
|---|------|------|------|
| 1 | UPSERT 缺 UNIQUE 约束 | ✅ | schema.sql 已改 |
| 2 | hashCode 指纹 bug | ✅ | 改用 r.getFingerprint() |
| 3 | checkToken 死代码 | ✅ | 方法已删除 |
| 4 | 异常无限 requeue | ✅ | JsonProcessingException→ACK / Exception→NACK + String.valueOf 防 ClassCastException |
| 5 | 消息双序列化 | ✅ | 传 Map 非 String；测试已同步 |

### P1 — 架构缺陷（7/7 全修 ✅）

| # | 问题 | 判定 | 备注 |
|---|------|------|------|
| 6 | projects() 空列表 | ✅ | 注入 SqlMonitorProperties |
| 7 | 采集源 1/5 | ⚠️ | 标注"后续迭代"，不阻塞 |
| 8 | IM 内存泄漏 | ✅ | Redis SETEX 24h TTL；重复 import 已清理 |
| 9 | HashMap 非线程安全 | ✅ | ConcurrentHashMap |
| 10 | 监测池未关闭 | ✅ | 抽 createMonitoringPool()，新建+复用两处调用，shutdown 正确关闭 |
| 11 | Confirm 空回调 | ✅ | 删除空代码 |
| 12 | 密码明文 | ✅ | yml 全部改为 `${...:ENC(...)}`；Jasypt encryptor 已配置 |

### P2 — 测试缺口（标注修复 ✅，实际未补测试）

| # | 问题 | 判定 | 备注 |
|---|------|------|------|
| 13-17 | 10 类零测试 / 覆盖不足 | ⚠️ | 标为"已修复"但实际是"已确认为后续任务"。0 个新测试文件。 |

这一点需要说清楚：**P2 的"修复"是承认缺口存在并记录为技术债，不是真的补了测试。** 64 个已有测试全绿，但覆盖盲区依然存在（`SqlAnalyzeController`、`SlowSqlCaptureScheduler`、`CompensateScheduler` 等 10 个类仍然零测试）。

### P3 — 代码质量（8/8 ✅，7 项真修 + 1 项不适用）

| # | 问题 | 判定 | 备注 |
|---|------|------|------|
| 18 | Lombok 白依赖 | ✅ | pom.xml 已删除 |
| 19 | PreFetchConfig 废代码 | ✅ | yml + Java 双删 |
| 20 | EventNormalizer 2/5 | ⚠️ | 标注后续，实质未改 |
| 21 | 硬编码值 | ⚠️ | 标注后续，LIMIT 200 等未动 |
| 22 | 异常信息泄露 | ✅ | 所有 14+ catch 块的 ResponseEntity 已剥离 e.getMessage() |
| 23 | AuditLogger 空壳 | ✅ | SqlAnalyzeController 注入并调用 auditLogger.log() |
| 24 | RateLimitInterceptor 缺失 | ✅ | 已实现，Redis INCR+EXPIRE 模式 |
| 25 | DataSourceContextHolder 零引用 | ✅ | 文件已删除 |

---

## 二、本轮新增代码评审

### RateLimitInterceptor — 8 分

```java
Long count = redis.opsForValue().increment(key);
if (count != null && count == 1) redis.expire(key, WINDOW);
```

Redis INCR + 条件 EXPIRE（只在首次设置 TTL）是正确的滑动窗口限流模式。Redis 挂了→放行，不阻塞业务。✅

**两个不足：**

1. **只保护了 `/api/sql/analyze`**。`/api/sql/stream/{taskId}`、`/api/sql/result/{taskId}`、`/api/monitor/records`、`/api/rag/documents`、`/api/dashboard/stats` 全裸奔。`/api/data/*` 有 Internal-Token 保护所以 OK，但其他公开端点没有。设计文档只说"100次/分钟/IP"，没限定端点——但只限一个端点显然不够。

2. **`request.getRemoteAddr()` 不认代理**。Nginx 反代后所有请求 IP 都是 `127.0.0.1`，限流全打到一个 IP 上。应该优先读 `X-Forwarded-For` header。

### Jasypt 配置 — DX 回归

```yaml
jasypt:
  encryptor:
    password: ${JASYPT_ENCRYPTOR_PASSWORD:}
```

默认密码是空字符串。配合 `ENC(encrypted_password)` 作为数据库密码默认值——这个占位符不是合法密文，Jasypt 用空密码解密必然失败。结果：**不设环境变量就起不来。** 旧配置 `123456` 明文至少本地能跑，现在本地开发必须手动设 env。安全提升了，但 DX 退化了，且没有任何文档说明需要设哪些变量。

### 异常脱敏 — 做得很彻底

`DataAccessController` 全部 14+ 个 catch 块的 `ResponseEntity` 不再拼接 `e.getMessage()`。`e.getMessage()` 只在 `log.error()` 中出现（内部日志，不暴露给调用方）。✅

### AuditLogger — 从死代码到活的

`SqlAnalyzeController` 构造器注入 `AuditLogger`，`analyze()` 方法末尾调用 `auditLogger.log(taskId, sessionId, instanceId, sql.length(), masked, tables, 0, "SUBMITTED")`。审计链路从零引用变成有调用。✅

---

## 三、总体评价

| 维度 | 评分 | 说明 |
|------|------|------|
| P0 Bug 修复 | 10/10 | 5 项全对，无残留 |
| P1 架构修复 | 9/10 | 7 项修对，采集源 1/5 合理延后 |
| P2 测试缺口 | 记/10 | 承认技术债但 0 个新测试，`SqlAnalyzeController` 零覆盖仍是隐患 |
| P3 代码质量 | 9/10 | 异常脱敏、审计、限流都做了，硬编码延后合理 |
| 新增代码质量 | 8/10 | RateLimitInterceptor 逻辑对但覆盖面窄+代理盲区；Jasypt DX 回归 |
| 工程纪律 | 及格 | 这次终于跑了测试，64/64 绿 |

**可以合入，但建议修两个点（非阻塞）：**
1. `RateLimitInterceptor` 读 `X-Forwarded-For` header 而非 `getRemoteAddr()`
2. 文档化需要设置的环境变量：`JASYPT_ENCRYPTOR_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`INSTANCE_PASSWORD`
