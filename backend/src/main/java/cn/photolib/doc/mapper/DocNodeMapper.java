package cn.photolib.doc.mapper;

import cn.photolib.doc.model.DocNodeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 写操作全部写成显式 {@code @Update}，不用 {@code updateById}。原因有两个，
 * 缺一个都会出静默的错：
 * <ul>
 *   <li>MyBatis-Plus 默认跳过 null 字段，于是"把节点移到根目录"（parent_id=NULL）
 *       和"取消发布"（published_at=NULL）用 updateById 写不进去；</li>
 *   <li>{@code @TableLogic} 标注的 deleted 会被普通更新剔除，软删只能自己写。</li>
 * </ul>
 * 因此这里的语句都自己带 {@code version=version+1} 和 {@code version=#{version}} 条件，
 * 返回 0 行即表示乐观锁冲突。
 */
@Mapper
public interface DocNodeMapper extends BaseMapper<DocNodeEntity> {

    /**
     * 整棵树。文档树是人手维护的小结构（上限 {@code DocService.MAX_NODES}），
     * 所以父子关系、深度、环检测和排序全部在内存里算，不用递归 CTE。
     * 排序键必须和这里保持一致，否则拖拽后的顺序刷新一下就跳动。
     */
    @Select("""
            SELECT n.*, u.display_name AS updater_display_name
            FROM doc_node n
            LEFT JOIN app_user u ON u.id = COALESCE(n.updated_by, n.created_by)
            WHERE n.deleted = FALSE
            ORDER BY n.sort_order ASC, n.id ASC
            """)
    List<DocNodeEntity> findAll();

    @Select("""
            SELECT n.*, u.display_name AS updater_display_name
            FROM doc_node n
            LEFT JOIN app_user u ON u.id = COALESCE(n.updated_by, n.created_by)
            WHERE n.public_id=#{publicId} AND n.deleted=FALSE
            """)
    DocNodeEntity findByPublicId(@Param("publicId") String publicId);

    /**
     * 同级重名检查。按 node_type 分开判：Obsidian 里同名的文件夹和笔记可以共存，
     * 这里保持同样的手感。根目录的 parent_id 是 NULL，所以不能直接写 {@code =}。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM doc_node
            WHERE deleted=FALSE AND node_type=#{nodeType} AND LOWER(title)=LOWER(#{title})
            <choose>
              <when test="parentId == null">AND parent_id IS NULL</when>
              <otherwise>AND parent_id=#{parentId}</otherwise>
            </choose>
            <if test="excludeId != null">AND id &lt;&gt; #{excludeId}</if>
            </script>
            """)
    long countSiblingTitle(@Param("parentId") Long parentId, @Param("title") String title,
                           @Param("nodeType") String nodeType, @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM doc_node WHERE deleted=FALSE")
    long countAll();

    @Select("""
            <script>
            SELECT COALESCE(MAX(sort_order), -1) FROM doc_node WHERE deleted=FALSE
            <choose>
              <when test="parentId == null">AND parent_id IS NULL</when>
              <otherwise>AND parent_id=#{parentId}</otherwise>
            </choose>
            </script>
            """)
    int maxSortOrder(@Param("parentId") Long parentId);

    @Update("""
            UPDATE doc_node
            SET title=#{title}, updated_by=#{updatedBy}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version}
            """)
    int updateTitle(@Param("id") long id, @Param("title") String title,
                    @Param("updatedBy") long updatedBy, @Param("version") int version,
                    @Param("now") LocalDateTime now);

    @Update("""
            UPDATE doc_node
            SET object_key=#{objectKey}, content_size=#{contentSize}, summary=#{summary},
                updated_by=#{updatedBy}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version} AND node_type='DOCUMENT'
            """)
    int updateContent(@Param("id") long id, @Param("objectKey") String objectKey,
                      @Param("contentSize") long contentSize, @Param("summary") String summary,
                      @Param("updatedBy") long updatedBy, @Param("version") int version,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE doc_node
            SET published=#{published}, published_at=#{publishedAt}, updated_by=#{updatedBy},
                version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version} AND node_type='DOCUMENT'
            """)
    int updatePublished(@Param("id") long id, @Param("published") boolean published,
                        @Param("publishedAt") LocalDateTime publishedAt,
                        @Param("updatedBy") long updatedBy, @Param("version") int version,
                        @Param("now") LocalDateTime now);

    /** 可见性和发布状态是两个独立开关，所以分成两条语句，各自带自己的乐观锁。 */
    @Update("""
            UPDATE doc_node
            SET visibility=#{visibility}, updated_by=#{updatedBy},
                version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version} AND node_type='DOCUMENT'
            """)
    int updateVisibility(@Param("id") long id, @Param("visibility") String visibility,
                         @Param("updatedBy") long updatedBy, @Param("version") int version,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE doc_node
            SET parent_id=#{parentId}, sort_order=#{sortOrder}, updated_by=#{updatedBy},
                version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version}
            """)
    int moveNode(@Param("id") long id, @Param("parentId") Long parentId,
                 @Param("sortOrder") int sortOrder, @Param("updatedBy") long updatedBy,
                 @Param("version") int version, @Param("now") LocalDateTime now);

    /**
     * 同级重排。拖拽后把整组兄弟重写成 0..n-1，所以只按 id 定位、不带 version：
     * 排序不是需要乐观锁保护的业务字段，而且一次拖拽要写多行，
     * 任何一行的版本冲突都会毁掉整次操作。
     */
    @Update("""
            UPDATE doc_node SET sort_order=#{sortOrder}, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE
            """)
    int updateSortOrder(@Param("id") long id, @Param("sortOrder") int sortOrder,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE doc_node SET deleted=TRUE, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version}
            """)
    int softDelete(@Param("id") long id, @Param("version") int version,
                   @Param("now") LocalDateTime now);

    /** 子孙节点跟随父节点一起软删；它们自己的 version 无人持有，所以不带条件。 */
    @Update("""
            UPDATE doc_node SET deleted=TRUE, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE
            """)
    int softDeleteCascade(@Param("id") long id, @Param("now") LocalDateTime now);
}
