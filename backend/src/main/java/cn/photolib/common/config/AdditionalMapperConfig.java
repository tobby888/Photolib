package cn.photolib.common.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "cn.photolib", annotationClass = Mapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class AdditionalMapperConfig {
}
