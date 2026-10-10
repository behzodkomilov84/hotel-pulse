package behzoddev.hotelpulse.report;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.kpi.StayMetrics;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.service.KpiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talab dinamikasi uchun kunlik surat: har kuni bir marta har bir mehmonxonaning kelgusi 365 kun bo'yicha
 * sotilgan xonalar soni saqlanadi ("Оценка интенсивности спроса" — 1 va 7 kunlik pickup shundan hisoblanadi).
 * Soatlik tekshiriladi — server o'chiq bo'lgan bo'lsa ham kun ichida olinadi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtbSnapshotJob {

    static final int DAYS_AHEAD = 365;

    private final HotelRepository hotelRepository;
    private final KpiService kpiService;
    private final ReportData data;

    @Scheduled(cron = "0 5 * * * *", zone = "${app.zone:Asia/Tashkent}")
    public void run() {
        LocalDate today = kpiService.today();
        for (Hotel h : hotelRepository.findAll()) {
            if (h.isActive() && kpiService.hasData(h) && !data.hasSnapshot(h.getId(), today)) {
                try {
                    capture(h, today);
                } catch (RuntimeException e) {
                    log.warn("OTB surati ({}) olinmadi: {}", h.getName(), e.getMessage());
                }
            }
        }
    }

    public void capture(Hotel hotel, LocalDate today) {
        StayMetrics s = kpiService.metrics(hotel, new Period("custom", today, today.plusDays(DAYS_AHEAD - 1)));
        Map<LocalDate, Integer> sold = new LinkedHashMap<>();
        for (StayMetrics.DailyPoint d : s.daily()) {
            sold.put(d.date(), d.roomsSold());
        }
        data.saveSnapshot(hotel.getId(), today, sold);
    }
}
