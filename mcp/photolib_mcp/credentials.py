"""本机凭据存放。

存的是刷新令牌（以及当前这张访问令牌），不是密码——它可以被单独吊销，
吊销之后拿到文件的人也做不了任何事。文件按站点地址分条，一台机器同时接
测试站和正式站不会互相覆盖。

权限收到 0600：这份文件等价于一个登录会话。Windows 上 `chmod` 只是个空操作，
所以那边额外用 ACL 把除属主外的访问去掉；两条路径都失败时不静默——宁可让人
看到一行告警，也不要让他以为文件是受保护的。
"""

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class StoredCredentials:
    access_token: str
    refresh_token: str
    #: 登录时后端回的成员信息，只用于在工具里显示"当前是谁"，不参与任何判定。
    username: str = ""
    display_name: str = ""
    user_id: int | None = None


class CredentialStore:
    def __init__(self, path: Path) -> None:
        self._path = path

    @property
    def path(self) -> Path:
        return self._path

    def load(self, base_url: str) -> StoredCredentials | None:
        data = self._read_all()
        entry = data.get(self._key(base_url))
        if not isinstance(entry, dict):
            return None
        access = entry.get("access_token") or ""
        refresh = entry.get("refresh_token") or ""
        if not refresh:
            # 没有刷新令牌就续不了期，等同于没有凭据：让调用方直接走重新登录，
            # 而不是拿着一张迟早过期的访问令牌反复撞 401。
            return None
        return StoredCredentials(
            access_token=access,
            refresh_token=refresh,
            username=entry.get("username") or "",
            display_name=entry.get("display_name") or "",
            user_id=entry.get("user_id"),
        )

    def save(self, base_url: str, credentials: StoredCredentials) -> None:
        data = self._read_all()
        data[self._key(base_url)] = asdict(credentials)
        self._write_all(data)

    def clear(self, base_url: str) -> bool:
        data = self._read_all()
        if data.pop(self._key(base_url), None) is None:
            return False
        self._write_all(data)
        return True

    @staticmethod
    def _key(base_url: str) -> str:
        return base_url.rstrip("/")

    def _read_all(self) -> dict:
        try:
            raw = self._path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return {}
        except OSError:
            return {}
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            # 文件被改坏了。当成"没有凭据"重新登录一次即可，不值得让服务起不来。
            return {}
        return data if isinstance(data, dict) else {}

    def _write_all(self, data: dict) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        # 先建成空文件再收权限，最后才写内容：反过来的话，令牌会有一瞬间
        # 躺在一个默认权限的文件里。
        self._path.touch(mode=0o600, exist_ok=True)
        _restrict(self._path)
        self._path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def _restrict(path: Path) -> None:
    try:
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass
    if sys.platform != "win32":
        return
    # Windows 的 chmod 不改 ACL，令牌文件会跟着父目录的继承权限走。用 icacls
    # 断掉继承、只留当前用户。失败就告警：让人知道这份文件没有被保护住。
    user = os.environ.get("USERNAME")
    if not user:
        return
    try:
        subprocess.run(
            ["icacls", str(path), "/inheritance:r", "/grant:r", f"{user}:(F)"],
            check=True, capture_output=True, timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        print(
            f"[photolib-mcp] 警告：未能收紧 {path} 的访问权限，"
            f"该文件保存着可用的登录令牌，请自行确认它没有被共享。",
            file=sys.stderr,
        )
