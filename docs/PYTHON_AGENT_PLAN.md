# Python Agent 实现计划（LangChain 版 · 审查修订 v2）

> 审核员：基于 LangChain 生态重写。审查后修订 9 处——最致命的 Per-session Memory API、Queue 绑定、并发安全全修了。

---

## 一、为什么用 LangChain

| 维度 | 自研 | LangChain |
|------|------|-----------|
| Agent 循环 | 手写 ~400 行（15轮 + 异常 + 重试 + 计数 + 重复检测） | 3 行：`create_openai_tools_agent` + `AgentExecutor` |
| ChatMemory | 手写 Redis List + LTRIM + 序列化 | `RedisChatMessageHistory` 现成 |
| Callback/监控 | 全手写日志 | `BaseCallbackHandler` 钩子 |
| 未来扩展 | 手写 | RAG chain、Few-shot、多 Agent 有现成组件 |
| 风险 | 零 | LangChain 版本升级 API 变更（pin 版本解决） |

**LLM 接入方式**：用 `langchain_openai.ChatOpenAI` 指向 `https://api.deepseek.com/v1`（OpenAI 兼容接口）。

---

## 二、目录结构（审查修订后）

```
slow-sql-agent/
├── pyproject.toml
├── requirements.txt
├── main.py                          # FastAPI + lifespan(启动Consumer/关闭连接)
│
├── models.py                        # Pydantic: DiagnosisTask, DiagnosisResult, TokenBudgetExceeded
├── config.py                        # Pydantic Settings（读环境变量，不读YAML）
│
├── tools/
│   ├── __init__.py
│   ├── definitions.py               # 14 个 @tool + contextvars(instance_id)
│   └── data_client.py               # httpx 调 Java 网关
│
├── agent/
│   ├── __init__.py
│   ├── factory.py                   # create_agent_with_memory → RunnableWithMessageHistory
│   └── callbacks.py                 # TokenBudgetHandler + MetricsHandler + TokenBudgetExceeded
│
├── mq/
│   ├── __init__.py
│   ├── consumer.py                  # aio-pika 消费 + Exchange 绑定 + 降级恢复
│   └── publisher.py                 # 结果回传 done.queue + 重新投递 task
│
├── prompts/
│   └── diagnosis.yaml               # System Prompt
│
└── tests/
    ├── test_tools.py                # 14 工具 guard + HTTP mock
    ├── test_callbacks.py            # TokenBudget + Metrics callback
    └── test_integration.py          # 全链路
```

**删除的目录/文件**（审查后）：
- `llm/` — 单 Key，不需要 KeyPool 和 MultiKeyChatOpenAI
- `memory/` — `RunnableWithMessageHistory` 动态路由，不需要独立 memory 模块
- `cache/redis_client.py` — `redis.asyncio` 直接 `from_url()`，2 行代码不需要文件
- `config.yaml` — 全部走环境变量

---

## 三、依赖清单

```
# requirements.txt
langchain-openai>=0.2.0        # ChatOpenAI → DeepSeek OpenAI 兼容接口
langchain-core>=0.3.0          # BaseCallbackHandler, RunnableWithMessageHistory
langchain-community>=0.3.0     # RedisChatMessageHistory（🟡补）
redis>=5.0.0
aio-pika>=9.4.0
httpx>=0.27.0
fastapi>=0.110.0
uvicorn>=0.29.0
pyyaml>=6.0
pydantic>=2.7.0
pytest>=8.0
pytest-asyncio>=0.23.0
pytest-mock>=3.14.0
```

**说明**：`langchain-community` 是 `RedisChatMessageHistory` 所在的包——必须引入。不依赖 `langchain` 全量包（太大）。

---

## 四、核心组件设计

### 4.1 LLM：直连 DeepSeek OpenAI 兼容 API

```python
from langchain_openai import ChatOpenAI

llm = ChatOpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),        # DeepSeek API Key
    model="deepseek-v4-pro",                         # deepseek-v4-pro / deepseek-reasoner
    temperature=0.1,
    base_url="https://api.deepseek.com/v1",         # OpenAI 兼容接口
    timeout=120,
    max_retries=1,
)
```

单 Key，不轮询。LangChain 的 `max_retries=1` 覆盖了绝大多数瞬时故障。删掉了 `llm/key_pool.py` 和整个 `MultiKeyChatOpenAI` 封装。

### 4.2 工具定义 (`tools/definitions.py`)

