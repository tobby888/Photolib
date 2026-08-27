package cn.photolib.backup;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("database_restore")
public class DatabaseRestoreEntity {
    @TableId private String id;
    private String backupId;
    private String safetyBackupId;
    private String status;
    private Integer tableCount;
    private Long rowCount;
    private String errorMessage;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
