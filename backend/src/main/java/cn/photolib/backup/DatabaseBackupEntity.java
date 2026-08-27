package cn.photolib.backup;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("database_backup")
public class DatabaseBackupEntity {
    @TableId private String id;
    private String type;
    private String status;
    private String objectKey;
    private Long sizeBytes;
    private String sha256;
    private Integer tableCount;
    private Long rowCount;
    private String schemaVersion;
    private Integer migrationCount;
    private String errorMessage;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
