from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from photolib_mcp.config import Settings  # noqa: E402
from photolib_mcp.session import PhotoLibSession  # noqa: E402


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    """指向一个假地址、凭据落在临时目录里的配置。

    凭据路径一定要隔离：测试跑在开发者自己的机器上，绝不能碰到
    `~/.photolib-mcp/credentials.json` 里那份真的令牌。
    """
    return Settings(
        base_url="https://photolib.test",
        credentials_path=tmp_path / "credentials.json",
        client_name="PhotoLib MCP Tests",
        device_label="test-host",
        open_browser=False,
    )


@pytest.fixture
def session(settings: Settings) -> PhotoLibSession:
    return PhotoLibSession(settings)
