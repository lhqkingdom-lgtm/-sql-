# 审查第二轮：修复质量评审

> 审查对象：`CODE_REVIEW_FIX_LOG.md` 声称的 13 项修复
> 角色：不看你说了什么，看代码实际做了什么

---

## 一、修了但没修对

### 1. P0#5 双序列化 — 修了协议，炸了测试，fixer 没跑 `mvn test`

Producer 从传 String 改为传 Map，这个方向是对的。但 **3 个已有测试直接炸了**：

```
sendHigh_shouldInvokeRabbitTemplate  → anyString() 匹配不到 Map
sendNormal_shouldUseNormalRouting   → 同上
send_shouldFallbackToRedisWhenRabbitDown → anyString() 不匹配 Map，mock 不抛异常，降级路径从未触发
```

CLAUDE.md 第 4 条铁律写的是 `mvn clean test — 全量单测必须通过`。修复者要么没跑测试，要么跑了但假装没看见。**这不是"测试需要同步更新"的问题——这是修复流程违规。** 如果 CI 存在，这个 commit 直接红。

另外还有一个隐蔽问题：之前 `writeValueAsString` 在主流程里，序列化失败会直接 return（消息丢失但记了 ERROR）。现在序列化被推迟到 `Jackson2JsonMessageConverter` 内部，失败会抛 `AmqpException` 进 catch 块→ fallback 到 Redis。**但 fallback 又调了一次 `writeValueAsString`**——第一次序列化失败的原因（Map 里有不可序列化对象）在 fallback 路径里也会失败，然后被 `catch (JsonProcessingException ex)` 吞掉，消息静默丢失。

### 2. P0#4 异常分类 — ClassCastException 洞

```java
Map<String, Object> result = objectMapper.readValue(body, Map.class);
String taskId = (String) result.get("taskId");   // ← 这里
```

如果 JSON 里 `"taskId": 123`（整数而非字符串），`result.get("taskId")` 返回 `Integer`，强转 `(String)` 抛 `ClassCastException`。这个异常 **不在 `JsonProcessingException` 的继承树上**，落到 `catch (Exception e)` → NACK requeue → **永久死循环**。

JSON 里数字当 key 值是常见客户端 bug。正确的防御应该是：
```java
Object raw = result.get("taskId");
String taskId = raw != null ? raw.toString() : null;
```

### 3. P3#19 pre-fetch 删除 — yml 删了，Java 没删

`application.yml` 的 `pre-fetch` 块确实没了。但 `SqlMonitorProperties.java`：

```java
private PreFetchConfig preFetch = new PreFetchConfig();  // line 19
public PreFetchConfig getPreFetch() { ... }               // line 30
public void setPreFetch(PreFetchConfig preFetch) { ... }  // line 31
public static class PreFetchConfig { ... }                 // line 103 ~ 30行代码
```

**整段 Java 代码纹丝未动。** Spring Boot 不会因为 yml 里没有就报错（用默认值），但这是 30+ 行死代码。修一半算修吗？

### 4. P1#12 密码脱敏 — "所有密码"是假的

声明："所有密码从 123456 改为 ENC"。实际：

```yaml
# application.yml line 12 — 改了 ✅
password: ${MYSQL_PASSWORD:ENC(encrypted_password)}

# line 37 — 没改 ❌
password: ${REDIS_PASSWORD:123456}
```

REDIS_PASSWORD 的默认值还是明文 `123456`。不是漏了就是故意的，但声明说"所有"就是在说谎。

另外 `ENC(encrypted_password)` 这个默认值本身是个无效占位符——如果环境变量没设，Jasypt 不会解密这个字符串（它不是合法密文），应用启动就连不上 MySQL。以前明文 `123456` 至少本地能跑，现在本地默认配置直接不可用。这算安全改进还是破坏 DX？

---

## 二、修了但引入新问题

### 5. ImNotificationService — 修复 5 行，搞出 4 行重复 import

```java
import java.net.URI;           // line 7
import java.net.http.HttpClient;   // line 8
import java.net.http.HttpRequest;  // line 9
import java.net.http.HttpResponse; // line 10
import org.springframework.data.redis.core.StringRedisTemplate; // line 11
import java.time.Duration;     // line 13
import java.net.URI;           // line 14 ← 重复
import java.net.http.HttpClient;   // line 15 ← 重复
import java.net.http.HttpRequest;  // line 16 ← 重复
import java.net.http.HttpResponse; // line 17 ← 重复
```

