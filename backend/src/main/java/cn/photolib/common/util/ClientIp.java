package cn.photolib.common.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 给审计日志等"要给人看"的地方取访问者 IP。
 *
 * <p>生产环境前面是同机的 nginx，{@code remoteAddr} 永远是 127.0.0.1。只有当请求确实
 * 来自本机回环地址（也就是 nginx）时，才读它带过来的 {@code X-Real-IP}；没有就退到
 * {@code X-Forwarded-For} 的最后一段（紧挨着本机代理的那一跳）。直接打到 8080 的请求
 * 头部可以随便伪造，所以那种情况下一律用 {@code remoteAddr}。</p>
 *
 * <p>限流不要用它：限流刻意按 {@code remoteAddr} 计数、遇到内网地址失败开放，
 * 原因见 {@link cn.photolib.common.ratelimit.ClientAddress}。</p>
 */
public final class ClientIp {
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isLoopback(remote)) return remote;
        String realIp = valid(request.getHeader("X-Real-IP"));
        if (realIp != null) return realIp;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String last = valid(forwarded.substring(forwarded.lastIndexOf(',') + 1));
            if (last != null) return last;
        }
        return remote;
    }

    private static boolean isLoopback(String address) {
        if (address == null) return false;
        return address.startsWith("127.") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1");
    }

    private static String valid(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return ADDRESS.matcher(trimmed).matches() ? trimmed : null;
    }
}
