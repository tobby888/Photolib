package cn.photolib.settlement;

import cn.photolib.audit.AuditAction;
import cn.photolib.audit.AuditSkip;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 邮件清算单（统计面板「发送清算单」）与清算单邮件模板（管理员面板「邮件模板」）。见 API.md §12.6。
 */
@RestController
@RequestMapping("/settlement-mails")
@RequiredArgsConstructor
public class SettlementMailController {
    private final SettlementMailService mails;
    private final MailTemplateService templates;

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('SETTLEMENT_MAIL_SEND')")
    ApiResponse<SettlementMailService.Status> status() {
        return ApiResponse.ok(mails.status());
    }

    @GetMapping("/recipients")
    @PreAuthorize("hasAuthority('SETTLEMENT_MAIL_SEND')")
    ApiResponse<List<SettlementStatementService.Recipient>> recipients(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(mails.recipients(from, to, principal));
    }

    /**
     * 预览一份清算单。只读，POST 只是因为模板编辑器要把未保存的模板正文带上来。
     * 带模板正文预览只给管理员：其余人只能看当前模板套出来的样子。
     */
    @AuditSkip("只读预览，不发信、不落库；编辑器每改一次都会请求")
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('SETTLEMENT_MAIL_SEND')")
    ApiResponse<PreviewResponse> preview(@Valid @RequestBody PreviewRequest request,
                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        if ((request.subject() != null || request.html() != null) && !principal.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以预览自定义模板");
        }
        SettlementMailRenderer.Rendered rendered = mails.preview(request.userId(), request.from(), request.to(),
                request.subject(), request.html(), principal);
        return ApiResponse.ok(new PreviewResponse(rendered.subject(), rendered.html()));
    }

    @AuditAction("发送清算单邮件")
    @PostMapping("/batches")
    @PreAuthorize("hasAuthority('SETTLEMENT_MAIL_SEND')")
    ApiResponse<SettlementMailService.BatchView> send(@Valid @RequestBody SendRequest request,
                                                      @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(mails.create(request.from(), request.to(), request.userIds(), principal));
    }

    @GetMapping("/batches")
    @PreAuthorize("hasAuthority('SETTLEMENT_MAIL_SEND')")
    ApiResponse<List<SettlementMailService.BatchView>> batches(@RequestParam(defaultValue = "20") int limit,
                                                               @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(mails.batches(limit, principal));
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("hasAuthority('SETTLEMENT_MAIL_SEND')")
    ApiResponse<SettlementMailService.BatchView> batch(@PathVariable long id,
                                                       @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(mails.batch(id, principal));
    }

    @GetMapping("/template")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<MailTemplateService.TemplateView> template() {
        return ApiResponse.ok(templates.view());
    }

    @AuditAction("修改清算单邮件模板")
    @PutMapping("/template")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresStepUp
    ApiResponse<MailTemplateService.TemplateView> saveTemplate(@Valid @RequestBody TemplateRequest request,
                                                               @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(templates.save(request.subject(), request.html(),
                request.version() == null ? 0 : request.version(), principal));
    }

    @AuditAction("恢复默认清算单邮件模板")
    @DeleteMapping("/template")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresStepUp
    ApiResponse<MailTemplateService.TemplateView> resetTemplate() {
        return ApiResponse.ok(templates.reset());
    }

    @AuditAction("发送清算单测试邮件")
    @PostMapping("/template/test")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<TestResponse> sendTest(@Valid @RequestBody PreviewRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(new TestResponse(mails.sendTest(request.userId(), request.from(), request.to(),
                request.subject(), request.html(), principal)));
    }

    record PreviewRequest(@NotNull Long userId, @NotNull LocalDate from, @NotNull LocalDate to,
                          @Size(max = MailTemplateService.MAX_SUBJECT) String subject,
                          @Size(max = MailTemplateService.MAX_HTML) String html) {}

    record PreviewResponse(String subject, String html) {}

    record SendRequest(@NotNull LocalDate from, @NotNull LocalDate to,
                       @NotEmpty @Size(max = SettlementMailService.MAX_RECIPIENTS) List<@NotNull Long> userIds) {}

    record TemplateRequest(@NotNull String subject, @NotNull String html, Integer version) {}

    record TestResponse(String email) {}
}
