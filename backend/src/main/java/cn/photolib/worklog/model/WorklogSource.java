package cn.photolib.worklog.model;

/** 工时的两种来源，列表筛选用：站内填报的挂在需求下，链接申报的挂在上传链接下（Flyway V63）。 */
public enum WorklogSource {
    /** 站内填报：{@code request_id} 有值、{@code share_link_id} 为空。 */
    IN_APP,
    /** 通过上传链接申报：{@code share_link_id} 有值、没有需求也没有校区。 */
    SHARE_LINK
}