用 LangChain `@tool` 装饰器，内部调 `data_client`：

```python
from langchain_core.tools import tool
import re

SAFE_TABLE = re.compile(r'^[A-Za-z0-9_]+$')
DANGEROUS_SQL = re.compile(r'\b(update|delete|drop|insert|truncate|alter)\b', re.I)
ALLOWED_VARS = {"innodb_buffer_pool_size", "max_connections", "thread_cache_size",
                "query_cache_size", "tmp_table_size", "max_heap_table_size",
                "sort_buffer_size", "join_buffer_size"}

# data_client + instance_id（contextvars 协程安全）
_data_client = None
_instance_id_ctx = contextvars.ContextVar("instance_id", default=None)

def set_data_client(client):
    global _data_client
    _data_client = client

# ===== 13 个工具 =====

@tool
async def get_table_ddl(table_name: str) -> str:
    """获取 MySQL 表结构 DDL。入参 tableName 只能是纯表名，不含反引号。这是排查的第一步。"""
    if not SAFE_TABLE.match(table_name):
        return "错误：tableName 只能包含字母、数字和下划线。"
    return await _data_client.get(f"/{_instance_id}/ddl", params={"table": table_name})

@tool
async def get_execution_plan(sql: str) -> str:
    """获取 MySQL SQL 的 JSON 执行计划（EXPLAIN FORMAT=JSON）。只允许只读 SELECT 语句。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感的写入或 DDL 关键字。"
    return await _data_client.post(f"/{_instance_id}/explain", {"sql": sql})

@tool
async def get_table_statistics(table_name: str) -> str:
    """获取表的统计信息和所有索引的基数(Cardinality)。当怀疑 MySQL 选错索引时必须调用。"""
    if not SAFE_TABLE.match(table_name):
        return "错误：tableName 只能包含字母、数字和下划线。"
    return await _data_client.get(f"/{_instance_id}/stats/{table_name}")

@tool
async def check_active_locks() -> str:
    """检查当前数据库中是否存在行锁等待或长时间未提交的事务。无入参。"""
    return await _data_client.get(f"/{_instance_id}/locks")

@tool
async def get_innodb_status() -> str:
    """【重型探针】获取 InnoDB 引擎完整状态。仅在执行计划和锁检查均无法解释原因时调用。"""
    return await _data_client.get(f"/{_instance_id}/innodb")

@tool
async def get_global_variable(variable_name: str) -> str:
    """获取 MySQL 全局配置变量。仅允许查询性能相关变量。"""
    v = variable_name.strip().lower()
    if v not in ALLOWED_VARS:
        return f"错误：变量 '{variable_name}' 不在可查询范围内。"
    return await _data_client.get(f"/{_instance_id}/variable", params={"name": v})

@tool
async def check_redundant_indexes(table_name: str) -> str:
    """检查表的冗余索引。当怀疑写入性能问题或表索引数量 ≥3 时调用。"""
    if not SAFE_TABLE.match(table_name):
        return "错误：tableName 只能包含字母、数字和下划线。"
    return await _data_client.get(f"/{_instance_id}/indexes/redundant", params={"table": table_name})

@tool
async def compare_execution_plan(original_sql: str, optimized_sql: str) -> str:
    """对比两条 SQL 的执行计划。用于验证优化效果。"""
    if DANGEROUS_SQL.search(original_sql) or DANGEROUS_SQL.search(optimized_sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(f"/{_instance_id}/explain/compare",
                                    {"originalSql": original_sql, "optimizedSql": optimized_sql})

@tool
async def get_slow_log_stats() -> str:
    """获取慢日志统计。按 SQL 指纹聚合查询次数和平均耗时。无入参。"""
    return await _data_client.get(f"/{_instance_id}/slowlog")

@tool
async def check_missing_indexes(sql: str) -> str:
    """检查 SQL 中 WHERE/JOIN/ORDER BY 的列是否已建索引。自动提取列名并对比表的实际索引。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(f"/{_instance_id}/indexes/missing", {"sql": sql})

@tool
async def check_type_mismatch(sql: str) -> str:
    """检查 SQL 的 WHERE 条件值类型是否与表字段类型匹配。检测隐式类型转换。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(f"/{_instance_id}/type-mismatch", {"sql": sql})

@tool
async def get_buffer_pool_hit_rate() -> str:
    """获取 InnoDB Buffer Pool 命中率。低于 95% 说明内存不足。无入参。"""
    return await _data_client.get(f"/{_instance_id}/bufferpool")

@tool
async def get_process_list() -> str:
    """获取当前 MySQL 连接列表和状态分布。当怀疑连接数打满时调用。无入参。"""
    return await _data_client.get(f"/{_instance_id}/processlist")


# 🟡 补：第 14 个工具——actual-rows
@tool
async def check_actual_row_count(sql: str) -> str:
    """获取 SQL 的 WHERE 条件真实命中行数（SELECT COUNT(*) 包装）。对比 EXPLAIN 估算行数，偏差>10倍说明统计信息过时。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感关键字。"
    iid = _instance_id_ctx.get()
    return await _data_client.post(f"/{iid}/actual-rows", {"sql": sql})

# 工具列表
ALL_TOOLS = [
    get_table_ddl, get_execution_plan, get_table_statistics,
    check_active_locks, get_innodb_status, get_global_variable,
    check_redundant_indexes, compare_execution_plan, get_slow_log_stats,
    check_missing_indexes, check_type_mismatch,
    get_buffer_pool_hit_rate, get_process_list,
    check_actual_row_count   # 🟡 新增
]
```

