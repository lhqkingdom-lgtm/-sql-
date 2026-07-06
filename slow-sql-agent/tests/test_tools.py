"""工具层单元测试——验证 guard 逻辑，Mock HTTP。"""
import pytest
from unittest.mock import AsyncMock, patch
from tools import definitions as td


class TestToolGuards:
    """只测 Python 侧的 guard，不调真实 Java"""

    @pytest.mark.asyncio
    async def test_ddl_legal_table(self):
        td._data_client = AsyncMock()
        td._data_client.get.return_value = "CREATE TABLE..."
        td._instance_id_ctx.set("tc-dev-mysql")
        result = await td.get_table_ddl.ainvoke("orders")
        assert "CREATE TABLE" in result

    @pytest.mark.asyncio
    async def test_ddl_illegal_table(self):
        td._data_client = AsyncMock()
        result = await td.get_table_ddl.ainvoke("orders;DROP")
        assert "字母、数字和下划线" in result

    @pytest.mark.asyncio
    async def test_explain_dangerous_sql(self):
        result = await td.get_execution_plan.ainvoke("DELETE FROM orders")
        assert "权限拒绝" in result or "敏感" in result

    @pytest.mark.asyncio
    async def test_explain_normal_sql(self):
        td._data_client = AsyncMock()
        td._data_client.post.return_value = '{"query_block":{}}'
        td._instance_id_ctx.set("tc-dev-mysql")
        result = await td.get_execution_plan.ainvoke("SELECT * FROM orders")
        assert "query_block" in result

    @pytest.mark.asyncio
    async def test_variable_allowed(self):
        td._data_client = AsyncMock()
        td._data_client.get.return_value = "{Value=134217728}"
        td._instance_id_ctx.set("tc-dev-mysql")
        result = await td.get_global_variable.ainvoke("innodb_buffer_pool_size")
        assert "134217728" in result

    @pytest.mark.asyncio
    async def test_variable_denied(self):
        result = await td.get_global_variable.ainvoke("datadir")
        assert "不在可查询范围内" in result

    @pytest.mark.asyncio
    async def test_stats_reject_illegal_table(self):
        result = await td.get_table_statistics.ainvoke("bad--table")
        assert "字母、数字和下划线" in result

    @pytest.mark.asyncio
    async def test_missing_indexes_dangerous_sql(self):
        result = await td.check_missing_indexes.ainvoke("DROP TABLE x")
        assert "敏感" in result or "权限拒绝" in result

    @pytest.mark.asyncio
    async def test_type_mismatch_dangerous_sql(self):
        result = await td.check_type_mismatch.ainvoke("ALTER TABLE x")
        assert "敏感" in result or "权限拒绝" in result

    @pytest.mark.asyncio
    async def test_compare_blocks_dangerous_sql(self):
        result = await td.compare_execution_plan.ainvoke({
            "original_sql": "SELECT 1",
            "optimized_sql": "DELETE FROM x"})
        assert "敏感" in result or "权限拒绝" in result

    @pytest.mark.asyncio
    async def test_actual_rows_dangerous(self):
        result = await td.check_actual_row_count.ainvoke("INSERT INTO x VALUES(1)")
        assert "敏感" in result or "权限拒绝" in result

    @pytest.mark.asyncio
    async def test_all_14_tools_registered(self):
        assert len(td.ALL_TOOLS) == 14


class TestDataClient:
    """测试 HTTP 降级——超时/连接失败不抛异常"""

    @pytest.mark.asyncio
    async def test_timeout_returns_fallback(self):
        from tools.data_client import DataClient
        from config import Settings
        s = Settings(java_gateway_timeout=1, java_gateway_base_url="http://192.0.2.1:9999/api/data")
        client = DataClient(s)
        result = await client.get("/tc-dev-mysql/ddl?table=orders")
        assert "⚠️" in result
        await client.close()

    @pytest.mark.asyncio
    async def test_connect_error_returns_fallback(self):
        from tools.data_client import DataClient
        from config import Settings
        s = Settings(java_gateway_timeout=1, java_gateway_base_url="http://127.0.0.1:19999/api/data")
        client = DataClient(s)
        result = await client.get("/tc-dev-mysql/locks")
        assert "⚠️" in result
        await client.close()
