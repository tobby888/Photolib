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
     * 读取一张插图所需的全部判定，一次查询完成：图片本身、所属文档是否还在、
     * 是否已发布、以及这位读者够不够格看它。
     *
     * <p>插图的可见性必须和它所在文档完全一致，这是安全边界而不是体验问题：
     * 未发布文档里的图片、以及 MEMBERS 文档里的图片，如果能被匿名直链读到，
     * 那么把图片地址发出去就绕过了发布开关和登录要求。所以这里 join 回
     * doc_node 判断，而不是只按 asset id 查。</p>
     *
     * @param authenticated 调用方是否已登录；未登录时只放行 PUBLIC 文档的插图
     */
    @Select("""
            <script>
            SELECT a.* FROM doc_asset a
            JOIN doc_node n ON n.id = a.node_id
            WHERE a.id=#{id} AND n.deleted=FALSE AND n.published=TRUE
            <if test="!authenticated">AND n.visibility='PUBLIC'</if>
            </script>
            """)
    DocAssetEntity findReadable(@Param("id") String id,
                                @Param("authenticated") boolean authenticated);
}
