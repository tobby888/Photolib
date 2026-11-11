package cn.photolib.worklog.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("worklog")
public class WorklogEntity extends BaseEntity {
    /** 站内填报的工时挂在需求下；通过上传链接申报的为 {@code null}（见 {@link #shareLinkId}）。 */
    private Long requestId;
    /**
     * 通过哪条上传链接申报的（Flyway V63）。站内填报的为 {@code null}。
     * 这类工时的所属选题由链接现读，校区如实没有。
     */
    private Long shareLinkId;
    /** 填报账号；链接申报的工时记的是开这条链接的成员——站内唯一可追责的人。 */
    private Long userId;
    private String memberName;
    private String memberStudentId;
    private LocalDate workDate;
    private Integer shootingMinutes;
    private Integer retouchingMinutes;
    private String remark;
    /**
     * 管理员手写的选题名（Flyway V78）。非空时这条工时在统计、导出、清算单、工资计算里算在这个选题下，
     * 不再算进需求 / 链接所属的真实选题，清算期按 {@link #workDate} 判断。见 {@code WorklogProjectSql}。
     * 填报人填报时可写，管理员修改时可改。{@code ALWAYS}：填报人改草稿时清空它，{@code updateById} 也要写进 NULL。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String customProjectTitle;
    private WorklogStatus status;
    private String rejectReason;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    @TableField(exist = false)
    private String requestTitle;
    @TableField(exist = false)
    private String userDisplayName;
    /** 仅链接申报的工时由列表接口补充：所属选题，以及那条链接的备注名。 */
    @TableField(exist = false)
    private Long projectId;
    @TableField(exist = false)
    private String projectTitle;
    @TableField(exist = false)
    private String shareLinkName;
}
