package cn.photolib.mail;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台配置的发信邮箱（{@code smtp_account}）。发信时按 {@code sort_order} 依次尝试，前一个失败就顺延，
 * 详见 {@link SmtpMailSender}。
 *
 * <p>所有查询都用显式的 {@code RowMapper} lambda，不用 {@code query(Class)} 的反射映射——
 * 原生镜像里那条路需要额外的反射提示。</p>
 */
@Slf4j
@Service
public class SmtpAccountService {
    static final int MAX_ACCOUNTS = 20;
    private static final Set<String> SECURITY = Set.of(SmtpServer.SSL, SmtpServer.STARTTLS, SmtpServer.NONE);
    private static final String COLUMNS = """
            id, name, host, port, username, password_secret, from_address, from_name, security, enabled,
            sort_order, last_success_at, last_failure_at, last_error, updated_at""";

    private final JdbcClient jdbc;
    private final SmtpProperties properties;
    private final SmtpPasswordCipher cipher;

    public SmtpAccountService(JdbcClient jdbc, SmtpProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cipher = new SmtpPasswordCipher(properties.encryptionKey());
    }

    /** 管理端看到的一行。密码永远不出库，只告诉前端「填过没有、是不是加密存的」。 */
    public record AccountView(long id, String name, String host, int port, String username, String fromAddress,
                              String sender, String fromName, String security, boolean enabled, int sortOrder,
                              boolean passwordEncrypted, LocalDateTime lastSuccessAt, LocalDateTime lastFailureAt,
                              String lastError, LocalDateTime updatedAt) {}

    /** {@code .env} 里的兜底邮箱，只读展示。 */
    public record EnvView(String host, int port, String username, String sender, String security) {}

    public record Overview(List<AccountView> accounts, EnvView envFallback, boolean encrypting) {}

    /** {@code password} 为空表示保持原密码（只在修改时允许）。 */
    public record Input(String name, String host, Integer port, String username, String password, String fromAddress,
                        String fromName, String security, Boolean enabled) {}

    private record Row(AccountView view, String secret) {}

    public Overview overview() {
        SmtpServer env = properties.envServer();
        EnvView envView = env == null ? null
                : new EnvView(env.host(), env.port(), env.username(), env.sender(), env.security());
        return new Overview(rows().stream().map(Row::view).toList(), envView, cipher.encrypting());
    }