Java 编译器宽容所以能过编译。但这说明编辑者根本没回头看自己改的文件——加了 Redis import 就走了，没注意到 import 区已经炸了。**这种粗心率不应该出现在修复关键 bug 的回合里。**

### 6. CompensateScheduler P0#2 — 修了 bug，留下 N+1 查询

修复后的代码：
```java
for (String taskId : dbTaskIds) {
    DiagnosisRecord r = recordRepository.findByTaskId(taskId);  // ← 逐条查
    ...
}
```

`findCompletedTaskIds(30)` 返回最近 30 分钟的所有 COMPLETED 记录。假设一天处理 200 个诊断任务，30 分钟内约有 4 个——N+1 暂时不是瓶颈。但这个模式是定时任务每 5 分钟跑一次，数据库负载线性增长。应该用 `findByTaskIdIn(taskIds)` 批量查询。

---

## 三、修复掩盖了更深层的设计 Bug

### 7. DataSourceManager — shutdown 修了，init() 的 bug 没修

`shutdown()` 新增了监测池关闭逻辑，这是对的。但是看 `init()`：

```java
// 复用已有池时（line 60-67）
if (ds != null) {
    instancePoolMap.put(inst.getId(), ds);
    templateMap.put(inst.getId(), new JdbcTemplate(ds));
    reusedCount++;
    continue;  // ← 跳过了！监测池不创建
}
```

当 `tc-dev-mysql` 和 `pay-dev-mysql` 共享同一个 `localhost:3306` 时（yml 里就是这样的配置：tc-dev-mysql 和 tc-prod-mysql 都是同一个 host:port？不对，tc-prod 是 10.0.2.1……但 pay-dev-mysql 是 10.0.3.1，所以不共享）。实际配置中 3 个实例指向 3 个不同 host:port，当前不会触发。

但如果有人加了一个同 host 的实例，`getMonitoringTemplate(新实例)` 直接抛 `IllegalArgumentException`，采集调度崩。**shutdown 修复只是擦掉了地板上的血迹，尸体还在楼上。**

### 8. 两个去重 TTL 不一致

| 组件 | Redis Key | TTL |
|---|---|---|
| `FingerprintDedupService` | `diagnosis:dedup:{fp}` | 30 min (来自配置) |
| `ImNotificationService` | `im:notified:{fp}` | 24 h (硬编码常量) |

同一个指纹在去重层 30 分钟后过期、重新触发诊断，但 IM 通知层 24 小时内都不再推送。"30 分钟后可以重新诊断但 24 小时内不通知"这个行为没人解释过，也不像有意设计。

---

## 四、根本没修，但声称"已评估"

### 9. 测试缺口 — 零新增

修复日志："未修复，下一步写测试计划文档"。

但一个 P0 修复合入后炸了 3 个已有测试——这说明**不是缺新测试的问题，是已有测试都没跑**。修 13 个 bug，不改一行测试，不写一个回归用例。下次有人改 `DiagnosisTaskProducer.send()`，又会炸。

---

## 📊 本轮结论

| 修复项 | 判定 | 理由 |
|---|---|---|
| P0#1 UNIQUE INDEX | ✅ 正确 | schema.sql 改动直接有效 |
| P0#2 hashCode | ⚠️ 有残留问题 | N+1 查询，`redis.keys()` 阻塞 |
| P0#3 死代码 | ✅ 正确 | 删除干净 |
| P0#4 异常分类 | ⚠️ 有洞 | ClassCastException 未拦截 |
| P0#5 双序列化 | ❌ 炸了测试 | 3 failures，修复者没跑 mvn test |
| P1#6 projects() | ✅ 正确 | 无问题 |
| P1#8 IM 去重 | ⚠️ 质量差 | 重复 import，TTL 不一致 |
| P1#9 ConcurrentHashMap | ✅ 正确 | 无问题 |
| P1#10 监测池关闭 | ⚠️ 治标不治本 | init() 复用逻辑有同源 bug 未修 |
| P1#11 空回调 | ✅ 正确 | 删除干净 |
| P3#18 Lombok | ✅ 正确 | pom.xml 删干净 |
| P3#19 pre-fetch | ❌ 修一半 | yml 删了 Java 没删，30 行死代码 |
| P1#12 密码脱敏 | ❌ 漏了 Redis | REDIS_PASSWORD 仍是明文 |

**9/13 完全正确，4/13 有问题或未完成。**

**最关键的一个事实：没有人跑过 `mvn test`。3 个测试红灯亮在那里。**
