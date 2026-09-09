package cn.photolib.share;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("project_share_link")
public class ProjectShareLinkEntity extends BaseEntity {
    private String token;
    private Long projectId;
    private String name;
    private String passwordHash;
    private Boolean allowDownload;
    private Boolean allowAdoption;
    private LocalDateTime expiresAt;
    private Long viewCount;
    private LocalDateTime lastViewedAt;
    private Long createdBy;
}
