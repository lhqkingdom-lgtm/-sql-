# V5.0 代码审查报告 — 犀利指正

> 审查范围：Step 1-8 全部 Java 源码 + 全部测试文件 + 测试计划（rippling-moseying-kahan.md）
> 审查日期：2026-07-06

---

## 🔴 P0 — Bug / 会导致线上故障

### 1. diagnosis_record 表缺 UNIQUE 约束，UPSERT 永不生效

**`DiagnosisRecordRepository.java:56-69`** 用了 `INSERT ... ON DUPLICATE KEY UPDATE`，但 `schema.sql:26` 对 `task_id` 只有 `INDEX`，没有 `UNIQUE INDEX`。MySQL 永远不会触发 UPDATE 分支 → 每次 save 都 INSERT 新行。重复 taskId 会堆多条记录。修复：`schema.sql` 把 `INDEX idx_task_id (task_id)` 改成 `UNIQUE INDEX idx_task_id (task_id)`。

### 2. `CompensateScheduler.rebuildFingerprintCache` — 用 hashCode 代替 fingerprint，缓存 Key 错误

**`CompensateScheduler.java:93`**：
```java
String fpKey = "diagnosis:result:fp:" + taskId.hashCode();
```
这行用 `taskId.hashCode()` 构造了 Redis key，而不是真正的 fingerprint。等于缓存重建逻辑完全失效——查询时用 `diagnosis:result:fp:{fingerprint}`，重建时写 `diagnosis:result:fp:{hashCode}`，永远匹配不上。应改为：
```java
String fpKey = "diagnosis:result:fp:" + r.getFingerprint();
```

### 3. `DataAccessController.checkToken` — 死代码，认证完全无效的方法

**`DataAccessController.java:50-53`**：
```java
private void checkToken(String token) {
    if (token == null || !token.equals(internalToken)) {
        log.warn("数据API认证失败");
    }
}
```
该方法只 log 不抛异常不返回 false，且 **从未被调用**。真正的认证走 `isAuthorized()` 加 403 返回。这段代码是纯粹的噪音，应删除。

### 4. `DiagnosisResultConsumer` 对所有异常都 requeue，死循环风险

**`DiagnosisResultConsumer.java:82-85`**：catch 块里统一 `basicNack(deliveryTag, false, true)` 无条件 requeue。消息解析失败（JSON 格式错误 / null taskId）也会被 requeue → 无限重试。设计文档明确的防御 #24："语法错误/空内容→直接 DEAD"。修复：区分可恢复异常（DB 写入失败）和不可恢复异常（JSON 解析失败、null taskId），后者直接 ACK + 记日志。

### 5. RabbitMQ 消息双序列化

**`DiagnosisTaskProducer.java:57`** 调用 `objectMapper.writeValueAsString(taskMsg)` 把 Map 序列化为 JSON 字符串，然后 `rabbitTemplate.convertAndSend(exchange, routingKey, json)` 发送。但 `RabbitMqConfig.java:116` 给 RabbitTemplate 配置了 `Jackson2JsonMessageConverter`——它会再次序列化这个 String。最终队列里收到的是 `"\"{\\"taskId\\":...}\""`（JSON 字符串被包了一层 JSON string）。集成测试 `shouldSendAndReceiveHighPriorityMessage` 实际收到的是 String 而非 Map。修复：要么 producer 传 Map 对象给 `convertAndSend`，要么 RabbitTemplate 不用 Jackson2JsonMessageConverter。

---

## 🟠 P1 — 架构缺陷 / 逻辑缺口

### 6. `SqlAnalyzeController#projects()` 端点返回硬编码空列表

**`SqlAnalyzeController.java:60`**：前端级联选择器的数据源直接返回 `List.of()`。项目列表是空的前端无法选择项目。应注入 `SqlMonitorProperties`，遍历 `getProjects()` 返回。

### 7. 5 个采集入口只实现了 1 个

计划定义了 5 个采集源（`CaptureSource` 接口），但 `SlowSqlCaptureScheduler` 只硬编码调用了 `mysql.slow_log` 表。`SlowLogFileSource`、`HttpCaptureController` 的采集逻辑、`PerformanceSchemaSource` **全都没有实际实现**。`CaptureSource` 接口实际上没有被任何类 implements（连 Scheduler 都没用它）。要么补实现，要么从设计文档里移除。

