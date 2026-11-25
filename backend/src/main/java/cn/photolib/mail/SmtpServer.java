package cn.photolib.mail;

/**
 * 发信时用的一个 SMTP 邮箱（密码已解密）。
 *
 * @param id          {@code smtp_account.id}；{@code .env} 兜底邮箱为 {@code null}
 * @param username    SMTP 登录名
 * @param fromAddress 发件地址；为空时用登录名（多数邮箱两者相同）
 * @param security    {@link #SSL} / {@link #STARTTLS} / {@link #NONE}
 * @param problem     这个邮箱眼下用不了的原因（例如密码解不开）；非空时直接算失败、顺延到下一个
 */
record SmtpServer(Long id, String name, String host, int port, String username, String password,
                  String fromAddress, String fromName, String security, String problem) {
    static final String SSL = "SSL";
    static final String STARTTLS = "STARTTLS";
    static final String NONE = "NONE";
    static final String ENV_NAME = "服务器 .env 配置";

    /** 实际写进 From 头和 SMTP {@code MAIL FROM} 的地址。 */
    String sender() {
        return fromAddress != null && !fromAddress.isBlank() ? fromAddress.trim() : username.trim();
    }

    String label() {
        return "「" + name + "」";
    }

    @Override
    public String toString() {
        // 不要让密码随着日志或异常信息漏出去。
        return "SmtpServer[id=" + id + ", name=" + name + ", host=" + host + ":" + port + ", sender=" + sender() + "]";
    }
}
