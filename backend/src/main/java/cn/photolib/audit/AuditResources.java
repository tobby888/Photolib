package cn.photolib.audit;

import java.util.Locale;
import java.util.Map;

/**
 * 操作日志里的「模块」：取 {@code /api/v1/} 后第一段（匿名接口取 {@code /public/} 后那段），
 * 大写后存进 {@code resource_type}。这个取法从第一版起没变过，旧记录照样能按模块筛。
 */
final class AuditResources {
    static final String UNKNOWN = "UNKNOWN";

    /** 模块的中文名。新增模块时在这里补一行，否则界面上显示的是英文代码。 */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("ADMIN-ALERTS", "系统告警"),
            Map.entry("ADMIN-DASHBOARD", "管理面板"),
            Map.entry("AUDIT-LOGS", "操作日志"),
            Map.entry("AUTH", "登录与账号安全"),
            Map.entry("BRANDING", "站点外观"),
            Map.entry("CAMPUS-MEMBERS", "通讯录"),
            Map.entry("CAMPUSES", "校区"),
            Map.entry("DATABASE-BACKUPS", "数据备份"),
            Map.entry("DESCRIPTION-IMAGES", "正文插图"),
            Map.entry("DOC-FILES", "文件库"),
            Map.entry("DOCS", "文档中心"),
            Map.entry("EXPORT-JOBS", "导出任务"),
            Map.entry("FEATURED-COLLECTIONS", "好图精选"),
            Map.entry("FEEDBACK", "问题反馈"),
            Map.entry("LOCAL-STORAGE", "本地存储"),
            Map.entry("MFA-SETTINGS", "两步验证设置"),
            Map.entry("NOTIFICATION-LOGS", "通知投递"),
            Map.entry("NOTIFICATIONS", "消息中心"),
            Map.entry("PERMISSION-GROUPS", "权限组"),
            Map.entry("PHOTOS", "图片库"),
            Map.entry("PROJECTS", "选题项目"),
            Map.entry("RECRUITMENT-TASKS", "成员招募"),
            Map.entry("RECRUITMENTS", "公开招募报名"),
            Map.entry("REGISTRATION-APPLICATIONS", "注册申请"),
            Map.entry("REGISTRATION-CODES", "注册码"),
            Map.entry("REGISTRATIONS", "注册申请"),
            Map.entry("REQUESTS", "图片需求"),
            Map.entry("SHARES", "选题对外链接"),
            Map.entry("STATISTICS", "数据统计"),
            Map.entry("SURVEYS", "问卷"),
            Map.entry("TEACHING", "教学资料"),
            Map.entry("UPLOAD-LIMITS", "上传限额"),
            Map.entry("USERS", "账号"),
            Map.entry("WORKLOGS", "工时记录"),
            Map.entry(UNKNOWN, "其他"));

    private AuditResources() {
    }

    /** {@code path} 可以是实际的请求路径，也可以是 {@code /api/v1/projects/{id}} 这样的映射模式。 */
    static String typeOf(String path) {
        if (path == null) return UNKNOWN;
        String[] segments = path.split("/");
        // 匿名接口多一层（/api/v1/public/<模块>/<id>/…），不跳过的话所有公开写操作都会记成 PUBLIC。
        int typeIndex = segments.length > 3 && "public".equals(segments[3]) ? 4 : 3;
        if (segments.length <= typeIndex || segments[typeIndex].isBlank()) return UNKNOWN;
        return segments[typeIndex].toUpperCase(Locale.ROOT);
    }

    static String label(String type) {
        if (type == null) return null;
        return LABELS.getOrDefault(type, type);
    }
}
