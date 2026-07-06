# Python Agent 代码审查

> 14/14 测试通过。以下是刁钻视角的代码审查。

---

## 🔴 Bug — 会产出错误结果

### 1. Callback 跨 task 数据污染

`factory.py` 里三个 callback 实例只在 `create_agent_with_memory()` 中创建一次，之后每个 task 创建的 AgentExecutor 都共享同一个实例：

```python
# factory.py line 34-36
token_handler = TokenBudgetHandler(settings.agent_token_budget)
metrics_handler = MetricsHandler()
repeat_handler = RepeatGuardHandler()
```

**后果一 — Token 预算跨 task 累积：**
```python
# callbacks.py line 21
self.used += usage.get("total_tokens", 0)
```
Task A 用了 25K token → `token_handler.used = 25000`。Task B 开始→用了 6K → `used = 31000` → 触发 `TokenBudgetExceeded`。Task B 被错误终止（实际只用了 6K，远未到 30K 预算）。

**后果二 — 工具调用计数串号：**
```python
# callbacks.py line 44
self.tool_calls += 1   # 从不重置
```
Task A 调了 3 次工具，Task B 调了 5 次 → consumer 里 `metrics_handler.tool_calls` 返回 8。Task B 的 `DiagnosisResult.tool_call_count = 8`，实际是 5。

**后果三 — 死循环检测跨 task 误杀：**
```python
# callbacks.py line 59-62
self._history.append(key)
if len(self._history) == 3 and len(set(self._history)) == 1:
```
Task A 调了两次 `get_table_ddl('orders')`，Task B 调了一次同样的 → `_history = [ddl:orders, ddl:orders, ddl:orders]` → 误触发 `RepeatGuardTripped`。

**改法**：每个 task 创建新的 callback 实例。把 callback 创建移到 `_make_executor()` 或 `_create_agent()` 内部。

### 2. `recover_fallback` 每 5 分钟只消费一条消息

```python
# consumer.py line 109-120
async def recover_fallback(redis, publisher):
    while True:
        msg_raw = await redis.lpop("diagnosis:fallback:queue")
        if msg_raw:
            # 处理...
        await asyncio.sleep(300)   # ← 在内层，每次循环都睡
```

如果降级队列积压 50 条 → 50 × 5min = 250 分钟才能排空。**改法**：内层 while 循环 drain 完所有积压，外层再 sleep：

```python
while True:
    while True:
        msg_raw = await redis.lpop("diagnosis:fallback:queue")
        if not msg_raw:
            break
        # 处理...
    await asyncio.sleep(300)
```

---

## 🟠 跑不起来 / 缺依赖

### 3. `langchain_classic` 不在 requirements.txt

```python
# factory.py line 2
from langchain_classic.agents import AgentExecutor, create_openai_tools_agent
```

`requirements.txt` 没有 `langchain_classic`。这个包是 LangChain 0.3+ 从主包拆出来的——不装就跑不起来。加上 `langchain-classic>=0.1.0`。

---

## 🟡 代码质量问题

### 4. `TokenBudgetExceeded` 在 `models.py` 和 `callbacks.py` 各定义一次

`models.py:26` 和 `callbacks.py:7` 定义了完全相同的异常类。`consumer.py` 从 `models` import 的是 models 版，callback 内部 raise 的是 callbacks 版。二者是不同类，`isinstance` 检查不通过——但 `consumer.py` 的 `except TokenBudgetExceeded` 实际上只捕获了 models 版的，callback 版不是它的子类。

等等——看 `callbacks.py` 的 `TokenBudgetExceeded` 的 `raise`，和 `consumer.py` 的 `except TokenBudgetExceeded`（从 `models` 导入）。这是两个不同的类！callback raise 的是 `callbacks.TokenBudgetExceeded`，consumer catch 的是 `models.TokenBudgetExceeded`。**它们不是同一种异常，catch 不会命中。** Token 超限不会走 "Token预算耗尽" 分支，而是落到通用 `except Exception` → "诊断异常: TokenBudgetExceeded"。

**改法**：删除 `callbacks.py` 里的定义，只保留 `models.py` 的。callback 从 `models` import。

### 5. `_create_agent` 的 `system_prompt` 参数从未使用

```python
# factory.py line 51-53
def _create_agent(tools, system_prompt):  # ← system_prompt 收到了
    llm_with_tools = llm.bind_tools(tools)
    return _make_executor(llm_with_tools, tools)  # ← 没传给 _make_executor
```

