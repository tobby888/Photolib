package cn.photolib.worklog;

/**
 * 工时「所属选题」的 SQL 片段，统计、导出、清算单、工资计算、工时分析共用一份。
 *
 * <p>所属选题按优先级取：</p>
 * <ol>
 *   <li>管理员填了自定义选题（{@code worklog.custom_project_title}，Flyway V78）就算在它下面，
 *       同名的合并成一组。它没有结束时间，清算期按这条工时的 {@code work_date} 判断；</li>
 *   <li>否则是 {@code COALESCE(需求的选题, 链接的选题)}，清算期按选题 {@code completed_at} 判断。</li>
 * </ol>
 *
 * <p>{@link #joins} 里选题是 LEFT JOIN 且带着「没有自定义选题」的条件：有自定义选题的工时 {@code p} 整行为空，
 * 不会被同时算进真实选题。需求 {@code r} 和链接 {@code l} 照旧关联，校区仍从需求取。</p>
 */
public final class WorklogProjectSql {
    private WorklogProjectSql() {
    }

    /** 关联需求 {@code r}、链接 {@code l}、真实选题 {@code p}；参数是四个别名，工时 {@code w} 要已经在 FROM 里。 */
    public static String joins(String w, String r, String l, String p) {
        return """
                LEFT JOIN photo_request %2$s ON %2$s.id=%1$s.request_id
                LEFT JOIN project_share_link %3$s ON %3$s.id=%1$s.share_link_id
                LEFT JOIN project %4$s ON %4$s.id=COALESCE(%2$s.project_id, %3$s.project_id)
                    AND %1$s.custom_project_title IS NULL
                """.formatted(w, r, l, p);
    }

    /** {@link #joins} 的常用别名版本：{@code w / r / l / p}。 */
    public static String joins() {
        return joins("w", "r", "l", "p");
    }

    /**
     * 这条工时落在清算期 {@code [:fromDate, :toExclusive)} 里：真实选题按结束时间（已完成、未删除），
     * 自定义选题按工作日期。
     */
    public static String settled(String w, String p) {
        return """
                ((%1$s.custom_project_title IS NULL AND %2$s.deleted=0 AND %2$s.status='COMPLETED'
                  AND %2$s.completed_at >= :fromDate AND %2$s.completed_at < :toExclusive)
                 OR (%1$s.custom_project_title IS NOT NULL
                  AND %1$s.work_date >= :fromDate AND %1$s.work_date < :toExclusive))""".formatted(w, p);
    }

    public static String settled() {
        return settled("w", "p");
    }

    /** 所属选题的名字：自定义选题优先。 */
    public static String title(String w, String p) {
        return "COALESCE(%1$s.custom_project_title, %2$s.title)".formatted(w, p);
    }
}
