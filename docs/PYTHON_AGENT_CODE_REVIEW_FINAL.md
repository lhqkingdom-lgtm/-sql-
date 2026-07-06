# Python Agent 最终审查

> 14/14 测试通过。上一轮 4 项全部修对。

---

## 上轮修复确认

| # | 问题 | 判定 |
|---|------|------|
| 🔴 #1 Callback 跨 task 污染 | ✅ `factory.py` `make_agent()` 内每次创建新 callback |
| 🔴 #2 fallback 每次只消费一条 | ✅ 内层 while drain 全部，外层 sleep |
| 🟠 #3 缺 langchain-classic | ✅ `requirements.txt` 已补 |
| 🟡 TokenBudgetExceeded 重复定义 | ✅ 统一在 `callbacks.py`，`consumer.py` 从 callbacks 导入 |

---

## 本轮新发现

### 1. `make_agent` 的 `system_prompt` 参数仍然是死的

```python
# factory.py line 39
def make_agent(tools, system_prompt) -> tuple:
    ...
    agent = create_openai_tools_agent(llm_with_tools, tools, prompt)
    ...
    return runner, metrics
```

`system_prompt` 传入但从未在函数体内使用。真正生效的 system_prompt 来自 consumer 的 `agent.ainvoke({"system_prompt": SYSTEM_PROMPT, ...})`。consumer 传参给 `make_agent` 是多余的，factory 接收也是多余的。要么删掉参数，要么把 system_prompt 在 `create_openai_tools_agent` 时就固定到 prompt 里（省掉 invoke 时传）。

### 2. `recover_fallback` 外层异常静默吞

```python
# consumer.py line 126-127
except Exception:
    pass            # ← Redis 挂了也静默，每 300s 白跑一轮
await asyncio.sleep(300)
```

至少加一句 `logger.warning("降级恢复异常，300s后重试")`。而且异常时 sleep 退避应该缩短（比如 10s），不必等 5 分钟——Redis 断开可能是瞬时的。

### 3. `publish_result` 和 `republish_task` 走不同的 exchange

```python
# publisher.py
publish_result   → default_exchange + routing_key="diagnosis.done.queue"  # 直连队列
republish_task   → diagnosis.exchange + routing_key="task.high"            # 走 topic exchange
```

「直连队列」和「走 topic exchange」混用，逻辑上不一致。`publish_result` 应该也走 `diagnosis.exchange` 用 `done.queue` routing key（或 `done.result`）。虽然两种都能用，但这个不一致未来会埋坑——如果有人检查 exchange 的 binding 数量，会发现 done 消息不从这里走，以为是 bug。

### 4. `DiagnosisTask.model_route` 字段已废弃但未删除

```python
# models.py line 11
model_route: str = "plus"
```

模型路由已明确不做，Java 侧 `task.model_route` 字段还在消息里传，Python 照收不误但从来不读。要么删掉字段，要么加注释标注"保留未使用"。

---

## 📊 质量评分

| 维度 | 评分 | 说明 |
|------|------|------|
| Bug 修复 | 10/10 | 4 项全对 |
| 异常处理 | 8/10 | Token 超限/死循环/JSON 错误分类正确，fallback 异常静默扣分 |
| API 使用 | 9/10 | RunnableWithMessageHistory 正确，langchain-classic 补上 |
| 代码整洁 | 7/10 | system_prompt 死参数、exchange 不一致、废弃字段残留 |

**可以合入。** 4 个新问题都是 P3 级别，不影响正确性。
