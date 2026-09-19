"""与 PhotoLib 后端之间的一条会话。

这一层只做四件事：拼地址、带令牌、拆信封、续期。业务判断一概不在这里——
**后端才是授权边界**，客户端"帮着判断一下能不能做"只会带来两种结果：要么和后端
不一致，要么给人一种它在做访问控制的错觉。
"""

from __future__ import annotations

import asyncio
from typing import Any, Mapping

import httpx

from .config import Settings
from .credentials import CredentialStore, StoredCredentials
from .errors import NotAuthenticatedError, PermissionDeniedError, PhotoLibError


class PhotoLibSession:
    """带自动续期的 HTTP 会话。"""

    def __init__(self, settings: Settings, store: CredentialStore | None = None) -> None:
        self._settings = settings
        self._store = store or CredentialStore(settings.credentials_path)
        self._credentials: StoredCredentials | None = None
        self._loaded = False
        self._client: httpx.AsyncClient | None = None
        # 一次只允许一个续期在飞。并发的工具调用会同时撞上 401，各自续一次的话
        # 后面那些会拿着已经被轮换掉的刷新令牌去换，直接把这条会话作废。
        self._refresh_lock = asyncio.Lock()

    # ---- 生命周期 -------------------------------------------------------

    @property
    def settings(self) -> Settings:
        return self._settings

    @property
    def store(self) -> CredentialStore:
        return self._store

    async def client(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=self._settings.timeout, follow_redirects=False)
        return self._client

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    # ---- 凭据 -----------------------------------------------------------

    def credentials(self) -> StoredCredentials | None:
        if not self._loaded:
            self._credentials = self._store.load(self._settings.base_url)
            self._loaded = True
        return self._credentials

    def remember(self, credentials: StoredCredentials) -> None:
        self._credentials = credentials
        self._loaded = True
        self._store.save(self._settings.base_url, credentials)

    def forget(self) -> bool:
        self._credentials = None
        self._loaded = True
        return self._store.clear(self._settings.base_url)

    def require_credentials(self) -> StoredCredentials:
        credentials = self.credentials()
        if credentials is None:
            raise NotAuthenticatedError()
        return credentials

    # ---- 请求 -----------------------------------------------------------

    async def request(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        json_body: Any | None = None,
        authenticated: bool = True,
        timeout: float | None = None,
        headers: Mapping[str, str] | None = None,
        file_path: str | None = None,
        file_field: str = "file",
    ) -> Any:
        """调一个 `/api/v1` 接口，返回信封里的 `data`。"""
        response = await self.raw_request(
            method, path, params=params, json_body=json_body,
            authenticated=authenticated, timeout=timeout, headers=headers,
            file_path=file_path, file_field=file_field,
        )
        return _unwrap(response)

    async def raw_request(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        json_body: Any | None = None,
        authenticated: bool = True,
        timeout: float | None = None,
        accept: str | None = None,
        headers: Mapping[str, str] | None = None,
        file_path: str | None = None,
        file_field: str = "file",
    ) -> httpx.Response:
        """同上，但把原始应答给出来。下载 CSV/ZIP/图片这类二进制响应要用它。

        `file_path` 给了就发 multipart（品牌图标、备份文件这类直接 POST 给后端的上传）。
        文件每次重试都要重新打开——httpx 不会替你把已经读完的流倒回去。
        """
        client = await self.client()
        url = self._settings.url(path)
        sent: dict[str, str] = dict(headers or {})
        if accept:
            sent["Accept"] = accept
        if authenticated:
            sent["Authorization"] = f"Bearer {self.require_credentials().access_token}"

        async def send() -> httpx.Response:
            if file_path is None:
                return await client.request(
                    method, url, params=_clean_params(params), json=json_body,
                    headers=sent, timeout=timeout or self._settings.timeout,
                )
            from pathlib import Path

            target = Path(file_path).expanduser()
            with target.open("rb") as stream:
                return await client.request(
                    method, url, params=_clean_params(params),
                    files={file_field: (target.name, stream)},
                    headers=sent, timeout=timeout or self._settings.transfer_timeout,
                )

        response = await send()
        if response.status_code == 401 and authenticated:
            # 访问令牌 15 分钟就过期，而 MCP 服务往往一开就是一整天。续一次再重来，
            # 续不动才真的算没登录。
            await self._refresh()
            sent["Authorization"] = f"Bearer {self.require_credentials().access_token}"
            response = await send()
        _raise_for_status(response)
        return response

    async def _refresh(self) -> None:
        async with self._refresh_lock:
            credentials = self.require_credentials()
            client = await self.client()
            response = await client.post(
                self._settings.url("/auth/mcp/token/refresh"),
                json={"refreshToken": credentials.refresh_token},
                timeout=self._settings.timeout,
            )
            if response.status_code >= 400:
                self.forget()
                raise NotAuthenticatedError(
                    "登录已失效（可能是改过密码、账号被停用，或者太久没用过）。"
                    "请重新调用 photolib_login 工具完成浏览器登录。"
                )
            data = _unwrap(response)
            user = data.get("user") or {}
            self.remember(StoredCredentials(
                access_token=data["accessToken"],
                refresh_token=data["refreshToken"],
                username=user.get("username") or credentials.username,
                display_name=user.get("displayName") or credentials.display_name,
                user_id=user.get("id") if user.get("id") is not None else credentials.user_id,
            ))


def _clean_params(params: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """丢掉值为 None 的查询参数。

    后端的可选筛选项一律是"不传＝不过滤"，而 httpx 会把 None 发成空串——
    那是"过滤成空值"，两回事。列表原样保留，Spring 会按重复参数解析。
    """
    if not params:
        return None
    cleaned: dict[str, Any] = {}
    for key, value in params.items():
        if value is None:
            continue
        if isinstance(value, bool):
            cleaned[key] = "true" if value else "false"
        else:
            cleaned[key] = value
    return cleaned or None


def _unwrap(response: httpx.Response) -> Any:
    try:
        payload = response.json()
    except ValueError as exc:
        raise PhotoLibError(
            f"后端返回了无法解析的内容（HTTP {response.status_code}）。"
            "如果站点地址指向的是前端而不是后端，通常就会这样。",
            status=response.status_code,
        ) from exc
    if isinstance(payload, dict) and "data" in payload:
        return payload["data"]
    return payload


def _raise_for_status(response: httpx.Response) -> None:
    if response.status_code < 400:
        return
    code, message, details = _error_of(response)
    if response.status_code == 401:
        raise NotAuthenticatedError(message)
    if response.status_code == 403:
        raise PermissionDeniedError(message)
    raise PhotoLibError(
        message or f"请求失败（HTTP {response.status_code}）",
        code=code, status=response.status_code, details=details,
    )


def _error_of(response: httpx.Response) -> tuple[str, str, list[str]]:
    try:
        payload = response.json()
    except ValueError:
        return "", response.text[:500], []
    if not isinstance(payload, dict):
        return "", str(payload)[:500], []
    details = [
        f"{item.get('field')}: {item.get('message')}" if item.get("field") else str(item.get("message"))
        for item in payload.get("details") or []
        if isinstance(item, dict)
    ]
    return payload.get("code") or "", payload.get("message") or "", details
