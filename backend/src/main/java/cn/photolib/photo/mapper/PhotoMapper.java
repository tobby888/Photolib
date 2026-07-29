package cn.photolib.photo.mapper;

import cn.photolib.photo.model.PhotoEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface PhotoMapper extends BaseMapper<PhotoEntity> {
    /**
     * Publishes all derived photo fields in one row/profile CAS. The profile
     * predicate is evaluated by the database in the same statement as the
     * PROCESSING -> AVAILABLE transition, closing the gap between encoding and
     * a profile switch committed by another application instance.
     *
     * <p>{@code thumbnailObjectKey} and {@code thumbnailSize} are both null when
     * preview encoding failed for this source. The photo still becomes
     * AVAILABLE; the gallery falls back to the finished object and the preview
     * repair pipeline regenerates the missing preview later.</p>
     */
    @Update("""
            UPDATE photo
            SET stored_file_name=#{storedFileName}, size=#{size}, width=#{width},
                height=#{height}, thumbnail_object_key=#{thumbnailObjectKey,jdbcType=VARCHAR},
                thumbnail_size=#{thumbnailSize,jdbcType=BIGINT}, original_delete_after=#{originalDeleteAfter},
                status='AVAILABLE', failure_reason=NULL,
                version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=0 AND status='PROCESSING' AND version=#{version}
              AND (
                  EXISTS (
                      SELECT 1 FROM preview_setting ps
                      WHERE ps.id=1 AND ps.compression_ratio=#{targetRatio}
                        AND ps.generator_fingerprint=#{targetGenerator}
                  )
                  OR (
                      #{bootstrapping}=1
                      AND (
                          (
                              #{observedProfilePresent}=1
                              AND EXISTS (
                                  SELECT 1 FROM preview_setting ps
                                  WHERE ps.id=1 AND ps.compression_ratio=#{observedRatio}
                                    AND ps.generator_fingerprint=#{observedGenerator}
                              )
                          )
                          OR (
                              #{observedProfilePresent}=0
                              AND NOT EXISTS (
                                  SELECT 1 FROM preview_setting ps WHERE ps.id=1
                              )
                          )
                      )
                  )
              )
            """)
    int completeProcessingWithProfileGuard(
            @Param("id") Long id,
            @Param("version") Integer version,
            @Param("storedFileName") String storedFileName,
            @Param("size") long size,
            @Param("width") int width,
            @Param("height") int height,
            @Param("thumbnailObjectKey") String thumbnailObjectKey,
            @Param("thumbnailSize") Long thumbnailSize,
            @Param("originalDeleteAfter") LocalDateTime originalDeleteAfter,
            @Param("targetRatio") BigDecimal targetRatio,
            @Param("targetGenerator") String targetGenerator,
            @Param("bootstrapping") int bootstrapping,
            @Param("observedProfilePresent") int observedProfilePresent,
            @Param("observedRatio") BigDecimal observedRatio,
            @Param("observedGenerator") String observedGenerator,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE photo
            SET status='UPLOADING', failure_reason=#{failureReason},
                version=version+1, updated_at=#{now}
            WHERE id=#{id} AND deleted=0 AND status='PROCESSING' AND version=#{version}
            """)
    int failProcessing(@Param("id") Long id, @Param("version") Integer version,
                       @Param("failureReason") String failureReason,
                       @Param("now") LocalDateTime now);
}
