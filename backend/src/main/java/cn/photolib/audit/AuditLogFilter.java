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

    public static AuditLogFilter none() {
        return AuditLogFilter.builder().build();
    }

    /** 设置关键词并转义 LIKE 通配符（转义字符是 {@code !}）。 */
    public AuditLogFilter withKeyword(String raw) {
        String trimmed = raw == null ? null : raw.trim();
        return toBuilder().keyword(trimmed == null || trimmed.isEmpty() ? null : LikeFilter.escape(trimmed)).build();
    }
}
