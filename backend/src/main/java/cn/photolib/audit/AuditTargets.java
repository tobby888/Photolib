package cn.photolib.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把操作日志里的「对象」从一串 ID 翻成人认得的名字：选题标题、成员姓名、文件名……
 *
 * <p>名字在写日志时查一次、存进 {@code resource_name}，不在列表里实时联表：对象可能
 * 后来改了名或被删掉，日志要的是操作那一刻它叫什么。删除类操作在处理之前查
 * （见 {@link AuditInterceptor#preHandle}），物理删除的对象也有名字。</p>
 *
 * <p>键是 URL 里紧挨着第一个路径变量的那一段（{@code /projects/{id}/...} 的 {@code projects}）。
 * 查不到、查询出错都只返回 {@code null}，绝不影响请求本身。</p>
 */
@Component
public class AuditTargets {
    private static final Logger log = LoggerFactory.getLogger(AuditTargets.class);
    private static final Pattern NUMERIC = Pattern.compile("[0-9]{1,18}");
    static final int MAX_NAME_LENGTH = 255;

    /** {@code byId} 用数字 ID 查，{@code byPublicId} 用对外的 ULID/令牌查；没有的那一路写 {@code null}。 */
    record Lookup(String byId, String byPublicId) {
    }

    static final Map<String, Lookup> LOOKUPS = Map.ofEntries(
            Map.entry("projects", new Lookup("SELECT title FROM project WHERE id = ?", null)),
            Map.entry("photos", new Lookup(
                    "SELECT COALESCE(NULLIF(title, ''), stored_file_name) FROM photo WHERE id = ?", null)),
            Map.entry("requests", new Lookup("SELECT title FROM photo_request WHERE id = ?", null)),
            Map.entry("users", new Lookup(
                    "SELECT CONCAT(COALESCE(display_name, ''), ' @', username) FROM app_user WHERE id = ?", null)),
            Map.entry("campuses", new Lookup("SELECT name FROM campus WHERE id = ?", null)),
            Map.entry("campus-members", new Lookup("SELECT name FROM campus_member WHERE id = ?", null)),
            Map.entry("docs", new Lookup("SELECT title FROM doc_node WHERE id = ?",
                    "SELECT title FROM doc_node WHERE public_id = ?")),
            Map.entry("doc-files", new Lookup("SELECT title FROM doc_file WHERE id = ?",
                    "SELECT title FROM doc_file WHERE public_id = ?")),
            Map.entry("featured-collections", new Lookup("SELECT title FROM featured_collection WHERE id = ?", null)),
            Map.entry("feedback", new Lookup("SELECT title FROM feedback WHERE id = ?", null)),
            Map.entry("permission-groups", new Lookup("SELECT name FROM permission_group WHERE id = ?", null)),
            Map.entry("registration-codes", new Lookup("SELECT name FROM registration_code WHERE id = ?", null)),
            Map.entry("recruitment-tasks", new Lookup("SELECT title FROM recruitment_task WHERE id = ?", null)),
            Map.entry("recruitments", new Lookup(null, "SELECT title FROM recruitment_task WHERE public_id = ?")),
            Map.entry("surveys", new Lookup("SELECT title FROM survey WHERE id = ?", null)),
            Map.entry("teaching", new Lookup("SELECT title FROM teaching_material WHERE id = ?", null)),
            Map.entry("materials", new Lookup(null, "SELECT title FROM teaching_material WHERE public_id = ?")),
            Map.entry("worklogs", new Lookup("SELECT member_name FROM worklog WHERE id = ?", null)),
            Map.entry("shares", new Lookup(null, "SELECT name FROM project_share_link WHERE token = ?")),
            Map.entry("admin-alerts", new Lookup("SELECT message FROM admin_alert WHERE id = ?", null)));

    private final JdbcClient jdbc;

    public AuditTargets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code segment} 对象所在的 URL 段，{@code value} 路径变量的值。 */
    public String nameOf(String segment, String value) {
        if (segment == null || value == null || value.isBlank()) return null;
        Lookup lookup = LOOKUPS.get(segment);
        if (lookup == null) return null;
        try {
            String name;
            if (NUMERIC.matcher(value).matches() && lookup.byId() != null) {
                name = jdbc.sql(lookup.byId()).param(Long.parseLong(value)).query(String.class).optional().orElse(null);
            } else if (lookup.byPublicId() != null && value.length() <= 64) {
                name = jdbc.sql(lookup.byPublicId()).param(value).query(String.class).optional().orElse(null);
            } else {
                return null;
            }
            return clip(name);
        } catch (RuntimeException e) {
            log.debug("操作日志对象名查询失败: {} {}", segment, value, e);
            return null;
        }
    }

    static String clip(String name) {
        if (name == null) return null;
        String trimmed = name.strip();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH - 1) + "…" : trimmed;
    }
}
