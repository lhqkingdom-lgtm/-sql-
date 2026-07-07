"""Pydantic 数据模型——Java 端发 camelCase，用 alias 兼容"""
from pydantic import BaseModel, Field


class DiagnosisTask(BaseModel):
    task_id: str = Field(alias="taskId")
    session_id: str = Field(default="", alias="sessionId")
    instance_id: str = Field(alias="instanceId")
    project_code: str = Field(default="", alias="projectCode")
    enriched_prompt: str = Field(alias="enrichedPrompt")
    fingerprint: str = Field(default="", alias="fingerprint")
    source: str = Field(default="manual", alias="source")
    timestamp: str = Field(default="", alias="timestamp")

    model_config = {"populate_by_name": True}


class DiagnosisResult(BaseModel):
    task_id: str = Field(alias="taskId")
    status: str
    report: str = ""
    error: str = ""
    duration_ms: int = 0
    tool_call_count: int = 0

    model_config = {"populate_by_name": True}