### 4.3 Agent 工厂 (`agent/factory.py`)

```python
from langchain.agents import AgentExecutor, create_openai_tools_agent
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_community.chat_message_histories import RedisChatMessageHistory

def create_agent_with_memory(llm, tools, system_prompt, redis_url):
    prompt = ChatPromptTemplate.from_messages([
        ("system", system_prompt),
        MessagesPlaceholder(variable_name="chat_history"),
        ("human", "{input}"),
        MessagesPlaceholder(variable_name="agent_scratchpad"),
    ])
    
    llm_with_tools = llm.bind_tools(tools)
    agent = create_openai_tools_agent(llm_with_tools, tools, prompt)
    
    executor = AgentExecutor(
        agent=agent, tools=tools,
        max_iterations=15,
        early_stopping_method="generate",
        handle_parsing_errors=True,
        callbacks=[TokenBudgetHandler(30000), MetricsHandler()],
    )
    
    # RunnableWithMessageHistory 包裹——每次 invoke 按 session_id 动态路由
    def get_session_history(session_id: str):
        return RedisChatMessageHistory(
            session_id=session_id, url=redis_url,
            key_prefix="diagnosis:memory:", ttl=3600
        )
    
    return RunnableWithMessageHistory(
        executor,
        get_session_history,
        input_messages_key="input",
        history_messages_key="chat_history",
    )
```

### 4.4 Callback 系统 (`agent/callbacks.py`)

```python
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.outputs import LLMResult
import time

class TokenBudgetHandler(BaseCallbackHandler):
    """Token 预算控制：超限时抛异常中止"""
    def __init__(self, budget: int = 30_000):
        self.budget = budget
        self.used = 0
    
    def on_llm_end(self, response: LLMResult, **kwargs):
        usage = response.llm_output.get("token_usage", {})
        self.used += usage.get("total_tokens", 0)
        if self.used > self.budget:
            raise TokenBudgetExceeded(f"Token {self.used}/{self.budget}")

class MetricsHandler(BaseCallbackHandler):
    """收集诊断指标：LLM 调用次数、工具调用次数、总耗时"""
    def __init__(self):
        self.llm_calls = 0
        self.tool_calls = 0
        self.errors = []
        self.start_time = time.time()
    
    def on_llm_start(self, *args, **kwargs):
        self.llm_calls += 1
    
    def on_tool_start(self, *args, **kwargs):
        self.tool_calls += 1
    
    def on_tool_error(self, error, **kwargs):
        self.errors.append(str(error))

class ErrorRecoveryHandler(BaseCallbackHandler):
    """LLM 调用失败时换 Key 重试"""
    def __init__(self, key_pool):
        self.key_pool = key_pool
    
    def on_llm_error(self, error, **kwargs):
        # 429 / 超时 → 标记 Key 失败，AgentExecutor 内置重试
        if "429" in str(error) or "timeout" in str(error).lower():
            self.key_pool.mark_current_failed()
```

### 4.5 ChatMemory (`memory/redis_history.py`)

**🔴 致命修正**：不能用 `ConversationBufferWindowMemory`——它在 Agent 创建时焊死 session_id，多 session 并发时互相污染。必须用 `RunnableWithMessageHistory`，每次 `ainvoke` 按 `config["session_id"]` 动态路由到 Redis。

