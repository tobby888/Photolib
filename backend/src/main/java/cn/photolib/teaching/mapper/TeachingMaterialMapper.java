package cn.photolib.teaching.mapper;

import cn.photolib.teaching.model.TeachingMaterialEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 写操作全部写成显式 {@code @Update}，不依赖 {@code updateById}，
 * 因为每个写都要带乐观锁（{@code version=version+1 ... AND version=#{version}}），
 * 返回 0 行即表示冲突。
 */
@Mapper
public interface TeachingMaterialMapper extends BaseMapper<TeachingMaterialEntity> {

    /** 全部未删除资料，作者与上传人的显示名一次 join 出来。 */
    @Select("""
            SELECT m.*, a.display_name AS author_display_name,
                   u.display_name AS uploader_display_name
            FROM teaching_material m
            LEFT JOIN app_user a ON a.id = m.author_id
            LEFT JOIN app_user u ON u.id = m.created_by
            WHERE m.deleted = FALSE
            ORDER BY m.created_at DESC, m.id DESC
            """)
    List<TeachingMaterialEntity> findAll();

    @Select("""
            SELECT m.*, a.display_name AS author_display_name,
                   u.display_name AS uploader_display_name
            FROM teaching_material m
            LEFT JOIN app_user a ON a.id = m.author_id
            LEFT JOIN app_user u ON u.id = m.created_by
            WHERE m.public_id = #{publicId} AND m.deleted = FALSE
            """)
    TeachingMaterialEntity findByPublicId(@Param("publicId") String publicId);

    @Select("""
            SELECT DISTINCT category FROM teaching_material
            WHERE deleted = FALSE ORDER BY category ASC
            """)
    List<String> distinctCategories();

    @Select("SELECT COUNT(*) FROM teaching_material WHERE deleted = FALSE")
    long countAll();

    @Select("""
            <script>
            SELECT COUNT(*) FROM teaching_material
            WHERE deleted = FALSE AND LOWER(title) = LOWER(#{title})
            <if test="excludeId != null">AND id &lt;&gt; #{excludeId}</if>
            </script>
            """)
    long countByTitle(@Param("title") String title, @Param("excludeId") Long excludeId);

    @Update("""
            UPDATE teaching_material
            SET title = #{title}, description = #{description}, category = #{category},
                author_id = #{authorId}, updated_by = #{updatedBy},
                version = version + 1, updated_at = #{now}
            WHERE id = #{id} AND deleted = FALSE AND version = #{version}
            """)
    int updateMetadata(@Param("id") long id, @Param("title") String title,
                       @Param("description") String description, @Param("category") String category,
                       @Param("authorId") Long authorId, @Param("updatedBy") long updatedBy,
                       @Param("version") int version, @Param("now") LocalDateTime now);

    /** 原地换文件：对象键跟 publicId 走，所以读者手上的链接继续有效。 */
    @Update("""
            UPDATE teaching_material
            SET object_key = #{objectKey}, content_size = #{contentSize}, format = #{format},
                updated_by = #{updatedBy}, version = version + 1, updated_at = #{now}
            WHERE id = #{id} AND deleted = FALSE AND version = #{version}
            """)
    int updateFile(@Param("id") long id, @Param("objectKey") String objectKey,
                   @Param("contentSize") long contentSize, @Param("format") String format,
                   @Param("updatedBy") long updatedBy, @Param("version") int version,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE teaching_material SET download_count = download_count + 1
            WHERE id = #{id} AND deleted = FALSE
            """)
    int incrementDownloadCount(@Param("id") long id);

    /**
     * 重命名分类 = 把这一类的资料批量改到新名字。分类是单层字符串，没有独立的表。
     *
     * <p>照样要推进 {@code version}：分类是用户可编辑的元数据，{@code updateMetadata} 对它会做
     * 乐观锁。不推进的话，一个在重命名之前打开编辑框的人再保存，就能悄悄把这行的分类改回旧值。</p>
     */
    @Update("""
            UPDATE teaching_material
            SET category = #{toCategory}, version = version + 1, updated_at = #{now}
            WHERE deleted = FALSE AND category = #{fromCategory}
            """)
    int renameCategory(@Param("fromCategory") String fromCategory,
                       @Param("toCategory") String toCategory, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE teaching_material SET deleted = TRUE, version = version + 1, updated_at = #{now}
            WHERE id = #{id} AND deleted = FALSE AND version = #{version}
            """)
    int softDelete(@Param("id") long id, @Param("version") int version,
                   @Param("now") LocalDateTime now);
}
