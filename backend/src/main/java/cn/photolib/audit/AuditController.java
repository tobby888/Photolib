package cn.photolib.audit;

import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.util.SpreadsheetText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
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
    static final int EXPORT_BATCH = 1_000;
    /** 开头是 UTF-8 BOM，Excel 才会按 UTF-8 打开中文。 */
    static final String CSV_HEADER = "﻿时间,操作者账号,操作者姓名,操作者角色,操作者权限组,操作,请求方式,"
            + "模块,对象ID,对象名称,结果,状态码,失败原因,IP地址,设备,耗时(毫秒),请求ID,详情\n";
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

    /**
     * 边查边写：按 {@code created_at DESC, id DESC} 用游标分批读（每批 {@link #EXPORT_BATCH} 条），
     * 每批写进响应流后就丢掉。以前是一次查出全部 10 万条，再拼成一整个字符串、再转一份字节数组，
     * 同一份数据在堆里同时有好几份。
     *
     * <p>直接写 {@link HttpServletResponse} 而不是返回 {@code StreamingResponseBody}：后者走异步派发，
     * 审计、再验证拦截器会在异步派发上再跑一遍。导出途中有人写入新日志不影响结果——新记录比游标新，
     * 不会被翻到，也不会让已翻过的记录错位（偏移量分页会）。
     */
    @AuditAction("导出操作日志")
    @GetMapping(value = "/export", produces = "text/csv")
    void export(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actionName,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {
        AuditLogFilter filter = filter(operatorId, action, actionName, resourceType, success, ipAddress,
                keyword, from, to);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8).toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"audit-logs-" + LocalDate.now() + ".csv\"");
        Writer out = new BufferedWriter(
                new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), 64 * 1024);
        writeCsv(filter, out, EXPORT_BATCH, EXPORT_LIMIT);
        out.flush();
    }

    /** 写出表头和至多 {@code limit} 条记录，返回写了多少条。 */
    int writeCsv(AuditLogFilter filter, Writer out, int batchSize, int limit) throws IOException {
        out.write(CSV_HEADER);
        int written = 0;
        AuditLogFilter page = filter;
        while (written < limit) {
            List<AuditLogView> batch = mapper.findLogs(page, Math.min(batchSize, limit - written), 0);
            if (batch.isEmpty()) break;
            for (AuditLogView log : complete(batch)) {
                writeRow(out, log);
            }
            written += batch.size();
            if (batch.size() < batchSize) break;
            page = filter.after(batch.getLast());
        }
        return written;
    }

    private static void writeRow(Writer out, AuditLogView log) throws IOException {
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
        out.append(row.toString()).append("\r\n");
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
