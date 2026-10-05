package behzoddev.hotelpulse.exely;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O'zbekiston Markaziy banki (cbu.uz) rasmiy kurslari — Exely'dan boshqa valyutada (masalan,
 * Booking.com/Trip.com bronlari USD'da) kelgan summalarni mehmonxona valyutasiga o'girish uchun.
 * Kurs sana bo'yicha olinadi va xotirada saqlanadi (o'tgan kunlar kursi o'zgarmaydi).
 */
@Slf4j
@Component
public class CurrencyRates {

    static final String BASE = "UZS";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RestClient rest;
    private final Clock clock;
    private final Map<String, BigDecimal> cache = new ConcurrentHashMap<>();

    public CurrencyRates(@Qualifier("exelyRestClientBuilder") RestClient.Builder builder,
                         @Value("${app.currency.cbu-url:https://cbu.uz}") String baseUrl,
                         Clock clock) {
        this.rest = builder.clone().baseUrl(baseUrl).build();
        this.clock = clock;
    }

    /**
     * amount'ni from valyutasidan to valyutasiga o'giradi (date kunidagi kurs bo'yicha; kelajak sanalar
     * uchun — bugungi kurs). Valyuta noma'lum yoki bir xil bo'lsa — o'zgarishsiz.
     */
    public BigDecimal convert(BigDecimal amount, String from, String to, LocalDate date) {
        if (amount == null || amount.signum() == 0) {
            return amount;
        }
        String src = normalize(from);
        String dst = normalize(to == null ? BASE : to);
        if (src == null || src.equals(dst)) {
            return amount;
        }
        BigDecimal uzs = amount.multiply(uzsPer(src, date));
        BigDecimal result = dst.equals(BASE) ? uzs : uzs.divide(uzsPer(dst, date), MathContext.DECIMAL64);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    /** 1 birlik valyuta necha so'm (Nominal hisobga olingan). */
    BigDecimal uzsPer(String ccy, LocalDate date) {
        if (BASE.equals(ccy)) {
            return BigDecimal.ONE;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate day = date == null || date.isAfter(today) ? today : date;
        return cache.computeIfAbsent(ccy + "@" + day, k -> fetch(ccy, day));
    }

    private BigDecimal fetch(String ccy, LocalDate day) {
        try {
            CbuRate[] r = rest.get().uri("/uz/arkhiv-kursov-valyut/json/{ccy}/{date}/", ccy, day.format(DAY))
                    .retrieve().body(CbuRate[].class);
            if (r == null || r.length == 0 || r[0].Rate() == null) {
                throw new ExelyException("Markaziy bank: " + ccy + " kursi topilmadi (" + day + ")", null, true);
            }
            BigDecimal nominal = r[0].Nominal() == null ? BigDecimal.ONE : new BigDecimal(r[0].Nominal());
            return new BigDecimal(r[0].Rate()).divide(nominal, MathContext.DECIMAL64);
        } catch (RestClientException | NumberFormatException e) {
            // Kurs bo'lmasa summani noto'g'ri yozgandan ko'ra sinxronlashni keyinga qoldirgan ma'qul.
            throw new ExelyException("Markaziy bank kursini olib bo'lmadi (" + ccy + ", " + day + "): " + e.getMessage(), e, true);
        }
    }

    private static String normalize(String ccy) {
        if (ccy == null || ccy.isBlank()) {
            return null;
        }
        String c = ccy.trim().toUpperCase(Locale.ROOT);
        return c.equals("UZB") || c.equals("SUM") || c.equals("SO'M") ? BASE : c;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CbuRate(String Ccy, String Rate, String Nominal, String Date) {
    }
}
