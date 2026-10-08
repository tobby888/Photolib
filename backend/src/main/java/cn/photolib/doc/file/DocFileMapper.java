package cn.photolib.doc.file;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件库的查询。
 *
 * <p><b>列表里的读者范围条件是 {@code DocAudience.allows} 在 SQL 里的投影</b>，两边必须一致：
 * 分页只能在库里做，所以列表这一处没法像下载那样逐条调 Java 判定。不变量是"列表不得列出
 * 下载不了的文件"，{@code DocFileServiceTests.listingNeverShowsAFileTheReaderCannotDownload}
 * 钉住了它——改这里的条件，必须同时改 {@code DocFileService.canDownload}。</p>
 */
@Mapper
public interface DocFileMapper extends BaseMapper<DocFileEntity> {

    String VISIBLE = """
            <if test="!manager">
              AND (f.visibility='PUBLIC'
              <if test="readerId != null">
                OR f.visibility='MEMBERS'
                OR f.uploaded_by=#{readerId}
                OR (f.visibility='RESTRICTED' AND EXISTS (
                      SELECT 1 FROM resource_access_grant g
                      WHERE g.resource_type='FILE' AND g.resource_id=f.id
                        AND ((g.grantee_type='USER' AND g.grantee_id=#{readerId})
                        <if test="readerGroupId != null">
                             OR (g.grantee_type='GROUP' AND g.grantee_id=#{readerGroupId})
                        </if>)))
              </if>)
            </if>
            <if test="keyword != null">
              AND (LOWER(f.title) LIKE CONCAT('%', #{keyword}, '%') ESCAPE '!'
                   OR LOWER(f.file_name) LIKE CONCAT('%', #{keyword}, '%') ESCAPE '!')
            </if>
            <if test="uploaderId != null">AND f.uploaded_by=#{uploaderId}</if>
            """;

    @Select("<script>SELECT f.*, u.display_name AS uploader_display_name FROM doc_file f "
            + "LEFT JOIN app_user u ON u.id=f.uploaded_by WHERE f.deleted=FALSE " + VISIBLE
            + " ORDER BY f.created_at DESC, f.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<DocFileEntity> page(@Param("readerId") Long readerId, @Param("readerGroupId") Long readerGroupId,
                             @Param("manager") boolean manager, @Param("keyword") String keyword,
                             @Param("uploaderId") Long uploaderId,
                             @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM doc_file f WHERE f.deleted=FALSE " + VISIBLE + "</script>")
    long count(@Param("readerId") Long readerId, @Param("readerGroupId") Long readerGroupId,
               @Param("manager") boolean manager, @Param("keyword") String keyword,
               @Param("uploaderId") Long uploaderId);

    @Select("""
            SELECT f.*, u.display_name AS uploader_display_name FROM doc_file f
            LEFT JOIN app_user u ON u.id=f.uploaded_by
            WHERE f.public_id=#{publicId} AND f.deleted=FALSE
            """)
    DocFileEntity findByPublicId(@Param("publicId") String publicId);

    /** 未删除文件的总大小：每人存储空间按它算。 */
    @Select("SELECT COALESCE(SUM(size), 0) FROM doc_file WHERE uploaded_by=#{userId} AND deleted=FALSE")
    long usedBytes(@Param("userId") long userId);

    /** 某个时刻之后上传的个数，删掉的也算——否则"传了删、删了传"就绕过了每日个数。 */
    @Select("SELECT COUNT(*) FROM doc_file WHERE uploaded_by=#{userId} AND created_at >= #{since}")
    long uploadedSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

    @Update("""
            UPDATE doc_file SET title=#{title}, description=#{description}, visibility=#{visibility},
                updated_by=#{updatedBy}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version}
            """)
    int updateMetadata(@Param("id") long id, @Param("title") String title,
                       @Param("description") String description, @Param("visibility") String visibility,
                       @Param("updatedBy") long updatedBy, @Param("version") int version,
                       @Param("now") LocalDateTime now);

    /** 软删除。{@code @TableLogic} 标注的 deleted 会被普通更新剔除，只能自己写。 */
    @Update("""
            UPDATE doc_file SET deleted=TRUE, updated_by=#{updatedBy}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=FALSE AND version=#{version}
            """)
    int softDelete(@Param("id") long id, @Param("updatedBy") long updatedBy, @Param("version") int version,
                   @Param("now") LocalDateTime now);

    @Update("UPDATE doc_file SET download_count=download_count+1 WHERE id=#{id} AND deleted=FALSE")
    int incrementDownloadCount(@Param("id") long id);
}
