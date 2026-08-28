package cn.photolib.featured.mapper;

import cn.photolib.featured.model.FeaturedAssignmentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FeaturedAssignmentMapper extends BaseMapper<FeaturedAssignmentEntity> {
    /**
     * 取回若干精选的指派行。列表页和单条详情共用它：详情传一个 id 即可，
     * 这样"指派"只有一条读取路径，不会出现两处各自查询后结论不一致。
     */
    @Select("""
            <script>
            SELECT * FROM featured_collection_assignment
            WHERE collection_id IN
            <foreach item="id" collection="collectionIds" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY collection_id, campus_id, user_id, id
            </script>
            """)
    List<FeaturedAssignmentEntity> findByCollections(
            @Param("collectionIds") java.util.Collection<Long> collectionIds);
}
