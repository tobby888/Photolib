package cn.photolib.recruitment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class RecruitmentTimeConfig {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final Duration MAX_UPLOAD_URL_TTL = Duration.ofDays(7);

    @Bean
    Clock recruitmentClock() {
        return Clock.system(ZONE);
    }
}