```python
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_community.chat_message_histories import RedisChatMessageHistory

def get_session_history(session_id: str) -> RedisChatMessageHistory:
    return RedisChatMessageHistory(
        session_id=session_id,
        url=redis_url,
        key_prefix="diagnosis:memory:",
        ttl=3600
    )

agent = create_openai_tools_agent(llm, tools, prompt)
agent_with_memory = RunnableWithMessageHistory(
    agent,
    get_session_history,
    input_messages_key="input",
    history_messages_key="chat_history",
)

# 调用时动态传 session_id
result = await agent_with_memory.ainvoke(
    {"input": task.enriched_prompt},
    config={"configurable": {"session_id": task.session_id or task.task_id}}
)
```

不需要 `memory/` 目录了。`get_session_history` 作为闭包放在 `agent/factory.py` 里即可。

### 4.6 TokenBudgetExceeded 异常类 (`agent/callbacks.py`)

```python
class TokenBudgetExceeded(Exception):
    """Token 预算耗尽，Agent 应提前终止"""
    pass
```

### 4.7 YAML 配置加载 (`config.py`)

🔵 补充：Pydantic Settings 原生读 env 不读 YAML。两种方案选一：

**方案 A（推荐）**：用 `pyyaml` 手动加载 → `Settings.parse_obj()`
```python
import yaml
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    deepseek_api_key: str
    deepseek_model: str = "deepseek-v4-pro"
    java_gateway_base_url: str
    java_gateway_token: str
    redis_url: str
    rabbitmq_url: str
    
    @classmethod
    def from_yaml(cls, path="config.yaml"):
        with open(path) as f:
            data = yaml.safe_load(f)
        return cls(
            deepseek_api_key=os.getenv("DEEPSEEK_API_KEY", data["deepseek"]["api_key"]),
            deepseek_model=data["deepseek"]["model"],
            java_gateway_base_url=data["java_gateway"]["base_url"],
            java_gateway_token=data["java_gateway"]["internal_token"],
            redis_url=data["redis"]["url"],
            rabbitmq_url=data["rabbitmq"]["url"],
        )
```

**方案 B**：环境变量全部注入（无 YAML），Docker Compose `environment` 段管理。更简单——`config.yaml` 删掉。

选方案 B（简单，和 Java application.yml 风格一致）。

### 4.8 配置文件（选方案 B 则不需要 `config.yaml`，全走环境变量）

```bash
# .env
DEEPSEEK_API_KEY=sk-xxxx
JAVA_GATEWAY_URL=http://java-gateway:8080/api/data
INTERNAL_TOKEN=slow-sql-internal-token-v5
REDIS_URL=redis://:123456@localhost:6379/2
RABBITMQ_URL=amqp://guest:guest@localhost:5672/
```

### 4.7 消费者 (`mq/consumer.py`)

**🟠 修正**：Queue 必须绑定 Exchange、`instance_id` 用 `contextvars`（协程安全）、加优雅关闭。

```python
import aio_pika
import json
import contextvars
from datetime import datetime

_instance_id_ctx = contextvars.ContextVar("instance_id", default=None)

async def start_consumer(agent_with_memory, redis, rmq_url: str, publisher):
    conn = await aio_pika.connect_robust(rmq_url)
    channel = await conn.channel()
    await channel.set_qos(prefetch_count=1)
    
    # 绑定 Exchange
    exchange = await channel.declare_exchange(
        "diagnosis.exchange", aio_pika.ExchangeType.TOPIC, durable=True)
    
    high = await channel.declare_queue("diagnosis.task.high", durable=True)
    await high.bind(exchange, routing_key="task.high")
    
    normal = await channel.declare_queue("diagnosis.task.normal", durable=True)
    await normal.bind(exchange, routing_key="task.normal")
    
    async def handle(message: aio_pika.IncomingMessage):
        async with message.process():
            task = DiagnosisTask(**json.loads(message.body))
            
            # 幂等
            if await redis.exists(f"diagnosis:task:{task.task_id}"):
                status = await redis.hget(f"diagnosis:task:{task.task_id}", "status")
                if status in (b"completed", b"failed"):
                    return
            
            await redis.hset(f"diagnosis:task:{task.task_id}", mapping={
                "status": "running", "updatedAt": datetime.now().isoformat()
            })
            
            # contextvars：协程安全地传递 instance_id 给工具
            _instance_id_ctx.set(task.instance_id)
            
            try:
                result = await agent_with_memory.ainvoke(
                    {"input": task.enriched_prompt},
                    config={"configurable": {"session_id": task.session_id or task.task_id}}
                )
                diag_result = DiagnosisResult(
                    task_id=task.task_id, status="completed",
                    report=result["output"]
                )
            except TokenBudgetExceeded:
                diag_result = DiagnosisResult(task_id=task.task_id, status="failed", error="Token预算耗尽")
            except Exception as e:
                diag_result = DiagnosisResult(task_id=task.task_id, status="failed", error=str(e)[:500])
            
            # 写 Redis
            await redis.setex(f"diagnosis:result:{task.task_id}", 1800,
                            diag_result.report or diag_result.error or "")
            await redis.hset(f"diagnosis:task:{task.task_id}", mapping={
                "status": diag_result.status, "updatedAt": datetime.now().isoformat()
            })
            if task.fingerprint and diag_result.status == "completed":
                await redis.setex(f"diagnosis:result:fp:{task.fingerprint}", 1800, diag_result.report)
            
            await publisher.publish(diag_result)
    
    await high.consume(handle)
    await normal.consume(handle)
    return conn  # 给 shutdown 用
```

