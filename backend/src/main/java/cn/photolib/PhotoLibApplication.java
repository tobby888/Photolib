package cn.photolib;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@EnableScheduling
@MapperScan("cn.photolib.**.mapper")
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class PhotoLibApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhotoLibApplication.class, args);
    }
}