### 8. `ImNotificationService` 去重 Set 无 TTL，内存泄漏

**`ImNotificationService.java:20`**：`ConcurrentHashMap.newKeySet()` 只增不删。注释写"24h内同指纹不重复推送"但无任何清理机制。按一天采集 200 条新指纹，一个月就 6000 条，一年 72000 条。应改用 Redis `SETEX` 或 Guava Cache 加 TTL。

### 9. 采集调度器 `lastCheckMap` 非线程安全

**`SlowSqlCaptureScheduler.java:27`**：`new HashMap<>()` 不具备线程安全性。当前 `@Scheduled(fixedDelay=60000)` 默认单线程调度没问题，但一旦配置了 `spring.task.scheduling.pool.size` > 1 就会出并发问题。应改用 `ConcurrentHashMap`。

### 10. DataSourceManager 为同一实例创建两个 HikariCP 池但监测池永不释放

**`DataSourceManager.java:88-97`**：每个实例创建一个诊断池（max 15）+ 一个监测池（max 3），但 `shutdown()` 只关闭 `poolMap` 里的诊断池，**监测池没被关**。`monitorTemplateMap` 在 `shutdown()` 中只被 `clear()` 了 Map，没调 `HikariDataSource.close()`。MySQL 连接泄漏。

### 11. RabbitMQ Publisher Confirm / ReturnCallback 空实现

**`RabbitMqConfig.java:120-130`**：两个回调全是空函数体，注释写"降级处理（外部注入的回调）"但外部从未注入。消息路由失败时静默丢失，生产者完全不知情。应在这里直接调 Redis fallback。

### 12. Jasypt 依赖存在但密码明文存储

**`application.yml:97-98`**：密码写 `password: 123456` 明文，设计文档要求的 `ENC(...)` 加密全未使用。`pom.xml` 有 jasypt-starter 依赖但没配 `jasypt.encryptor.password`，加密链路完全未启用。

---

## 🟡 P2 — 测试覆盖缺口

### 13. 零测试覆盖的类（共 10 个）

以下类没有任何单元测试：

| 类 | 关键性 |
|---|---|
| `SqlAnalyzeController` | 🔴 整个诊断入口 |
| `SqlResultController` | 🟠 SSE 推送 + 轮询兜底 |
| `SlowSqlCaptureScheduler` | 🔴 采集调度核心 |
| `CompensateScheduler` | 🟠 超时检测 + 补偿补写 |
| `SseEmitterManager` | 🟠 SSE 连接管理 |
| `ImNotificationService` | 🟡 IM 通知 |
| `FingerprintDedupService` | 🔴 去重核心 |
| `RagRetriever` | 🟡 RAG 检索 |
| `RagController` | 🟡 知识库 CRUD |
| `DashboardController` | 🟡 统计面板 |

### 14. DataAccessControllerTest 只覆盖了 8/13 个端点

未测试的端点：`/explain/compare`、`/indexes/missing`、`/type-mismatch`、`/bufferpool`、`/processlist`、`/actual-rows`。测试计划列了 14 个用例——实际测试文件只有 12 个 `@Test`。

### 15. DataSourceManagerTest 是集成测试伪装成单测

**`DataSourceManagerTest.java:17-21`** 直接连 `localhost:3306` 的 `test_sql` 库。按 CLAUDE.md 的铁律"单元测试 Mock 所有外部依赖"，这个测试违反了规则。而且它用旧项目的 `test_sql` 库（CLAUDE.md 明确说"不使用旧项目 test_sql 库，V5 使用独立库 slow_sql_platform"）。

### 16. DiagnosisGatewayTest 的 ACK 测试是假测试

**`DiagnosisGatewayTest.java:100-123`**：`consumer_shouldProcessCompletedResult` 标题暗示测试消费者逻辑，实际只测了 `objectMapper.readValue()` 反序列化——根本没走 Consumer 的任何代码路径。真正的 `onResult` 方法从来没被调用过。

### 17. 测试计划 vs 实际测试用例数量不匹配

