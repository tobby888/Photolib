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
    /** 用途：看相册还是往里传图。建立后不可改，见 {@link ShareLinkPurpose}。 */
    private ShareLinkPurpose purpose;
    private Boolean allowDownload;
    private Boolean allowAdoption;
    private LocalDateTime expiresAt;
    private Long viewCount;
    private LocalDateTime lastViewedAt;
    /** 通过这条上传链接传进来的图片张数（完成上传时 +1）。 */
    private Long uploadCount;
    private Long createdBy;
}
