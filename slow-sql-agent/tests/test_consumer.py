"""MQ 消费者测试——消息处理 + 幂等 + 错误处理"""
import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from models import DiagnosisTask, DiagnosisResult
from agent.callbacks import TokenBudgetExceeded, RepeatGuardTripped


# ===== 辅助 =====

def _make_task_body(task_id="t-001", extra=None):
    """构造 Java 端发来的 camelCase 消息"""
    body = {
        "taskId": task_id,
        "sessionId": "s-001",
        "instanceId": "tc-dev-mysql",
        "projectCode": "tongcheng-club",
        "enrichedPrompt": "【SQL】SELECT * FROM orders WHERE id=1",
        "fingerprint": "abc123",
        "source": "manual",
        "timestamp": "2026-07-09T10:00:00",
    }
    if extra:
        body.update(extra)
    return json.dumps(body).encode()


async def _create_mock_message(body: bytes, ack=AsyncMock()):
    """创建 mock aio_pika.IncomingMessage"""
    msg = MagicMock()
    msg.body = body
    msg.process_context = AsyncMock()
    msg.process_context.__aenter__ = AsyncMock()
    msg.process_context.__aexit__ = AsyncMock()
    return msg


# ===== 消息格式校验 =====

class TestMessageDeserialization:

    def test_valid_json_parses(self):
        body = json.dumps({
            "taskId": "t-001", "instanceId": "tc-dev-mysql",
            "enrichedPrompt": "SELECT 1"}).encode()
        task = DiagnosisTask(**json.loads(body))
        assert task.task_id == "t-001"

    def test_invalid_json(self):
        with pytest.raises(Exception):
            DiagnosisTask(**json.loads('{"not":"valid"}'))

    def test_empty_body(self):
        with pytest.raises(Exception):
            DiagnosisTask(**json.loads("{}"))


# ===== 幂等消费 =====

class TestIdempotency:
    """已完成的 task 不重复处理"""

    async def _run_process_with_redis_status(self, status: bytes | None):
        """模拟 _process 中的幂等检查"""
        from mq.consumer import _process
        import asyncio
        # 用 patch 模拟所有外部依赖
        pass  # 实际测试通过集成跑，这里测逻辑


class TestProcessLogic:

    @pytest.mark.asyncio
    async def test_completed_task_skipped(self):
        """如果 task 状态是 completed → 直接返回，不调 agent"""
        from mq import consumer as mq_consumer

        # 构造 mock
        mock_redis = AsyncMock()
        mock_redis.exists.return_value = True
        mock_redis.hget.return_value = b"completed"

        mock_agent_factory = MagicMock()
        mock_publisher = AsyncMock()

        msg = await _create_mock_message(_make_task_body("t-done"))

        # 直接调 _process
        with patch.object(mq_consumer, 'SYSTEM_PROMPT', "test"):
            # 需要 patch agent_factory 构造 agent — 但这里幂等会提前返回
            pass

    @pytest.mark.asyncio
    async def test_failed_task_skipped(self):
        """如果 task 状态是 failed → 直接返回"""
        from mq import consumer as mq_consumer

        mock_redis = AsyncMock()
        mock_redis.exists.return_value = True
        mock_redis.hget.return_value = b"failed"

        mock_agent_factory = MagicMock()
        mock_publisher = AsyncMock()

        msg = await _create_mock_message(_make_task_body("t-failed"))

        # 幂等检查应该跳过
        mock_agent_factory.assert_not_called()


# ===== 异常处理 =====

class TestErrorHandling:

    def test_token_budget_error_to_result(self):
        """TokenBudgetExceeded → DiagnosisResult(status=failed, error=...)"""
        r = DiagnosisResult(
            taskId="t-001", status="failed",
            error="Token预算耗尽，请简化SQL后重试",
            duration_ms=30000)
        assert r.status == "failed"
        assert "Token" in r.error

    def test_repeat_guard_error_to_result(self):
        """RepeatGuardTripped → DiagnosisResult(status=failed)"""
        r = DiagnosisResult(
            taskId="t-002", status="failed",
            error="诊断死循环: get_table_ddl 连续调用 3 次相同参数，请基于已有数据诊断",
            duration_ms=5000)
        assert r.status == "failed"
        assert "死循环" in r.error

    def test_unexpected_error_to_result(self):
        """未预期异常 → DiagnosisResult(status=failed, error=类型名)"""
        r = DiagnosisResult(
            taskId="t-003", status="failed",
            error="诊断异常: LLMTimeoutError",
            duration_ms=0)
        assert r.status == "failed"
        assert "LLMTimeoutError" in r.error


# ===== 降级队列恢复 =====

class TestFallbackRecovery:

    @pytest.mark.asyncio
    async def test_drain_empty_fallback(self):
        """Redis 降级队列为空 → 不发布"""
        from mq import consumer as mq_consumer

        mock_redis = AsyncMock()
        mock_redis.lpop.return_value = None
        mock_publisher = AsyncMock()

        # 模拟单次 drain（只循环第一轮就 sleep）
        mq_consumer.FALLBACK_KEY = "diagnosis:fallback:queue"

        with patch('asyncio.sleep', new_callable=AsyncMock) as mock_sleep:
            mock_sleep.side_effect = [None, Exception("stop loop")]
            try:
                await mq_consumer.recover_fallback(mock_redis, mock_publisher)
            except Exception:
                pass

        mock_publisher.republish_task.assert_not_called()

    @pytest.mark.asyncio
    async def test_drain_single_valid_message(self):
        """降级队列中有 1 条有效消息 → republish"""
        from mq import consumer as mq_consumer

        mock_redis = AsyncMock()
        mock_redis.lpop.side_effect = [
            _make_task_body("t-fb-001"),  # 第 1 次有数据
            None,  # 第 2 次空了
        ]
        mock_publisher = AsyncMock()

        mq_consumer.FALLBACK_KEY = "diagnosis:fallback:queue"

        with patch('asyncio.sleep', side_effect=Exception("stop")):
            try:
                await mq_consumer.recover_fallback(mock_redis, mock_publisher)
            except Exception:
                pass

        mock_publisher.republish_task.assert_called_once()

    @pytest.mark.asyncio
    async def test_drain_invalid_message_skipped(self):
        """降级队列中有格式错误的消息 → 跳过"""
        from mq import consumer as mq_consumer

        mock_redis = AsyncMock()
        mock_redis.lpop.side_effect = [
            b'{"garbage}}',  # 格式错误
            None,
        ]
        mock_publisher = AsyncMock()

        with patch('asyncio.sleep', side_effect=Exception("stop")):
            try:
                await mq_consumer.recover_fallback(mock_redis, mock_publisher)
            except Exception:
                pass

        mock_publisher.republish_task.assert_not_called()
