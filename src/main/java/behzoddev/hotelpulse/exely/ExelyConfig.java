package behzoddev.hotelpulse.exely;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ExelyProperties.class)
public class ExelyConfig {

    /** Exely uchun alohida HTTP klient sozlamasi (timeout'lar bilan). Testlarda soxta server bilan almashtiriladi. */
    @Bean
    public RestClient.Builder exelyRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory);
    }

    /** Qo'lda ("Hozir sinxronlash") ishga tushirilgan sinxronlashlar uchun — so'rovni kuttirmaslik. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService exelySyncExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "exely-sync");
            t.setDaemon(true);
            return t;
        });
    }
}
