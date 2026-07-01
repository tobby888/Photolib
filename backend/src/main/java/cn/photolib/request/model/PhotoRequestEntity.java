package cn.photolib.request.model;

import cn.photolib.common.model.BaseEntity;
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
    private LocalDateTime firstAcceptedAt;
    private LocalDateTime completedAt;
    private String cancelReason;
}
