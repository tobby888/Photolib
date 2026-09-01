# PhotoLib 使用指南

本目录包含面向三种角色的系统使用指南，使用 LaTeX（ElegantBook 文档类）编写。

## 三份文档

| 文件 | 面向对象 | 内容侧重 |
| --- | --- | --- |
| `管理员使用指南.tex` | 系统管理员（ADMIN） | 技术架构、部署运维、生产配置、权限体系、后台管理、数据安全 |
| `部长使用指南.tex` | 摄影部正副部长（MINISTER） | 立项、发需求、标记采用、工时确认、统计导出、招募、文档、消息 |
| `校区负责人使用指南.tex` | 校区负责人（CAMPUS_MANAGER） | 接需求、上传图片、交付、填报工时、维护通讯录 |

其余文件：

- `elegant-style.tex`：三份文档共用的样式与宏定义（颜色、提示框、表格样式等）。
- `elegantbook.cls`：ElegantBook 文档类（随目录附带，便于离线编译）。
- `cover.png`：三份文档共用的封面图。
- `*.pdf`：已编译好的成品，可直接查看/分发。

## 如何编译

需要一套 TeX 发行版（TeX Live / MacTeX），并**使用 XeLaTeX 编译**（含中文，必须用 XeLaTeX，不能用 pdfLaTeX）。

在本目录下执行（以管理员指南为例，连续编译两次以生成目录和交叉引用）：

```bash
xelatex 管理员使用指南.tex
xelatex 管理员使用指南.tex
```

其余两份同理。也可以用 `latexmk`：

```bash
latexmk -xelatex 管理员使用指南.tex
```

> 提示：`elegantbook.cls` 已随目录提供，无需额外安装。若你的 TeX 发行版自带 ElegantBook，也可删除本地的 `.cls` 使用系统版本。ElegantBook 在 Overleaf 上作为内置模板可直接使用。

## 修改说明

- 三份文档共享 `elegant-style.tex`：改动样式（配色、提示框、表格）只需改这一个文件，三份一起生效。
- 正文里的行内标记宏：`\menu{}`（菜单）、`\ui{}`（界面按钮/字段）、`\code{}`（权限码/环境变量等等宽标识）、`\role{}`（角色）。
- 四种提示框环境：`tipbox`（小贴士）、`notebox`（说明）、`warnbox`（注意）、`stepbox`（操作步骤），均支持可选自定义标题，例如 `\begin{warnbox}[务必先备份] ... \end{warnbox}`。
