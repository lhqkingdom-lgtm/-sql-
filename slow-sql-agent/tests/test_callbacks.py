"""测试 Callback 系统——Token预算 + 指标收集 + 死循环防护"""
import time
import pytest
from unittest.mock import MagicMock
from langchain_core.outputs import LLMResult

from agent.callbacks import (
    TokenBudgetHandler, TokenBudgetExceeded,
    MetricsHandler,
    RepeatGuardHandler, RepeatGuardTripped,
)


def _make_llm_result(total_tokens):
    """构造 LangChain LLMResult 对象"""
    return LLMResult(
        generations=[[]],
        llm_output={"token_usage": {"total_tokens": total_tokens}},
    )


class TestTokenBudgetHandler:

    def test_under_budget_does_not_raise(self):
        h = TokenBudgetHandler(budget=5000)
        h.on_llm_end(_make_llm_result(1000))
        h.on_llm_end(_make_llm_result(2000))
        assert h.used == 3000

    def test_over_budget_raises(self):
        h = TokenBudgetHandler(budget=1000)
        h.on_llm_end(_make_llm_result(800))
        with pytest.raises(TokenBudgetExceeded):
            h.on_llm_end(_make_llm_result(500))
        assert h.used == 1300

    def test_exact_budget_does_not_raise(self):
        h = TokenBudgetHandler(budget=1000)
        h.on_llm_end(_make_llm_result(1000))
        assert h.used == 1000  # exactly at budget, no raise (> not >=)

    def test_default_budget(self):
        h = TokenBudgetHandler()
        assert h.budget == 30000
        assert h.used == 0

    def test_no_llm_output_does_not_crash(self):
        """LLMResult.llm_output 可能为 None"""
        h = TokenBudgetHandler(budget=1000)
        result = LLMResult(generations=[[]], llm_output=None)
        h.on_llm_end(result)  # 不应抛异常
        assert h.used == 0


class TestMetricsHandler:

    def test_records_llm_calls(self):
        h = MetricsHandler()
        h.on_llm_start()
        h.on_llm_start()
        assert h.llm_calls == 2

    def test_records_tool_calls(self):
        h = MetricsHandler()
        h.on_tool_start()
        h.on_tool_start()
        h.on_tool_start()
        assert h.tool_calls == 3

    def test_records_tool_errors(self):
        h = MetricsHandler()
        h.on_tool_error(Exception("timeout"))
        h.on_tool_error(Exception("connection refused"))
        assert len(h.errors) == 2
        assert "timeout" in h.errors[0]

    def test_elapsed_ms_tracks_real_time(self):
        h = MetricsHandler()
        time.sleep(0.01)
        assert h.elapsed_ms >= 10

    def test_error_truncation(self):
        """错误信息截断到 200 字符"""
        h = MetricsHandler()
        long_error = "x" * 300
        h.on_tool_error(Exception(long_error))
        assert len(h.errors[0]) == 200


class TestRepeatGuardHandler:

    def test_no_raise_on_first_three_unique(self):
        h = RepeatGuardHandler()
        _simulate_tool(h, "get_table_ddl", '{"table_name":"t1"}')
        _simulate_tool(h, "get_execution_plan", '{"sql":"SELECT 1"}')
        _simulate_tool(h, "get_table_statistics", '{"table_name":"t1"}')
        # 3 次都不同 → 不抛异常

    def test_raises_on_three_same(self):
        h = RepeatGuardHandler()
        _simulate_tool(h, "get_table_ddl", '{"table_name":"t1"}')
        _simulate_tool(h, "get_table_ddl", '{"table_name":"t1"}')
        with pytest.raises(RepeatGuardTripped) as exc_info:
            _simulate_tool(h, "get_table_ddl", '{"table_name":"t1"}')
        assert "死循环" in str(exc_info.value)

    def test_window_sliding(self):
        """超过 3 次后窗口滑动，只检测最近 3 次"""
        h = RepeatGuardHandler()
        # 4 次，但前 3 次不同，不触发
        _simulate_tool(h, "get_table_ddl", '{"table_name":"t1"}')
        _simulate_tool(h, "get_execution_plan", '{"sql":"SELECT 1"}')
        _simulate_tool(h, "get_table_ddl", '{"table_name":"t1"}')
        _simulate_tool(h, "get_execution_plan", '{"sql":"SELECT 1"}')
        # 最近 3 次：唯一、get_table_ddl、唯一 → 不触发

    def test_raises_on_sliding_three_same(self):
        """窗口滑动后，最近 3 次全相同 → 触发"""
        h = RepeatGuardHandler()
        _simulate_tool(h, "other_tool", '{}')          # 历史
        _simulate_tool(h, "get_table_ddl", '{"t":"1"}')  # #1
        _simulate_tool(h, "get_table_ddl", '{"t":"1"}')  # #2
        with pytest.raises(RepeatGuardTripped):
            _simulate_tool(h, "get_table_ddl", '{"t":"1"}')  # #3 触发


def _simulate_tool(handler, tool_name: str, input_str: str):
    """模拟 LangChain 回调的 on_tool_start 参数"""
    handler.on_tool_start(
        serialized={"name": tool_name},
        input_str=input_str,
    )
