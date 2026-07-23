package cn.photolib.admin;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ScheduledBrandIconService {
    static final int MAX_RULES = 20;
    static final ZoneId SYSTEM_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int GREGORIAN_CYCLE_YEARS = 400;

    private final ScheduledBrandIconMapper mapper;
    private final BrandIconValidator iconValidator;

    List<ScheduledIconView> list() {
        return metadata().stream().map(this::toView).toList();
    }

    ScheduledBrandIconEntity findActive(LocalDate date) {
        for (ScheduledBrandIconEntity icon : metadata()) {
            CronExpression expression = parse(icon.getCronExpression(), 0);
            if (date.equals(nextMatchingDate(expression, date))) {
                return icon;
            }
        }
        return null;
    }

    ScheduledBrandIconEntity getIcon(long id) {
        return mapper.selectById(id);
    }

    @Transactional
    public List<ScheduledIconView> replace(List<RuleInput> rules, List<MultipartFile> files) throws IOException {
        List<RuleInput> requestedRules = rules == null ? List.of() : rules;
        List<MultipartFile> uploadedFiles = files == null ? List.of() : files;
        if (requestedRules.size() > MAX_RULES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "定时图标规则不能超过 " + MAX_RULES + " 条");
        }

        List<ScheduledBrandIconEntity> existing = mapper.selectList(null);
        Map<Long, ScheduledBrandIconEntity> existingById = new HashMap<>();
        existing.forEach(icon -> existingById.put(icon.getId(), icon));

        Set<Long> retainedIds = new HashSet<>();
        Set<Integer> referencedFileIndexes = new HashSet<>();
        List<CronExpression> expressions = new ArrayList<>();
        List<ScheduledBrandIconEntity> prepared = new ArrayList<>();

        for (int index = 0; index < requestedRules.size(); index++) {
            RuleInput rule = requestedRules.get(index);
            if (rule == null || rule.cronExpression() == null || rule.cronExpression().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "第 " + (index + 1) + " 条规则缺少 Cron 表达式");
            }
            String cron = rule.cronExpression().trim();
            if (cron.length() > 128) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "第 " + (index + 1) + " 条 Cron 表达式不能超过 128 个字符");
            }
            expressions.add(parse(cron, index));

            ScheduledBrandIconEntity entity = resolveExisting(rule.id(), index, existingById, retainedIds);
            entity.setCronExpression(cron);
            if (rule.fileIndex() != null) {
                int fileIndex = rule.fileIndex();
                if (fileIndex < 0 || fileIndex >= uploadedFiles.size()
                        || !referencedFileIndexes.add(fileIndex)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "第 " + (index + 1) + " 条规则引用的图标文件无效");
                }
                BrandIconValidator.NormalizedIcon normalized = iconValidator.normalize(uploadedFiles.get(fileIndex));
                entity.setIcon(normalized.bytes());
                entity.setIconContentType(normalized.contentType());
            } else if (entity.getId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "第 " + (index + 1) + " 条规则必须上传图标");
            }
            prepared.add(entity);
        }

        if (referencedFileIndexes.size() != uploadedFiles.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "存在未与 Cron 表达式配对的图标文件");
        }
        validateNoConflicts(expressions);

        List<Long> removedIds = existing.stream().map(ScheduledBrandIconEntity::getId)
                .filter(id -> !retainedIds.contains(id)).toList();
        if (!removedIds.isEmpty()) {
            mapper.deleteByIds(removedIds);
        }
        for (ScheduledBrandIconEntity entity : prepared) {
            if (entity.getId() == null) {
                mapper.insert(entity);
            } else {
                entity.setUpdatedAt(LocalDateTime.now());
                mapper.updateById(entity);
            }
        }
        return list();
    }

    private ScheduledBrandIconEntity resolveExisting(String id, int index,
                                                      Map<Long, ScheduledBrandIconEntity> existingById,
                                                      Set<Long> retainedIds) {
        if (id == null || id.isBlank()) {
            return new ScheduledBrandIconEntity();
        }
        final long parsedId;
        try {
            parsedId = Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "第 " + (index + 1) + " 条规则 ID 无效");
        }
        ScheduledBrandIconEntity entity = existingById.get(parsedId);
        if (entity == null || !retainedIds.add(parsedId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "第 " + (index + 1) + " 条规则引用了无效或重复的配置");
        }
        return entity;
    }

    private void validateNoConflicts(List<CronExpression> expressions) {
        if (expressions.size() < 2) {
            return;
        }
        LocalDate start = LocalDate.now(SYSTEM_ZONE);
        LocalDate end = start.plusYears(GREGORIAN_CYCLE_YEARS);
        List<LocalDate> nextDates = expressions.stream()
                .map(expression -> nextMatchingDate(expression, start)).collect(ArrayList::new, List::add, List::addAll);

        while (true) {
            LocalDate earliest = nextDates.stream().filter(date -> date != null && date.isBefore(end))
                    .min(LocalDate::compareTo).orElse(null);
            if (earliest == null) {
                return;
            }
            List<Integer> matching = new ArrayList<>();
            for (int index = 0; index < nextDates.size(); index++) {
                if (earliest.equals(nextDates.get(index))) {
                    matching.add(index);
                }
            }
            if (matching.size() > 1) {
                String ruleNumbers = matching.stream().map(index -> String.valueOf(index + 1))
                        .reduce((left, right) -> left + "、" + right).orElse("");
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                        "Cron 规则 " + ruleNumbers + " 在 " + earliest + " 同时生效，设置失败");
            }
            int index = matching.getFirst();
            nextDates.set(index, nextMatchingDate(expressions.get(index), earliest.plusDays(1)));
        }
    }

    private CronExpression parse(String cron, int index) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "第 " + (index + 1) + " 条 Cron 表达式无效：" + exception.getMessage());
        }
    }

    private LocalDate nextMatchingDate(CronExpression expression, LocalDate onOrAfter) {
        LocalDateTime next = expression.next(onOrAfter.atStartOfDay().minusNanos(1));
        return next == null ? null : next.toLocalDate();
    }

    private List<ScheduledBrandIconEntity> metadata() {
        return mapper.selectList(Wrappers.<ScheduledBrandIconEntity>lambdaQuery()
                .select(ScheduledBrandIconEntity::getId, ScheduledBrandIconEntity::getCronExpression,
                        ScheduledBrandIconEntity::getIconContentType, ScheduledBrandIconEntity::getUpdatedAt)
                .orderByAsc(ScheduledBrandIconEntity::getId));
    }

    private ScheduledIconView toView(ScheduledBrandIconEntity entity) {
        String version = entity.getUpdatedAt() == null ? "0" : String.valueOf(entity.getUpdatedAt().hashCode());
        return new ScheduledIconView(String.valueOf(entity.getId()), entity.getCronExpression(),
                "/api/v1/branding/scheduled-icons/" + entity.getId() + "/icon?v=" + version);
    }

    public record RuleInput(String id, String cronExpression, Integer fileIndex) {
    }

    public record ScheduledIconView(String id, String cronExpression, String iconUrl) {
    }
}
