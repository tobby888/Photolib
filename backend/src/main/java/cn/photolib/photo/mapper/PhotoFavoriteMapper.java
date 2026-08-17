package cn.photolib.photo.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PhotoFavoriteMapper {

    /**
     * MySQL's INSERT IGNORE plus the (user_id, photo_id) primary key makes
     * repeated and concurrent favorite requests idempotent.
     */
    @Insert("""
            INSERT IGNORE INTO photo_favorite (user_id, photo_id)
            VALUES (#{userId}, #{photoId})
            """)
    int add(@Param("userId") Long userId, @Param("photoId") Long photoId);

    @Delete("""
            DELETE FROM photo_favorite
            WHERE user_id=#{userId} AND photo_id=#{photoId}
            """)
    int remove(@Param("userId") Long userId, @Param("photoId") Long photoId);

    @Select("""
            SELECT COUNT(*) FROM photo_favorite
            WHERE user_id=#{userId} AND photo_id=#{photoId}
            """)
    long count(@Param("userId") Long userId, @Param("photoId") Long photoId);

    @Select("""
            <script>
            SELECT photo_id
            FROM photo_favorite
            WHERE user_id=#{userId}
              AND photo_id IN
              <foreach collection="photoIds" item="photoId" open="(" separator="," close=")">
                #{photoId}
              </foreach>
            </script>
            """)
    List<Long> findFavoritePhotoIds(@Param("userId") Long userId,
                                    @Param("photoIds") List<Long> photoIds);
}
