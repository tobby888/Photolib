package cn.photolib.auth.mfa;

import cn.photolib.auth.AccessTokenFilter;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.LoginThrottle;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员自己的两步验证：查看状态、绑定 / 删除设备、管理信任的浏览器、敏感操作前再验证。
 *
 * <p>这一组接口在"被强制绑定却还没绑定"的受限会话里也能调（见 {@code AccessTokenFilter}），
 * 否则被关进绑定流程的人连绑定都做不了。
 */
@RestController
@RequestMapping("/auth/mfa")
@RequiredArgsConstructor
public class MfaController {
    private final MfaService mfa;
    private final WebAuthnSupport webAuthn;
    private final LoginThrottle loginThrottle;

    @GetMapping
    ApiResponse<MfaService.Overview> overview(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(mfa.overview(user));
    }

    @PostMapping("/totp")
    @RequiresStepUp
    ApiResponse<MfaService.TotpSetup> beginTotp(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(mfa.beginTotp(user));
    }

    @PostMapping("/totp/{id}/confirm")
    ApiResponse<MfaService.DeviceView> confirmTotp(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody ConfirmTotpRequest request,
                                                   HttpServletRequest servletRequest) {
        return ApiResponse.ok(mfa.confirmTotp(user, sessionId(servletRequest), id, request.code(), request.name()));
    }

    @PostMapping("/webauthn/options")
    @RequiresStepUp
    ApiResponse<MfaService.WebAuthnChallenge> beginWebAuthn(@AuthenticationPrincipal AuthenticatedUser user,
                                                            HttpServletRequest request) {
        return ApiResponse.ok(mfa.beginWebAuthnRegistration(user, sessionId(request),
                webAuthn.relyingParty(request)));
    }

    @PostMapping("/webauthn")
    ApiResponse<MfaService.DeviceView> finishWebAuthn(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @Valid @RequestBody RegisterWebAuthnRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(mfa.finishWebAuthnRegistration(user, sessionId(request), body.challengeToken(),
                body.name(), new WebAuthnSupport.Attestation(body.clientDataJSON(), body.attestationObject(),
                        body.transports()), webAuthn.relyingParty(request)));
    }

    @DeleteMapping("/devices/{id}")
    @RequiresStepUp
    ApiResponse<Void> deleteDevice(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        mfa.deleteDevice(user, id);
        return ApiResponse.ok();
    }

    /** 撤销信任只会让人多验证一次，不需要先再验证。 */
    @DeleteMapping("/trusted-browsers/{id}")
    ApiResponse<Void> revokeTrustedBrowser(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        mfa.revokeTrustedBrowser(user.id(), id);
        return ApiResponse.ok();
    }

    @GetMapping("/step-up")
    ApiResponse<MfaService.StepUpStatus> stepUpStatus(@AuthenticationPrincipal AuthenticatedUser user,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(mfa.stepUpStatus(user, sessionId(request)));
    }

    @PostMapping("/step-up/webauthn-options")
    ApiResponse<MfaService.WebAuthnChallenge> stepUpWebAuthnOptions(@AuthenticationPrincipal AuthenticatedUser user,
                                                                    HttpServletRequest request) {
        return ApiResponse.ok(mfa.stepUpWebAuthnOptions(user, sessionId(request), webAuthn.relyingParty(request)));
    }

    /** 失败计数和登录第二步一样放在事务外记录，但两者分开计数，见 {@link LoginThrottle.MfaAttempt}。 */
    @PostMapping("/step-up")
    ApiResponse<MfaService.StepUpStatus> stepUp(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestBody StepUpRequest body,
                                                HttpServletRequest request) {
        loginThrottle.requireMfaNotLocked(user.id(), LoginThrottle.MfaAttempt.STEP_UP);
        MfaService.StepUpStatus status;
        try {
            status = mfa.completeStepUp(user, sessionId(request),
                    new MfaService.Verification(body.code(), body.assertion(), body.challengeToken()),
                    webAuthn.relyingParty(request));
        } catch (BusinessException failure) {
            if (failure.getCode() == ErrorCode.MFA_INVALID_CODE) {
                loginThrottle.recordMfaFailure(user.id(), LoginThrottle.MfaAttempt.STEP_UP);
            }
            throw failure;
        }
        loginThrottle.clearMfa(user.id(), LoginThrottle.MfaAttempt.STEP_UP);
        return ApiResponse.ok(status);
    }

    static Long sessionId(HttpServletRequest request) {
        return request.getAttribute(AccessTokenFilter.SESSION_ID_ATTRIBUTE) instanceof Long id ? id : null;
    }

    record ConfirmTotpRequest(@NotBlank String code, @Size(max = 64) String name) {
    }

    record RegisterWebAuthnRequest(@NotBlank String challengeToken, @Size(max = 64) String name,
                                   @NotBlank String clientDataJSON, @NotBlank String attestationObject,
                                   List<String> transports) {
    }

    record StepUpRequest(String code, WebAuthnSupport.Assertion assertion, String challengeToken) {
    }
}
