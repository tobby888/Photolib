# 教学资料作为独立模块，而非扩展「文档中心」

为图库成员提供课程文件（教学资料）时，决定新建独立模块（`teaching`），而不是在已有「文档中心」（`doc`）的树里增加 Word/PPT/PDF 节点。两者语义不同：文档中心是撰写型 Wiki（Markdown 正文 + PDF，MEMBERS/PUBLIC 两态可见），教学资料是下载型文件库（受众 = 持有 `PHOTO_VIEW` 的图库成员，管理权限 = `TEACHING_MANAGE`）。硬塞进 `doc` 会让通用 Wiki 背上课程文件的受众与格式包袱。
