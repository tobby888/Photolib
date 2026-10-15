package cn.photolib.project.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 选题置顶（V65）的写入。
 *
 * <p>必须走 MyBatis 而不是 {@code JdbcClient}：选题列表是 MyBatis 查的，同一个会话里
 * 绕过 MyBatis 写库不会清掉它的一级缓存，紧接着再查一次列表会拿到置顶之前的顺序。
 * 与 {@code PhotoService.batchTags} 必须走 mapper 是同一个理由。</p>
 */
@Mapper
public interface ProjectPinMapper {
    /** INSERT IGNORE：同一个人双击两下的并发请求不该有一个报主键冲突。 */
    @Insert("INSERT IGNORE INTO project_pin (user_id, project_id) VALUES (#{userId}, #{projectId})")
    int pin(@Param("userId") Long userId, @Param("projectId") Long projectId);

    @Delete("DELETE FROM project_pin WHERE user_id = #{userId} AND project_id = #{projectId}")
    int unpin(@Param("userId") Long userId, @Param("projectId") Long projectId);
}
