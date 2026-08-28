package cn.photolib.featured.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一条指派记录。{@code campusId} 与 {@code userId} 恰好一个非空：
 * 前者表示"该校区的全部负责人"，后者表示单独点名的一位负责人。
 */
@Getter
@Setter
@TableName("featured_collection_assignment")
public class FeaturedAssignmentEntity {
    @TableId
    private Long id;
    private Long collectionId;
    private Long campusId;
    private Long userId;
    private LocalDateTime createdAt;
}
