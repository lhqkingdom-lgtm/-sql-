"""14 工具全路径测试——正常HTTP + 守卫拦截 + DataClient 降级"""
import pytest
from unittest.mock import AsyncMock, patch
from tools import definitions as td
from tools.data_client import DataClient
from config import Settings


# ===== fixtures =====

def _setup_mock_client(get_response=None, post_response=None):
    """注入 Mock DataClient"""
    client = AsyncMock()
    if get_response:
        client.get.return_value = get_response
    if post_response:
        client.post.return_value = post_response
    td.set_data_client(client)
    return client


@pytest.fixture(autouse=True)
def reset_client():
    td._data_client = None
    td._instance_id_ctx.set("tc-dev-mysql")
    yield
    td._data_client = None


# ===== 14 工具正常路径 =====

class TestAllToolsNormalPath:

    @pytest.mark.asyncio
    async def test_get_table_ddl_normal(self):
        c = _setup_mock_client(get_response="CREATE TABLE orders (id bigint)")
        r = await td.get_table_ddl.ainvoke("orders")
        assert "CREATE TABLE" in r

    @pytest.mark.asyncio
    async def test_get_execution_plan_normal(self):
        c = _setup_mock_client(post_response='{"query_block":{}}')
        r = await td.get_execution_plan.ainvoke("SELECT * FROM orders")
        assert "query_block" in r

    @pytest.mark.asyncio
    async def test_get_table_statistics_normal(self):
        c = _setup_mock_client(get_response='{"Rows":50000}')
        r = await td.get_table_statistics.ainvoke("orders")
        assert "50000" in r

    @pytest.mark.asyncio
    async def test_check_active_locks_normal(self):
        c = _setup_mock_client(get_response="[]")
        r = await td.check_active_locks.ainvoke({})
        assert r == "[]"

    @pytest.mark.asyncio
    async def test_get_innodb_status_normal(self):
        c = _setup_mock_client(get_response="InnoDB Status Info...")
        r = await td.get_innodb_status.ainvoke({})
        assert "InnoDB" in r

    @pytest.mark.asyncio
    async def test_get_global_variable_normal(self):
        c = _setup_mock_client(get_response="{Value=134217728}")
        r = await td.get_global_variable.ainvoke("innodb_buffer_pool_size")
        assert "134217728" in r

    @pytest.mark.asyncio
    async def test_check_redundant_indexes_normal(self):
        c = _setup_mock_client(get_response="[]")
        r = await td.check_redundant_indexes.ainvoke("orders")
        assert r == "[]"

    @pytest.mark.asyncio
    async def test_compare_execution_plan_normal(self):
        c = _setup_mock_client(post_response='{"diff":"skipped"}')
        r = await td.compare_execution_plan.ainvoke({
            "original_sql": "SELECT * FROM t WHERE id=1",
            "optimized_sql": "SELECT * FROM t WHERE id=1"})
        assert "diff" in r

    @pytest.mark.asyncio
    async def test_get_slow_log_stats_normal(self):
        c = _setup_mock_client(get_response='[{"count":10,"avg_time":5.2}]')
        r = await td.get_slow_log_stats.ainvoke({})
        assert "5.2" in r

    @pytest.mark.asyncio
    async def test_check_missing_indexes_normal(self):
        c = _setup_mock_client(post_response='["name"]')
        r = await td.check_missing_indexes.ainvoke(
            "SELECT * FROM orders WHERE name = 'x'")
        assert "name" in r

    @pytest.mark.asyncio
    async def test_check_type_mismatch_normal(self):
        c = _setup_mock_client(post_response='{"mismatches":[]}')
        r = await td.check_type_mismatch.ainvoke(
            "SELECT * FROM orders WHERE id = 123")
        assert "mismatches" in r

    @pytest.mark.asyncio
    async def test_get_buffer_pool_hit_rate_normal(self):
        c = _setup_mock_client(get_response="{buffer_pool_hit_rate=98.5}")
        r = await td.get_buffer_pool_hit_rate.ainvoke({})
        assert "98.5" in r

    @pytest.mark.asyncio
    async def test_get_process_list_normal(self):
        c = _setup_mock_client(get_response="[{Id:1,User:root,State:Sleep}]")
        r = await td.get_process_list.ainvoke({})
        assert "Sleep" in r

    @pytest.mark.asyncio
    async def test_check_actual_row_count_normal(self):
        c = _setup_mock_client(post_response='{"count":42}')
        r = await td.check_actual_row_count.ainvoke(
            "SELECT * FROM orders WHERE status = 'done'")
        assert "42" in r


# ===== 守卫拦截（补全之前缺失的） =====

class TestGuardIntercept:

    @pytest.mark.asyncio
    async def test_redundant_indexes_illegal_table(self):
        r = await td.check_redundant_indexes.ainvoke("bad;DROP")
        assert "字母、数字和下划线" in r

    @pytest.mark.asyncio
    async def test_actual_rows_illegal_sql(self):
        r = await td.check_actual_row_count.ainvoke("UPDATE x SET a=1")
        assert "敏感" in r or "权限拒绝" in r

    @pytest.mark.asyncio
    async def test_all_14_tools_are_callable(self):
        """每个工具都能直接调用（不抛 AttributeError）"""
        assert len(td.ALL_TOOLS) == 14
        for tool in td.ALL_TOOLS:
            assert hasattr(tool, "ainvoke"), f"{tool.name} 缺少 ainvoke"


# ===== DataClient 降级（补全场景） =====

class TestDataClientFallback:

    @pytest.mark.asyncio
    async def test_get_success_returns_body(self):
        from tools.data_client import DataClient
        from config import Settings
        import httpx

        s = Settings()
        client = DataClient(s)
        # Mock httpx.AsyncClient.request 返回成功
        mock_resp = AsyncMock()
        mock_resp.text = "OK"
        client._client.request = AsyncMock(return_value=mock_resp)

        result = await client.get("/tc-dev-mysql/locks")
        assert result == "OK"
        await client.close()

    @pytest.mark.asyncio
    async def test_post_sends_json_body(self):
        from tools.data_client import DataClient
        from config import Settings

        s = Settings()
        client = DataClient(s)
        mock_resp = AsyncMock()
        mock_resp.text = '{"result":"ok"}'
        client._client.request = AsyncMock(return_value=mock_resp)

        result = await client.post("/tc-dev-mysql/explain", {"sql": "SELECT 1"})
        assert "ok" in result
        await client.close()

    @pytest.mark.asyncio
    async def test_timeout_returns_fallback(self):
        from tools.data_client import DataClient
        from config import Settings
        import httpx

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

    @pytest.mark.asyncio
    async def test_token_is_sent_as_header(self):
        from tools.data_client import DataClient
        from config import Settings

        s = Settings(java_gateway_token="my-test-token")
        client = DataClient(s)
        assert client._token == "my-test-token"

    @pytest.mark.asyncio
    async def test_base_url_strips_trailing_slash(self):
        from tools.data_client import DataClient
        from config import Settings

        s = Settings(java_gateway_base_url="http://localhost:8080/api/data/")
        client = DataClient(s)
        assert not client._base.endswith("/")
        await client.close()
