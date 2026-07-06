"""Pydantic 数据模型"""
from pydantic import BaseModel


class DiagnosisTask(BaseModel):
    task_id: str
    session_id: str = ""
    instance_id: str
    project_code: str = ""
    enriched_prompt: str
    fingerprint: str = ""
    source: str = "manual"
    timestamp: str = ""


class DiagnosisResult(BaseModel):
    task_id: str
    status: str   # completed | failed
    report: str = ""
    error: str = ""
    duration_ms: int = 0
    tool_call_count: int = 0


