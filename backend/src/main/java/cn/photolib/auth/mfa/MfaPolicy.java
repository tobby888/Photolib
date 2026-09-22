package cn.photolib.auth.mfa;

/** 权限组对两步验证的要求。 */
public enum MfaPolicy {
    /** 不使用：不提示绑定、登录不要第二步、敏感操作不再验证。 */
    OFF,
    /** 建议：没绑定的人每次登录后弹一次建议页；绑定了就和强制一样生效。 */
    SUGGESTED,
    /** 强制：没绑定之前会话只能走绑定流程，连首次改密都要排在绑定之后。 */
    REQUIRED
}
