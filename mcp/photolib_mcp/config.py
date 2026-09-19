"""运行配置。

所有设置都来自环境变量：MCP 服务由宿主（Claude Desktop / Claude Code）拉起，
除了它在配置文件里写的 env 之外没有别的输入渠道。**这里刻意没有"账号密码"一项**——
凭据一律靠浏览器登录换取（见 :mod:`photolib_mcp.device_login`），密码不该出现在
任何一份会被同步、会被截图的配置文件里。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_TIMEOUT_SECONDS = 60.0
#: 上传/下载走的是对象存储，大图慢链路要给足时间，和普通接口分开算。
DEFAULT_TRANSFER_TIMEOUT_SECONDS = 600.0

#: 工具分组。默认全开——"这个 MCP 要包含项目的所有能力"是它存在的前提。
#: 想收窄的人设 PHOTOLIB_MCP_TOOLSETS，理由见 README 的"工具太多怎么办"。
ALL_TOOLSETS = (
    "auth",
    "projects",
    "requests",
    "photos",
    "adoptions",
    "worklogs",
    "statistics",
    "notifications",
    "directory",
    "featured",
    "docs",
    "recruitment",
    "shares",
    "admin",
    "misc",
)


def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name).lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}


def _env_float(name: str, default: float) -> float:
    raw = _env(name)
    try:
        return float(raw) if raw else default
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """一次运行的全部设置。"""

    #: 站点地址，不含 /api/v1。例如 https://photowarehouse.cn
    base_url: str = DEFAULT_BASE_URL
    #: 凭据文件。默认放在用户目录下，按站点地址分条存放。
    credentials_path: Path = field(default_factory=lambda: Path.home() / ".photolib-mcp" / "credentials.json")
    #: 出现在批准页上的客户端名字，让成员看得出是哪个客户端在申请。
    client_name: str = "PhotoLib MCP"
    #: 出现在批准页上的设备名。默认用主机名。
    device_label: str = ""
    timeout: float = DEFAULT_TIMEOUT_SECONDS
    transfer_timeout: float = DEFAULT_TRANSFER_TIMEOUT_SECONDS
    #: 登录时是否自动拉起浏览器。宿主跑在没有桌面的机器上时置 false，只打印链接。
    open_browser: bool = True
    #: 启用的工具分组。
    toolsets: tuple[str, ...] = ALL_TOOLSETS
    #: 只读模式。开启后所有写操作工具都不注册——想让 AI 只帮忙查数据时用。
    read_only: bool = False

    @property
    def api_base(self) -> str:
        return f"{self.base_url}/api/v1"

    def url(self, path: str) -> str:
        return f"{self.api_base}/{path.lstrip('/')}"


def load_settings() -> Settings:
    base_url = _env("PHOTOLIB_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    if base_url and "://" not in base_url:
        # 少写协议头是最常见的配置手误，按 https 补全，别让人对着连接错误猜。
        base_url = f"https://{base_url}"

    credentials = _env("PHOTOLIB_MCP_CREDENTIALS")
    device_label = _env("PHOTOLIB_MCP_DEVICE_LABEL") or _default_device_label()

    raw_toolsets = _env("PHOTOLIB_MCP_TOOLSETS")
    if raw_toolsets:
        requested = tuple(item.strip() for item in raw_toolsets.split(",") if item.strip())
        unknown = [item for item in requested if item not in ALL_TOOLSETS]
        if unknown:
            raise ValueError(
                f"PHOTOLIB_MCP_TOOLSETS 里有未知的分组: {', '.join(unknown)}；"
                f"可选值为 {', '.join(ALL_TOOLSETS)}"
            )
        toolsets = requested
    else:
        toolsets = ALL_TOOLSETS

    return Settings(
        base_url=base_url,
        credentials_path=Path(credentials).expanduser() if credentials
        else Path.home() / ".photolib-mcp" / "credentials.json",
        client_name=_env("PHOTOLIB_MCP_CLIENT_NAME", "PhotoLib MCP"),
        device_label=device_label,
        timeout=_env_float("PHOTOLIB_MCP_TIMEOUT", DEFAULT_TIMEOUT_SECONDS),
        transfer_timeout=_env_float("PHOTOLIB_MCP_TRANSFER_TIMEOUT", DEFAULT_TRANSFER_TIMEOUT_SECONDS),
        open_browser=_env_bool("PHOTOLIB_MCP_OPEN_BROWSER", True),
        toolsets=toolsets,
        read_only=_env_bool("PHOTOLIB_MCP_READ_ONLY", False),
    )


def _default_device_label() -> str:
    import socket

    try:
        return socket.gethostname()[:100]
    except OSError:
        return ""
