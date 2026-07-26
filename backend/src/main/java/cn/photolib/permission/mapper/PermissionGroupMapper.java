package cn.photolib.permission.mapper;

import cn.photolib.permission.PermissionGroupEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;

public interface PermissionGroupMapper extends BaseMapper<PermissionGroupEntity> {
    @Delete("DELETE FROM permission_group WHERE id = #{id} AND built_in = FALSE")
    int deletePhysically(Long id);
}
