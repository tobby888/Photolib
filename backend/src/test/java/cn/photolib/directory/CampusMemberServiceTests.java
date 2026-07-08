package cn.photolib.directory;

import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 通讯录服务测试：拍摄者解析校验与部长去重视图。
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
}
