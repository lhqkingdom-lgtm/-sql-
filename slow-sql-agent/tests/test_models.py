"""测试数据模型——camelCase 别名解析 + 序列化"""
import json
from models import DiagnosisTask, DiagnosisResult


class TestDiagnosisTask:
    """Java 端发 camelCase → Python snake_case 别名"""

    def test_deserialize_from_camel_case(self):
        body = json.dumps({
            "taskId": "t-001",
            "sessionId": "s-001",
            "instanceId": "tc-dev-mysql",
            "projectCode": "tongcheng-club",
            "enrichedPrompt": "【待分析SQL】\nSELECT * FROM orders",
            "fingerprint": "abc123",
            "source": "manual",
            "timestamp": "2026-07-09T10:00:00"
        })
        task = DiagnosisTask(**json.loads(body))
        assert task.task_id == "t-001"
        assert task.session_id == "s-001"
        assert task.instance_id == "tc-dev-mysql"
        assert task.project_code == "tongcheng-club"
        assert task.enriched_prompt == "【待分析SQL】\nSELECT * FROM orders"
        assert task.fingerprint == "abc123"
        assert task.source == "manual"
        assert task.timestamp == "2026-07-09T10:00:00"

    def test_deserialize_minimal_fields(self):
        """最少必填字段（taskId + instanceId + enrichedPrompt）"""
        body = json.dumps({
            "taskId": "t-min",
            "instanceId": "tc-dev-mysql",
            "enrichedPrompt": "SELECT 1",
        })
        task = DiagnosisTask(**json.loads(body))
        assert task.task_id == "t-min"
        assert task.instance_id == "tc-dev-mysql"
        assert task.enriched_prompt == "SELECT 1"
        # 可选字段用默认值
        assert task.session_id == ""
        assert task.project_code == ""
        assert task.fingerprint == ""
        assert task.source == "manual"
        assert task.timestamp == ""

    def test_deserialize_extra_fields_ignored(self):
        """多余字段被忽略（来自 Python 端没用到的 Java 未来字段）"""
        body = json.dumps({
            "taskId": "t-extra",
            "instanceId": "tc-dev-mysql",
            "enrichedPrompt": "SELECT 1",
            "extraField": "should-be-ignored",
            "modelRoute": "plus",
        })
        task = DiagnosisTask(**json.loads(body))
        assert task.task_id == "t-extra"

    def test_deserialize_missing_required_field(self):
        """缺少必填字段 taskId → ValidationError"""
        import pydantic
        try:
            DiagnosisTask(**json.loads('{"instanceId":"x","enrichedPrompt":"x"}'))
            assert False, "应该抛 ValidationError"
        except pydantic.ValidationError:
            pass


class TestDiagnosisResult:

    def test_completed_result(self):
        r = DiagnosisResult(
            taskId="t-001", status="completed",
            report="## 诊断报告\n...", duration_ms=5000, tool_call_count=3)
        d = r.model_dump(by_alias=True)
        assert d["taskId"] == "t-001"
        assert d["status"] == "completed"
        assert d["report"] == "## 诊断报告\n..."
        assert d["duration_ms"] == 5000
        assert d["tool_call_count"] == 3
        assert d["error"] == ""

    def test_failed_result(self):
        r = DiagnosisResult(
            taskId="t-002", status="failed",
            error="诊断异常: LLMTimeoutError", duration_ms=30000, tool_call_count=0)
        assert r.task_id == "t-002"
        assert r.status == "failed"
        assert r.error == "诊断异常: LLMTimeoutError"
        assert r.report == ""  # 默认空字符串

    def test_serialize_to_json_with_alias(self):
        """model_dump(by_alias=True) 输出 camelCase，Java 端可以解析"""
        r = DiagnosisResult(taskId="t-json", status="completed", report="# R")
        d = r.model_dump(by_alias=True)
        assert "taskId" in d
        assert d["taskId"] == "t-json"
        assert d["status"] == "completed"
