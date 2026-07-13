package cn.photolib.directory.mapper;

import cn.photolib.directory.CampusMemberEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface CampusMemberMapper extends BaseMapper<CampusMemberEntity> {
    /**
     * 物理删除通讯录成员：绕过 {@code @TableLogic} 的软删语义（deleteById 只会置 deleted=1）。
     * 历史工时/照片仅存姓名学号快照、不引用本行，物理删除可释放唯一约束 (campus_id, student_id) 供重加。
     */
    @Delete("DELETE FROM campus_member WHERE id = #{id}")
    int deletePhysically(@Param("id") Long id);
}
