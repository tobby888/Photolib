package cn.photolib.featured.mapper;

import cn.photolib.common.util.LikeFilter;
import cn.photolib.featured.model.FeaturedCollectionEntity;
import cn.photolib.featured.model.FeaturedCollectionStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeaturedCollectionMapper extends BaseMapper<FeaturedCollectionEntity> {

    @Select("""
            <script>
            SELECT c.*, u.display_name AS creator_display_name,
                   (SELECT COUNT(*) FROM featured_entry e WHERE e.collection_id=c.id) AS entry_count
            FROM featured_collection c
            JOIN app_user u ON u.id=c.created_by
            WHERE c.deleted=FALSE
            <if test="status != null">AND c.status=#{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (LOWER(c.title) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!'
                   OR LOWER(COALESCE(c.requirement_text, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!')
            </if>
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<FeaturedCollectionEntity> findPageQuery(@Param("status") FeaturedCollectionStatus status,
                                                 @Param("keyword") String keyword,
                                                 @Param("limit") int limit,
                                                 @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM featured_collection c
            WHERE c.deleted=FALSE
            <if test="status != null">AND c.status=#{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (LOWER(c.title) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!'
                   OR LOWER(COALESCE(c.requirement_text, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!')
            </if>
            </script>
            """)
    long countPageQuery(@Param("status") FeaturedCollectionStatus status,
                        @Param("keyword") String keyword);

    /**
     * 列表查询。语句里写死了 {@code ESCAPE '!'}，所以关键词必须先经过
     * {@link LikeFilter#escape}，否则用户搜一个字面量 {@code %} 会匹配到全部精选。
     * 列表与计数两条语句的筛选条件必须同步修改，否则分页总数会算错。
     */
    default List<FeaturedCollectionEntity> findPage(FeaturedCollectionStatus status, String keyword,
                                                    int limit, long offset) {
        return findPageQuery(status, LikeFilter.escape(keyword), limit, offset);
    }

    default long countPage(FeaturedCollectionStatus status, String keyword) {
        return countPageQuery(status, LikeFilter.escape(keyword));
    }

    @Select("""
            SELECT c.*, u.display_name AS creator_display_name,
                   (SELECT COUNT(*) FROM featured_entry e WHERE e.collection_id=c.id) AS entry_count
            FROM featured_collection c
            JOIN app_user u ON u.id=c.created_by
            WHERE c.id=#{id} AND c.deleted=FALSE
            """)
    FeaturedCollectionEntity findViewById(@Param("id") long id);

    /** 已发布且已过截止时间的精选，供定时任务关闭。 */
    @Select("""
            SELECT id FROM featured_collection
            WHERE deleted=FALSE AND status='PUBLISHED' AND ends_at <= #{now}
            ORDER BY ends_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> findDueForClose(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 以状态为条件的原子关闭。定时任务与部长手动截止可能同时发生，靠
     * {@code status='PUBLISHED'} 这个条件保证只有一方成功，文档生成也就只会被触发一次。
     */
    @Update("""
            UPDATE featured_collection
            SET status='CLOSED', closed_by=#{closedBy}, closed_at=#{closedAt},
                closed_reason=#{reason}, document_status='GENERATING',
                document_error=NULL, version=version+1, updated_at=#{closedAt}
            WHERE id=#{id} AND deleted=FALSE AND status='PUBLISHED'
            """)
    int closeIfPublished(@Param("id") long id, @Param("closedBy") Long closedBy,
                         @Param("closedAt") LocalDateTime closedAt, @Param("reason") String reason);

    /**
     * 文档生成结果写回。同样带状态条件：只有仍处于 GENERATING 的行会被改写，
     * 避免一次迟到的生成结果覆盖掉后来重新生成的成功文档。
     */
    @Update("""
            UPDATE featured_collection
            SET document_status=#{status}, document_object_key=#{objectKey},
                document_size=#{size}, document_generated_at=#{generatedAt},
                document_error=#{error}, version=version+1, updated_at=#{generatedAt}
            WHERE id=#{id} AND document_status='GENERATING'
            """)
    int finishDocument(@Param("id") long id, @Param("status") String status,
                       @Param("objectKey") String objectKey, @Param("size") Long size,
                       @Param("generatedAt") LocalDateTime generatedAt,
                       @Param("error") String error);

    /**
     * 逻辑删除。刻意不用 {@code updateById}：MyBatis-Plus 会把 {@code @TableLogic}
     * 标注的字段从普通更新里剔除，那样写只会白白 +1 version，行还留在所有查询里。
     */
    @Update("""
            UPDATE featured_collection
            SET deleted=TRUE, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version}
            """)
    int softDelete(@Param("id") long id, @Param("version") int version,
                   @Param("now") LocalDateTime now);

    /** 重新生成：只允许把已截止且当前不在生成中的精选重新推回 GENERATING。 */
    @Update("""
            UPDATE featured_collection
            SET document_status='GENERATING', document_error=NULL,
                version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND status='CLOSED' AND document_status <> 'GENERATING'
            """)
    int markRegenerating(@Param("id") long id, @Param("now") LocalDateTime now);
}
