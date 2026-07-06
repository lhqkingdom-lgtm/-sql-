"""Java 网关 HTTP 客户端——所有工具调用最终都走这里。"""
import httpx
from config import Settings


class DataClient:
    def __init__(self, settings: Settings):
        self._base = settings.java_gateway_base_url.rstrip("/")
        self._token = settings.java_gateway_token
        self._timeout = settings.java_gateway_timeout
        self._client = httpx.AsyncClient(
            headers={"X-Internal-Token": self._token},
            timeout=httpx.Timeout(self._timeout),
        )

    async def close(self):
        await self._client.aclose()

    async def get(self, path: str, **kwargs) -> str:
        return await self._request("GET", path, **kwargs)

    async def post(self, path: str, body: dict) -> str:
        return await self._request("POST", path, json=body)

    async def _request(self, method: str, path: str, **kwargs) -> str:
        url = f"{self._base}{path}"
        try:
            resp = await self._client.request(method, url, **kwargs)
            return resp.text
        except httpx.TimeoutException:
            return "⚠️ 数据服务暂时不可用（超时），请基于已有信息继续分析"
        except httpx.ConnectError:
            return "⚠️ 数据服务不可用（连接失败），请基于已有信息继续分析"
        except Exception as e:
            return f"⚠️ 工具调用异常，请基于已有信息继续分析"
