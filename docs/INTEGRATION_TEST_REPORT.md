# 全链路集成测试报告

> 2026-07-07 | Java 网关 + Python Agent + RabbitMQ + Redis + MySQL

---

## 测试结果总览

| 类别 | 通过/总数 | 关键失败 |
|------|-----------|----------|
| 基础设施连通 | 3/3 | — |
| 安全守卫 & 参数校验 | 8/8 | — |
| 数据 API | 4/4 | — |
| 异步诊断全链路 | 0/1 | 🔴 Redis RESP3 协议不兼容 |

---

## 详细结果

### 基础设施 ✅ 3/3

| # | 测试 | 结果 |
|---|------|------|
| 1 | MySQL `slow_sql_platform` 可达 | ✅ |
| 2 | Redis 认证 + PING | ✅ |
| 3 | RabbitMQ 管理面板可达 | ✅ |

### Java 数据 API ✅ 4/4

| # | 端点 | 结果 |
|---|------|------|
| 4 | `GET /ddl?table=_slow_test` | ✅ 返回 CREATE TABLE |
| 5 | `GET /locks` | ✅ "当前无锁等待" |
| 6 | `GET /bufferpool` | ✅ 命中率 94.5% |
| 7 | `GET /projects` | ✅ 返回 2 个真实项目 |

### 安全守卫 ✅ 5/5

| # | 输入 | 结果 |
|---|------|------|
| 8 | 无 Token 访问数据 API | ✅ 403 |
| 9 | DELETE SQL | ✅ SECURITY_BLOCKED |
| 10 | 多语句注入 | ✅ SECURITY_BLOCKED |
| 11 | 空 SQL | ✅ SQL_BLANK |
| 12 | 空 instanceId | ✅ INSTANCE_BLANK |

### 诊断参数校验 ✅ 3/3

| # | 输入 | 结果 |
|---|------|------|
| 13 | 空请求体 | ✅ REQUEST_BLANK |
| 14 | 正常请求 | ✅ 202 + taskId + pending |
| 15 | 无效 instanceId | ✅ DATABASE_UNAVAILABLE |

### 异步诊断全链路 ❌ 1/1

| # | 步骤 | 结果 |
|---|------|------|
| 16a | POST /analyze | ✅ 202 accepted, taskId 已生成 |
| 16b | Java→RMQ 投递 | ✅ task.high 队列收到 |
| 16c | Python 消费 | ✅ 消费者在线 (consumers=1) |
| 16d | Redis 状态写入 | ❌ HELLO 命令失败 |
| 16e | LLM 调用 | ❌ 未到达 |
| 16f | 结果写回 + done.queue | ❌ 未到达 |
| 16g | 轮询返回 completed | ❌ 120s 始终 pending |

---

## 🔴 根因分析

**Python `redis-py` 默认使用 RESP3 协议，Redis 5.0.14 不支持 `HELLO` 命令。**

```
Redis 版本: 5.0.14.1
RESP3/HELLO: ❌ 需要 Redis ≥ 6.0

Python main.py line 30:
  redis = Redis.from_url("redis://:123456@localhost:6379/2", decode_responses=True)
  → redis-py 默认尝试 RESP3 协商 → HELLO 命令 → Redis 5.0 不认识 → ResponseError
```

所有 Redis 操作（`hset status=running`、`setex result`、`exists task`）静默失败 → task 永远 pending → 消费者 `except Exception` 吞掉异常后 ACK 消息 → 消息丢失。

**改法**：`main.py` 一行：

```python
redis = Redis.from_url(settings.redis_url, decode_responses=True, protocol=2)
```

---

## 附带发现

| # | 问题 | 详细 |
|---|------|------|
| A | `recover_fallback` 每 5 分钟刷爆日志 | Redis HELLO 错误 × N 次，应降级为 DEBUG |
| B | `config.py` Redis URL 不兼容 RESP3 | 加 `protocol=2` 或 URL 加 `?protocol=2` |
| C | Java 网关没有 /health 端点 | curl localhost:8080/health → 404 |
| D | CREDENTIALS.md 启动命令 `python main.py` 无效 | main.py 须用 uvicorn 启动（已在 b52e2c6 修复）|

---

## 测试矩阵覆盖

| 维度 | 已测 | 未测 |
|------|------|------|
| 安全（SQL 注入/多语句/空参数） | ✅ 5 例 | |
| 数据 API 认证 | ✅ Internal-Token | |
| 数据 API 功能性 | ✅ DDL/Locks/BufferPool | EXPLAIN/stats/variable 等 10 个端点 |
| 项目列表 | ✅ 真实数据 | |
| 异步链路 | ❌ Redis 阻塞 | 全链路、指纹缓存、幂等消费 |
| 边界条件 | ✅ 空值/null | 超长 SQL、特殊字符表名 |
| 采集调度 | — | 完全未测 |
