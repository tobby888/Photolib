package cn.photolib.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 这个写接口不进操作日志。只给高频且没有信息量的请求用（轮询、定时刷新、只签发
 * 一次性票据而结果另有记录的），理由写在 {@link #value()} 里，方便下一个人判断还成不成立。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditSkip {
    /** 为什么不记。 */
    String value();
}
