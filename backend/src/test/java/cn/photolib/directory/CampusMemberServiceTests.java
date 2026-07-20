package cn.photolib.directory;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 通讯录服务测试：拍摄者解析校验、部长维护权限与去重视图。
 */
@SpringBootTest
@Transactional
class CampusMemberServiceTests {
    @Autowired
    private CampusMemberService service;
    @Autowired
    private CampusService campusService;
    @Autowired
    private JdbcClient jdbc;

    private CampusEntity campusA;
    private CampusEntity campusB;

    @BeforeEach
    void setUp() {
        campusA = campusService.create("DIRA", "甲校区");
        campusB = campusService.create("DIRB", "乙校区");
        jdbc.sql("""
                INSERT INTO campus_member (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES
                    (500, :a, '20250001', '张三', true, 1, false),
                    (501, :a, '20250002', '李四', true, 1, false),
                    (502, :a, '20250003', '王五', false, 1, false),
                    (510, :b, '20250001', '张三', true, 1, false),
                    (511, :b, '20250004', '赵六', true, 1, false)
                """).param("a", campusA.getId()).param("b", campusB.getId()).update();
    }

    @Test
    void listDeduped_shouldMergeByStudentIdAndSkipDisabled() {
        List<CampusMemberService.DedupedMember> result = service.listDeduped();
        // 启用成员按学号去重：20250001（甲、乙各一条）、20250002、20250004；停用的 20250003 被排除
        assertThat(result).extracting(CampusMemberService.DedupedMember::studentId)
                .containsExactlyInAnyOrder("20250001", "20250002", "20250004");
        CampusMemberService.DedupedMember shared = result.stream()
                .filter(m -> m.studentId().equals("20250001")).findFirst().orElseThrow();
        assertThat(shared.id()).isEqualTo(500L); // 代表取最小 id
        assertThat(shared.campusNames()).containsExactlyInAnyOrder("甲校区", "乙校区");
    }

    @Test
    void ministerList_shouldReadAllCampusesAndSupportCampusFilter() {
        AuthenticatedUser minister = new AuthenticatedUser(
                2L, "minister", "部长", UserRole.MINISTER, null, false);

        assertThat(service.list(null, true, minister))
                .extracting(CampusMemberEntity::getStudentId)
                .containsExactlyInAnyOrder("20250001", "20250002", "20250001", "20250004");
        assertThat(service.list(campusB.getId(), true, minister))
                .extracting(CampusMemberEntity::getStudentId)
                .containsExactlyInAnyOrder("20250001", "20250004");
    }

    @Test
    void ministerShouldCreateUpdateAndDeleteMembersAcrossCampuses() {
        AuthenticatedUser minister = new AuthenticatedUser(
                2L, "minister", "部长", UserRole.MINISTER, null, false);

        CampusMemberEntity created = service.create(campusB.getId(), "20250005", "孙七", minister);
        assertThat(created.getCampusId()).isEqualTo(campusB.getId());

        CampusMemberEntity persisted = service.list(campusB.getId(), null, minister).stream()
                .filter(member -> member.getId().equals(created.getId()))
                .findFirst()
                .orElseThrow();
        CampusMemberEntity updated = service.update(persisted.getId(), "20250005", "孙小七",
                false, persisted.getVersion(), minister);
        assertThat(updated.getName()).isEqualTo("孙小七");
        assertThat(updated.getEnabled()).isFalse();

        service.delete(created.getId(), minister);
        assertThat(service.list(campusB.getId(), null, minister))
                .extracting(CampusMemberEntity::getStudentId)
                .doesNotContain("20250005");
    }

    @Test
    void resolvePhotographer_sameCampus_shouldReturnMember() {
        CampusMemberEntity member = service.resolvePhotographer(500L, campusA.getId());
        assertThat(member.getStudentId()).isEqualTo("20250001");
        assertThat(member.getName()).isEqualTo("张三");
    }

    @Test
    void resolvePhotographer_nullCampus_shouldAcceptAnyEnabledMember() {
        assertThat(service.resolvePhotographer(510L, null).getStudentId()).isEqualTo("20250001");
    }

    @Test
    void resolvePhotographer_crossCampus_shouldThrow() {
        assertThatThrownBy(() -> service.resolvePhotographer(510L, campusA.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("校区通讯录");
    }

    @Test
    void resolvePhotographer_disabledMember_shouldThrow() {
        assertThatThrownBy(() -> service.resolvePhotographer(502L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效");
    }

    @Test
    void resolvePhotographer_nullContact_shouldThrow() {
        assertThatThrownBy(() -> service.resolvePhotographer(null, campusA.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void delete_shouldRemoveMemberAndAllowReadd() {
        AuthenticatedUser admin = new AuthenticatedUser(1L, "admin", "管理员", UserRole.ADMIN, null, false);
        // 物理删除甲校区学号 20250001
        service.delete(500L, admin);
        assertThat(service.list(campusA.getId(), null, admin))
                .extracting(CampusMemberEntity::getStudentId)
                .doesNotContain("20250001");
        // 唯一约束已释放：相同学号可重新添加，不触发 DuplicateKeyException
        CampusMemberEntity readded = service.create(campusA.getId(), "20250001", "张三", admin);
        assertThat(readded.getStudentId()).isEqualTo("20250001");
        assertThat(readded.getEnabled()).isTrue();
        assertThat(service.list(campusA.getId(), null, admin))
                .extracting(CampusMemberEntity::getStudentId)
                .contains("20250001");
    }

    @Test
    void delete_missingMember_shouldThrow() {
        AuthenticatedUser admin = new AuthenticatedUser(1L, "admin", "管理员", UserRole.ADMIN, null, false);
        assertThatThrownBy(() -> service.delete(99999L, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void disabledCampus_shouldFreezeDirectoryWritesAndPhotographerSelection() {
        campusService.update(campusA.getId(), campusA.getName(), false, 1);
        AuthenticatedUser admin = new AuthenticatedUser(1L, "admin", "管理员", UserRole.ADMIN, null, false);

        assertThatThrownBy(() -> service.resolvePhotographer(500L, campusA.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("校区已停用");
        assertThatThrownBy(() -> service.create(campusA.getId(), "20259999", "新成员", admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("校区已停用");
        assertThat(service.listDeduped()).extracting(CampusMemberService.DedupedMember::studentId)
                .doesNotContain("20250002");
    }
}
