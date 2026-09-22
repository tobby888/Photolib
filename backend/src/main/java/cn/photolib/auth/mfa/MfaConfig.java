package cn.photolib.auth.mfa;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;

@Configuration
@EnableConfigurationProperties(MfaProperties.class)
public class MfaConfig {

    /**
     * 挂在类或方法上的 {@link RequiresStepUp} 都生效。顺序紧跟在 {@code @PreAuthorize} 之后：
     * 先判有没有权限，再要求验证。标成基础设施角色，是因为项目里没有 AspectJ，
     * 负责创建代理的是只认基础设施 Advisor 的那一个。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor stepUpAdvisor(ObjectProvider<MfaService> mfa) {
        ComposablePointcut pointcut = new ComposablePointcut(new AnnotationMatchingPointcut(RequiresStepUp.class, true))
                .union(AnnotationMatchingPointcut.forMethodAnnotation(RequiresStepUp.class));
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, new StepUpInterceptor(mfa));
        advisor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1);
        return advisor;
    }
}
