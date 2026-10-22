package cn.photolib.audit;

import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.util.SpreadsheetText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequiresStepUp
public class AuditController {
    static final int EXPORT_LIMIT = 100_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> ROLE_LABELS = Map.of(
            "ADMIN", "管理员", "MINISTER", "部长", "CAMPUS_MANAGER", "校区负责人");

    private final AuditLogMapper mapper;
    private final AuditCatalog catalog;

    @GetMapping
    ApiResponse<PageResponse<AuditLogView>> list(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actionName,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        page = Math.max(1, page);
        pageSize = Math.max(1, Math.min(100, pageSize));
        AuditLogFilter filter = filter(operatorId, action, actionName, resourceType, success, ipAddress,
                keyword, from, to);
        long total = mapper.countLogs(filter);
        List<AuditLogView> items = complete(mapper.findLogs(filter, pageSize, (long) (page - 1) * pageSize));
        return ApiResponse.ok(new PageResponse<>(items, page, pageSize, total,
                total == 0 ? 0 : (total + pageSize - 1) / pageSize));
    }

    /** 筛选下拉框的选项：所有操作名（按模块分组）和所有模块。 */
    @GetMapping("/catalog")
    ApiResponse<Catalog> catalog() {
        return ApiResponse.ok(new Catalog(catalog.actions(mapper.distinctActionNames()), catalog.resourceTypes()));
    }

    record Catalog(List<AuditCatalog.ActionOption> actions, List<AuditCatalog.ResourceOption> resourceTypes) {
    }

    @AuditAction("导出操作日志")
    @GetMapping(value = "/export", produces = "text/csv")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actionName,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuditLogFilter filter = filter(operatorId, action, actionName, resourceType, success, ipAddress,
                keyword, from, to);
        List<AuditLogView> logs = complete(mapper.findLogs(filter, EXPORT_LIMIT, 0));
        StringBuilder csv = new StringBuilder("﻿时间,操作者账号,操作者姓名,操作者角色,操作者权限组,操作,请求方式,"
                + "模块,对象ID,对象名称,结果,状态码,失败原因,IP地址,设备,耗时(毫秒),请求ID,详情\n");
        for (AuditLogView log : logs) {
            StringJoiner row = new StringJoiner(",");
            row.add(csv(log.getCreatedAt()));
            row.add(csv(log.getOperatorUsername()));
            row.add(csv(log.getOperatorDisplayName()));
            row.add(csv(ROLE_LABELS.getOrDefault(String.valueOf(log.getOperatorRole()), log.getOperatorRole())));
            row.add(csv(log.getOperatorGroupName()));
            row.add(csv(log.getActionName()));
            row.add(csv(log.getAction()));
            row.add(csv(log.getResourceLabel()));
            row.add(csv(log.getResourceId()));
            row.add(csv(log.getResourceName()));
            row.add(csv(log.getSuccess() == null ? null : log.getSuccess() ? "成功" : "失败"));
            row.add(csv(log.getHttpStatus()));
            row.add(csv(log.getErrorMessage()));
            row.add(csv(log.getIpAddress()));
            row.add(csv(log.getUserAgent()));
            row.add(csv(log.getDurationMs()));
            row.add(csv(log.getRequestId()));
            row.add(csv(log.getDetailJson()));
            csv.append(row).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"audit-logs-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static AuditLogFilter filter(Long operatorId, String action, String actionName, String resourceType,
                                         Boolean success, String ipAddress, String keyword,
                                         LocalDate from, LocalDate to) {
        return AuditLogFilter.builder()
                .operatorId(operatorId)
                .action(clean(action))
                .actionName(clean(actionName))
                .resourceType(clean(resourceType))
                .success(success)
                .ipAddress(clean(ipAddress))
                .from(start(from))
                .to(end(to))
                .build()
                .withKeyword(keyword);
    }

    /**
     * 补上列表要显示、但库里没有的字段：模块中文名；升级前的老记录还要从详情里的路径和
     * 状态码推出操作名和结果。
     */
    List<AuditLogView> complete(List<AuditLogView> logs) {
        List<AuditLogView> completed = new ArrayList<>(logs.size());
        for (AuditLogView log : logs) {
            log.setResourceLabel(AuditResources.label(log.getResourceType()));
            if (log.getActionName() == null || log.getHttpStatus() == null) {
                JsonNode detail = detail(log.getDetailJson());
                if (log.getActionName() == null && detail != null && detail.path("path").isTextual()) {
                    String inferred = catalog.describe(log.getAction(), detail.path("path").asText());
                    if (inferred != null) {
                        log.setActionName(inferred);
                        log.setActionNameInferred(true);
                    }
                }
                if (log.getHttpStatus() == null && detail != null && detail.path("status").isInt()) {
                    log.setHttpStatus(detail.path("status").asInt());
                }
            }
            if (log.getSuccess() == null && log.getHttpStatus() != null) log.setSuccess(log.getHttpStatus() < 400);
            completed.add(log);
        }
        return completed;
    }

    private static JsonNode detail(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readTree(json);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static LocalDateTime start(LocalDate date) { return date == null ? null : date.atStartOfDay(); }
    private static LocalDateTime end(LocalDate date) { return date == null ? null : date.plusDays(1).atStartOfDay(); }
    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    private static String csv(Object value) {
        return "\"" + SpreadsheetText.safe(value).replace("\"", "\"\"") + "\"";
    }
}