`system_prompt` 在 consumer.py line 71 调用时传入，但在函数体内被忽略。实际生效的 system_prompt 是通过 `agent.ainvoke({"system_prompt": ...})` 在 invoke 时动态注入的——这个设计 OK，但参数白传了。要么删掉参数，要么用起来（比如把这个版本的 system_prompt 固定在 executor 里）。当前是死参数。

### 6. `load_settings()` 冗余

```python
# config.py line 34-36
def load_settings() -> Settings:
    return Settings(
        deepseek_api_key=os.getenv("DEEPSEEK_API_KEY", ""),
    )
```

pydantic-settings 的 `BaseSettings` 已经自动读同名环境变量。`model_config = {"case_sensitive": False}` 意味着 `deepseek_api_key` 字段会匹配 `DEEPSEEK_API_KEY` 环境变量。所以 `load_settings()` 里的 `os.getenv("DEEPSEEK_API_KEY", "")` 就是重复造轮子——`Settings()` 直接就能读到。

**改法**：删掉 `load_settings()`，`main.py` 直接 `settings = Settings()`。

### 7. Redis 密码明文

```python
# config.py line 26
redis_url: str = "redis://:123456@localhost:6379/2"
```

跟 Java `application.yml` 之前一个毛病。改成 `"redis://:${REDIS_PASSWORD}@localhost:6379/2"`，或者从单独环境变量拼接。

### 8. `main.py` import 顺序混乱

```python
from config import load_settings
settings = load_settings()        # ← 模块级执行，卡在 import 中间

from tools.data_client import DataClient   # ← settings 之后才 import
```

如果 `DataClient` 或任何之后的导入依赖 `settings`，这种写法会埋坑。而且不符合 PEP8 "import 全部放文件头"的惯例。把 `settings = load_settings()` 移到所有 import 之后，或者在 import 完成后再调。

### 9. `recover_fallback` 无错误恢复

```python
except Exception:
    pass          # ← 静默吞掉所有异常
await asyncio.sleep(300)
```

如果 Redis 连接断开，`lpop` 抛异常被吞 → sleep → 下一轮又抛异常 → 永远不工作。至少应该加一条 `logger.warning`，并且异常时 sleep 时间退避（10s 而非 300s）。

---

## 🔵 潜在隐患

### 10. `RepeatGuardTripped` 会导致 NACK requeue

consumer 的 `_process` catch 了 `RepeatGuardTripped` 并返回 FAILED（line 87）。但因为 `async with message.process()` 包裹了整个 `_process`，如果 `_process` 内部抛出未捕获的异常……实际上这里没问题，因为 `RepeatGuardTripped` 被 catch 了。但 `TokenBudgetExceeded` 如果来自 callbacks 的版本（问题 4），就不会被 catch，会在 `message.process()` 层面触发 requeue → 这个任务就永远重试。

### 11. 没有 `__init__.py`

`tools/`、`agent/`、`mq/` 目录都没 `__init__.py`。Python 3.3+ 隐式命名空间包允许这样 work，但 mypy/pylint 会抱怨，且 `pytest` 在某些配置下会有 import 问题。每个子目录丢一个空的 `__init__.py`。

---

## 📊 测试覆盖评估

| 测试 | 状态 | 覆盖什么 |
|------|------|----------|
| 工具 guard 逻辑 | ✅ 12 个 | 表名正则、SQL 黑名单、变量白名单、工具数量 |
| DataClient 降级 | ✅ 2 个 | 超时返回 fallback 文案、连接失败不抛异常 |
| Agent 执行 | ❌ 0 个 | LLM 调用、tool call 循环、RunnableWithMessageHistory |
| Callback | ❌ 0 个 | Token 累积、Metrics 计数、RepeatGuard |
| 消费者 | ❌ 0 个 | 幂等、异常分类、JSON 解析失败 |
| 发布者 | ❌ 0 个 | 结果回传、降级恢复 |
| Key 池 | N/A | 已移除 |

**只有 DataClient 和工具 guard 有测试。Agent 核心链路（factory → executor → callback → consumer）零覆盖。**

---

## 📊 汇总

| 级别 | 数量 | 条目 |
|------|------|------|
| 🔴 Bug | 2 | Callback 跨 task 污染、fallback 恢复每 5 分钟只消费一条 |
| 🟠 缺依赖 | 1 | `langchain_classic` 不在 requirements.txt |
| 🟡 代码质量 | 6 | 异常类重复定义、死参数、load_settings 冗余、Redis 密码明文、import 混乱、异常静默吞 |
| 🔵 隐患 | 2 | 无 `__init__.py`、异常类版本不一致可能绕过 catch |
| ⚪ 测试缺口 | 核心链路 0 覆盖 | Agent 执行/Consumer/Callback 无测试 |
