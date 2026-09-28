package cn.photolib.form;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FormFileUploadMapper extends BaseMapper<FormFileUploadEntity> {
    @Select("""
            SELECT COUNT(*) FROM form_file_upload
            WHERE owner_type=#{ownerType} AND owner_ref=#{ownerRef} AND status='PENDING'
            """)
    long countPending(@Param("ownerType") FormFileOwnerType ownerType,
                      @Param("ownerRef") String ownerRef);

    @Select("""
            SELECT * FROM form_file_upload WHERE id=#{id} FOR UPDATE
            """)
    FormFileUploadEntity findByIdForUpdate(@Param("id") String id);

    /** 条件更新：同一个文件被两份并发提交引用时，只有一份能把它从 PENDING 改走。 */
    @Update("""
            UPDATE form_file_upload SET status='ATTACHED', attached_at=#{now}
            WHERE id=#{id} AND status='PENDING'
            """)
    int attach(@Param("id") String id, @Param("now") LocalDateTime now);

    /** 预签名 PUT 已经过期、却始终没被提交引用的文件。 */
    @Select("""
            SELECT * FROM form_file_upload
            WHERE status='PENDING' AND upload_url_expires_at < #{before}
            ORDER BY upload_url_expires_at ASC
            LIMIT #{limit}
            """)
    List<FormFileUploadEntity> findAbandoned(@Param("before") LocalDateTime before,
                                             @Param("limit") int limit);

    @Delete("DELETE FROM form_file_upload WHERE id=#{id} AND status='PENDING'")
    int deletePending(@Param("id") String id);
}
