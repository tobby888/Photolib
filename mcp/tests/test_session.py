"""会话层：拆信封、丢空参数、401 自动续期、失败时的报错。"""

from __future__ import annotations

import httpx
import pytest

from photolib_mcp.credentials import StoredCredentials
from photolib_mcp.errors import NotAuthenticatedError, PermissionDeniedError, PhotoLibError
from photolib_mcp.session import PhotoLibSession


def _mount(session: PhotoLibSession, handler) -> None:
    session._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_request_unwraps_the_envelope(session: PhotoLibSession) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/projects"
        return httpx.Response(200, json={"code": "OK", "message": "", "data": {"items": []}})

    session.remember(StoredCredentials(access_token="a", refresh_token="r"))
    _mount(session, handler)

    assert await session.request("GET", "/projects") == {"items": []}


async def test_none_params_are_dropped(session: PhotoLibSession) -> None:
    """不传的筛选项必须整条消失，不能变成空串——那是"筛选成空值"，是另一回事。"""
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(dict(request.url.params))
        return httpx.Response(200, json={"data": []})

    session.remember(StoredCredentials(access_token="a", refresh_token="r"))
    _mount(session, handler)

    await session.request("GET", "/photos", params={"keyword": None, "page": 1,
                                                    "favoritesOnly": False})

    assert seen == {"page": "1", "favoritesOnly": "false"}


async def test_401_triggers_one_refresh_then_retries(session: PhotoLibSession) -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(f"{request.method} {request.url.path}")
        if request.url.path == "/api/v1/auth/mcp/token/refresh":
            return httpx.Response(200, json={"data": {
                "accessToken": "fresh", "refreshToken": "rotated",
                "user": {"id": 1, "username": "u", "displayName": "成员"},
            }})
        if request.headers.get("Authorization") == "Bearer fresh":
            return httpx.Response(200, json={"data": "ok"})
        return httpx.Response(401, json={"code": "UNAUTHORIZED", "message": "令牌失效"})

    session.remember(StoredCredentials(access_token="stale", refresh_token="r"))
    _mount(session, handler)

    assert await session.request("GET", "/auth/me") == "ok"
    assert calls == [
        "GET /api/v1/auth/me",
        "POST /api/v1/auth/mcp/token/refresh",
        "GET /api/v1/auth/me",
    ]
    # 轮换后的刷新令牌要落盘，否则下一次续期会拿着已经作废的那份去换。
    assert session.credentials().refresh_token == "rotated"


async def test_failed_refresh_clears_credentials(session: PhotoLibSession) -> None:
    """续不动就是真的没登录了：留着一份用不了的令牌只会让后面每一次调用都报含糊的错。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/auth/mcp/token/refresh":
            return httpx.Response(401, json={"code": "UNAUTHORIZED", "message": "会话已失效"})
        return httpx.Response(401, json={"code": "UNAUTHORIZED", "message": "令牌失效"})

    session.remember(StoredCredentials(access_token="stale", refresh_token="gone"))
    _mount(session, handler)

    with pytest.raises(NotAuthenticatedError):
        await session.request("GET", "/auth/me")
    assert session.credentials() is None


async def test_missing_credentials_says_how_to_log_in(session: PhotoLibSession) -> None:
    with pytest.raises(NotAuthenticatedError) as caught:
        await session.request("GET", "/auth/me")
    assert "photolib_login" in str(caught.value)


async def test_403_is_reported_as_a_permission_problem(session: PhotoLibSession) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(403, json={"code": "FORBIDDEN", "message": "无权执行该操作"})

    session.remember(StoredCredentials(access_token="a", refresh_token="r"))
    _mount(session, handler)

    with pytest.raises(PermissionDeniedError):
        await session.request("POST", "/projects", json_body={})


async def test_validation_details_reach_the_caller(session: PhotoLibSession) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={
            "code": "VALIDATION_ERROR", "message": "参数不合法",
            "details": [{"field": "deadline", "message": "必须是将来的时间"}],
        })

    session.remember(StoredCredentials(access_token="a", refresh_token="r"))
    _mount(session, handler)

    with pytest.raises(PhotoLibError) as caught:
        await session.request("POST", "/projects/1/requests", json_body={})
    assert "deadline" in str(caught.value)
    assert "必须是将来的时间" in str(caught.value)


async def test_html_response_hints_at_a_wrong_base_url(session: PhotoLibSession) -> None:
    """指到前端而不是后端是最常见的配置错误，报错要说得出这一点。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<!doctype html><title>PhotoLib</title>")

    session.remember(StoredCredentials(access_token="a", refresh_token="r"))
    _mount(session, handler)

    with pytest.raises(PhotoLibError) as caught:
        await session.request("GET", "/projects")
    assert "前端" in str(caught.value)
