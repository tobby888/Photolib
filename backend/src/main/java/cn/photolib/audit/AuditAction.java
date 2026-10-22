package cn.photolib.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 这个接口在操作日志里叫什么，例如「删除图片」「认领需求」。
 *
 * <p>每个写接口（POST / PUT / PATCH / DELETE）都必须带它或 {@link AuditSkip}，
 * {@code AuditCoverageTests} 会挡住漏标的。GET 接口默认不记，标了它就会记——
 * 用在导出、下载备份这类"读走一大批数据"的接口上。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditAction {
    /** 操作名，动宾结构、不带主语，显示在日志列表的「操作」列。 */
    String value();
}
