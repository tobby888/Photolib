"""浏览器登录：链接怎么拼、批准后令牌怎么落盘、没批准时怎么交代。"""

from __future__ import annotations

import httpx
import pytest

from photolib_mcp.device_login import await_approval, start_pairing
from photolib_mcp.errors import PhotoLibError
from photolib_mcp.session import PhotoLibSession

PAIRING = {
    "requestId": "req-123",
    "deviceCode": "device-secret",
    "userCode": "ABCD-EFGH",
    "verificationPath": "/mcp/authorize",
    "expiresIn": 600,
    "interval": 0,  # 测试里不要真的睡 2 秒
}


def _mount(session: PhotoLibSession, handler) -> None:
    session._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_start_pairing_builds_the_verification_link(session: PhotoLibSession) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/auth/mcp/authorizations"
        # 发起配对时还没有任何身份，不该带令牌。
        assert "Authorization" not in request.headers
        return httpx.Response(200, json={"data": PAIRING})

    _mount(session, handler)
    pairing = await start_pairing(session)

    assert pairing.verification_url == "https://photolib.test/mcp/authorize?request=req-123"
    assert pairing.user_code == "ABCD-EFGH"
    assert pairing.browser_opened is False  # 配置里关掉了自动打开


async def test_approval_stores_tokens(session: PhotoLibSession) -> None:
    polls = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/auth/mcp/authorizations":
            return httpx.Response(200, json={"data": PAIRING})
        polls["count"] += 1
        if polls["count"] == 1:
            return httpx.Response(200, json={"data": {"status": "PENDING"}})
        return httpx.Response(200, json={"data": {
            "status": "APPROVED", "accessToken": "access", "refreshToken": "refresh",
            "user": {"id": 7, "username": "meng", "displayName": "孟同学",
                     "permissionGroupName": "部长"},
        }})

    _mount(session, handler)
    pairing = await start_pairing(session)
    result = await await_approval(session, pairing)

    assert result.username == "meng"
    assert result.permission_group == "部长"
    stored = session.credentials()
    assert (stored.access_token, stored.refresh_token) == ("access", "refresh")
    # 落盘之后另起一个会话也要能读到，否则宿主重启就白登录了。
    assert PhotoLibSession(session.settings).credentials().access_token == "access"


async def test_denied_pairing_stops_immediately(session: PhotoLibSession) -> None:
    """被拒绝要当场报错，不能一直轮询到超时——那会让人以为是自己没点对。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/auth/mcp/authorizations":
            return httpx.Response(200, json={"data": PAIRING})
        return httpx.Response(403, json={"code": "FORBIDDEN", "message": "配对请求已被拒绝"})

    _mount(session, handler)
    pairing = await start_pairing(session)

    with pytest.raises(PhotoLibError) as caught:
        await await_approval(session, pairing)
    assert "拒绝" in str(caught.value)
    assert session.credentials() is None


async def test_timeout_message_keeps_the_link_and_code(session: PhotoLibSession) -> None:
    """等待超时不是失败：配对还在有效期内，报错里必须带着链接和配对码。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/auth/mcp/authorizations":
            return httpx.Response(200, json={"data": PAIRING})
        return httpx.Response(200, json={"data": {"status": "PENDING"}})

    _mount(session, handler)
    pairing = await start_pairing(session)

    with pytest.raises(PhotoLibError) as caught:
        await await_approval(session, pairing, timeout=0)
    message = str(caught.value)
    assert "ABCD-EFGH" in message
    assert "https://photolib.test/mcp/authorize?request=req-123" in message