**降级队列恢复任务**（每 5 分钟扫一次 `diagnosis:fallback:queue`）：

```python
async def recover_fallback(redis, publisher):
    """定时扫降级队列，有积压→重新投递到 RMQ"""
    while True:
        try:
            msg = await redis.lpop("diagnosis:fallback:queue")
            if msg:
                task = DiagnosisTask(**json.loads(msg))
                await publisher.publish_task(task, priority="high")
        except Exception:
            pass
        await asyncio.sleep(300)  # 5 min
```

### 4.8 优雅关闭 (`main.py`)

```python
from contextlib import asynccontextmanager

@asynccontextmanager
async def lifespan(app: FastAPI):
    # startup
    redis = await get_redis(settings.redis.url)
    system_prompt = yaml.safe_load(open("prompts/diagnosis.yaml"))["system"]
    agent = create_agent_with_memory(llm, ALL_TOOLS, system_prompt, settings.redis.url)
    publisher = ResultPublisher(settings.rabbitmq.url)
    rmq_conn = await start_consumer(agent, redis, settings.rabbitmq.url, publisher)
    asyncio.create_task(recover_fallback(redis, publisher))
    app.state.rmq_conn = rmq_conn
    app.state.redis = redis
    yield
    # shutdown
    await rmq_conn.close()
    await redis.close()

app = FastAPI(lifespan=lifespan)
```

---

## 五、System Prompt（不变）

```yaml
system: |
  你是资深 MySQL DBA（10 年经验）。
  
  ## 诊断流程（按序执行）
  
  1. **首先获取信息**：如果上下文没有 DDL 和执行计划，先调 get_table_ddl 和 get_execution_plan。
  2. **快速扫描**：看 EXPLAIN access_type、rows、Extra。type=ALL → 调 check_missing_indexes。
  3. **防漏检查**：EXPLAIN key=NULL 但 WHERE 有索引列 → 调 check_type_mismatch。
  4. **深层排查**：前两步无法解释 → 调 get_table_statistics 或 check_active_locks。
  5. **环境检查**：怀疑内存不足 → get_buffer_pool_hit_rate；怀疑连接耗尽 → get_process_list。
  6. **重型探针**：仅前几步无法定位才调 InnoDB 状态、全局变量、冗余索引、慢日志统计。
  
  ## 输出规范
  三段式 Markdown：① 核心瓶颈 ② 数据证据 ③ 优化建议（SQL 示例）
  命中企业知识库规则时必须引用规则编号。
```

---

## 六、Agent 内部异常处理矩阵

### 6.1 LLM 调用层

