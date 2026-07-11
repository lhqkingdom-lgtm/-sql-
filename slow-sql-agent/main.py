"""Slow SQL Agent V5.0 — FastAPI 入口"""
# RESP3 兼容 Redis 5.x——必须在任何 redis import 之前，同步+异步都要设
import redis.connection
import redis.asyncio.connection
redis.connection.Connection.DEFAULT_PROTOCOL = 2
redis.asyncio.connection.Connection.DEFAULT_PROTOCOL = 2

import asyncio
import logging
import uuid
from contextlib import asynccontextmanager

import yaml
from fastapi import FastAPI
from pydantic import BaseModel
from redis.asyncio import Redis

from config import load_settings

settings = load_settings()

from tools.data_client import DataClient
from tools.definitions import set_data_client, _instance_id_ctx, ALL_TOOLS
from agent.factory import create_agent_with_memory
from agent.callbacks import TokenBudgetExceeded, RepeatGuardTripped
from mq.publisher import ResultPublisher
from mq.consumer import start_consumer, recover_fallback

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # ---- startup ----
    logger.info("Agent starting...")

    # Redis
    redis = Redis.from_url(settings.redis_url, decode_responses=True)

    # DataClient
    data_client = DataClient(settings)
    set_data_client(data_client)

    # Agent (pass redis_url for progress tracking)
    agent_factory = create_agent_with_memory(settings, settings.redis_url)

    # System Prompt
    with open("prompts/diagnosis.yaml", encoding="utf-8") as f:
        system_prompt = yaml.safe_load(f)["system"]

    # RabbitMQ
    publisher = ResultPublisher(settings.rabbitmq_url)
    await publisher.start()

    # Consumer
    rmq_conn = await start_consumer(
        agent_factory, redis,
        settings.rabbitmq_url, publisher, system_prompt,
    )

    # Fallback recovery
    fallback_task = asyncio.create_task(recover_fallback(redis, publisher))

    app.state.redis = redis
    app.state.data_client = data_client
    app.state.rmq_conn = rmq_conn
    app.state.fallback_task = fallback_task
    app.state.publisher = publisher
    app.state.agent_factory = agent_factory
    app.state.system_prompt = system_prompt
    logger.info("Agent started")

    yield

    # ---- shutdown ----
    logger.info("Agent shutting down...")
    fallback_task.cancel()
    await rmq_conn.close()
    await publisher.close()
    await data_client.close()
    await redis.close()
    logger.info("Agent stopped")


app = FastAPI(lifespan=lifespan)


class DiagnoseRequest(BaseModel):
    sql: str
    instanceId: str
    projectCode: str = ""
    taskId: str = ""


class DiagnoseResponse(BaseModel):
    taskId: str
    status: str
    report: str = ""
    error: str = ""
    durationMs: int = 0
    toolCallCount: int = 0


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/diagnose")
async def diagnose(req: DiagnoseRequest):
    """同步诊断入口——手动 SQL/MyBatis 日志直发 Agent，不经过 RMQ。"""
    task_id = req.taskId or uuid.uuid4().hex
    _instance_id_ctx.set(req.instanceId)

    agent_factory = app.state.agent_factory
    agent, metrics, progress = agent_factory(ALL_TOOLS, task_id)

    # 写初始进度到 Redis
    if progress:
        progress._add_step("agent", "start", f"HTTP直连诊断: {req.instanceId}")

    prompt = f"【项目】{req.projectCode}\n【实例】{req.instanceId}\n【待分析SQL】\n{req.sql}\n\n请按需调用工具获取DDL和执行计划进行诊断。"

    try:
        result = await agent.ainvoke(
            {"input": prompt, "system_prompt": app.state.system_prompt},
            config={"configurable": {"session_id": task_id}}
        )
        if progress:
            progress._add_step("AI引擎", "done",
                f"诊断完成，{metrics.tool_calls}次工具调用，{metrics.elapsed_ms}ms")

        return DiagnoseResponse(
            taskId=task_id,
            status="completed",
            report=result.get("output", "") or "",
            durationMs=metrics.elapsed_ms,
            toolCallCount=metrics.tool_calls,
        )
    except TokenBudgetExceeded:
        return DiagnoseResponse(
            taskId=task_id, status="failed",
            error="Token预算耗尽，请简化SQL后重试")
    except RepeatGuardTripped as e:
        return DiagnoseResponse(
            taskId=task_id, status="failed",
            error=f"诊断死循环: {e}")
    except Exception as e:
        logger.exception("HTTP诊断异常")
        return DiagnoseResponse(
            taskId=task_id, status="failed",
            error=f"{type(e).__name__}: {e}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
