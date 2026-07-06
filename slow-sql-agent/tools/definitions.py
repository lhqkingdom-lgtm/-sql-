"""14 个 LangChain @tool 工具——Python 侧安全守卫 + HTTP 调 Java 网关。"""
import contextvars
import re
from langchain_core.tools import tool

# ===== 全局状态 =====
_data_client = None
_instance_id_ctx = contextvars.ContextVar("instance_id", default=None)

# ===== 安全规则 =====
SAFE_TABLE = re.compile(r"^[A-Za-z0-9_]+$")
DANGEROUS_SQL = re.compile(
    r"\b(update|delete|drop|insert|truncate|alter)\b", re.IGNORECASE)
ALLOWED_VARS = {
    "innodb_buffer_pool_size", "max_connections", "thread_cache_size",
    "query_cache_size", "tmp_table_size", "max_heap_table_size",
    "sort_buffer_size", "join_buffer_size",
}


def set_data_client(client):
    global _data_client
    _data_client = client


def _iid():
    return _instance_id_ctx.get()


# ===== 14 个工具 =====

@tool
async def get_table_ddl(table_name: str) -> str:
    """获取 MySQL 表结构 DDL。入参 tableName 只能是纯表名，不含反引号。这是排查的第一步。"""
    if not SAFE_TABLE.match(table_name):
        return "错误：tableName 只能包含字母、数字和下划线。"
    return await _data_client.get(f"/{_iid()}/ddl?table={table_name}")


@tool
async def get_execution_plan(sql: str) -> str:
    """获取 MySQL SQL 的 JSON 执行计划（EXPLAIN FORMAT=JSON）。只允许只读 SELECT 语句。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：权限拒绝！该 SQL 包含敏感的写入或 DDL 关键字。"
    return await _data_client.post(f"/{_iid()}/explain", {"sql": sql})


@tool
async def get_table_statistics(table_name: str) -> str:
    """获取表的统计信息和所有索引的基数(Cardinality)。当怀疑 MySQL 选错索引时必须调用。"""
    if not SAFE_TABLE.match(table_name):
        return "错误：tableName 只能包含字母、数字和下划线。"
    return await _data_client.get(f"/{_iid()}/stats/{table_name}")


@tool
async def check_active_locks() -> str:
    """检查当前数据库中是否存在行锁等待或长时间未提交的事务。无入参。"""
    return await _data_client.get(f"/{_iid()}/locks")


@tool
async def get_innodb_status() -> str:
    """【重型探针】获取 InnoDB 引擎完整状态（截断 10000 字符）。仅在执行计划和锁检查均无法解释时调用。"""
    return await _data_client.get(f"/{_iid()}/innodb")


@tool
async def get_global_variable(variable_name: str) -> str:
    """获取 MySQL 全局配置变量。仅允许查询性能相关变量（如 innodb_buffer_pool_size）。"""
    v = variable_name.strip().lower()
    if v not in ALLOWED_VARS:
        return f"错误：变量 '{variable_name}' 不在可查询范围内。"
    return await _data_client.get(f"/{_iid()}/variable?name={v}")


@tool
async def check_redundant_indexes(table_name: str) -> str:
    """检查表的冗余索引。当怀疑写入性能问题或表索引数量≥3时调用。"""
    if not SAFE_TABLE.match(table_name):
        return "错误：tableName 只能包含字母、数字和下划线。"
    return await _data_client.get(f"/{_iid()}/indexes/redundant?table={table_name}")


@tool
async def compare_execution_plan(original_sql: str, optimized_sql: str) -> str:
    """对比两条 SQL 的执行计划。用于验证优化效果。"""
    if DANGEROUS_SQL.search(original_sql) or DANGEROUS_SQL.search(optimized_sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(
        f"/{_iid()}/explain/compare",
        {"originalSql": original_sql, "optimizedSql": optimized_sql})


@tool
async def get_slow_log_stats() -> str:
    """获取慢日志统计。按 SQL 指纹聚合查询次数和平均耗时。无入参。"""
    return await _data_client.get(f"/{_iid()}/slowlog")


@tool
async def check_missing_indexes(sql: str) -> str:
    """检查 SQL 中 WHERE/JOIN/ORDER BY 的列是否已建索引。自动提取列名并对比表的实际索引。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(f"/{_iid()}/indexes/missing", {"sql": sql})


@tool
async def check_type_mismatch(sql: str) -> str:
    """检查 SQL 的 WHERE 条件值类型是否与表字段类型匹配。检测隐式类型转换（VARCHAR 传数字是常见索引杀手）。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(f"/{_iid()}/type-mismatch", {"sql": sql})


@tool
async def get_buffer_pool_hit_rate() -> str:
    """获取 InnoDB Buffer Pool 命中率。低于 95% 说明内存不足。无入参。"""
    return await _data_client.get(f"/{_iid()}/bufferpool")


@tool
async def get_process_list() -> str:
    """获取当前 MySQL 连接列表和状态分布。当怀疑连接数打满或存在大量 Sleep 连接时调用。无入参。"""
    return await _data_client.get(f"/{_iid()}/processlist")


@tool
async def check_actual_row_count(sql: str) -> str:
    """获取 SQL 的 WHERE 条件真实命中行数（SELECT COUNT(*)）。与 EXPLAIN 估算行数对比，偏差>10 倍说明统计信息过时。"""
    if DANGEROUS_SQL.search(sql):
        return "错误：SQL 包含敏感关键字。"
    return await _data_client.post(f"/{_iid()}/actual-rows", {"sql": sql})


# ===== 工具列表 =====
ALL_TOOLS = [
    get_table_ddl, get_execution_plan, get_table_statistics,
    check_active_locks, get_innodb_status, get_global_variable,
    check_redundant_indexes, compare_execution_plan, get_slow_log_stats,
    check_missing_indexes, check_type_mismatch,
    get_buffer_pool_hit_rate, get_process_list,
    check_actual_row_count,
]