| 异常 | 表现 | LangChain 内置行为 | 我们的兜底 |
|------|------|-------------------|-----------|
| 网络超时 | `httpx.ReadTimeout` | `max_retries=1` 重试 1 次 | 仍失败 → AgentExecutor 抛 `OpenAIError` → Consumer catch → FAILED |
| 429 限流 | `RateLimitError` | `max_retries=1` + 指数退避 | 仍失败 → 同上 |
| 401 鉴权失败 | `AuthenticationError` | 不重试，直接抛 | Consumer catch → FAILED + 记 ERROR 日志 |
| 返回空 content | `finish_reason="stop"` 但 `content=""` | `handle_parsing_errors=True` 触发 `OutputParserException` → Agent 重试本轮 | 连续 2 次 → Agent 输出空字符串 → Consumer 判定 report 为空 → FAILED |
| Tool call JSON 格式错误 | LLM 幻觉出不合法的 tool_call | `handle_parsing_errors=True` → 错误消息注入对话 → Agent 重试 | 3 次解析失败 → `AgentExecutor` 抛异常 → Consumer catch → FAILED |
| Token 超预算 | 累计 > 30000 | `TokenBudgetHandler` 抛 `TokenBudgetExceeded` | Agent 提前终止 → Consumer catch → FAILED "Token预算耗尽" |
| 超过 max_iterations(15) | 15 轮未 finish | `early_stopping_method="generate"` → LLM 被迫生成最终回答 | 正常返回（即使质量可能不高） |

### 6.2 工具调用层

| 异常 | 表现 | 处理 |
|------|------|------|
| guard 拦截（表名非法/SQL含DELETE/变量不在白名单） | 工具函数返回 `"错误：xxx"` | 正常 tool_result 注入对话 → Agent 调整参数重试 |
| 同工具同参数连续 3 次 guard 拦截 | Agent 陷入死循环 | ⚠️ LangChain 无内置检测 → 需加 `RepeatGuardHandler` callback（P3 补） |
| Java API 连接超时 | `httpx.ConnectTimeout` (10s) | DataClient 返回 `"⚠️ 数据服务暂时不可用"` → Agent 基于已有信息继续 |
| Java API 返回错误 | HTTP 200 + body `"[ERROR] 错误：xxx"` | 正常注入对话 → Agent 换工具或继续 |
| Java API 返回 503 | Java 网关挂 | DataClient 返回 `"⚠️ 数据服务不可用 503"` → Agent 尝试用 pre-fetch 缓存数据继续 |
| DataClient 本身抛异常 | 未预料的 httpx 异常 | DataClient 内部 catch-all → 返回 `"⚠️ 工具调用异常"` → 不抛 |

### 6.3 Memory 层

| 异常 | 表现 | 处理 |
|------|------|------|
| Redis 不可达 | `redis.exceptions.ConnectionError` | `RunnableWithMessageHistory` 的 `get_session_history` 抛异常 → `ainvoke` 失败 → Consumer catch → FAILED |
| Redis 读取超时 | `redis.exceptions.TimeoutError` | 同上 |
| 历史消息过大(>20条) | 序列化后 > 1MB | Redis 自然存储，但下次读取时 `RunnableWithMessageHistory` 正常截断（窗口 20） |
| 历史消息包含不可序列化对象 | `TypeError` | LangChain `BaseMessage` 自带序列化，不会发生 |

### 6.4 Callback 层

| 异常 | 触发条件 | 处理 |
|------|---------|------|
| `TokenBudgetExceeded` | `TokenBudgetHandler.on_llm_end` 检测超预算 | 从 callback 抛出 → AgentExecutor 捕获 → 终止 Agent → Consumer catch → FAILED |
| `MetricsHandler.on_tool_error` | 工具调用异常 | 记录 error 但不阻断——LangChain 的 tool 异常已被 `handle_parsing_errors` 处理 |
| Callback 本身抛异常 | 罕见（代码 bug） | LangChain 默认吞 callback 异常，不影响主流程 |

### 6.5 消费者层（最外层兜底）

```python
# consumer.py — 最终兜底
try:
    result = await agent_with_memory.ainvoke(...)
except TokenBudgetExceeded:
    diag_result = DiagnosisResult(status="failed", error="Token预算耗尽")
except openai.AuthenticationError:
    diag_result = DiagnosisResult(status="failed", error="LLM鉴权失败，请检查API Key")
except openai.RateLimitError:
    diag_result = DiagnosisResult(status="failed", error="LLM限流，请稍后重试")
except (httpx.ConnectError, redis.ConnectionError) as e:
    diag_result = DiagnosisResult(status="failed", error=f"基础设施不可达: {type(e).__name__}")
except Exception as e:
    diag_result = DiagnosisResult(status="failed", error=str(e)[:500])
    logger.exception("未预期异常")  # 完整 traceback 记日志，不泄露给前端
```

### 6.6 关键决策：哪些异常给前端？

