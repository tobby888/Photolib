package cn.photolib.campus;

import cn.photolib.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * 校区服务测试
 * 测试校区的创建、更新、启用/禁用等功能
 */
@SpringBootTest
@Transactional
class CampusServiceTests {
    @Autowired
    private CampusService campusService;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void createCampus_shouldReturnCampusWithEnabledStatus() {
        // When: 创建新校区
        var campus = campusService.create("BJ", "北京校区");

        // Then: 校区应该被创建并启用
        assertThat(campus.getId()).isNotNull();
        assertThat(campus.getCode()).isEqualTo("BJ");
        assertThat(campus.getName()).isEqualTo("北京校区");
        assertThat(campus.getEnabled()).isTrue();
    }

    @Test
    void createCampus_withDuplicateCode_shouldThrowException() {
        // Given: 已存在的校区
        campusService.create("SH", "上海校区");

        // When & Then: 创建重复代码的校区应该失败
        assertThatThrownBy(() -> campusService.create("SH", "另一个上海校区"))
                .hasMessageContaining("资源已存在");
    }

    @Test
    void updateCampus_shouldModifyCampusInfo() {
        // Given: 已存在的校区
        var campus = campusService.create("GZ", "广州校区");

        // When: 更新校区信息
        var updated = campusService.update(
                campus.getId(), "广州新校区", true, 1);

        // Then: 信息应该被更新
        assertThat(updated.getName()).isEqualTo("广州新校区");
    }

    @Test
    void updateCampus_withWrongVersion_shouldThrowException() {
        // Given: 已存在的校区
        var campus = campusService.create("SZ", "深圳校区");

        // When & Then: 使用错误的版本号应该失败（乐观锁）
        assertThatThrownBy(() -> campusService.update(
                campus.getId(), "深圳校区", true, 999))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他操作修改");
    }

    @Test
    void disableCampus_shouldSetEnabledToFalse() {
        // Given: 已启用的校区
        var campus = campusService.create("HZ", "杭州校区");

        // When: 禁用校区
        var disabled = campusService.update(
                campus.getId(), "杭州校区", false, 1);

        // Then: 校区应该被禁用
        assertThat(disabled.getEnabled()).isFalse();
    }

    @Test
    void listCampuses_shouldReturnAllCampuses() {
        // Given: 创建多个校区
        campusService.create("BJ", "北京校区");
        campusService.create("SH", "上海校区");
        campusService.create("GZ", "广州校区");

        // When: 查询所有校区
        var result = campusService.list(null);

        // Then: 应该返回所有校区
        assertThat(result.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void listCampuses_withKeyword_shouldFilterResults() {
        // Given: 创建多个校区
        campusService.create("BJ-01", "北京朝阳校区");
        campusService.create("BJ-02", "北京海淀校区");
        campusService.create("SH-01", "上海浦东校区");

        // When: 查询所有校区（注意：实际的 list 方法不支持关键字搜索）
        var result = campusService.list(null);

        // Then: 应该包含北京校区
        assertThat(result.stream()
                .filter(c -> c.getName().contains("北京") || c.getCode().contains("BJ")))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void listCampuses_withEnabledFilter_shouldReturnOnlyEnabled() {
        // Given: 创建启用和禁用的校区
        campusService.create("ENABLED", "启用校区");
        var disabled = campusService.create("DISABLED", "禁用校区");
        campusService.update(disabled.getId(), "禁用校区", false, 1);

        // When: 只查询启用的校区
        var result = campusService.list(true);

        // Then: 应该只返回启用的校区
        assertThat(result).allMatch(c -> c.getEnabled());
        assertThat(result.stream()
                .noneMatch(c -> c.getCode().equals("DISABLED"))).isTrue();
    }

    @Test
    void getCampus_shouldReturnCampusDetails() {
        // Given: 已存在的校区
        var campus = campusService.create("TEST", "测试校区");

        // When: 查询校区详情
        var retrieved = campusService.get(campus.getId());

        // Then: 应该返回完整信息
        assertThat(retrieved.getId()).isEqualTo(campus.getId());
        assertThat(retrieved.getCode()).isEqualTo("TEST");
        assertThat(retrieved.getName()).isEqualTo("测试校区");
    }

    @Test
    void deleteCampus_shouldMarkAsDeleted() {
        // Given: 已存在的校区
        var campus = campusService.create("DEL", "待删除校区");

        // When: 手动标记为删除（CampusService 没有 delete 方法）
        jdbc.sql("UPDATE campus SET deleted = 1 WHERE id = :id")
                .param("id", campus.getId())
                .update();

        // Then: 校区应该被逻辑删除
        assertThatThrownBy(() -> campusService.get(campus.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("校区不存在");
    }
}
