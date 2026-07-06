"""结果回传——RabbitMQ done.queue"""
import json
import aio_pika
from models import DiagnosisTask, DiagnosisResult


class ResultPublisher:
    def __init__(self, rmq_url: str):
        self._url = rmq_url
        self._conn: aio_pika.RobustConnection | None = None
        self._channel: aio_pika.Channel | None = None

    async def start(self):
        self._conn = await aio_pika.connect_robust(self._url)
        self._channel = await self._conn.channel()

    async def close(self):
        if self._conn:
            await self._conn.close()

    async def publish_result(self, result: DiagnosisResult):
        if not self._channel:
            return
        body = json.dumps(result.model_dump()).encode()
        exchange = await self._channel.declare_exchange(
            "diagnosis.exchange", aio_pika.ExchangeType.TOPIC, durable=True)
        await exchange.publish(
            aio_pika.Message(body=body, delivery_mode=2),
            routing_key="done.result",
        )

    async def republish_task(self, task: DiagnosisTask, routing_key: str = "task.high"):
        """降级队列恢复——重新投递任务"""
        if not self._channel:
            return
        body = json.dumps(task.model_dump()).encode()
        exchange = await self._channel.declare_exchange(
            "diagnosis.exchange", aio_pika.ExchangeType.TOPIC, durable=True)
        await exchange.publish(
            aio_pika.Message(body=body, delivery_mode=2),
            routing_key=routing_key,
        )