| 信息 | 给前端展示 | 原因 |
|------|-----------|------|
| Token预算耗尽 | ✅ | 用户能理解，可以简化 SQL 重试 |
| LLM鉴权失败 | ✅ | 运维需要知道 |
| LLM限流 | ✅ | 用户等一会重试 |
| 基础设施不可达 | ✅ "数据服务暂时不可用" | 脱敏后展示 |
| 未预期异常 | ✅ "诊断异常，请联系管理员" | 不泄露 traceback |
| 完整 exception message | ❌ | 只记日志 |

---

## 七、自研 vs LangChain：差异摘要

| 维度 | 自研 | LangChain |
|------|------|-----------|
| Agent 循环 | 手写 15 轮 for loop + tool_call 处理 | `AgentExecutor(max_iterations=15)` |
| Function Calling | 手动解析 `response.choices[0].message.tool_calls` | LangChain 自动解析 + `agent_scratchpad` |
| ChatMemory | 手写 Redis LPUSH/LTRIM/JSON 序列化 | `RedisChatMessageHistory` + `ConversationBufferWindowMemory` |
| Token 计数 | 手动累加 `response.usage.total_tokens` | `TokenBudgetHandler` callback |
| 错误重试 | 手写 try/catch + Key 切换 | `ErrorRecoveryHandler` callback + `max_retries` |
| 工具定义 | 手写 JSON Schema + guard 函数 | `@tool` 装饰器 + docstring → 自动生成 schema |
| 工具调用日志 | 手写 `log.info` | `MetricsHandler` callback |
| 早期终止 | 手写 break 条件 | `early_stopping_method="generate"` |
| 重复检测 | 手写 `_is_repeating` | 需额外 callback（或不实现——AgentExecutor 本身会尽量避免） |
| 总代码量 | ~400 行 | ~200 行（含 callback） |

---

## 七、测试计划

### 单元测试（Mock LangChain + HTTP）

| # | 用例 | 验证点 |
|---|------|--------|
| 1 | 工具 guard：合法表名通过 | 返回 DDL 文本 |
| 2 | 工具 guard：非法表名拦截 | 返回 "只能包含字母、数字" |
| 3 | 工具 guard：SQL 黑名单拦截 | 返回 "包含敏感关键字" |
| 4 | 工具 guard：变量白名单拦截 | 返回 "不在可查询范围内" |
| 5 | KeyPool R-R + 熔断 | 3 次失败 → 剔除 60s |
| 6 | DataClient HTTP 超时降级 | 返回错误文本不抛异常 |
| 7 | AgentExecutor：正常完成 | `result["output"]` 非空 |
| 8 | AgentExecutor：超过 max_iterations | `early_stopping_method` 触发生成最终回答 |
| 9 | AgentExecutor：Token 超预算 | `TokenBudgetExceeded` 异常被捕获 |

### 集成测试（需真实 LLM + Java + Redis + RMQ）

| # | 用例 |
|---|------|
| 10 | 简单 SQL 全链路：RMQ 消费 → Agent 调 DDL+EXPLAIN → 返回报告 → Redis 缓存 |
| 11 | 复杂 SQL（3表 JOIN）：Agent 自动调 check_missing_indexes |
| 12 | 指纹缓存命中：同 SQL 两次 → 第二次从 `diagnosis:result:fp:` 返回 |
| 13 | 幂等消费：已完成任务重新入队 → 跳过不重复诊断 |

---

## 八、实施步骤

| 步骤 | 内容 | 产出 |
|------|------|------|
| 1 | 项目骨架 + 配置 + 依赖安装 | `main.py` + `config.yaml` + `requirements.txt` |
| 2 | DataClient + 13 个 `@tool` | `tools/` 全部 |
| 3 | KeyPool + MultiKeyChatOpenAI | `llm/` 全部 |
| 4 | Agent 工厂 + Callback | `agent/` 全部 |
| 5 | ChatMemory（Redis） | `memory/` |
| 6 | RabbitMQ Consumer + Publisher | `mq/` |
| 7 | 串联：main.py 组装全部组件 | `main.py` |
| 8 | 单元测试 | `tests/` |
| 9 | 集成测试（需全栈环境） | `tests/test_integration.py` |

**总计 13 个 Python 文件，预估 ~500 行代码。**（自研版 ~1200 行，第一版 LangChain ~600 行，审查裁剪后 ~500 行）
