"""Agent 工厂：每次 task 创建独立的 Callback 实例，防跨 task 污染。"""
from langchain_classic.agents import AgentExecutor, create_openai_tools_agent
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_community.chat_message_histories import RedisChatMessageHistory
from langchain_openai import ChatOpenAI

from config import Settings
from agent.callbacks import TokenBudgetHandler, MetricsHandler, RepeatGuardHandler


def create_agent_with_memory(settings: Settings):
    """返回 (get_agent, get_metrics) ——每个 task 独立"""

    llm = ChatOpenAI(
        api_key=settings.deepseek_api_key,
        model=settings.deepseek_model,
        temperature=settings.llm_temperature,
        base_url=settings.deepseek_base_url,
        timeout=settings.llm_timeout,
        max_retries=settings.llm_max_retries,
    )

    prompt = ChatPromptTemplate.from_messages([
        ("system", "{system_prompt}"),
        MessagesPlaceholder(variable_name="chat_history"),
        ("human", "{input}"),
        MessagesPlaceholder(variable_name="agent_scratchpad"),
    ])

    def _get_session_history(session_id: str):
        return RedisChatMessageHistory(
            session_id=session_id,
            url=settings.redis_url,
            key_prefix="diagnosis:memory:",
            ttl=3600,
        )

    def make_agent(tools) -> tuple:
        """每次调用创建全新 callback 实例——不跨 task 共享"""
        token_handler = TokenBudgetHandler(settings.agent_token_budget)
        metrics = MetricsHandler()
        repeat = RepeatGuardHandler()

        llm_with_tools = llm.bind_tools(tools)
        agent = create_openai_tools_agent(llm_with_tools, tools, prompt)
        executor = AgentExecutor(
            agent=agent, tools=tools,
            max_iterations=settings.agent_max_iterations,
            early_stopping_method="generate",
            handle_parsing_errors=True,
            callbacks=[token_handler, metrics, repeat],
            return_intermediate_steps=False,
            verbose=False,
        )
        runner = RunnableWithMessageHistory(
            executor,
            _get_session_history,
            input_messages_key="input",
            history_messages_key="chat_history",
        )
        return runner, metrics

    return make_agent
