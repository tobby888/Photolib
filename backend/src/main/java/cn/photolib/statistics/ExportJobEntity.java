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
    private String objectKey;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
