package cn.photolib.featured.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("featured_collection")
public class FeaturedCollectionEntity extends BaseEntity {
    private String title;
    private String requirementHtml;
    private String requirementText;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private FeaturedCollectionStatus status;
    private Boolean assignAll;
    private Integer entryLimit;
    private FeaturedDocumentStatus documentStatus;
    private String documentObjectKey;
    private Long documentSize;
    private LocalDateTime documentGeneratedAt;
    private String documentError;
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private Long closedBy;
    private LocalDateTime closedAt;
    private FeaturedCloseReason closedReason;

    @TableField(exist = false)
    private String creatorDisplayName;

    @TableField(exist = false)
    private Long entryCount;
}
