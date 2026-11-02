package cn.photolib.settlement;

import cn.photolib.admin.BrandSnapshotService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.permission.PermissionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 站内信里的清算单（{@code settlement_statement} 快照）。收件人本人能看；能发清算单的全局账号也能看，
 * 方便在发送记录里核对发出去的是什么。其他人一律当作不存在，不透露这条清算单是谁的。
 */
@RestController
@RequestMapping("/settlement-statements")
@RequiredArgsConstructor
public class SettlementInboxController {
    private final JdbcClient jdbc;
    private final BrandSnapshotService brands;

    @GetMapping("/{id}")
    ApiResponse<StatementView> get(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        StatementView view = jdbc.sql("""
                SELECT id, user_id, recipient_name, from_date, to_date, subject, html_body, created_by_name, created_at
                FROM settlement_statement WHERE id=:id
                """).param("id", id)
                .query((rs, n) -> new StatementView(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getObject(9, LocalDateTime.class)))
                .optional()
                .filter(statement -> canView(statement, principal))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "清算单不存在"));
        return ApiResponse.ok(new StatementView(view.id(), view.userId(), view.recipientName(), view.from(), view.to(),
                view.subject(), SettlementMailRenderer.inlineLogo(view.html(), brands.current()),
                view.createdByName(), view.createdAt()));
    }

    static boolean canView(StatementView statement, AuthenticatedUser principal) {
        if (principal == null) return false;
        if (principal.id() != null && principal.id() == statement.userId()) return true;
        return principal.hasPermission(PermissionCode.SETTLEMENT_MAIL_SEND) && !principal.isCampusScoped();
    }

    public record StatementView(long id, long userId, String recipientName, LocalDate from, LocalDate to,
                                String subject, String html, String createdByName, LocalDateTime createdAt) {}
}
