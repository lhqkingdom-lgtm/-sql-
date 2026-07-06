"""Slow SQL Agent V5.0 — FastAPI 入口"""
import asyncio
import logging
from contextlib import asynccontextmanager

import yaml
from fastapi import FastAPI
from redis.asyncio import Redis

from config import load_settings

settings = load_settings()

from tools.data_client import DataClient
from tools.definitions import set_data_client
from agent.factory import create_agent_with_memory
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

    # Agent
    agent_factory = create_agent_with_memory(settings)

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


@app.get("/health")
async def health():
    return {"status": "ok"}
