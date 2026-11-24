package cn.photolib.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.type.EnumOrdinalTypeHandler;
import org.apache.ibatis.type.EnumTypeHandler;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.time.LocalDateTime;

@Configuration
@ImportRuntimeHints(MybatisConfig.Hints.class)
public class MybatisConfig {

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "version", Integer.class, 1);
                strictInsertFill(metaObject, "deleted", Boolean.class, false);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }

    /**
     * 补齐 MyBatis-Plus 3.5.18.1 原生支持模块遗漏的反射提示：
     * <ul>
     *   <li>枚举 TypeHandler 的单参构造器（查询含枚举列的实体时 {@code TypeHandlerRegistry.getInstance} 反射调用）；</li>
     *   <li>{@link SystemMetaObject} 与 {@link MetaObject} 的方法反射：{@code BaseMapper.deleteById}
     *       （实体带 {@code @TableLogic} 且有 {@code FieldFill.UPDATE/INSERT_UPDATE} 字段，如所有 {@code BaseEntity} 子类）
     *       以及 {@code BaseMapper.deleteByIds} 会注入 OGNL 表达式
     *       {@code @org.apache.ibatis.reflection.SystemMetaObject@forObject(...).findProperty('id', false) != null}，
     *       MyBatis OGNL 在运行时通过 {@code Class.getDeclaredMethods()} 反射调用它们。</li>
     * </ul>
     */
    static final class Hints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            for (Class<?> handler : new Class<?>[]{
                    CompositeEnumTypeHandler.class,
                    MybatisEnumTypeHandler.class,
                    EnumTypeHandler.class,
                    EnumOrdinalTypeHandler.class
            }) {
                hints.reflection().registerType(handler, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            }
            for (Class<?> ognlType : new Class<?>[]{
                    SystemMetaObject.class,
                    MetaObject.class
            }) {
                hints.reflection().registerType(ognlType,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
            }
        }
    }
}
