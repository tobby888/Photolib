package cn.photolib.worklog.model;

import cn.photolib.common.model.BaseEntity;
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
    private Long requestId;
    private Long userId;
    private String memberName;
    private String memberStudentId;
    private LocalDate workDate;
    private Integer shootingMinutes;
    private Integer retouchingMinutes;
    private String remark;
    private WorklogStatus status;
    private String rejectReason;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    @TableField(exist = false)
    private String requestTitle;
    @TableField(exist = false)
    private String userDisplayName;
}
