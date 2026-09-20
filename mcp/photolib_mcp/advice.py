"""`doctor` 输出里的那几条提示。

收窄工具链的取舍写在 `mcp/README.md` 里，但**出问题的人不会先去翻 README**——
他会在终端里跑一次 `photolib-mcp doctor`，所以当前这套配置有什么后果，得在那行
输出旁边直接说出来：哪些工具因为这次的配置不见了、缺哪个分组会卡住、想换个样子
该改哪个环境变量。

这里只给提示，不改任何行为：开哪些分组、什么时候关、什么时候退出登录，都是用这台
机器的人自己的决定，这个模块既不替他决定，也不替他生效。
"""

from __future__ import annotations

import re
import unicodedata

from .config import ALL_TOOLSETS, Settings

#: 日常最常用的一套，够走完"选题 → 需求 → 传图 → 工时"。提示里拿它当例子。
EVERYDAY_TOOLSETS = "auth,projects,requests,photos,worklogs,directory"

#: 只读模式下一起消失的那批"其实是查数据"的工具：它们在后端都是 POST 建任务，
#: 按"会不会改数据"划线时会被一起划走。这是只读模式最容易踩的一脚。
READ_ONLY_CASUALTIES = (
    "photolib_login", "photolib_statistics_export", "photolib_worklogs_export",
    "photolib_export_job_download", "photolib_audit_logs_export",
    "photolib_photos_download", "photolib_photos_batch_download",
)


def configuration_notes(settings: Settings, *, has_credentials: bool = False) -> list[str]:
    """按当前配置给出提示，每条一句话，已经是可以直接打印的顺序。"""
    notes: list[str] = []
    enabled = set(settings.toolsets)
    disabled = [name for name in ALL_TOOLSETS if name not in enabled]

    if settings.read_only:
        notes.append(
            "只读模式：写工具不注册。注意它按\"会不会改数据\"划线，"
            f"{'、'.join(READ_ONLY_CASUALTIES[:4])} 等导出下载（后端都是 POST 建任务）"
            "和登录工具一起没了——要先在终端跑 `photolib-mcp login` 登录好，再让宿主起服务。"
        )
        narrowed = len(enabled) < len(ALL_TOOLSETS)
        notes.append(
            "想让 AI 只查数据、但还能导出报表：别用只读。"
            + ("分组已经收窄了，把 PHOTOLIB_MCP_READ_ONLY 去掉即可。" if narrowed
               else "改成按分组收窄，例如 PHOTOLIB_MCP_TOOLSETS=auth,statistics,directory。")
        )

    if "auth" not in enabled:
        notes.append(
            "没开 auth 分组：这个宿主里没有 photolib_login，也查不了当前身份。"
            "凭据只能先在终端 `photolib-mcp login` 拿到；要让 AI 自己发起登录就把 auth 加回去。"
        )

    missing_directory = [name for name in ("photos", "worklogs") if name in enabled]
    if missing_directory and "directory" not in enabled:
        notes.append(
            f"开了 {'、'.join(missing_directory)} 却没开 directory："
            "上传要 photographer_contact_id、工时要 member_contact_id，两个都只能从通讯录查，"
            "缺了这组这两件事做不完。把 directory 加进 PHOTOLIB_MCP_TOOLSETS。"
        )

    if "projects" in enabled and "photos" not in enabled:
        notes.append(
            "开了 projects 没开 photos：往相册里加图（photolib_projects_add_photos）需要先在图库里"
            "找到图片 id，这一步会卡住。"
        )

    if "misc" in enabled and not settings.read_only:
        notes.append(
            "misc 分组里的 photolib_api_request 能直接调任意 /api/v1 接口："
            "开着它的时候，收窄分组只是省上下文，不是能力边界。"
            "能做什么始终由后端按你的账号判定；真想收住能力面，就别开 misc。"
        )

    if "admin" in enabled:
        notes.append(
            "admin 分组开着：账号、权限组、审计日志、品牌、数据库备份与回滚都在里面。"
            "它更适合\"临时开、用完关\"——维护窗口结束后要不要关回去，你自己定。"
        )

    if disabled:
        notes.append(
            f"当前关掉的分组：{'、'.join(disabled)}。"
            "要用的时候把它加进 PHOTOLIB_MCP_TOOLSETS，重启宿主即可，不用重装。"
        )
    else:
        notes.append(
            "当前是默认全开。宿主的上下文占用和工具数量上限是真实约束，"
            f"按这次实际要干的事收窄，例如 PHOTOLIB_MCP_TOOLSETS={EVERYDAY_TOOLSETS}。"
        )

    if has_credentials:
        notes.append(
            f"本机存着凭据（{settings.credentials_path}）：谁用这台机器，谁就是以你的身份在说话。"
            "机器要借人、公用或者演示投屏前，跑 `photolib-mcp logout`。"
        )

    notes.append(
        "以上都是提示，不是限制：改完环境变量要重启宿主才生效，"
        "完整的分组表、按场景抄的配方和\"什么时候该关\"见 mcp/README.md 的\"收窄工具链\"一节。"
    )
    return notes


#: 折行时不能被拆开的最小单位：一段连续的 ASCII（命令、环境变量名、工具名）算一个，
#: 其余按单字。汉字里断在哪都还能读，`PHOTOLIB_MCP_TOOLSETS=auth,photos` 断开就不能抄了。
_ATOM = re.compile(r"[!-~]+|.", re.S)

#: 不能出现在行首的收尾标点。折到行首的"、"或"。"一眼就能看出是机器折的。
_NO_LINE_START = set("、。，；：？！）】》」』%")


def _width(text: str) -> int:
    """显示宽度。全角字符占两列——按字符数折行的话，一行中文会甩出终端两倍宽。"""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def render_notes(notes: list[str], *, width: int = 88,
                 bullet: str = "  - ", indent: str = "    ") -> list[str]:
    """把提示折成可以直接打印的行：首行带项目符号，续行缩进对齐。

    `doctor` 的输出常被整段贴进群里问"这样对不对"，折行乱掉就看不出一条提示从哪开始。
    """
    lines: list[str] = []
    for note in notes:
        prefix, current = bullet, ""
        for atom in _ATOM.findall(note):
            if not current and atom == " ":
                continue
            too_long = _width(prefix) + _width(current) + _width(atom) > width
            if too_long and current and atom not in _NO_LINE_START:
                lines.append((prefix + current).rstrip())
                prefix, current = indent, "" if atom == " " else atom
            else:
                current += atom
        if current:
            lines.append((prefix + current).rstrip())
    return lines
