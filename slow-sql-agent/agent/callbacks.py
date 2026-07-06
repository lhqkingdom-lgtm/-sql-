"""Agent Callback 系统——Token 预算 + 指标收集 + 死循环防护。"""
import time
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.outputs import LLMResult


class TokenBudgetExceeded(Exception):
    """Token 预算耗尽——Agent 提前终止"""
    pass


class TokenBudgetHandler(BaseCallbackHandler):
    """Token 预算控制：超限时抛异常终止 Agent"""

    def __init__(self, budget: int = 30000):
        self.budget = budget
        self.used = 0

    def on_llm_end(self, response: LLMResult, **kwargs) -> None:
        usage = response.llm_output.get("token_usage", {}) if response.llm_output else {}
        self.used += usage.get("total_tokens", 0)
        if self.used > self.budget:
            raise TokenBudgetExceeded(
                f"Token 预算耗尽: {self.used}/{self.budget}")


class MetricsHandler(BaseCallbackHandler):
    """收集诊断指标：LLM 调用次数、工具调用次数、耗时、错误"""

    def __init__(self):
        self.llm_calls = 0
        self.tool_calls = 0
        self.errors: list[str] = []
        self.start_time = time.time()

    @property
    def elapsed_ms(self) -> int:
        return int((time.time() - self.start_time) * 1000)

    def on_llm_start(self, *args, **kwargs) -> None:
        self.llm_calls += 1

    def on_tool_start(self, *args, **kwargs) -> None:
        self.tool_calls += 1

    def on_tool_error(self, error: Exception, **kwargs) -> None:
        self.errors.append(str(error)[:200])


class RepeatGuardHandler(BaseCallbackHandler):
    """防死循环：检测连续 3 次同工具+同参数的调用"""

    def __init__(self):
        self._history: list[str] = []  # 最近 3 次的 "{tool_name}:{args_json}"

    def on_tool_start(self, serialized, input_str: str, **kwargs) -> None:
        tool_name = serialized.get("name", "unknown")
        key = f"{tool_name}:{input_str}"
        self._history.append(key)
        if len(self._history) > 3:
            self._history.pop(0)
        if len(self._history) == 3 and len(set(self._history)) == 1:
            raise RepeatGuardTripped(
                f"检测到死循环：{tool_name} 连续调用 3 次相同参数，请基于已有数据诊断")


class RepeatGuardTripped(Exception):
    """死循环检测触发"""
    pass
