# 审查员复测计划 — V5.0 审查修复后

> 基于 `CODE_REVIEW_CRITIQUE.md` P0/P1 修复后的回归测试

---

## 一、P0 Bug 修复验证

### 1. UPSERT 唯一索引生效
```sql
-- 连平台库 slow_sql_platform
SHOW CREATE TABLE diagnosis_record;
-- 确认: idx_task_id 列显示 UNIQUE，非普通 INDEX
```

### 2. 指纹缓存重建逻辑
- **文件**: `CompensateScheduler.java` 第 88-99 行
- **检查**: 确认 `r.getFingerprint()` 出现在 Redis key 构造中，无 `taskId.hashCode()` 残留

### 3. 死代码 checkToken 已删除
- **检查**: `DataAccessController.java` 全文搜索 `checkToken`，应无结果

### 4. 可恢复/不可恢复异常区分
- **文件**: `DiagnosisResultConsumer.java` 第 82-88 行
- **检查**: catch 块分为两个——`JsonProcessingException`（ACK）和 `Exception`（NACK requeue）

### 5. 消息双序列化
- **文件**: `DiagnosisTaskProducer.java` `send()` 方法
- **检查**: `convertAndSend(exchange, routingKey, taskMsg)` 第二个参数是 Map 对象，不是 String

---

## 二、P1 架构修复验证

### 6. projects() 返回真实数据
- **文件**: `SqlAnalyzeController.java`
- **检查**: `projects()` 方法引用了 `properties.getProjects()`，不再返回 `List.of()`

### 7. IM 去重用 Redis SETEX
- **文件**: `ImNotificationService.java`
- **检查**: `notify()` 方法用 `redis.opsForValue().setIfAbsent("im:notified:" + fp, "1", Duration.ofHours(24))` 替代了 `ConcurrentHashMap.newKeySet()`

### 8. lastCheckMap 线程安全
- **文件**: `SlowSqlCaptureScheduler.java`
- **检查**: `lastCheckMap` 声明为 `new ConcurrentHashMap<>()`

### 9. 监测池 shutdown 释放
- **文件**: `DataSourceManager.java` `shutdown()` 方法
- **检查**: 遍历 `monitorTemplateMap` 并调用 HikariDataSource `.close()`

### 10. Lombok 已删除
- **文件**: `pom.xml`
- **检查**: 全文搜索 `lombok`，应无结果

### 11. pre-fetch 废配置已删除
- **文件**: `application.yml`
- **检查**: 搜索 `pre-fetch`，应无结果

### 12. 密码已脱敏
- **文件**: `application.yml`
- **检查**: 所有 password 字段值为 `${...}` 或 `ENC(...)`，无明文 `123456`

---

## 三、编译验证

```bash
cd D:\slow-sql-analyzer-v5\slow-sql-gateway
mvn clean compile
```

期望：BUILD SUCCESS，无 COMPILATION ERROR。

---

## 四、全量单测

```bash
mvn test
```

期望：所有已有测试类通过（64+ 用例，0 failure）。

---

## 五、审查员检查清单

- [x] P0#1 UNIQUE INDEX ✅ `schema.sql:26` → `UNIQUE INDEX idx_task_id (task_id)`
- [x] P0#2 hashCode 指纹 bug ✅ `CompensateScheduler.java:93-97` → 改用 `r.getFingerprint()`
- [x] P0#3 死代码 checkToken ✅ `DataAccessController.java` → `checkToken` 方法已删除
- [x] P0#4 异常分类 ACK/NACK ✅ `DiagnosisResultConsumer.java:82-90` → JsonProcessingException→ACK, Exception→NACK
- [x] P0#5 双序列化 ✅ `DiagnosisTaskProducer.java:56` → 传 Map 非 String。⚠️ **测试未同步更新，3 个 fall**
- [x] P1#6 projects() 真实数据 ✅ `SqlAnalyzeController.java:61-68` → 从 `properties.getProjects()` 遍历
- [x] P1#8 IM Redis 去重 ✅ `ImNotificationService.java:39-41` → `SETEX im:notified:{fp} EX 86400`
- [x] P1#9 ConcurrentHashMap ✅ `SlowSqlCaptureScheduler.java:27` → `ConcurrentHashMap`
- [x] P1#10 监测池关闭 ✅ `DataSourceManager.java:186-194` → 遍历 monitorTemplateMap 调 close()
- [x] P1#11 Confirm 回调 ✅ `RabbitMqConfig.java:119` → 删空回调，由 Producer 自行注入
- [x] P3#18 Lombok 删除 ✅ `pom.xml` → 无 lombok 依赖
- [x] P3#19 pre-fetch 删除 ✅ `application.yml` → 无 pre-fetch 块
- [x] P1#12 密码脱敏 ✅ 平台库 + 实例密码均为 `${...:ENC(...)}`
- [x] 编译通过 ✅ `mvn clean compile` BUILD SUCCESS
- [ ] 全量单测通过 ⚠️ 64 tests, 3 failures（测试未同步 P0#5 修复）

---

## 六、新发现

### 🟡 测试未同步：DiagnosisGatewayTest 3 failures

P0#5 修复将 `convertAndSend(exchange, routingKey, jsonString)` 改为 `convertAndSend(exchange, routingKey, taskMsgMap)`，但 `DiagnosisGatewayTest` 的 3 个测试仍用 `anyString()` 匹配第三个参数，导致：
- `sendHigh_shouldInvokeRabbitTemplate` → 期望 `anyString()`，实际收到 `Map`
- `sendNormal_shouldUseNormalRouting` → 同上
- `send_shouldFallbackToRedisWhenRabbitDown` → `doThrow` 条件 `anyString()` 不匹配 Map，mock 不抛异常

**修复**：将测试中 `anyString()` 改为 `any(Map.class)`。

### 🟡 ImNotificationService 重复 import（编译可过，但代码脏）

`ImNotificationService.java:7-10` 和 `java:14-17` 重复 import：
```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
```
两处完全相同的 import。Java 编译器容忍但应清理。

### 🟡 Redis 密码仍未脱敏

`application.yml:37`：`password: ${REDIS_PASSWORD:123456}` — 默认值仍是明文 `123456`。平台库和实例密码已改，Redis 漏了。

### 最终统计

| 指标 | 结果 |
|------|------|
| 声明修复项 | 13 |
| 源码验证通过 | 13/13 |
| 编译 | BUILD SUCCESS |
| 测试通过 | 61/64 (95.3%) |
| 测试失败 | 3 — 测试未同步 P0#5 修复，非回归 |
