package cn.photolib;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@EnableScheduling
@MapperScan(basePackages = "cn.photolib.**.mapper", sqlSessionTemplateRef = "sqlSessionTemplate")
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ImportRuntimeHints(PhotoLibRuntimeHints.class)
public class PhotoLibApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhotoLibApplication.class, args);
    }
}
