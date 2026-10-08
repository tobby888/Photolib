package cn.photolib.doc.file;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.ratelimit.ClientAddress;
import cn.photolib.common.ratelimit.FixedWindowRateLimiter;
import cn.photolib.doc.DocReader;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 文件库的流量闸门。所有数字都来自管理员的「上传限额 → 文件库」（{@link UploadLimitService}），
 * 这里不写死任何额度，改完立即生效（限额服务自带 30 秒快照，本实例改动即时失效）。
 *
 * <p>四道闸，从外到内：</p>
 * <ol>
 *   <li><b>全站 QPS</b>（{@link #tryAcquireUpload} / {@link #tryAcquireDownload}）——由
 *       {@link DocFileQpsFilter} 在 Servlet 过滤器里调用，早于 multipart 解析：超出的上传请求
 *       连文件内容都不读，不占带宽也不落临时文件。进程内计数，按单个后端实例计。</li>
 *   <li><b>每位读者每小时下载次数</b>——登录成员按账号，匿名访客按 IP。IP 取自
 *       {@code remoteAddr}，反向代理后面是代理地址，{@link ClientAddress} 对内网地址失败开放
 *       （否则全站访客共用一个计数，互相误伤）。</li>
 *   <li><b>匿名访客每日总流量</b>——不看 IP，按业务日在库里条件扣减（{@code doc_file_download_usage}）。
 *       这是防盗刷的主闸：换多少代理都绕不过，重启和多实例也绕不过。</li>
 *   <li><b>下载链接有效期</b>——签名直链的寿命，在 {@code DocFileService.download} 里用。</li>
 * </ol>
 */
@Component
public class DocFileTraffic {
    static final int MAX_TRACKED_READERS = 8_192;
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final UploadLimitService limits;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final FixedWindowRateLimiter global;
    private final FixedWindowRateLimiter perReader;

    public DocFileTraffic(UploadLimitService limits, JdbcClient jdbc, Clock clock) {
        this.limits = limits;
        this.jdbc = jdbc;
        this.clock = clock;
        this.global = new FixedWindowRateLimiter(clock, 8);
        this.perReader = new FixedWindowRateLimiter(clock, MAX_TRACKED_READERS);
    }

    public boolean tryAcquireUpload() {
        return global.tryAcquire("upload", limits.count(UploadLimit.FILE_UPLOAD_QPS), SECOND);
    }

    public boolean tryAcquireDownload() {
        return global.tryAcquire("download", limits.count(UploadLimit.FILE_DOWNLOAD_QPS), SECOND);
    }

    /** 每位读者每小时的下载次数。匿名访客拿不到公网地址时让行，由每日总流量兜底。 */
    public void requireReaderAllowance(DocReader reader, String remoteAddress) {
        String key;
        if (reader != null && reader.authenticated()) {
            key = "user:" + reader.userId();
        } else {
            String address = ClientAddress.normalize(remoteAddress);
            if (address == null) return;
            key = "ip:" + address;
        }
        if (!perReader.tryAcquire(key, limits.count(UploadLimit.FILE_DOWNLOADS_PER_HOUR), HOUR)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "下载太频繁了：每小时最多 " + limits.describe(UploadLimit.FILE_DOWNLOADS_PER_HOUR) + "，请稍后再试");
        }
    }

    /**
     * 记一次下载的用量。匿名下载要先在当天的全站额度里扣掉文件大小，扣不动就拒绝；
     * 成员下载只记账不设上限（成员可追责，限住他们只会妨碍正常使用）。
     *
     * <p>扣减是一条带条件的 UPDATE，并发下不会超卖；当天第一次下载时行还不存在，
     * 插入撞上主键（另一个请求刚插进去）就再扣一次。调用方应在事务里调用，
     * 后续步骤失败时这次扣减随之回滚。</p>
     */
    public void recordDownload(DocReader reader, long bytes) {
        boolean anonymous = reader == null || !reader.authenticated();
        String scope = anonymous ? "ANONYMOUS" : "MEMBER";
        long cap = anonymous ? limits.value(UploadLimit.FILE_ANONYMOUS_DAILY_BYTES) : Long.MAX_VALUE;
        LocalDate today = LocalDate.now(clock);
        if (anonymous && bytes > cap) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "这个文件超过了未登录访客每天的下载总量，请登录后下载");
        }
        if (consume(today, scope, bytes, cap)) return;
        try {
            jdbc.sql("""
                    INSERT INTO doc_file_download_usage(usage_date, reader_scope, bytes, downloads)
                    VALUES (:day, :scope, :bytes, 1)
                    """).param("day", today).param("scope", scope).param("bytes", bytes).update();
            return;
        } catch (DuplicateKeyException raced) {
            if (consume(today, scope, bytes, cap)) return;
        }
        if (!rowExists(today, scope)) {
            // 理论上走不到：插入撞了主键，行就一定在。留着这条免得静默放行。
            throw new IllegalStateException("下载用量行既插不进去也读不到");
        }
        throw new BusinessException(ErrorCode.RATE_LIMITED,
                "今天未登录访客的下载流量已经用完了，请登录后下载，或者明天再来");
    }

    private boolean consume(LocalDate day, String scope, long bytes, long cap) {
        return jdbc.sql("""
                UPDATE doc_file_download_usage SET bytes=bytes+:bytes, downloads=downloads+1
                WHERE usage_date=:day AND reader_scope=:scope AND bytes <= :cap - :bytes
                """).param("bytes", bytes).param("day", day).param("scope", scope).param("cap", cap)
                .update() == 1;
    }

    private boolean rowExists(LocalDate day, String scope) {
        Long count = jdbc.sql("""
                SELECT COUNT(*) FROM doc_file_download_usage WHERE usage_date=:day AND reader_scope=:scope
                """).param("day", day).param("scope", scope).query(Long.class).single();
        return count != null && count > 0;
    }

    /** 今天北京时间零点。每日上传个数从这里开始数。 */
    public LocalDateTime startOfToday() {
        return LocalDate.now(clock).atStartOfDay();
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
