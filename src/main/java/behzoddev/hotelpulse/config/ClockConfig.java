package behzoddev.hotelpulse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * "Bugun" mehmonxona vaqt zonasida aniqlanadi (server UTC'da ishlasa ham
 * tun yarmidan keyin sana noto'g'ri bo'lib qolmasligi uchun).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.zone:Asia/Tashkent}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
