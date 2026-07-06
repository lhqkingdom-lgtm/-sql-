# 审查报告修复日志

> 基于 `CODE_REVIEW_CRITIQUE.md` 25 个问题的修复记录

---

## 已修复

### P0#1 — diagnosis_record 缺 UNIQUE 约束
- **文件**: `schema.sql:26`
- **修改**: `INDEX idx_task_id (task_id)` → `UNIQUE INDEX idx_task_id (task_id)`

### P0#2 — CompensateScheduler 用 hashCode 替代 fingerprint
- **文件**: `CompensateScheduler.java:88-99`
- **修改**: 重构 `rebuildFingerprintCache` 方法，先用 `findByTaskId` 查 record，再用 `r.getFingerprint()` 构造 Redis key。原代码 `"diagnosis:result:fp:" + taskId.hashCode()` 完全错误。

### P0#3 — DataAccessController.checkToken 死代码
- **文件**: `DataAccessController.java:50-53`
- **修改**: 删除 `checkToken()` 方法。该方法只打日志不拦截，且从未被调用。

### P0#4 — DiagnosisResultConsumer 对所有异常 requeue
- **文件**: `DiagnosisResultConsumer.java:82-85`
- **修改**: 区分 `JsonProcessingException`（不可恢复→ACK丢弃）和 `Exception`（可重试→NACK requeue）。防御 #24 已实现。

### P0#5 — RabbitMQ 消息双序列化
- **文件**: `DiagnosisTaskProducer.java:57-76`
- **修改**: `convertAndSend(exchange, routingKey, json)` → `convertAndSend(exchange, routingKey, taskMsg)`。传 Map 对象让 Jackson2JsonMessageConverter 自动序列化一次，不再先转 String 导致双重 JSON 包裹。

### P1#6 — projects() 返回空列表
- **文件**: `SqlAnalyzeController.java:57-62` + 构造器
- **修改**: 注入 `SqlMonitorProperties`，`projects()` 遍历 `getProjects()` 返回真实项目列表。

### P1#8 — IM 去重 Set 无 TTL
- **文件**: `ImNotificationService.java:14-38`
- **修改**: `ConcurrentHashMap.newKeySet()` → `Redis SETEX im:notified:{fp} 1 EX 86400`。24h TTL 防内存泄漏。

### P1#9 — lastCheckMap 非线程安全
- **文件**: `SlowSqlCaptureScheduler.java:27`
- **修改**: `new HashMap<>()` → `new ConcurrentHashMap<>()`

### P1#10 — 监测池未关闭
- **文件**: `DataSourceManager.java:155-172`
- **修改**: `shutdown()` 新增遍历 `monitorTemplateMap`，对每个 HikariDataSource 调用 `.close()` 释放连接。

### P1#11 — RabbitMQ Confirm/Return 空回调
- **文件**: `RabbitMqConfig.java:120-130`
- **修改**: 删除空回调占位代码，由 `DiagnosisTaskProducer` 构造器自行注入实现。

### P3#18 — 依赖 Lombok 但不用
- **文件**: `pom.xml:86-89, 112-113`
- **修改**: 删除 Lombok dependency 和 maven-surefire-plugin 的 exclude 配置。

### P3#19 — 废 pre-fetch 配置
- **文件**: `application.yml:67-73`
- **修改**: 删除整个 `pre-fetch` 配置块（V5 已取消预加载）。

### P1#12 — 密码改为环境变量 + ENC
- **文件**: `application.yml:97-98, 15-16`
- **修改**: 所有密码从 `123456` 改为 `${ENV_VAR:ENC(encrypted_password)}`。

---

## 未修复（需评估）

### P1#7 — 采集源 1/5 实现
- **状态**: 未修复。`SlowLogTableSource` 是唯一有实际代码的采集源。`SlowLogFileSource`、`PerformanceSchemaSource` 不存在。`HttpCaptureController` 未实现。
- **建议**: 作为 Step 7 的后续迭代任务，非阻塞。

### P2#13-16 — 测试覆盖缺口
- **状态**: 未修复。10 个类无测试，测试计划报告与实际用例数不符。
- **建议**: 下一步写测试计划文档。

### P3#21 — 硬编码值
- **状态**: 未修复。`LIMIT 200`、`minusMinutes(10)`、Webhook URL 等仍硬编码。
- **建议**: 非阻塞，可后续配置化。

### P3#22 — 异常信息泄露
- **状态**: 未修复。`DataAccessController` catch 块直接拼接 `e.getMessage()` 返回给调用方。
- **建议**: 生产环境需加通用脱敏，当前内部 API 场景影响可控。

### P3#23 — AuditLogger 无人调用
- **状态**: 未修复。`AuditLogger.log()` 在代码库零引用。
- **建议**: 后续集成。

### P3#24 — RateLimitInterceptor 不存在
- **状态**: 未修复。限流配置存在但拦截器未实现。
- **建议**: 后续添加。

### P3#25 — DataSourceContextHolder 零引用
- **状态**: 未修复。ThreadLocal 工具类定义了但没 Controller 用它。
- **建议**: 因为 V5 改为按 instanceId 直接传参（路径变量 `{instanceId}`），不再需要 ThreadLocal。可考虑删除该类。

