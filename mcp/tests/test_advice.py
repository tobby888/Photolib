"""doctor 里那几条提示：该出现的出现、不该出现的别出现。

这些提示是使用者判断"这套配置会怎样"的依据，说错了比不说更糟——所以每条触发条件
都有一个用例盯着。
"""

from __future__ import annotations

from photolib_mcp.advice import configuration_notes, render_notes
from photolib_mcp.config import ALL_TOOLSETS, Settings


def notes_for(settings: Settings, **kwargs) -> str:
    return "\n".join(configuration_notes(settings, **kwargs))


def test_default_build_suggests_narrowing(settings: Settings) -> None:
    text = notes_for(settings)
    assert "默认全开" in text
    assert "PHOTOLIB_MCP_TOOLSETS=" in text
    assert "当前关掉的分组" not in text


def test_narrowed_build_lists_what_is_off(settings: Settings) -> None:
    text = notes_for(Settings(**{**settings.__dict__, "toolsets": ("auth", "statistics", "directory")}))
    assert "当前关掉的分组" in text
    assert "admin" in text and "photos" in text
    assert "默认全开" not in text


def test_read_only_warns_login_and_exports_are_gone(settings: Settings) -> None:
    """只读模式最容易踩的一脚：登录和导出在后端都是 POST，会被一起划走。"""
    text = notes_for(Settings(**{**settings.__dict__, "read_only": True}))
    assert "photolib_login" in text
    assert "photolib-mcp login" in text
    assert "photolib_statistics_export" in text
    # 想"只查数据还能导表"的人该被指到分组收窄，而不是继续用只读。
    assert "auth,statistics,directory" in text

    # 已经收窄过的人不需要再被推荐一遍分组，只要知道该去掉哪个变量。
    narrowed = notes_for(Settings(**{**settings.__dict__, "read_only": True,
                                     "toolsets": ("auth", "statistics", "directory")}))
    assert "把 PHOTOLIB_MCP_READ_ONLY 去掉" in narrowed
    assert "例如 PHOTOLIB_MCP_TOOLSETS=auth,statistics,directory" not in narrowed


def test_missing_directory_is_called_out_for_photos_and_worklogs(settings: Settings) -> None:
    text = notes_for(Settings(**{**settings.__dict__, "toolsets": ("auth", "photos", "worklogs")}))
    assert "没开 directory" in text
    assert "photographer_contact_id" in text

    with_directory = notes_for(
        Settings(**{**settings.__dict__, "toolsets": ("auth", "photos", "directory")}))
    assert "没开 directory" not in with_directory


def test_missing_auth_points_at_terminal_login(settings: Settings) -> None:
    text = notes_for(Settings(**{**settings.__dict__, "toolsets": ("photos", "directory")}))
    assert "没开 auth 分组" in text
    assert "photolib-mcp login" in text


def test_misc_is_flagged_as_not_a_boundary(settings: Settings) -> None:
    """开着通用出口时，收窄分组只是省上下文——这一点不说清楚会被当成权限收窄。"""
    text = notes_for(Settings(**{**settings.__dict__, "toolsets": ("auth", "misc")}))
    assert "photolib_api_request" in text
    assert "不是能力边界" in text

    without_misc = notes_for(Settings(**{**settings.__dict__, "toolsets": ("auth", "photos", "directory")}))
    assert "photolib_api_request" not in without_misc


def test_admin_and_credentials_notes(settings: Settings) -> None:
    text = notes_for(Settings(**{**settings.__dict__, "toolsets": ("auth", "admin")}),
                     has_credentials=True)
    assert "admin 分组开着" in text
    assert str(settings.credentials_path) in text
    assert "photolib-mcp logout" in text

    anonymous = notes_for(settings)
    assert "photolib-mcp logout" not in anonymous


def test_notes_always_end_with_the_decision_being_yours(settings: Settings) -> None:
    for variant in (settings,
                    Settings(**{**settings.__dict__, "read_only": True}),
                    Settings(**{**settings.__dict__, "toolsets": ("auth",)})):
        last = configuration_notes(variant)[-1]
        assert "重启宿主" in last and "mcp/README.md" in last


def test_every_toolset_name_is_known(settings: Settings) -> None:
    """提示里点名的分组必须真的存在，否则照着抄会把服务写挂。"""
    text = notes_for(Settings(**{**settings.__dict__, "toolsets": ("auth",)}), has_credentials=True)
    for chunk in text.split("PHOTOLIB_MCP_TOOLSETS=")[1:]:
        for name in chunk.split("。")[0].split("，")[0].strip().split(","):
            assert name.strip() in ALL_TOOLSETS, name


def test_render_notes_wraps_by_display_width(settings: Settings) -> None:
    """中文按显示宽度折行：按字符数折的话一行中文会甩出终端两倍宽。"""
    import unicodedata

    lines = render_notes(configuration_notes(settings, has_credentials=True), width=60)
    assert lines
    for line in lines:
        assert line.startswith("  - ") or line.startswith("    ")
        width = sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in line)
        if width > 60:
            # 只有两种情况允许超宽：这一行就是一个拆不开的 token（凭据路径、长命令），
            # 或者末尾那个字是被禁则规则拉回来的标点（最多挤出两列）。
            single_token = " " not in line.strip()
            punctuation_tail = line[-1] in "、。，；：？！）】》」』" and width <= 62
            assert single_token or punctuation_tail, line


def test_render_notes_keeps_copyable_tokens_intact(settings: Settings) -> None:
    """环境变量那一行断开就没法抄了，ASCII 连续段不许拆。"""
    joined = "\n".join(render_notes(configuration_notes(settings), width=40))
    assert "PHOTOLIB_MCP_TOOLSETS=auth,projects,requests,photos,worklogs,directory" in joined


def test_render_notes_never_starts_a_line_with_closing_punctuation(settings: Settings) -> None:
    """折到行首的"、"一眼就能看出是机器折的，读的人会以为是输出坏了。"""
    for line in render_notes(configuration_notes(settings), width=50):
        assert line.strip()[0] not in "、。，；：？！）】》」』", line
        assert line == line.rstrip(), line
