package cn.photolib.permission;

public enum PermissionCode {
    PROJECT_VIEW(PermissionCategory.PROJECT, "选题查看"),
    PROJECT_ADOPT(PermissionCategory.PROJECT, "标记或取消图片被引"),
    PROJECT_CREATE(PermissionCategory.PROJECT, "新建、编辑、发布和删除选题"),
    PROJECT_COMPLETE(PermissionCategory.PROJECT, "标记选题完成"),
    PROJECT_DOWNLOAD(PermissionCategory.PROJECT, "下载选题图片"),

    REQUEST_VIEW(PermissionCategory.REQUEST, "需求访问、接受和提交"),
    REQUEST_CREATE(PermissionCategory.REQUEST, "新建、编辑和发布需求"),
    REQUEST_DELETE(PermissionCategory.REQUEST, "删除需求"),
    REQUEST_CLOSE(PermissionCategory.REQUEST, "关闭需求"),
    REQUEST_CONFIRM(PermissionCategory.REQUEST, "确认或退回需求"),
    REQUEST_PHOTO_MANAGE(PermissionCategory.REQUEST, "管理需求图片（上传、下载、删除）"),

    PHOTO_VIEW(PermissionCategory.PHOTO, "图库访问"),
    PHOTO_DELETE(PermissionCategory.PHOTO, "删除、归档和恢复图库图片"),
    PHOTO_UPLOAD(PermissionCategory.PHOTO, "图库上传（含批量上传）"),
    PHOTO_DOWNLOAD(PermissionCategory.PHOTO, "图库下载（含批量下载）"),

    WORKLOG_SUBMIT(PermissionCategory.WORKLOG, "工时申报"),
    WORKLOG_CONFIRM(PermissionCategory.WORKLOG, "工时确认和退回"),
    WORKLOG_EXPORT(PermissionCategory.WORKLOG, "工时导出"),

    DIRECTORY_VIEW(PermissionCategory.DIRECTORY, "通讯录查看"),
    DIRECTORY_MANAGE(PermissionCategory.DIRECTORY, "通讯录管理"),
    MESSAGE_SEND(PermissionCategory.MESSAGE, "消息发送"),
    RECRUITMENT_VIEW(PermissionCategory.RECRUITMENT, "查看招募任务和报名详情"),
    RECRUITMENT_PUBLISH(PermissionCategory.RECRUITMENT, "创建、编辑、发布和关闭招募任务"),
    FEATURED_MANAGE(PermissionCategory.FEATURED, "发布、删除和手动截止好图精选"),
    DOC_MANAGE(PermissionCategory.DOC, "编写文档、拖拽整理目录、发布并指定是否需要登录查看"),
    STATISTICS_DOWNLOAD(PermissionCategory.STATISTICS, "统计数据查看和下载"),
    MANAGER_CAMPUS_ASSIGN(PermissionCategory.MANAGER_CAMPUS, "负责人校区重新指定");

    private final PermissionCategory category;
    private final String label;

    PermissionCode(PermissionCategory category, String label) {
        this.category = category;
        this.label = label;
    }

    public PermissionCategory category() {
        return category;
    }

    public String label() {
        return label;
    }
}
