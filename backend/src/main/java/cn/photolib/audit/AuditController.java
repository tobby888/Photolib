package cn.photolib.audit;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
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
import java.util.StringJoiner;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    private final AuditLogMapper mapper;

    @GetMapping
    ApiResponse<PageResponse<AuditLogView>> list(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        page = Math.max(1, page);
        pageSize = Math.max(1, Math.min(100, pageSize));
        long total = mapper.countLogs(operatorId, action, resourceType, start(from), end(to), clean(keyword));
        var items = mapper.findLogs(operatorId, action, resourceType, start(from), end(to), clean(keyword),
                pageSize, (long) (page - 1) * pageSize);
        return ApiResponse.ok(new PageResponse<>(items, page, pageSize, total,
                total == 0 ? 0 : (total + pageSize - 1) / pageSize));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var logs = mapper.findLogs(operatorId, action, resourceType, start(from), end(to), clean(keyword),
                100_000, 0);
        StringBuilder csv = new StringBuilder("\uFEFF时间,操作者账号,操作者姓名,动作,资源类型,资源ID,请求ID,IP地址,详情\n");
        for (AuditLogView log : logs) {
            StringJoiner row = new StringJoiner(",");
            row.add(csv(log.getCreatedAt()));
            row.add(csv(log.getOperatorUsername()));
            row.add(csv(log.getOperatorDisplayName()));
            row.add(csv(log.getAction()));
            row.add(csv(log.getResourceType()));
            row.add(csv(log.getResourceId()));
            row.add(csv(log.getRequestId()));
            row.add(csv(log.getIpAddress()));
            row.add(csv(log.getDetailJson()));
            csv.append(row).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"photolib-audit-logs-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static LocalDateTime start(LocalDate date) { return date == null ? null : date.atStartOfDay(); }
    private static LocalDateTime end(LocalDate date) { return date == null ? null : date.plusDays(1).atStartOfDay(); }
    private static String clean(String value) { return value == null ? null : value.trim(); }
    private static String csv(Object value) {
        if (value == null) return "";
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }
}
