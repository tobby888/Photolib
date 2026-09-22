package cn.photolib.auth;

import cn.photolib.auth.mfa.MfaPolicy;

/**
 * 当前账号的两步验证状态，随登录身份一起下发（{@code /auth/me}、登录应答）。
 *
 * <p>几个派生字段在这里一次算好，而不是交给前端拼：前端的跳转判断和后端的拦截
 * 必须给出同一个答案，把规则写两遍迟早会对不上。
 *
 * @param systemEnabled      管理员是否打开了全站两步验证
 * @param policy             账号所在权限组的策略（系统管理员组固定为 REQUIRED）
 * @param enrolled           是否至少有一个已确认的验证设备
 * @param active             两步验证对这个账号生效：登录要第二步、敏感操作要再验一次
 * @param enrollmentRequired 被强制却还没绑定——会话被限制在绑定流程里
 * @param suggested          被建议却还没绑定——登录后弹一次建议页
 */
public record MfaSummary(
        boolean systemEnabled,
        MfaPolicy policy,
        boolean enrolled,
        boolean active,
        boolean enrollmentRequired,
        boolean suggested
) {
    public static final MfaSummary NONE = of(false, MfaPolicy.OFF, false);

    public static MfaSummary of(boolean systemEnabled, MfaPolicy policy, boolean enrolled) {
        MfaPolicy effective = policy == null ? MfaPolicy.OFF : policy;
        boolean used = systemEnabled && effective != MfaPolicy.OFF;
        return new MfaSummary(systemEnabled, effective, enrolled,
                used && enrolled,
                used && effective == MfaPolicy.REQUIRED && !enrolled,
                used && effective == MfaPolicy.SUGGESTED && !enrolled);
    }
}
