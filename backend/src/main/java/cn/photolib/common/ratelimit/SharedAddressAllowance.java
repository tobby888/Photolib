package cn.photolib.common.ratelimit;

import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * 把"单人额度"放大成"单个 IP 的额度"。
 *
 * <p>一个公网 IPv4 背后常常是整个校园网或宿舍楼的 NAT：活动现场几十个人同时报名、同时
 * 打开一条分享链接，都会落在同一个计数键上。所以匿名限速器里写的额度都按"一个人"来定，
 * 按 IP 计数时再乘以管理员设的「同一 IP 按多少位访客计」（{@link UploadLimit#SHARED_IP_VISITORS}）。
 * 按链接、按账号这类不看 IP 的计数器不乘。</p>
 */
@Component
public class SharedAddressAllowance {
    private final IntSupplier visitorsPerAddress;

    @Autowired
    public SharedAddressAllowance(UploadLimitService limits) {
        this(() -> limits.count(UploadLimit.SHARED_IP_VISITORS));
    }

    SharedAddressAllowance(IntSupplier visitorsPerAddress) {
        this.visitorsPerAddress = visitorsPerAddress;
    }

    /** 测试用：固定每个 IP 按 {@code visitors} 人计。 */
    public static SharedAddressAllowance fixed(int visitors) {
        return new SharedAddressAllowance(() -> visitors);
    }

    /** 单人额度 × 每个 IP 的访客数，溢出时封顶。 */
    public int perAddress(int perVisitor) {
        long scaled = (long) perVisitor * Math.max(1, visitorsPerAddress.getAsInt());
        return (int) Math.min(Integer.MAX_VALUE, scaled);
    }
}
