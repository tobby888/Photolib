package cn.photolib.directory;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.directory.mapper.CampusMemberMapper;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CampusMemberService {
    private final CampusMemberMapper mapper;
    private final CampusService campusService;

    public List<CampusMemberEntity> list(Long campusId, Boolean enabled, AuthenticatedUser user) {
        Long effectiveCampusId = effectiveCampus(campusId, user);
        return mapper.selectList(Wrappers.<CampusMemberEntity>lambdaQuery()
                .eq(effectiveCampusId != null, CampusMemberEntity::getCampusId, effectiveCampusId)
                .eq(enabled != null, CampusMemberEntity::getEnabled, enabled)
                .orderByAsc(CampusMemberEntity::getName)
                .orderByAsc(CampusMemberEntity::getStudentId));
    }

    public CampusMemberEntity getForWorklog(Long id, Long requestCampusId) {
        CampusMemberEntity member = require(id);
        if (!Boolean.TRUE.equals(member.getEnabled()) || !member.getCampusId().equals(requestCampusId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择需求所属校区通讯录中的有效成员");
        }
        return member;
    }

    @Transactional
    public CampusMemberEntity create(Long campusId, String studentId, String name, AuthenticatedUser user) {
        Long effectiveCampusId = requireWritableCampus(campusId, user);
        campusService.get(effectiveCampusId);
        String normalizedStudentId = studentId.trim();
        CampusMemberEntity existing = mapper.selectOne(Wrappers.<CampusMemberEntity>lambdaQuery()
                .eq(CampusMemberEntity::getCampusId, effectiveCampusId)
                .eq(CampusMemberEntity::getStudentId, normalizedStudentId));
        if (existing != null) {
            existing.setName(name.trim());
            existing.setEnabled(true);
            mapper.updateById(existing);
            return require(existing.getId());
        }
        CampusMemberEntity member = new CampusMemberEntity();
        member.setCampusId(effectiveCampusId);
        member.setStudentId(normalizedStudentId);
        member.setName(name.trim());
        member.setEnabled(true);
        try {
            mapper.insert(member);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "该校区通讯录中已存在这个学号");
        }
        return member;
    }

    @Transactional
    public CampusMemberEntity update(Long id, String studentId, String name, boolean enabled,
                                     int version, AuthenticatedUser user) {
        CampusMemberEntity member = require(id);
        requireWritableCampus(member.getCampusId(), user);
        member.setStudentId(studentId.trim());
        member.setName(name.trim());
        member.setEnabled(enabled);
        member.setVersion(version);
        try {
            if (mapper.updateById(member) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "通讯录成员已被其他操作修改");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "该校区通讯录中已存在这个学号");
        }
        return require(id);
    }

    @Transactional
    public void delete(Long id, AuthenticatedUser user) {
        CampusMemberEntity member = require(id);
        requireWritableCampus(member.getCampusId(), user);
        member.setEnabled(false);
        mapper.updateById(member);
    }

    private CampusMemberEntity require(Long id) {
        CampusMemberEntity member = mapper.selectById(id);
        if (member == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "通讯录成员不存在");
        }
        return member;
    }

    private Long effectiveCampus(Long requestedCampusId, AuthenticatedUser user) {
        if (user.role() == UserRole.CAMPUS_MANAGER) {
            if (user.campusId() == null) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号未关联校区");
            }
            return user.campusId();
        }
        return requestedCampusId;
    }

    private Long requireWritableCampus(Long requestedCampusId, AuthenticatedUser user) {
        if (user.role() == UserRole.ADMIN) {
            if (requestedCampusId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择校区");
            }
            return requestedCampusId;
        }
        if (user.role() == UserRole.CAMPUS_MANAGER && user.campusId() != null
                && user.campusId().equals(requestedCampusId == null ? user.campusId() : requestedCampusId)) {
            return user.campusId();
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "只能维护所属校区的通讯录");
    }
}
