package cn.photolib.directory;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.directory.mapper.CampusMemberMapper;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CampusMemberService {
    private final CampusMemberMapper mapper;
    private final CampusService campusService;

    public List<CampusMemberEntity> list(Long campusId, Boolean enabled, AuthenticatedUser user) {
        Long effectiveCampusId = effectiveCampus(campusId, user);
        if (Boolean.TRUE.equals(enabled) && effectiveCampusId != null
                && !Boolean.TRUE.equals(campusService.get(effectiveCampusId).getEnabled())) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<CampusMemberEntity>lambdaQuery()
                .eq(effectiveCampusId != null, CampusMemberEntity::getCampusId, effectiveCampusId)
                .eq(enabled != null, CampusMemberEntity::getEnabled, enabled)
                .orderByAsc(CampusMemberEntity::getName)
                .orderByAsc(CampusMemberEntity::getStudentId));
    }

    /**
     * 部长/管理员的通讯录去重视图：把所有校区启用成员按学号合并去重。
     * 每个学号取最小 id 的成员行作为代表（提供一个可用于快照的 contactId），
     * campusNames 汇总该学号出现过的所有校区名。
     */
    public List<DedupedMember> listDeduped() {
        Map<Long, String> campusNames = campusService.list(true).stream()
                .collect(Collectors.toMap(CampusEntity::getId, CampusEntity::getName, (a, b) -> a));
        if (campusNames.isEmpty()) return List.of();
        List<CampusMemberEntity> members = mapper.selectList(Wrappers.<CampusMemberEntity>lambdaQuery()
                .eq(CampusMemberEntity::getEnabled, true)
                .in(CampusMemberEntity::getCampusId, campusNames.keySet())
                .orderByAsc(CampusMemberEntity::getId));
        LinkedHashMap<String, CampusMemberEntity> representative = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> campuses = new LinkedHashMap<>();
        for (CampusMemberEntity member : members) {
            String key = member.getStudentId();
            representative.putIfAbsent(key, member);
            List<String> names = campuses.computeIfAbsent(key, k -> new ArrayList<>());
            String campusName = campusNames.get(member.getCampusId());
            if (campusName != null && !names.contains(campusName)) {
                names.add(campusName);
            }
        }
        return representative.entrySet().stream()
                .map(entry -> new DedupedMember(entry.getValue().getId(), entry.getKey(),
                        entry.getValue().getName(), campuses.get(entry.getKey())))
                .sorted(Comparator.comparing(DedupedMember::name).thenComparing(DedupedMember::studentId))
                .toList();
    }

    /**
     * 解析并校验上传照片时选择的拍摄者（来自通讯录）。
     * campusId 非空（需求上传 / 校区负责人图库上传）时成员必须属于该校区；
     * campusId 为空（部长/管理员无校区的图库上传，来自去重视图的代表 id）时接受任意启用成员。
     */
    public CampusMemberEntity resolvePhotographer(Long contactId, Long campusId) {
        if (contactId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请从通讯录选择拍摄者");
        }
        CampusMemberEntity member = require(contactId);
        if (!Boolean.TRUE.equals(member.getEnabled())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择有效的通讯录成员");
        }
        requireEnabledCampus(member.getCampusId());
        if (campusId != null && !member.getCampusId().equals(campusId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择该校区通讯录中的成员");
        }
        return member;
    }

    public CampusMemberEntity getForWorklog(Long id, Long requestCampusId) {
        CampusMemberEntity member = require(id);
        requireEnabledCampus(member.getCampusId());
        if (!Boolean.TRUE.equals(member.getEnabled()) || !member.getCampusId().equals(requestCampusId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择需求所属校区通讯录中的有效成员");
        }
        return member;
    }

    @Transactional
    public CampusMemberEntity create(Long campusId, String studentId, String name, AuthenticatedUser user) {
        Long effectiveCampusId = requireWritableCampus(campusId, user);
        requireEnabledCampus(effectiveCampusId);
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
        requireEnabledCampus(member.getCampusId());
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

    /**
     * 从通讯录彻底删除成员（物理删除）。历史工时/照片仅存姓名学号快照、不引用本行，删除不改写历史；
     * 物理删除同时释放唯一约束 (campus_id, student_id)，使同一学号可重新添加。
     * 若只是暂时不可选，应改用停用（update 置 enabled=false）。
     */
    @Transactional
    public void delete(Long id, AuthenticatedUser user) {
        CampusMemberEntity member = require(id);
        requireWritableCampus(member.getCampusId(), user);
        requireEnabledCampus(member.getCampusId());
        mapper.deletePhysically(member.getId());
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
        if (user.role() == UserRole.ADMIN || user.role() == UserRole.MINISTER) {
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

    private void requireEnabledCampus(Long campusId) {
        if (!Boolean.TRUE.equals(campusService.get(campusId).getEnabled())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "校区已停用，不能维护通讯录或选择拍摄者");
        }
    }

    public record DedupedMember(Long id, String studentId, String name, List<String> campusNames) {}
}
