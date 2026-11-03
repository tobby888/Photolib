package cn.photolib.salary;

import cn.photolib.audit.AuditAction;
import cn.photolib.audit.AuditSkip;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 工资计算（统计面板「工资计算」）：按选题计算、查看明细、导出 XLSX。见 API.md §12.7。
 * 只有持 {@code SALARY_CALCULATE} 的全局账号（默认管理员组、部长组）能用。
 */
@RestController
@RequestMapping("/salary-calculations")
@RequiredArgsConstructor
public class SalaryCalculationController {
    private final SalaryCalculationService salaries;

    @GetMapping
    @PreAuthorize("hasAuthority('SALARY_CALCULATE')")
    ApiResponse<List<SalaryCalculationService.CalculationSummary>> list(
            @RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(salaries.list(limit, principal));
    }

    @AuditAction("计算工资")
    @PostMapping
    @PreAuthorize("hasAuthority('SALARY_CALCULATE')")
    ApiResponse<SalaryCalculationService.CalculationSummary> calculate(@Valid @RequestBody CalculateRequest request,
                                                                      @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(salaries.calculate(request.from(), request.to(), request.formula(), principal));
    }

    /** 公式编辑器：可用的字段、函数，默认公式和最近一次计算用的公式。 */
    @GetMapping("/formula")
    @PreAuthorize("hasAuthority('SALARY_CALCULATE')")
    ApiResponse<SalaryCalculationService.FormulaReference> formula(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(salaries.formulaReference(principal));
    }

    /** 检查公式并用示例值试算。只读，POST 只是因为公式放在请求体里更省事（不用转义）。 */
    @AuditSkip("只读校验，不落库；编辑器每改一次都会请求")
    @PostMapping("/formula/check")
    @PreAuthorize("hasAuthority('SALARY_CALCULATE')")
    ApiResponse<SalaryCalculationService.FormulaCheck> check(@Valid @RequestBody FormulaRequest request,
                                                             @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(salaries.check(request.formula(), principal));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SALARY_CALCULATE')")
    ApiResponse<SalaryCalculationService.Detail> detail(@PathVariable long id,
                                                        @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(salaries.detail(id, principal));
    }

    @AuditAction("导出工资明细")
    @GetMapping(value = "/{id}/export", produces = SalaryWorkbook.CONTENT_TYPE)
    @PreAuthorize("hasAuthority('SALARY_CALCULATE')")
    ResponseEntity<byte[]> export(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        SalaryCalculationService.Detail detail = salaries.detail(id, principal);
        return ResponseEntity.ok()
                // 文件名带中文，走 RFC 5987 编码。
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(SalaryWorkbook.fileName(detail.calculation()), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(SalaryWorkbook.CONTENT_TYPE))
                .body(SalaryWorkbook.write(detail));
    }

    @AuditAction("删除工资计算")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SALARY_CALCULATE')")
    ApiResponse<Void> delete(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        salaries.delete(id, principal);
        return ApiResponse.ok();
    }

    /** {@code formula} 为空时用默认公式。 */
    record CalculateRequest(@NotNull LocalDate from, @NotNull LocalDate to,
                            @Size(max = SalaryFormula.MAX_LENGTH) String formula) {}

    record FormulaRequest(@Size(max = SalaryFormula.MAX_LENGTH) String formula) {}
}
