package cn.photolib.dashboard;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.dashboard.mapper.DashboardLayoutMapper;
import cn.photolib.dashboard.model.DashboardLayoutEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 每位管理员自己的数据面板布局。
 *
 * <p>后端只管「形状对不对」：小面板类型、尺寸、刷新间隔走白名单，文字有长度上限，指标名只
 * 校验字符集。指标目录在前端（{@code src/adminDashboard.ts}），读回布局时前端会把目录里
 * 已经没有的指标标成「已下线」，而不是让整块面板渲染失败——所以这里不必和前端逐项同步。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardLayoutService {
    private static final Logger log = LoggerFactory.getLogger(DashboardLayoutService.class);
    static final Set<Integer> REFRESH_INTERVALS = Set.of(0, 5, 10, 30, 60);
    private static final Set<String> METRIC_WIDGETS = Set.of("stat", "gauge", "trend");
    // 与 AuditInterceptor 等处一样自带一个：容器里的 JSON 映射器带着本站接口的序列化约定。
    private static final ObjectMapper JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final DashboardLayoutMapper mapper;

    public LayoutView get(Long userId) {
        DashboardLayoutEntity entity = find(userId);
        if (entity == null) return new LayoutView(null, null, null);
        try {
            return new LayoutView(JSON.readValue(entity.getLayoutJson(), Layout.class),
                    entity.getVersion(), entity.getUpdatedAt());
        } catch (Exception e) {
            // 存进去之前校验过，读不出来只可能是有人直接改了库；退回默认布局，别让面板打不开。
            log.warn("管理员 {} 的数据面板布局无法解析，按默认布局处理", userId, e);
            return new LayoutView(null, entity.getVersion(), entity.getUpdatedAt());
        }
    }

    /**
     * 保存布局。{@code version} 是读到的那一版：第一次保存传 {@code null}；已经存过却不带版本，
     * 或者带的版本比库里旧，都按「别处改过了」拒绝。
     */
    @Transactional
    public LayoutView save(Long userId, Layout layout, Integer version) {
        validate(layout);
        String json;
        try {
            json = JSON.writeValueAsString(layout);
        } catch (Exception e) {
            throw new IllegalStateException("数据面板布局序列化失败", e);
        }
        DashboardLayoutEntity existing = find(userId);
        if (existing == null) {
            DashboardLayoutEntity entity = new DashboardLayoutEntity();
            entity.setUserId(userId);
            entity.setLayoutJson(json);
            entity.setVersion(1);
            try {
                mapper.insert(entity);
            } catch (DuplicateKeyException e) {
                throw conflict();
            }
        } else {
            if (version == null || !version.equals(existing.getVersion())) throw conflict();
            existing.setLayoutJson(json);
            // 置空才会由 MetaObjectHandler 填上现在的时间；带着读出来的旧值会原样写回去。
            existing.setUpdatedAt(null);
            if (mapper.updateById(existing) == 0) throw conflict();
        }
        return get(userId);
    }

    /** 恢复默认：删掉这一行，前端回到内置布局。 */
    @Transactional
    public void reset(Long userId) {
        mapper.delete(Wrappers.<DashboardLayoutEntity>lambdaQuery().eq(DashboardLayoutEntity::getUserId, userId));
    }

    static void validate(Layout layout) {
        if (!REFRESH_INTERVALS.contains(layout.refreshSeconds())) {
            throw invalid("自动刷新间隔只能是 0、5、10、30 或 60 秒");
        }
        Set<String> ids = new HashSet<>();
        for (Widget widget : layout.widgets()) {
            if (!ids.add(widget.id())) throw invalid("小面板编号重复：" + widget.id());
            if (METRIC_WIDGETS.contains(widget.type()) && (widget.metric() == null || widget.metric().isBlank())) {
                throw invalid("「" + displayName(widget) + "」没有选择指标");
            }
            for (Double threshold : new Double[]{widget.warnAt(), widget.criticalAt()}) {
                if (threshold != null && !Double.isFinite(threshold)) throw invalid("告警阈值必须是有限的数字");
            }
        }
    }

    private static String displayName(Widget widget) {
        return widget.title() == null || widget.title().isBlank() ? widget.id() : widget.title();
    }

    private DashboardLayoutEntity find(Long userId) {
        return mapper.selectOne(Wrappers.<DashboardLayoutEntity>lambdaQuery()
                .eq(DashboardLayoutEntity::getUserId, userId));
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "数据面板已在别处修改，请刷新后再保存");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    public record Layout(
            @NotNull @Size(max = 40, message = "小面板最多 40 个") List<@NotNull @Valid Widget> widgets,
            @NotNull Integer refreshSeconds) {
    }

    public record Widget(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String id,
            @NotNull @Pattern(regexp = "stat|gauge|trend|endpoints|status|note|shortcuts",
                    message = "不支持的小面板类型") String type,
            @Size(max = 40, message = "小面板标题不能超过 40 个字符") String title,
            @NotNull @Pattern(regexp = "small|medium|large") String size,
            @Pattern(regexp = "[A-Za-z0-9_.]{1,64}") String metric,
            @Pattern(regexp = "5m|1h|24h") String range,
            Double warnAt,
            Double criticalAt,
            @Size(max = 2000, message = "便签不能超过 2000 个字符") String text,
            @Size(max = 12) List<@NotBlank @Pattern(regexp = "[a-z0-9-]{1,40}") String> links,
            @Min(3) @Max(20) Integer limit) {
    }

    public record LayoutView(Layout layout, Integer version, LocalDateTime updatedAt) {
    }
}
