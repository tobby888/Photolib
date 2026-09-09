package cn.photolib.statistics;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("export_job")
public class ExportJobEntity {
    @TableId private String id;
    private String type;
    private String status;
    private Integer progress;
    private Long createdBy;
    /**
     * 分享链接发起的打包任务归属的链接（其余任务为 null）。
     * 匿名访客查任务状态时只能靠它判定归属——{@code createdBy} 记的是链接创建者，
     * 对访客而言不是凭据。
     */
    private Long shareLinkId;
    private String objectKey;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
