package cn.photolib.featured.mapper;

import cn.photolib.featured.model.FeaturedEntryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FeaturedEntryMapper extends BaseMapper<FeaturedEntryEntity> {
    /**
     * 一份精选的全部条目，按"分章节"所需的顺序返回：先按校区编码，再按填报人给出的
     * 顺序，最后按 id 兜底。Word 文档的章节顺序与页面分组都直接沿用这一个顺序，
     * 不要在任何一侧再排一次，否则文档和页面会出现不同的排列。
     */
    @Select("""
            SELECT e.*, COALESCE(c.name, '未分配校区') AS campus_name,
                   u.display_name AS submitter_display_name
            FROM featured_entry e
            LEFT JOIN campus c ON c.id=e.campus_id
            JOIN app_user u ON u.id=e.submitted_by
            WHERE e.collection_id=#{collectionId}
            ORDER BY CASE WHEN e.campus_id IS NULL THEN 1 ELSE 0 END, c.code, c.id, e.sort_order, e.id
            """)
    List<FeaturedEntryEntity> findByCollection(@Param("collectionId") long collectionId);

    @Select("""
            SELECT COUNT(*) FROM featured_entry
            WHERE collection_id=#{collectionId} AND submitted_by=#{userId}
            """)
    long countBySubmitter(@Param("collectionId") long collectionId, @Param("userId") long userId);

    @Select("""
            SELECT COALESCE(MAX(sort_order), 0) FROM featured_entry
            WHERE collection_id=#{collectionId} AND submitted_by=#{userId}
            """)
    int maxSortOrder(@Param("collectionId") long collectionId, @Param("userId") long userId);

    /**
     * 列表页一次取回"当前用户在这批精选里各提交了几条"。没有条目的精选不会出现在
     * 结果里，调用方按缺省 0 处理。
     */
    @Select("""
            <script>
            SELECT collection_id AS collectionId, COUNT(*) AS total
            FROM featured_entry
            WHERE submitted_by=#{userId} AND collection_id IN
            <foreach item="id" collection="collectionIds" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY collection_id
            </script>
            """)
    List<SubmitterCount> countBySubmitterForCollections(
            @Param("collectionIds") java.util.Collection<Long> collectionIds,
            @Param("userId") long userId);

    record SubmitterCount(Long collectionId, Long total) {
    }
}