---

## 变更文件汇总

| 文件 | 改动类型 |
|------|----------|
| `schema.sql` | P0修复 |
| `CompensateScheduler.java` | P0修复 |
| `DataAccessController.java` | P0修复（删死代码） |
| `DiagnosisResultConsumer.java` | P0修复 |
| `DiagnosisTaskProducer.java` | P0修复 |
| `SqlAnalyzeController.java` | P1修复 + 注入新依赖 |
| `ImNotificationService.java` | P1修复 |
| `SlowSqlCaptureScheduler.java` | P1修复 |
| `DataSourceManager.java` | P1修复 |
| `RabbitMqConfig.java` | P1修复 |
| `pom.xml` | P3修复 |
| `application.yml` | P1+P3修复 |

共 12 个文件（Round 1）+ 8 个文件（Round 2）。

---

## Round 2 修复（审查员复测发现）

### P0#5-fix 测试未同步 — DiagnosisGatewayTest 3 failures
- **文件**: `DiagnosisGatewayTest.java:63, 73, 82`
- **修改**: `anyString()` → `any(Map.class)`（因为 P0#5 将传参从 String 改为 Map）

### P3#19 修一半 — PreFetchConfig Java 死代码未删除
- **文件**: `SqlMonitorProperties.java:19, 30-31, 98-116`
- **修改**: 删除 `preFetch` 字段、getter/setter、整个 `PreFetchConfig` 内部类

### P1#12 修一半 — Redis 密码仍明文
- **文件**: `application.yml:37`
- **修改**: `password: ${REDIS_PASSWORD:123456}` → `password: ${REDIS_PASSWORD:}`

### ImNotificationService 重复 import
- **文件**: `ImNotificationService.java:14-17`
- **修改**: 删除重复的 `java.net.URI/HttpClient/HttpRequest/HttpResponse` import

### DataSourceManager 监测池复用 bug
- **文件**: `DataSourceManager.java:60-67, 85-97`
- **修改**: 抽 `createMonitoringPool()` 方法，复用分支也调用（之前只有新建分支才建监测池）

### P0#4 ClassCastException 防御
- **文件**: `DiagnosisResultConsumer.java:58-60`
- **修改**: `(String) result.get("taskId")` → `String.valueOf(tidObj)`，防御 Integer taskId

### Round 2 变更文件汇总
| 文件 | 改动 |
|------|------|
| `DiagnosisGatewayTest.java` | 3处 `anyString()`→`any(Map.class)` |
| `SqlMonitorProperties.java` | 删 PreFetchConfig 死代码 |
| `application.yml` | Redis 密码去明文 |
| `ImNotificationService.java` | 删重复 import + 修复注释残留 |
| `DataSourceManager.java` | 抽 `createMonitoringPool`，复用+新建两处调用 |
| `DiagnosisResultConsumer.java` | 防 ClassCastException |

Round 1+2 共修改 18 个文件。

---

## Round 3 修复（P1+P3 残留）

### P1#12 Jasypt 解密配置
- **文件**: `application.yml:40-43`
- **修改**: 新增 `jasypt.encryptor.password` 和 `algorithm` 配置，使 ENC(...) 密文可在生产环境解密
- **注意**: 开发环境 `JASYPT_ENCRYPTOR_PASSWORD` 为空时，ENC(...) 会作为明文解析失败，本地测试需用明文密码

### P3#22 异常信息脱敏
- **文件**: `DataAccessController.java`（全局替换 15 处）
- **修改**: 所有 catch 块移除 `+ e.getMessage()`，HTTP 响应只返回固定错误描述。完整异常仍通过 `log.error` 记录

### P3#23 AuditLogger 接入诊断链路
- **文件**: `SqlAnalyzeController.java`（import + 注入 + 调用）
- **修改**: 注入 `AuditLogger`，在 `analyze()` 返回前调用 `auditLogger.log()` 记录审计流水

### P3#24 RateLimitInterceptor 实现
- **文件**: `RateLimitInterceptor.java`（新文件）+ `WebMvcConfig.java`（新文件）
- **修改**: Redis INCR + EXPIRE 实现 IP 令牌桶限流，拦截 `/api/sql/analyze`。Redis 不可达时放行不阻塞

### P3#25 DataSourceContextHolder 删除
- **文件**: `DataSourceContextHolder.java`（删除）
- **修改**: V5 用路径变量 `{instanceId}` 路由，不再需要 ThreadLocal。零引用的死代码直接删除

### Round 3 变更文件汇总
| 文件 | 改动 |
|------|------|
| `application.yml` | 新增 Jasypt 配置 |
| `DataAccessController.java` | 15处异常脱敏 |
| `SqlAnalyzeController.java` | 注入 AuditLogger + 调用 |
| `RateLimitInterceptor.java` | 新建——IP 限流 |
| `WebMvcConfig.java` | 新建——注册拦截器 |
| `DataSourceContextHolder.java` | 删除 |

Round 1+2+3 共修改 22 个文件（含 2 新建 1 删除）。

