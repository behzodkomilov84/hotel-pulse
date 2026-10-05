package behzoddev.hotelpulse.exely;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Exely Connect sozlamalari (application.yaml → app.exely.*).
 *
 * @param baseUrl         Exely Connect API va avtorizatsiya serveri manzili
 * @param pmsBaseUrl      Exely PMS Universal API (WebPMS) manzili
 * @param initialDays     birinchi sinxronlashda necha kun oldingacha o'zgargan bronlar olinadi
 * @param pageSize        bitta so'rovdagi bronlar soni (API maksimumi — 1000)
 * @param requestDelay    ketma-ket so'rovlar orasidagi pauza (API limitlarini hurmat qilish uchun)
 * @param schedulerEnabled avtomatik davriy sinxronlash yoqilganmi
 */
@ConfigurationProperties(prefix = "app.exely")
public record ExelyProperties(
        String baseUrl,
        String pmsBaseUrl,
        int initialDays,
        int pageSize,
        Duration requestDelay,
        boolean schedulerEnabled) {

    public ExelyProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://connect.hopenapi.com";
        }
        if (pmsBaseUrl == null || pmsBaseUrl.isBlank()) {
            pmsBaseUrl = "https://partner.tlintegration.com/api/webpms/v1";
        }
        if (initialDays <= 0) {
            initialDays = 400;
        }
        if (pageSize <= 0 || pageSize > 1000) {
            pageSize = 1000;
        }
        if (requestDelay == null) {
            requestDelay = Duration.ofMillis(120);
        }
    }
}