| Step | 计划宣称用例 | 实际 `@Test` 数 | 类 |
|---|---|---|---|
| Step 1 | 未写明 | 9 | DataSourceManagerTest |
| Step 2 | 27 | 20 | SecurityComponentsTest |
| Step 3 | 14 | 12 | DataAccessControllerTest |
| Step 4 | 9 | 9 | SlowSqlEventTest |
| Step 5 | 18 | 6 | DiagnosisGatewayTest（含 1 个假测试）|

---

## 🟢 P3 — 代码质量 / 设计异味

### 18. 依赖 Lombok 但不用

`pom.xml` 声明 Lombok，但全部 45 个 Java 文件 **没有一个用 `@Data`/`@Slf4j`/`@Getter`**。所有 getter/setter 全是手写。要么删除依赖，要么用起来。

### 19. `SqlMonitorProperties` 有未使用的配置块

`PreFetchConfig`（含 threadPoolSize、timeoutSeconds 等）被定义但 V5 已取消预加载（改为 Python Agent 按需 Function Call）。整个 `pre-fetch` 配置块是废代码。

### 20. `EventNormalizer` 只处理了 2/5 个采集源

`fromSlowLogRow`、`fromHttpCapture`、`fromManual` 已实现。`fromSlowLogFile` 和 `fromPerformanceSchema` 不存在。`SlowSqlFileSource` 类也不存在。

### 21. 硬编码值散落各处

| 位置 | 硬编码值 | 应有配置 |
|---|---|---|
| `ImNotificationService.java:57` | Webhook URL 带 `YOUR_KEY` | application.yml |
| `SlowSqlCaptureScheduler.java:118-119` | `LIMIT 200`, `minusMinutes(10)` | SqlMonitorProperties |
| `DataAccessController.java:31-34` | ALLOWED_VARIABLES 白名单 8 个 | 可配置化 |
| `RagRetriever.java:15-17` | CATEGORY_PRIORITY 和 SQL_KEYWORDS | 可配置化 |

### 22. 异常信息可能泄露内部细节

`DataAccessController` 所有 catch 块直接 `return ResponseEntity.ok("错误：... - " + e.getMessage())`。异常消息可能包含表名、库名甚至连接串，设计文档防御 #32 明确要求"异常信息不泄露"但没做。

### 23. `AuditLogger` 全部功能只是 SLF4J Marker

定义了一个 `AUDIT` marker，但 **没有任何地方调用 `auditLogger.log(...)`**。整个审计链路是空壳。

### 24. RateLimitInterceptor 不存在

设计文档防御 #29 "IP 限流 100次/分钟/IP"，`SqlMonitorProperties` 里有 `RateLimitConfig`，但 `RateLimitInterceptor.java` 文件不存在。限流完全未实现。

### 25. `DataSourceContextHolder` 定义了但没有任何地方调用

`CONTEXT.set()` / `.get()` / `.clear()` 在整个代码库中零引用。设计它的初衷是"Controller 在请求入口 set，finally 中 clear"——但没一个 Controller 这么做。

---

## 📊 总结

| 级别 | 数量 | 核心问题 |
|---|---|---|
| 🔴 P0 - Bug | 5 | UPSERT 失效、哈希 bug、死代码认证、消息双序列化、无限重试 |
| 🟠 P1 - 架构缺陷 | 7 | 采集入口缺失、IM 内存泄漏、连接池泄漏、Jasypt 白配、Password 明文 |
| 🟡 P2 - 测试缺口 | 5 | 10 个类零测试、端点覆盖不足、假测试混入、集成测试伪装单测 |
| 🟢 P3 - 代码质量 | 8 | Lombok 不用、废代码、硬编码、审计空壳、限流缺失、异常泄露 |

**最需要立即修的 3 件事：**

1. **P0#2** — `CompensateScheduler` 的 hashCode bug，缓存重建完全失效
2. **P0#5** — RabbitMQ 消息双序列化，所有异步任务消息格式错误
3. **P0#1** — `diagnosis_record` 缺 UNIQUE 索引，重复写入

**最大的架构级缺口：**

- 采集层只完成了 20%（1/5 个入口）
- 资产保护措施 40 条里至少 4 条未实现：#24 重试分类、#29 IP 限流、#31 审计日志、#32 异常脱敏
- Python Agent（Step 9）和前端（Step 11）还没开始，全链路不通
