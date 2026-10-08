package cn.photolib.doc.mapper;

import cn.photolib.doc.model.DocAssetEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocAssetMapper extends BaseMapper<DocAssetEntity> {

    @Select("SELECT * FROM doc_asset WHERE node_id=#{nodeId} ORDER BY created_at ASC, id ASC")
    List<DocAssetEntity> findByNode(@Param("nodeId") long nodeId);

    /**
     * 一张挂在"未删除、已发布"文档上的插图。这只是第一道筛：读者够不够格看它，
     * 由 {@code DocService.readerAsset} 拿所属文档再走一遍 {@code visibleTo}。
     *
     * <p>插图的可见性必须和它所在文档完全一致，这是安全边界而不是体验问题：
     * 未发布文档里的图片、以及限定读者的文档里的图片，如果能被直链读到，
     * 那么把图片地址发出去就绕过了发布开关和读者范围。所以这里 join 回
     * doc_node，而不是只按 asset id 查；"谁能读"不写进 SQL，是因为指定读者的名单
     * 和文档、文件共用一套判定（{@code DocAudience}），在 SQL 里另写一份迟早会漂移。</p>
     */
    @Select("""
            SELECT a.* FROM doc_asset a
            JOIN doc_node n ON n.id = a.node_id
            WHERE a.id=#{id} AND n.deleted=FALSE AND n.published=TRUE
            """)
    DocAssetEntity findPublished(@Param("id") String id);
}
