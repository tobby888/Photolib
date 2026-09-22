package cn.photolib.auth.mfa;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在控制器方法或类上：调用前要求本会话在信任期内（15 分钟）验证过两步验证。
 *
 * <p>只对两步验证生效的账号（全站开关打开、所在权限组不是"不使用"、且已绑定设备）起作用，
 * 其余账号照常放行——没有设备的人无从再验证。没通过时返回 {@code STEP_UP_REQUIRED}，
 * 前端的请求层据此弹出验证框、验证成功后自动重试原请求，所以新接口只需要加上这个注解。
 *
 * <p>当前用在：删除图库图片、删除选题、删除需求、系统管理面板的全部接口，以及绑定新设备、
 * 删除设备（否则偷到会话的人能换上自己的验证器）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresStepUp {
}
