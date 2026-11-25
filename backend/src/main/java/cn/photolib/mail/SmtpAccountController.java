package cn.photolib.mail;

import cn.photolib.admin.BrandSnapshotService;
import cn.photolib.audit.AuditAction;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.mail.SmtpAccountService.AccountView;
import cn.photolib.mail.SmtpAccountService.Input;
import cn.photolib.mail.SmtpAccountService.Overview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 管理员动态配置多个 SMTP 发信邮箱。发信时按列表顺序依次尝试，前一个失败就顺延到下一个。
 */
@RestController
@RequestMapping("/smtp-accounts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SmtpAccountController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SmtpAccountService accounts;
    private final BrandSnapshotService brands;
    private final JdbcClient jdbc;

    @GetMapping
    public ApiResponse<Overview> list() {
        return ApiResponse.ok(accounts.overview());
    }

    @AuditAction("添加发信邮箱")
    @RequiresStepUp
    @PostMapping
    public ApiResponse<AccountView> create(@RequestBody AccountRequest request) {
        return ApiResponse.ok(accounts.create(request.toInput()));
    }

    @AuditAction("修改发信邮箱")
    @RequiresStepUp
    @PutMapping("/{id}")
    public ApiResponse<AccountView> update(@PathVariable long id, @RequestBody AccountRequest request) {
        return ApiResponse.ok(accounts.update(id, request.toInput()));
    }

    @AuditAction("删除发信邮箱")
    @RequiresStepUp
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        accounts.delete(id);
        return ApiResponse.ok();
    }

    @AuditAction("调整发信邮箱顺序")
    @RequiresStepUp
    @PutMapping("/order")
    public ApiResponse<List<AccountView>> reorder(@Valid @RequestBody ReorderRequest request) {
        return ApiResponse.ok(accounts.reorder(request.ids()));
    }

    @AuditAction("测试发信邮箱")
    @RequiresStepUp
    @PostMapping("/{id}/test")
    public ApiResponse<TestResponse> test(@PathVariable long id,
                                          @RequestBody(required = false) TestRequest request,
                                          @AuthenticationPrincipal AuthenticatedUser principal) {
        String target = resolveTarget(request, principal);
        boolean toSelf = request == null || request.to() == null || request.to().isBlank();
        var brand = brands.current();
        String now = LocalDateTime.now().format(TIME_FORMAT);
        String subject = "[测试] 发信邮箱连通性测试";
        String plain = "这是一封由 " + brand.title() + " 发出的 SMTP 发信邮箱连通性测试邮件。\n发送时间：" + now;
        String html = "<p>这是一封由 <strong>" + MailRendering.escape(brand.title())
                + "</strong> 发出的 SMTP 发信邮箱连通性测试邮件。</p><p style=\"color:#666;font-size:13px\">发送时间："
                + now + "</p>";
        try {
            String fromAddress = accounts.sendVia(id, new OutgoingMail(target,
                    toSelf ? principal.displayName() : null, brand.title(), subject, html, plain, List.of()));
            return ApiResponse.ok(new TestResponse(target, fromAddress));
        } catch (MailDeliveryException ex) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, ex.getMessage());
        }
    }

    private String resolveTarget(TestRequest request, AuthenticatedUser principal) {
        if (request != null && request.to() != null && !request.to().isBlank()) {
            String email = request.to().strip();
            if (!SmtpAccountService.isMailbox(email)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "测试收件邮箱格式不正确");
            }
            return email;
        }
        String own = jdbc.sql("SELECT email FROM app_user WHERE id=:id AND deleted=0 AND email IS NOT NULL")
                .param("id", principal.id()).query(String.class).optional().orElse("").strip();
        if (own.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "你的账号没有填写邮箱，请手动填写测试收件地址");
        }
        return own;
    }

    public record AccountRequest(String name, String host, Integer port, String username, String password,
                                 String fromAddress, String fromName, String security, Boolean enabled) {
        Input toInput() {
            return new Input(name, host, port, username, password, fromAddress, fromName, security, enabled);
        }
    }

    public record ReorderRequest(@NotEmpty List<Long> ids) {}

    public record TestRequest(String to) {}

    public record TestResponse(String email, String fromAddress) {}
}
