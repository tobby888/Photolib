package cn.photolib.request.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("photo_request")
public class PhotoRequestEntity extends BaseEntity {
    private Long projectId;
    private String title;
    private String description;
    private Long campusId;
    private Integer requiredCount;
    private LocalDateTime deadline;
    private RequestStatus status;
    private Long createdBy;
    /** 新建时指定的被指派人；发布时成为参与人。编辑草稿可以清空，所以更新时总是写入。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long assigneeId;
    private LocalDateTime firstAcceptedAt;
    private LocalDateTime completedAt;
    private String cancelReason;
    private String returnReason;
    private Long returnedBy;
    private LocalDateTime returnedAt;
}
