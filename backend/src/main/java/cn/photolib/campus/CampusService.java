package cn.photolib.campus;

import cn.photolib.campus.mapper.CampusMapper;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CampusService {
    private final CampusMapper mapper;

    @Transactional
    public CampusEntity create(String code, String name) {
        CampusEntity campus = new CampusEntity();
        campus.setCode(code.toUpperCase());
        campus.setName(name);
        campus.setEnabled(true);
        mapper.insert(campus);
        return campus;
    }

    public List<CampusEntity> list(Boolean enabled) {
        return mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CampusEntity>()
                .eq(enabled != null, CampusEntity::getEnabled, enabled)
                .orderByAsc(CampusEntity::getName));
    }

    public CampusEntity get(Long id) {
        CampusEntity campus = mapper.selectById(id);
        if (campus == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "校区不存在");
        }
        return campus;
    }

    @Transactional
    public CampusEntity update(Long id, String name, boolean enabled, int version) {
        CampusEntity campus = get(id);
        campus.setName(name);
        campus.setEnabled(enabled);
        campus.setVersion(version);
        if (mapper.updateById(campus) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "校区已被其他操作修改");
        }
        return get(id);
    }
}
