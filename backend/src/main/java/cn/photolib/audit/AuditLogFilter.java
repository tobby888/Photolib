package cn.photolib.audit;

import cn.photolib.common.util.LikeFilter;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 操作日志的筛选条件。列表、计数、导出共用这一份，三处的条件就不会各改各的。
 * {@code to} 是开区间（次日零点），见 {@code AuditController#end}。
 */
@Value
@Builder(toBuilder = true)
public class AuditLogFilter {
    Long operatorId;
    /** HTTP 方法。 */
    String action;
    /** 操作名，精确匹配 {@link AuditAction} 的值。 */
    String actionName;
    String resourceType;
    /** {@code true} 只看成功，{@code false} 只看失败，{@code null} 不限。 */
    Boolean success;
    String ipAddress;
    LocalDateTime from;
    LocalDateTime to;
    /** 已转义的 LIKE 关键词；用 {@link #withKeyword} 设置。 */
    String keyword;
    /**
     * 翻页游标：只取排在这一条（{@code created_at DESC, id DESC}）之后的记录。导出分批读用，
     * 列表接口不设；用 {@link #after} 设置。两个字段要么都有、要么都没有。
     */
    LocalDateTime beforeCreatedAt;
    Long beforeId;

    public static AuditLogFilter none() {
        return AuditLogFilter.builder().build();
    }

    /** 同样的条件，只取排在 {@code last} 之后的记录。 */
    public AuditLogFilter after(AuditLogView last) {
        return toBuilder().beforeCreatedAt(last.getCreatedAt()).beforeId(last.getId()).build();
    }

    /** 设置关键词并转义 LIKE 通配符（转义字符是 {@code !}）。 */
    public AuditLogFilter withKeyword(String raw) {
        String trimmed = raw == null ? null : raw.trim();
        return toBuilder().keyword(trimmed == null || trimmed.isEmpty() ? null : LikeFilter.escape(trimmed)).build();
    }
}
