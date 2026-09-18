package cn.photolib.share;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("project_share_session")
public class ProjectShareSessionEntity {
    @TableId(type = IdType.INPUT) private String id;
    private Long linkId;
    private String tokenHash;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    /**
     * 上传链接的访客进门时自报的身份，作为这次会话里每一张照片的拍摄者快照。
     * 浏览链接的会话为 {@code null}。理由见 Flyway V48 的注释。
     */
    private String uploaderName;
    private String uploaderStudentId;
}
