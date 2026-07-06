# 全链路集成测试 — 发现

> Java 网关 ✅ 启动成功（需 env var 绕过 Jasypt）
> Python Agent ❌ 启动失败（RabbitMQ 队列参数冲突）
> 2026-07-07

---

## 已通过的检查点

| 检查项 | 结果 |
|--------|------|
| MySQL `slow_sql_platform` | ✅ 在线 |
| Redis (auth) | ✅ PONG |
| RabbitMQ | ✅ 管理面板可达 |
| Java 网关编译+启动 | ✅ localhost:8080 |
| Java 数据 API | ✅ `/api/data/tc-dev-mysql/locks` → "当前无锁等待" |
| Python import | ✅ `from main import app` 成功 |
| Python 依赖 | ✅ 全部安装 |

---

## 🔴 阻塞：RabbitMQ 队列声明参数不一致

```
aiormq.exceptions.ChannelPreconditionFailed:
PRECONDITION_FAILED - inequivalent arg 'x-message-ttl' for queue
'diagnosis.task.high': received none but current is the value '1800000'
```

**根因**：Java `RabbitMqConfig` 声明队列时带了：
```java
args.put("x-dead-letter-exchange", DLX_EXCHANGE);
args.put("x-dead-letter-routing-key", "dlq.task");
args.put("x-message-ttl", TTL_TASK_MS);  // 1800000
```

Python `consumer.py:38` 声明同一队列时未传 arguments，RabbitMQ 拒绝重新声明参数不一致的队列。

**改法**（`consumer.py`）：

```python
QUEUE_ARGS = {
    "x-dead-letter-exchange": "diagnosis.dlx",
    "x-dead-letter-routing-key": "dlq.task",
    "x-message-ttl": 1800000,       # 30 min, 与 Java 一致
}

high = await channel.declare_queue(
    "diagnosis.task.high", durable=True, arguments=QUEUE_ARGS)
normal = await channel.declare_queue(
    "diagnosis.task.normal", durable=True, arguments=QUEUE_ARGS)
```

---

## 🟡 附带发现

### 1. `main.py` 缺少 uvicorn 入口

`CREDENTIALS.md` 写 `python main.py` 启动，但 `main.py` 没有 `if __name__ == "__main__": uvicorn.run(app)`。实际需要 `python -m uvicorn main:app --port 8000`。

### 2. `application.yml` 默认 `ENC(encrypted_password)` 导致本地起不来

必须设 `$env:MYSQL_PASSWORD` 和 `$env:INSTANCE_PASSWORD` 才能启动。`CREDENTIALS.md` 里密码写的是明文 `123456`，但 yml 默认值没有对应改成 `123456`。

### 3. `recover_fallback` 降级恢复任务的 `KEY` 重复定义

```python
# consumer.py:110
KEY = "diagnosis:fallback:queue"
```

Java 侧 `DiagnosisTaskProducer.java:26` 定义了 `FALLBACK_KEY = "diagnosis:fallback:queue"`。两个不同的组件用同一个 magic string——应该统一到一个常量文件或配置。

---

## 下一步

修完 `consumer.py` 的 QUEUE_ARGS 后，重新跑：

```powershell
# 终端 1 — Java
$env:MYSQL_PASSWORD="123456"; $env:INSTANCE_PASSWORD="123456"
cd D:\slow-sql-analyzer-v5\slow-sql-gateway; mvn spring-boot:run

# 终端 2 — Python
$env:DEEPSEEK_API_KEY="sk-a8626034a5714edca3a23ca63fc462f82"
cd D:\slow-sql-analyzer-v5\slow-sql-agent; python -m uvicorn main:app --port 8000

# 终端 3 — E2E 测试
curl -X POST http://localhost:8080/api/sql/analyze \
  -H "Content-Type: application/json" \
  -d '{"instanceId":"tc-dev-mysql","sql":"SELECT * FROM _slow_test WHERE status=''done''","projectCode":"test"}'
```
