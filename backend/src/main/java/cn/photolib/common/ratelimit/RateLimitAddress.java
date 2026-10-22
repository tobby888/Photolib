package cn.photolib.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 限速器用来计数的客户端地址。只给限速用，审计日志的 IP 另有 {@code common.util.ClientIp}，
 * 两边刻意不共用：限速的地址必须不可伪造，宁可拿不到也不能拿错；日志的地址是给人看的，
 * 能多给一点线索就多给一点。
 *
 * <p>规则只有一条：请求来自本机回环地址（同机 nginx）时，取 nginx 用 {@code $remote_addr}
 * <b>覆盖写入</b>的 {@code X-Real-IP}；其余情况一律用 {@code remoteAddr}。不看
 * {@code X-Forwarded-For}——它是追加的，最左边那段是客户端自己写的。</p>
 *
 * <p>拿到的地址再交给 {@link ClientAddress#normalize} 变成计数键：内网/回环仍然失败开放，
 * IPv6 按 /64 归并。</p>
 */
public final class RateLimitAddress {
    static final String HEADER = "X-Real-IP";
    private static final Pattern LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private RateLimitAddress() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!fromLocalProxy(remote)) return remote;
        String forwarded = request.getHeader(HEADER);
        if (forwarded == null) return remote;
        String candidate = forwarded.trim();
        return LITERAL.matcher(candidate).matches() ? candidate : remote;
    }

    private static boolean fromLocalProxy(String remote) {
        return remote != null
                && (remote.startsWith("127.") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1"));
    }
}
