"""Pydantic Settings — 全部从环境变量读取，无 YAML 依赖。"""
import os
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # LLM
    deepseek_api_key: str = ""
    deepseek_model: str = "deepseek-v4-pro"
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    llm_temperature: float = 0.1
    llm_timeout: int = 120
    llm_max_retries: int = 1

    # Java Gateway
    java_gateway_base_url: str = "http://localhost:8080/api/data"
    java_gateway_token: str = "slow-sql-internal-token-v5"
    java_gateway_timeout: int = 10

    # Agent
    agent_max_iterations: int = 15
    agent_token_budget: int = 30000
    agent_memory_window: int = 20

    # Redis
    redis_url: str = "redis://:123456@localhost:6379/2"

    # RabbitMQ
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/"

    model_config = {"env_prefix": "", "case_sensitive": False}


def load_settings() -> Settings:
    return Settings(
        deepseek_api_key=os.getenv("DEEPSEEK_API_KEY", ""),
    )
