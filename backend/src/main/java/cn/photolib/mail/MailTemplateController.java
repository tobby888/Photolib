package cn.photolib.mail;

import cn.photolib.admin.BrandSnapshotService;
import cn.photolib.audit.AuditAction;
import cn.photolib.audit.AuditSkip;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.mail.MailTemplateStore.View;
import cn.photolib.mail.WorklogMailTemplates.Details;
import cn.photolib.mail.WorklogMailTemplates.Kind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

import java.time.LocalDate;

@RestController
@RequestMapping("/mail-templates")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
class MailTemplateController {
    private final JdbcClient jdbc;
    private final MailLayoutService layouts;
    private final WorklogMailTemplates templates;
    private final BrandSnapshotService brands;
    private final MailSender sender;

    @GetMapping("/layout")
    ApiResponse<View> layout() {
        return ApiResponse.ok(layouts.view());
    }

    @AuditAction("修改公共邮件版式")
    @RequiresStepUp
    @PutMapping("/layout")
    ApiResponse<View> saveLayout(@Valid @RequestBody LayoutRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(layouts.save(request.html(), request.version(), principal));
    }

    @AuditAction("恢复默认公共邮件版式")
    @RequiresStepUp
    @DeleteMapping("/layout")
    ApiResponse<View> resetLayout() {
        return ApiResponse.ok(layouts.reset());
    }

    @GetMapping("/worklog/{kind}")
    ApiResponse<View> content(@PathVariable Kind kind) {
        return ApiResponse.ok(templates.view(kind));
    }

    @AuditAction("修改工时邮件内容")
    @RequiresStepUp
    @PutMapping("/worklog/{kind}")
    ApiResponse<View> saveContent(@PathVariable Kind kind, @Valid @RequestBody ContentRequest request,
                                  @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(templates.save(kind, request.subject(), request.html(), request.version(), principal));
    }

    @AuditAction("恢复默认工时邮件内容")
    @RequiresStepUp
    @DeleteMapping("/worklog/{kind}")
    ApiResponse<View> resetContent(@PathVariable Kind kind) {
        return ApiResponse.ok(templates.reset(kind));
    }

    @AuditSkip("实时预览邮件，用的是示例数据")
    @PostMapping("/worklog/{kind}/preview")
    ApiResponse<Preview> preview(@PathVariable Kind kind, @Valid @RequestBody PreviewRequest request) {
        var rendered = templates.render(kind, sample(), brands.current(), true,
                request.subject(), request.html(), request.layout());
        return ApiResponse.ok(new Preview(rendered.subject(), rendered.html()));
    }

    @AuditAction("发送工时测试邮件")
    @PostMapping("/worklog/{kind}/test")
    ApiResponse<TestResponse> sendTest(@PathVariable Kind kind, @Valid @RequestBody PreviewRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser principal) {
        if (!sender.configured()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "邮件服务未配置：请先在「发信邮箱」中添加可用的 SMTP 邮箱");
        }
        String email = jdbc.sql("SELECT email FROM app_user WHERE id=:id AND deleted=0 AND email IS NOT NULL")
                .param("id", principal.id()).query(String.class).optional().orElse("").strip();
        if (email.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "你的账号没有填写邮箱，收不到测试邮件");
        var brand = brands.current();
        var rendered = templates.render(kind, sample(), brand, false, request.subject(), request.html(), request.layout());
        try {
            sender.send(new OutgoingMail(email, principal.displayName(), brand.title(), "[测试] " + rendered.subject(),
                    rendered.html(), rendered.plainText(), MailRendering.images(brand)));
        } catch (MailDeliveryException ex) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, ex.getMessage());
        }
        return ApiResponse.ok(new TestResponse(email));
    }

    private static Details sample() {
        return new Details("示例成员", "校园运动会（示例）", LocalDate.now(), 90, 30,
                "负责比赛现场拍摄并整理赛后照片", "192.0.2.10", "Mozilla/5.0 (示例浏览器)", "示例部长");
    }

    record LayoutRequest(@NotNull @Size(max = MailTemplateStore.MAX_HTML) String html, int version) {}

    record ContentRequest(@NotNull @Size(max = MailTemplateStore.MAX_SUBJECT) String subject,
                          @NotNull @Size(max = MailTemplateStore.MAX_HTML) String html, int version) {}

    record PreviewRequest(@Size(max = MailTemplateStore.MAX_SUBJECT) String subject,
                          @Size(max = MailTemplateStore.MAX_HTML) String html,
                          @Size(max = MailTemplateStore.MAX_HTML) String layout) {}

    record Preview(String subject, String html) {}

    record TestResponse(String email) {}
}
