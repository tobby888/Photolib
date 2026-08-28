package cn.photolib.featured.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 精选条目。刻意不继承 {@code BaseEntity}：这张表没有 deleted 列，条目在截止前
 * 由填报人物理增删，这样 {@code (collection_id, photo_id)} 唯一键在删除后允许
 * 重新加入同一张图片。拍摄人、拍摄时间与标题是提交时从图库快照过来的。
 */
@Getter
@Setter
@TableName("featured_entry")
public class FeaturedEntryEntity {
    @TableId
    private Long id;
    private Long collectionId;
    private Long photoId;
    private Long campusId;
    private Long submittedBy;
    private String idea;
    private String location;
    private String photographerName;
    private String photographerStudentId;
    private LocalDateTime takenAt;
    private String photoTitle;
    private Integer sortOrder;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private String campusName;

    @TableField(exist = false)
    private String submitterDisplayName;
}
