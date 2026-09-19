"""工具行为里几处容易出错的地方：上传三步走、只发填了的字段、本地文件校验。"""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from photolib_mcp.config import Settings
from photolib_mcp.credentials import StoredCredentials
from photolib_mcp.errors import PhotoLibError
from photolib_mcp.server import build_server
from photolib_mcp.toolkit import compact
from photolib_mcp.transfers import inspect_file

# 一张最小的合法 PNG（1×1 透明像素）。魔数对，所以能过类型判断。
PNG_BYTES = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
    "890000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082"
)


@pytest.fixture
def png(tmp_path: Path) -> Path:
    target = tmp_path / "shot.png"
    target.write_bytes(PNG_BYTES)
    return target


def _server(settings: Settings, handler):
    server = build_server(settings)
    server.photolib_session.remember(StoredCredentials(access_token="a", refresh_token="r"))
    server.photolib_session._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return server


def _payload(result) -> object:
    """从工具调用结果里取出结构化数据。"""
    if getattr(result, "structured_content", None) is not None:
        data = result.structured_content
        return data.get("result", data) if isinstance(data, dict) else data
    return json.loads(result.content[0].text)


def test_content_type_comes_from_the_bytes_not_the_extension(tmp_path: Path) -> None:
    """改个后缀不该骗过类型判断：后端是按文件魔数校验的，本地按扩展名猜只会让
    错误推迟到直传之后才爆出来。"""
    disguised = tmp_path / "actually-a-png.jpg"
    disguised.write_bytes(PNG_BYTES)

    assert inspect_file(str(disguised)).content_type == "image/png"


def test_non_image_is_refused_before_any_request(tmp_path: Path) -> None:
    text = tmp_path / "notes.txt"
    text.write_text("不是图片", encoding="utf-8")

    with pytest.raises(PhotoLibError) as caught:
        inspect_file(str(text))
    assert "JPEG" in str(caught.value)


def test_compact_drops_unset_fields_only() -> None:
    """没填的字段不发（更新接口多是全量覆盖，发 None 等于清空），但 false 和 0 要留。"""
    assert compact({"a": None, "b": False, "c": 0, "d": ""}) == {"b": False, "c": 0, "d": ""}


async def test_photo_upload_walks_ticket_put_complete(settings: Settings, png: Path) -> None:
    calls: list[str] = []
    uploaded: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(f"{request.method} {request.url.path}")
        if request.url.path == "/api/v1/photos/upload-tickets":
            body = json.loads(request.content)
            # 票据请求要带上真实的大小和内容摘要，后端据此查重和校验。
            assert body["size"] == len(PNG_BYTES)
            assert len(body["sha256"]) == 64
            assert body["contentType"] == "image/png"
            return httpx.Response(200, json={"data": {
                "photoId": 42, "uploadUrl": "https://oss.test/put/42",
                "method": "PUT", "contentType": "image/png",
            }})
        if str(request.url) == "https://oss.test/put/42":
            uploaded["content_type"] = request.headers.get("Content-Type")
            uploaded["authorization"] = request.headers.get("Authorization")
            uploaded["bytes"] = len(request.content)
            return httpx.Response(200)
        return httpx.Response(200, json={"data": {"id": 42, "title": "合影", "status": "PROCESSING"}})

    server = _server(settings, handler)
    result = await server.call_tool("photolib_photos_upload", {
        "file_path": str(png), "title": "合影", "photographer_contact_id": 3,
        "taken_at": "2026-09-01T14:30:00",
    })

    assert _payload(result)["id"] == 42
    assert calls == [
        "POST /api/v1/photos/upload-tickets",
        "PUT /put/42",
        "POST /api/v1/photos/42/complete-upload",
    ]
    # 直传的 Content-Type 必须和签名时一致，且不能带上我们自己的令牌。
    assert uploaded["content_type"] == "image/png"
    assert uploaded["authorization"] is None
    assert uploaded["bytes"] == len(PNG_BYTES)


async def test_project_update_omits_tags_when_not_given(settings: Settings) -> None:
    """`tags` 不传表示"保留原预设标签"，传空数组才是"取消限制"。两者不能混。"""
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return httpx.Response(200, json={"data": {"id": 1}})

    server = _server(settings, handler)
    await server.call_tool("photolib_projects_update", {
        "project_id": 1, "title": "秋招宣传", "version": 3,
    })

    assert "tags" not in seen
    assert seen == {"title": "秋招宣传", "version": 3}


async def test_guest_share_tools_send_the_session_header(settings: Settings) -> None:
    """访客通道的能力由分享会话决定，头少了就会被当成没进门。"""
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["share_session"] = request.headers.get("X-Share-Session")
        seen["authorization"] = request.headers.get("Authorization")
        return httpx.Response(200, json={"data": {"items": []}})

    server = _server(settings, handler)
    await server.call_tool("photolib_shares_guest_photos", {
        "token": "SHARETOKEN", "share_session": "guest-session-1",
    })

    assert seen["share_session"] == "guest-session-1"
    # 访客通道整条不看登录令牌，带上它只会把一个受限会话卷进来。
    assert seen["authorization"] is None
