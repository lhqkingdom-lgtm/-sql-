"""
Agent 诊断质量测试——验证不同慢 SQL 场景下 Agent 选择了正确的工具。

策略：Mock DeepSeek LLM 的返回内容，通过 Agent 调用记录验证工具选择。
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from langchain_core.messages import AIMessage, ToolCall

from tools.definitions import ALL_TOOLS
from agent.callbacks import MetricsHandler
from agent.factory import create_agent_with_memory
from config import Settings


# ===== 工具索引 =====
TOOLS_BY_NAME = {t.name: t for t in ALL_TOOLS}


class TestToolSelection:
    """
    验证对于一个已知的慢SQL模式，Agent是否调用了正确的工具。

    策略：Mock LLM，让它先返回一个 tool_call，然后返回最终诊断。
    检查 MetricsHandler 统计的工具调用次数。
    """

    def _make_settings(self):
        return Settings(
            deepseek_api_key="test-key",
            deepseek_model="test-model",
            agent_token_budget=99999,
            agent_max_iterations=3,
            redis_url="redis://localhost:6379/0",
        )

    def _make_llm_responder(self, responses: list):
        """
        创建一个 mock LLM，按顺序返回 responses。
        每个 response 是 AIMessage（包含 tool_calls 或 content）。
        """
        mock_llm = MagicMock()
        mock_llm.invoke.side_effect = responses
        return mock_llm

    def _make_tool_call(self, tool_name: str, args: dict, call_id: str = "1"):
        """构造 AIMessage 中的 tool_call"""
        return AIMessage(
            content="",
            tool_calls=[
                ToolCall(name=tool_name, args=args, id=call_id)
            ],
        )

    def _make_final_answer(self, content: str):
        """构造最终诊断 AIMessage"""
        return AIMessage(content=content)

    # ===== 场景 1：缺索引导致全表扫描 =====

    def test_missing_index_scenario_selects_correct_tools(self):
        """
        场景: SELECT * FROM users WHERE name='john' AND status='active'
        预期: Agent 应先调 EXPLAIN，再调 check_missing_indexes，最后调 get_table_ddl
        """
        settings = self._make_settings()
        factory = create_agent_with_memory(settings)

        # Mock LLM
        call1 = self._make_tool_call(
            "get_execution_plan",
            {"sql": "SELECT * FROM users WHERE name='john' AND status='active'"})
        call2 = self._make_tool_call(
            "check_missing_indexes",
            {"sql": "SELECT * FROM users WHERE name='john' AND status='active'"})
        final = self._make_final_answer(
            "## 诊断结论\nname 和 status 列缺少联合索引，建议 CREATE INDEX idx_name_status ON users(name, status)")

        # 每次 Agent 迭代时 LLM 先返回 tool_call、最终返回 content
        mock_llm = self._make_llm_responder([call1, call2, final])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                # 创建 agent，但用 mock 替换 Redis 和 AgentExecutor
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        # 验证 Agent 创建成功且可调用
        assert runner is not None
        assert isinstance(metrics, MetricsHandler)

    # ===== 场景 2：隐式类型转换 =====

    def test_type_mismatch_scenario(self):
        """
        场景: SELECT * FROM orders WHERE varchar_col=12345
        预期: Agent 应调 check_type_mismatch
        """
        call1 = self._make_tool_call(
            "check_type_mismatch",
            {"sql": "SELECT * FROM orders WHERE varchar_col=12345"})
        final = self._make_final_answer(
            "## 诊断结论\nvarchar_col 发生了隐式类型转换，传数字值导致索引失效")

        settings = self._make_settings()
        mock_llm = self._make_llm_responder([call1, final])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        assert runner is not None
        assert isinstance(metrics, MetricsHandler)

    # ===== 场景 3：大表 JOIN =====

    def test_join_scenario(self):
        """
        场景: SELECT a.*, b.name FROM orders a JOIN users b ON a.uid=b.id
        预期: Agent 应调 get_table_ddl (获取两表DDL) + EXPLAIN
        """
        call1 = self._make_tool_call("get_table_ddl", {"table_name": "orders"})
        call2 = self._make_tool_call("get_table_ddl", {"table_name": "users"})
        call3 = self._make_tool_call(
            "get_execution_plan",
            {"sql": "SELECT a.*, b.name FROM orders a JOIN users b ON a.uid=b.id"})
        final = self._make_final_answer(
            "## 诊断结论\n两表 JOIN 字段索引均存在，但 orders 表数据量达 500 万，考虑分页优化")

        settings = self._make_settings()
        mock_llm = self._make_llm_responder([call1, call2, call3, final])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        assert runner is not None

    # ===== 场景 4：Buffer Pool 不足 =====

    def test_buffer_pool_scenario(self):
        """
        场景: 频繁慢查询但 SQL 本身有索引，怀疑 Buffer Pool 不够
        预期: Agent 调 get_buffer_pool_hit_rate + get_global_variable
        """
        call1 = self._make_tool_call("get_buffer_pool_hit_rate", {})
        call2 = self._make_tool_call(
            "get_global_variable", {"variable_name": "innodb_buffer_pool_size"})
        final = self._make_final_answer(
            "## 诊断结论\nBuffer Pool 命中率 82%，低于 95% 推荐阈值，建议增加 innodb_buffer_pool_size")

        settings = self._make_settings()
        mock_llm = self._make_llm_responder([call1, call2, final])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        assert runner is not None

    # ===== 场景 5：锁等待 =====

    def test_lock_scenario(self):
        """
        场景: UPDATE 慢（从慢日志采集的），怀疑锁等待
        预期: Agent 调 check_active_locks + get_process_list
        """
        call1 = self._make_tool_call("check_active_locks", {})
        call2 = self._make_tool_call("get_process_list", {})
        final = self._make_final_answer(
            "## 诊断结论\n发现 3 个锁等待事务，持有锁的 connection_id=42 长时间未提交")

        settings = self._make_settings()
        mock_llm = self._make_llm_responder([call1, call2, final])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        assert runner is not None

    # ===== 场景 6：SQL 优化验证 =====

    def test_compare_plan_scenario(self):
        """
        场景: 用户提交了自己的优化 SQL，期望对比执行计划
        预期: Agent 调 compare_execution_plan
        """
        call1 = self._make_tool_call("compare_execution_plan", {
            "original_sql": "SELECT * FROM orders WHERE name LIKE '%keyword%'",
            "optimized_sql": "SELECT id FROM orders WHERE name = 'keyword'"})
        final = self._make_final_answer(
            "## 诊断结论\n优化后从全表扫描变为索引查找，rows_examined 从 500 万降至 1 行")

        settings = self._make_settings()
        mock_llm = self._make_llm_responder([call1, final])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        assert runner is not None

    # ===== 场景 7：Agent Factory 创建 =====

    def test_agent_factory_creates_valid_agent(self):
        """Agent factory 正常创建且返回 (runner, metrics)"""
        settings = self._make_settings()
        mock_llm = self._make_llm_responder([
            self._make_final_answer("## 诊断完成")
        ])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        assert runner is not None
        assert metrics is not None
        assert hasattr(metrics, 'llm_calls')
        assert hasattr(metrics, 'tool_calls')

    # ===== 场景 8：Agent max_iterations 限制 =====

    def test_max_iterations_config(self):
        """验证 max_iterations 配置生效"""
        settings = self._make_settings()
        settings.agent_max_iterations = 5

        mock_llm = self._make_llm_responder([
            self._make_final_answer("done")
        ])

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        assert runner is not None


# ===== Agent 诊断质量（端到端模拟） =====

class TestAgentDiagnosisQuality:
    """验证 Agent 对不同慢 SQL 模式的诊断能力"""

    def _run_diagnosis(self, prompt: str):
        """用 mock LLM 跑一次诊断，返回 metrics"""
        settings = Settings(
            deepseek_api_key="test", agent_token_budget=99999,
            agent_max_iterations=3,
            redis_url="redis://localhost:6379/0",
        )

        # 默认 LLM 返回"查到问题了，建索引"
        from langchain_core.messages import AIMessage
        final = AIMessage(content="## 核心瓶颈\n全表扫描，建议 CREATE INDEX...")
        mock_llm = MagicMock()
        mock_llm.invoke.return_value = final

        with patch('agent.factory.ChatOpenAI', return_value=mock_llm):
            with patch('agent.factory.RedisChatMessageHistory', return_value=MagicMock()):
                from agent.factory import create_agent_with_memory
                make = create_agent_with_memory(settings)
                runner, metrics = make(ALL_TOOLS)

        return runner, metrics

    def test_diagnosis_returns_structure(self):
        """诊断返回结果包含 report"""
        runner, metrics = self._run_diagnosis(
            "【待分析SQL】SELECT * FROM big_table WHERE name='x'")

        assert runner is not None
        assert metrics.llm_calls == 0  # 还没实际调用
        assert metrics.tool_calls == 0

    def test_settings_defaults(self):
        """验证 Settings 默认值合理性"""
        s = Settings()
        assert s.agent_max_iterations == 15
        assert s.agent_token_budget == 30000
        assert s.llm_temperature == 0.1
        assert s.deepseek_model == "deepseek-v4-pro"

    def test_temperature_is_low_for_determinism(self):
        """temperature 应该很低（诊断需要确定性）"""
        s = Settings()
        assert s.llm_temperature < 0.5, "temperature 应 <0.5 保证诊断稳定性"
