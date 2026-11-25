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
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
     *   <li>{@code cn.photolib} 下全部业务枚举（含仅用于实体列、请求参数或配置绑定的枚举），
     *       确保 {@code EnumTypeHandler} / {@code Enum.valueOf} / {@code values()} 在原生镜像下可用；</li>
     *   <li>{@link SystemMetaObject} 与 {@link MetaObject} 的方法反射：{@code BaseMapper.deleteById}
     *       （实体带 {@code @TableLogic} 且有 {@code FieldFill.UPDATE/INSERT_UPDATE} 字段，如所有 {@code BaseEntity} 子类）
     *       以及 {@code BaseMapper.deleteByIds} 会注入 OGNL 表达式
     *       {@code @org.apache.ibatis.reflection.SystemMetaObject@forObject(...).findProperty('id', false) != null}，
     *       MyBatis OGNL 在运行时通过 {@code Class.getDeclaredMethods()} 反射调用它们；</li>
     *   <li>JDK 集合接口及不可变/包装集合实现类的方法反射：{@code <script>} 动态 SQL 中如
     *       {@code campusIds.size() > 0}（{@code SurveyResponseMapper} 等）传入 {@code Set.of()} / {@code Set.copyOf()}
     *       生成的 {@code java.util.ImmutableCollections$SetN} 等类型时，OGNL 会反射查找 {@code size()} 等公开方法。</li>
     * </ul>
     */
    static final class Hints implements RuntimeHintsRegistrar {
        private static final List<String> OGNL_INTERNAL_COLLECTION_TYPES = List.of(
                "java.util.Arrays$ArrayList",
                "java.util.Collections$EmptyList",
                "java.util.Collections$EmptySet",
                "java.util.Collections$EmptyMap",
                "java.util.Collections$SingletonList",
                "java.util.Collections$SingletonSet",
                "java.util.Collections$SingletonMap",
                "java.util.Collections$UnmodifiableCollection",
                "java.util.Collections$UnmodifiableList",
                "java.util.Collections$UnmodifiableRandomAccessList",
                "java.util.Collections$UnmodifiableSet",
                "java.util.Collections$UnmodifiableMap",
                "java.util.ImmutableCollections$AbstractImmutableCollection",
                "java.util.ImmutableCollections$AbstractImmutableList",
                "java.util.ImmutableCollections$AbstractImmutableSet",
                "java.util.ImmutableCollections$AbstractImmutableMap",
                "java.util.ImmutableCollections$List12",
                "java.util.ImmutableCollections$ListN",
                "java.util.ImmutableCollections$SubList",
                "java.util.ImmutableCollections$Set12",
                "java.util.ImmutableCollections$SetN",
                "java.util.ImmutableCollections$Map1",
                "java.util.ImmutableCollections$MapN"
        );

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
                    MetaObject.class,
                    Collection.class,
                    List.class,
                    Set.class,
                    Map.class,
                    AbstractCollection.class,
                    AbstractList.class,
                    AbstractSet.class,
                    AbstractMap.class,
                    ArrayList.class,
                    LinkedList.class,
                    HashSet.class,
                    LinkedHashSet.class,
                    HashMap.class,
                    LinkedHashMap.class,
                    TreeSet.class,
                    TreeMap.class
            }) {
                hints.reflection().registerType(ognlType,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
            }
            for (String internalCollection : OGNL_INTERNAL_COLLECTION_TYPES) {
                hints.reflection().registerType(TypeReference.of(internalCollection),
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
            }
            registerApplicationEnums(hints, classLoader);
        }

        private static void registerApplicationEnums(RuntimeHints hints, ClassLoader classLoader) {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
            CachingMetadataReaderFactory metadataFactory = new CachingMetadataReaderFactory(resolver);
            try {
                for (Resource resource : resolver.getResources("classpath*:cn/photolib/**/*.class")) {
                    if (!resource.isReadable()) continue;
                    ClassMetadata metadata = metadataFactory.getMetadataReader(resource).getClassMetadata();
                    if (!Enum.class.getName().equals(metadata.getSuperClassName())) continue;
                    String className = metadata.getClassName();
                    if (className.contains("Tests") || className.endsWith("Test")) continue;
                    hints.reflection().registerType(TypeReference.of(className),
                            MemberCategory.INVOKE_PUBLIC_METHODS,
                            MemberCategory.ACCESS_DECLARED_FIELDS);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException("扫描 cn.photolib 枚举类以注册 Native Image 提示失败", ex);
            }
        }
    }
}