    @Transactional
    public AccountView create(Input input) {
        Normalized value = normalize(input, true);
        long count = jdbc.sql("SELECT COUNT(*) FROM smtp_account").query(Long.class).single();
        if (count >= MAX_ACCOUNTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "最多配置 " + MAX_ACCOUNTS + " 个发信邮箱");
        }
        int order = jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) FROM smtp_account").query(Integer.class).single() + 1;
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO smtp_account(name, host, port, username, password_secret, from_address, from_name,
                    security, enabled, sort_order, created_at, updated_at)
                VALUES (:name, :host, :port, :username, :secret, :fromAddress, :fromName, :security, :enabled,
                    :sortOrder, :now, :now)
                """)
                .param("name", value.name).param("host", value.host).param("port", value.port)
                .param("username", value.username).param("secret", cipher.seal(value.password))
                .param("fromAddress", value.fromAddress).param("fromName", value.fromName)
                .param("security", value.security).param("enabled", value.enabled)
                .param("sortOrder", order).param("now", now)
                .update(keys, "id");
        return view(keys.getKeyAs(Number.class).longValue());
    }

    @Transactional
    public AccountView update(long id, Input input) {
        require(id);
        Normalized value = normalize(input, false);
        jdbc.sql("""
                UPDATE smtp_account SET name=:name, host=:host, port=:port, username=:username,
                    password_secret=COALESCE(:secret, password_secret), from_address=:fromAddress,
                    from_name=:fromName, security=:security, enabled=:enabled, updated_at=:now
                WHERE id=:id
                """)
                .param("name", value.name).param("host", value.host).param("port", value.port)
                .param("username", value.username)
                .param("secret", value.password == null ? null : cipher.seal(value.password))
                .param("fromAddress", value.fromAddress).param("fromName", value.fromName)
                .param("security", value.security).param("enabled", value.enabled)
                .param("now", LocalDateTime.now()).param("id", id)
                .update();
        return view(id);
    }

    @Transactional
    public void delete(long id) {
        require(id);
        jdbc.sql("DELETE FROM smtp_account WHERE id=:id").param("id", id).update();
    }

    /** {@code ids} 必须恰好是现有的全部邮箱，按新顺序排列。 */
    @Transactional
    public List<AccountView> reorder(List<Long> ids) {
        List<Long> existing = jdbc.sql("SELECT id FROM smtp_account").query(Long.class).list();
        Set<Long> wanted = new HashSet<>(ids);
        if (wanted.size() != ids.size() || !wanted.equals(new HashSet<>(existing))) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "邮箱列表已经变了，请刷新后再调整顺序");
        }
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < ids.size(); i++) {
            jdbc.sql("UPDATE smtp_account SET sort_order=:order, updated_at=:now WHERE id=:id")
                    .param("order", i + 1).param("now", now).param("id", ids.get(i)).update();
        }
        return rows().stream().map(Row::view).toList();
    }

    /** 启用的邮箱，按发信顺序。密码解不开的也列出来（带上原因），发信时算失败、顺延到下一个。 */
    List<SmtpServer> enabledServers() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM smtp_account WHERE enabled=TRUE ORDER BY sort_order, id")
                .query(SmtpAccountService::row).list().stream().map(this::server).toList();
    }

    long enabledCount() {
        return jdbc.sql("SELECT COUNT(*) FROM smtp_account WHERE enabled=TRUE").query(Long.class).single();
    }

    /** 测试发信用：停用的邮箱也能单独测。 */
    SmtpServer server(long id) {
        return server(jdbc.sql("SELECT " + COLUMNS + " FROM smtp_account WHERE id=:id").param("id", id)
                .query(SmtpAccountService::row).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "发信邮箱不存在")));
    }

    /** 只用指定的后台邮箱发一封测试信，不顺延。返回实际发件地址。 */
    public String sendVia(long id, OutgoingMail mail) {
        SmtpServer target = server(id);
        try {
            SmtpTransport.send(target, properties, mail);
        } catch (MailDeliveryException ex) {
            if (ex.fallbackAllowed()) recordFailure(target.id(), ex.getMessage());
            throw ex;
        }
        recordSuccess(target.id());
        return target.sender();
    }

    void recordSuccess(Long id) {
        if (id == null) return;
        try {
            jdbc.sql("UPDATE smtp_account SET last_success_at=:now WHERE id=:id")
                    .param("now", LocalDateTime.now()).param("id", id).update();
        } catch (DataAccessException ex) {
            log.warn("记录发信邮箱 {} 的成功时间失败：{}", id, ex.getMessage());
        }
    }

    void recordFailure(Long id, String error) {
        if (id == null) return;
        try {
            jdbc.sql("UPDATE smtp_account SET last_failure_at=:now, last_error=:error WHERE id=:id")
                    .param("now", LocalDateTime.now()).param("error", clip(error, 500)).param("id", id).update();
        } catch (DataAccessException ex) {
            log.warn("记录发信邮箱 {} 的失败原因失败：{}", id, ex.getMessage());
        }
    }

    private SmtpServer server(Row row) {
        AccountView v = row.view();
        String password = null;
        String problem = null;
        try {
            password = cipher.open(row.secret());
        } catch (IllegalStateException ex) {
            problem = ex.getMessage();
        }
        return new SmtpServer(v.id(), v.name(), v.host(), v.port(), v.username(), password,
                v.fromAddress(), v.fromName(), v.security(), problem);
    }

    private List<Row> rows() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM smtp_account ORDER BY sort_order, id")
                .query(SmtpAccountService::row).list();
    }

    private AccountView view(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM smtp_account WHERE id=:id").param("id", id)
                .query(SmtpAccountService::row).optional().map(Row::view)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "发信邮箱不存在"));
    }

    private void require(long id) {
        long found = jdbc.sql("SELECT COUNT(*) FROM smtp_account WHERE id=:id").param("id", id).query(Long.class).single();
        if (found == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "发信邮箱不存在");
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        String username = rs.getString("username");
        String fromAddress = rs.getString("from_address");
        String secret = rs.getString("password_secret");
        String sender = fromAddress != null && !fromAddress.isBlank() ? fromAddress : username;
        return new Row(new AccountView(rs.getLong("id"), rs.getString("name"), rs.getString("host"),
                rs.getInt("port"), username, fromAddress, sender, rs.getString("from_name"),
                rs.getString("security"), rs.getBoolean("enabled"), rs.getInt("sort_order"),
                SmtpPasswordCipher.isEncrypted(secret),
                rs.getObject("last_success_at", LocalDateTime.class),
                rs.getObject("last_failure_at", LocalDateTime.class), rs.getString("last_error"),
                rs.getObject("updated_at", LocalDateTime.class)), secret);
    }

    record Normalized(String name, String host, int port, String username, String password,
                              String fromAddress, String fromName, String security, boolean enabled) {}

    static Normalized normalize(Input input, boolean creating) {
        String name = strip(input.name());
        if (name == null) throw invalid("请填写邮箱名称");
        if (name.length() > 100) throw invalid("邮箱名称不能超过 100 个字");
        String host = strip(input.host());
        if (host == null) throw invalid("请填写 SMTP 服务器地址");
        if (host.length() > 255 || !host.matches("[A-Za-z0-9.\\-\\[\\]:]+")) {
            throw invalid("SMTP 服务器地址只能是域名或 IP，例如 smtp.qq.com");
        }
        if (input.port() == null || input.port() < 1 || input.port() > 65535) throw invalid("端口必须在 1～65535 之间");
        String username = strip(input.username());
        if (username == null) throw invalid("请填写 SMTP 登录名");
        if (username.length() > 255 || hasLineBreak(username)) throw invalid("SMTP 登录名格式不正确");
        String password = input.password() == null || input.password().isBlank() ? null : input.password();
        if (password == null && creating) throw invalid("请填写密码或授权码");
        if (password != null && (password.length() > 512 || hasLineBreak(password))) throw invalid("密码格式不正确");
        String fromAddress = strip(input.fromAddress());
        if (fromAddress != null && (fromAddress.length() > 255 || !isMailbox(fromAddress))) {
            throw invalid("发件地址不是有效的邮箱地址");
        }
        if (fromAddress == null && !isMailbox(username)) {
            throw invalid("SMTP 登录名「" + username + "」不是邮箱地址，请另外填写发件地址"
                    + "（有些服务商的登录名和发件地址是分开的）");
        }
        String fromName = strip(input.fromName() == null ? null : input.fromName().replaceAll("[\\r\\n]+", " "));
        if (fromName != null && fromName.length() > 100) throw invalid("发件人显示名不能超过 100 个字");
        String security = strip(input.security());
        if (security == null || !SECURITY.contains(security)) throw invalid("加密方式只能是 SSL、STARTTLS 或 NONE");
        return new Normalized(name, host, input.port(), username, password, fromAddress, fromName, security,
                input.enabled() == null || input.enabled());
    }

    static boolean isMailbox(String value) {
        if (value == null || hasLineBreak(value) || value.indexOf('@') <= 0) return false;
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            return value.equals(address.getAddress());
        } catch (AddressException ex) {
            return false;
        }
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static String strip(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    static String clip(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
