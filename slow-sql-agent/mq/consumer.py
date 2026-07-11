"""RabbitMQ 消费者 + 降级队列恢复"""
import asyncio
import json
import logging
from datetime import datetime

import aio_pika
from redis.asyncio import Redis
from redis.exceptions import ConnectionError as RedisConnectionError

from models import DiagnosisTask, DiagnosisResult
from agent.callbacks import TokenBudgetExceeded, RepeatGuardTripped
from tools.definitions import _instance_id_ctx, ALL_TOOLS
from mq.publisher import ResultPublisher

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = ""

# 与 Java RabbitMqConfig 保持一致
FALLBACK_KEY = "diagnosis:fallback:queue"


async def start_consumer(
    agent_factory,          # (tools, system_prompt) → (RunnableWithMessageHistory, MetricsHandler)
    redis: Redis,
    rmq_url: str,
    publisher: ResultPublisher,
    system_prompt: str,
):
    global SYSTEM_PROMPT
    SYSTEM_PROMPT = system_prompt

    conn = await aio_pika.connect_robust(rmq_url)
    channel = await conn.channel()
    await channel.set_qos(prefetch_count=1)

    # Exchange 绑定
    exchange = await channel.declare_exchange(
        "diagnosis.exchange", aio_pika.ExchangeType.TOPIC, durable=True)
    normal = await channel.declare_queue(
        "diagnosis.task.normal", durable=True)
    await normal.bind(exchange, routing_key="task.normal")

    async def handle(message: aio_pika.IncomingMessage):
        async with message.process():
            await _process(message, agent_factory, redis, publisher)

    await normal.consume(handle)
    logger.info("Consumer started, listening on task.normal")
    return conn


async def _process(message, agent_factory, redis, publisher):
    try:
        task = DiagnosisTask(**json.loads(message.body))
    except Exception:
        logger.warning("消息格式错误，丢弃")
        return

    # 幂等
    if await redis.exists(f"diagnosis:task:{task.task_id}"):
        status = await redis.hget(f"diagnosis:task:{task.task_id}", "status")
        if status in (b"completed", b"failed"):
            return

    await redis.hset(f"diagnosis:task:{task.task_id}", mapping={
        "status": "running", "updatedAt": datetime.now().isoformat()
    })
    await redis.expire(f"diagnosis:task:{task.task_id}", 1800)

    _instance_id_ctx.set(task.instance_id)
    agent, metrics, progress = agent_factory(ALL_TOOLS, task.task_id)

    # Write initial progress
    if progress:
        progress._add_step("agent", "start", "Agent 启动，连接 DeepSeek...")

    try:
        result = await agent.ainvoke(
            {"input": task.enriched_prompt, "system_prompt": SYSTEM_PROMPT},
            config={"configurable": {"session_id": task.session_id or task.task_id}}
        )
        # Extract intermediate steps from agent output
        TOOL_CN = {
            'get_table_ddl':'获取表结构','get_execution_plan':'执行计划分析',
            'get_table_statistics':'表统计信息','check_active_locks':'锁检查',
            'get_innodb_status':'InnoDB状态','get_global_variable':'全局变量',
            'check_redundant_indexes':'冗余索引','compare_execution_plan':'计划对比',
            'get_slow_log_stats':'慢日志统计','check_missing_indexes':'缺失索引',
            'check_type_mismatch':'类型转换','get_buffer_pool_hit_rate':'缓存命中率',
            'get_process_list':'连接列表','check_actual_row_count':'实际行数',
        }
        if progress and "intermediate_steps" in result:
            for action, observation in result["intermediate_steps"]:
                tool_name = action.tool if hasattr(action, 'tool') else str(action)
                cn_name = TOOL_CN.get(tool_name, tool_name)
                obs_str = str(observation)[:200] if observation else ""
                progress._add_step(cn_name, "done", obs_str)

        if progress:
            progress._add_step("AI引擎", "done", f"诊断完成，{metrics.tool_calls}次工具调用，{metrics.elapsed_ms}ms")

        diag = DiagnosisResult(
            task_id=task.task_id, status="completed",
            report=result["output"] or "",
            duration_ms=metrics.elapsed_ms,
            tool_call_count=metrics.tool_calls,
            fingerprint=task.fingerprint,
            instance_id=task.instance_id,
            project_code=task.project_code,
            source=task.source,
        )
    except TokenBudgetExceeded:
        diag = DiagnosisResult(task_id=task.task_id, status="failed",
                               error="Token预算耗尽，请简化SQL后重试")
    except RepeatGuardTripped as e:
        diag = DiagnosisResult(task_id=task.task_id, status="failed",
                               error=f"诊断死循环: {e}")
    except Exception as e:
        logger.exception("诊断未预期异常")
        diag = DiagnosisResult(task_id=task.task_id, status="failed",
                               error=f"诊断异常: {type(e).__name__}")

    # 写 Redis
    content = diag.report or diag.error or ""
    await redis.setex(f"diagnosis:result:{task.task_id}", 1800, content)
    await redis.hset(f"diagnosis:task:{task.task_id}", mapping={
        "status": diag.status, "updatedAt": datetime.now().isoformat()
    })
    await redis.expire(f"diagnosis:task:{task.task_id}", 1800)
    if task.fingerprint and diag.status == "completed":
        await redis.setex(f"diagnosis:result:fp:{task.fingerprint}", 1800,
                          diag.report or "")

    # 回传 done.queue
    await publisher.publish_result(diag)


async def recover_fallback(redis: Redis, publisher: ResultPublisher):
    """每 5 分钟一次性 drain 全部降级队列积压"""
    while True:
        try:
            count = 0
            while True:
                msg_raw = await redis.lpop(FALLBACK_KEY)
                if not msg_raw:
                    break
                try:
                    task = DiagnosisTask(**json.loads(msg_raw))
                    await publisher.republish_task(task, "task.high")
                    count += 1
                except Exception:
                    logger.warning("降级消息格式错误，跳过")
            if count > 0:
                logger.info(f"降级队列恢复: {count} 条")
        except RedisConnectionError:
            pass  # Redis 暂时不可达，等下一轮
        except Exception as e:
            logger.warning(f"降级恢复异常: {e}")
        await asyncio.sleep(300)
