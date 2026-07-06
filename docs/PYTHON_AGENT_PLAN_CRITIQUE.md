# Python Agent 计划审查 — 最终版

> 审查对象：`docs/PYTHON_AGENT_PLAN.md`
> 已确认移除：多 Key 轮询、模型路由
> 2026-07-07

---

## 🔴 致命问题（1 个）

### 1. Per-session Memory 用了错误的 API

计划用 `ConversationBufferWindowMemory`。它在 AgentExecutor 创建时绑定，之后不能换 session_id。多 session 场景下所有诊断共享同一个 memory，互相污染。

只有 `RunnableWithMessageHistory` 支持每次 invoke 时根据 `config["session_id"]` 动态加载/保存历史。

**改法**：

```python
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_community.chat_message_histories import RedisChatMessageHistory

def get_session_history(session_id: str):
    return RedisChatMessageHistory(
        session_id=session_id, url=redis_url,
        key_prefix="diagnosis:memory:", ttl=3600
    )

agent = create_openai_tools_agent(llm, tools, prompt)
agent_with_memory = RunnableWithMessageHistory(
    agent, get_session_history,
    input_messages_key="input",
    history_messages_key="chat_history",
)

result = await agent_with_memory.ainvoke(
    {"input": task.enriched_prompt},
    config={"configurable": {"session_id": task.session_id}}
)
```

`ConversationBufferWindowMemory` 相关代码全部删掉。

---

## 🟠 严重问题（2 个）

### 2. Consumer 声明队列没绑定 Exchange

```python
high = await channel.declare_queue("diagnosis.task.high", durable=True)
await high.consume(handle)
```

只声明队列没有 `queue.bind()`。绑定是 Java `RabbitMqConfig` 建的——Python 先启动就收不到消息。

**改法**：

```python
exchange = await channel.declare_exchange("diagnosis.exchange", ExchangeType.TOPIC, durable=True)
await high.bind(exchange, routing_key="task.high")
await normal.bind(exchange, routing_key="task.normal")
```

### 3. `_instance_id` 全局变量——并发地雷

```python
# tools/definitions.py
_instance_id = None

# consumer.py
tools_mod._instance_id = task.instance_id
```

`prefetch=1` 掩盖了问题。未来并发消费 → instance_id 互相覆盖 → 工具调用打到错误的 MySQL 实例。

**改法**：换成 `contextvars`，协程安全的本地存储：

```python
# tools/definitions.py
import contextvars
_instance_id_ctx = contextvars.ContextVar("instance_id", default=None)

@tool
async def get_table_ddl(table_name: str) -> str:
    instance_id = _instance_id_ctx.get()
    return await _data_client.get(f"/{instance_id}/ddl", params={"table": table_name})

# consumer.py
_instance_id_ctx.set(task.instance_id)
```

---

## 🟡 中等问题（4 个）

### 4. 依赖漏了 `langchain_community`

计划说"不引入 `langchain_community`"，但 `RedisChatMessageHistory` 就在这个包里。要么补到 `requirements.txt`，要么手写一个 `BaseChatMessageHistory` 子类调 Redis（50 行，省一个重依赖）。

### 5. Redis 降级队列无人消费

Java 端 RMQ 不可达时把消息 RPUSH 到 `diagnosis:fallback:queue`。Python 端需要定时任务每 5 分钟把积压消息恢复到 RMQ。计划里完全没有。

### 6. 缺 `actual-rows` 工具

Java 有 14 个数据端点，Python 只实现了 13 个 `@tool`。`POST /{instanceId}/actual-rows` 缺失——这是对比"实际行数 vs EXPLAIN 估算"的关键诊断能力。

```python
@tool
async def check_actual_row_count(sql: str) -> str:
    """执行 COUNT(*) 获取实际行数，与 EXPLAIN rows 估算对比"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(f"/{_instance_id}/actual-rows", {"sql": sql})
```

### 7. 无优雅关闭

`lifespan` 只有 startup 没有 shutdown。RMQ 连接、Redis 连接池退出时不关闭，连接泄漏。

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    # startup
    yield
    # shutdown
    await rmq_conn.close()
    await redis.close()
```

---

## 🔵 设计文档遗漏（2 个）

### 8. `TokenBudgetExceeded` 未定义

`callbacks.py` 和 `consumer.py` 引用了这个异常类但没有定义。

### 9. `config.yaml` → Pydantic Settings 方式未交代

Pydantic Settings 原生读 env 不读 YAML。需要自定义 `YamlConfigSettingsSource` 或换方案，计划没写怎么加载。

---

## ✅ 已确认移除的组件

| 组件 | 原因 |
|------|------|
| `llm/key_pool.py` | 不轮询 Key，单个 Key 直接配 |
| `llm/dashscope_llm.py` 中的 `MultiKeyChatOpenAI` | 降级为普通 `ChatOpenAI` 实例 |
| `agent/callbacks.py` 中的 `ErrorRecoveryHandler` | 无 Key 切换，不需要 |
| 模型路由逻辑 | 统一用单一模型 |

---

## 📊 汇总

| 级别 | 数量 | 条目 |
|------|------|------|
| 🔴 致命 | 1 | Memory API 选错（`ConversationBufferWindowMemory` → `RunnableWithMessageHistory`） |
| 🟠 严重 | 2 | Queue 不绑 Exchange、全局 instance_id |
| 🟡 中等 | 4 | 缺 langchain_community、降级队列无人消费、缺 actual-rows 工具、无优雅关闭 |
| 🔵 遗漏 | 2 | TokenBudgetExceeded 未定义、YAML 加载未交代 |
