package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.controller.Formats;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.HotelKpi;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.kpi.StayMetrics;
import behzoddev.hotelpulse.kpi.TodaySnapshot;
import behzoddev.hotelpulse.service.KpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Telegram uchun hisobot matnlari (HTML parse_mode). */
@Service
@RequiredArgsConstructor
public class TelegramReportService {

    private static final Map<String, String> PERIOD_TITLES = Map.of(
            "today", "Bugun",
            "yesterday", "Kecha",
            "7d", "Oxirgi 7 kun",
            "month", "Shu oy");

    private final KpiService kpiService;
    private final Formats fmt;

    public Period period(String key) {
        return Period.resolve(key, null, null, kpiService.today());
    }

    /** Bitta mehmonxona bo'yicha to'liq hisobot. */
    public String hotelReport(Hotel hotel, String periodKey) {
        Period period = period(periodKey);
        if (!kpiService.hasData(hotel)) {
            return "🏨 <b>" + esc(hotel.getName()) + "</b>\n\nHali ma'lumot yo'q — Exely ulangach shu yerda ko'rinadi.";
        }
        HotelKpi kpi = kpiService.report(hotel, period);
        StayMetrics s = kpi.stays();
        StayMetrics ps = kpi.previousStays();
        String cur = hotel.getCurrency();
        boolean paymentsComplete = kpiService.paymentsComplete(hotel);

        StringBuilder sb = new StringBuilder();
        sb.append("🏨 <b>").append(esc(hotel.getName())).append("</b>\n");
        sb.append("📅 ").append(PERIOD_TITLES.getOrDefault(period.key(), "Davr"))
                .append(": ").append(period.label()).append("\n\n");

        sb.append("🛏 Bandlik: <b>").append(fmt.pct(s.occupancy())).append("</b>")
                .append(paren(fmt.pointChange(s.occupancy(), ps.occupancy()))).append("\n");
        sb.append("💰 Xona daromadi: <b>").append(fmt.moneyShort(s.roomRevenue(), cur)).append("</b>")
                .append(paren(fmt.change(s.roomRevenue(), ps.roomRevenue()))).append("\n");
        sb.append("🏷 ADR: ").append(fmt.money(s.adr(), cur)).append(paren(fmt.change(s.adr(), ps.adr()))).append("\n");
        sb.append("📈 RevPAR: ").append(fmt.money(s.revpar(), cur)).append("\n");
        sb.append("💳 Tushgan to'lovlar: ").append(fmt.moneyShort(kpi.paymentsReceived(), cur))
                .append(paymentsComplete ? "" : " <i>(faqat oldindan)</i>").append("\n");
        sb.append("🆕 Yangi bronlar: ").append(kpi.newBookings())
                .append(" · ❌ Bekor: ").append(kpi.cancellations()).append("\n");

        TodaySnapshot t = kpiService.todaySnapshot(hotel);
        sb.append("\n<b>Bugun</b>: 🛬 keladi ").append(t.arrivals())
                .append(" · 🛫 ketadi ").append(t.departures())
                .append(" · 🛏 band ").append(t.inHouseRooms()).append("/").append(hotel.getRoomsCount()).append("\n");
        if (paymentsComplete && t.debtorCount() > 0) {
            sb.append("⚠️ Qarzdorlik: <b>").append(fmt.moneyShort(t.debt(), cur)).append("</b> (")
                    .append(t.debtorCount()).append(" ta bron)\n");
        }
        return sb.toString();
    }

    /** Bir nechta mehmonxona bo'yicha qisqa jadval. */
    public String summaryReport(List<Hotel> hotels, String periodKey) {
        Period period = period(periodKey);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Barcha mehmonxonalar</b>\n");
        sb.append("📅 ").append(PERIOD_TITLES.getOrDefault(period.key(), "Davr"))
                .append(": ").append(period.label()).append("\n\n");

        Map<String, BigDecimal> totals = new TreeMap<>();
        long sold = 0;
        long available = 0;
        for (Hotel h : hotels) {
            if (!kpiService.hasData(h)) {
                sb.append("• ").append(esc(h.getName())).append(" — <i>ma'lumot yo'q</i>\n");
                continue;
            }
            StayMetrics s = kpiService.report(h, period).stays();
            sold += s.soldRoomNights();
            available += s.availableRoomNights();
            totals.merge(h.getCurrency(), s.roomRevenue(), BigDecimal::add);
            sb.append("• <b>").append(esc(h.getName())).append("</b> — ")
                    .append(fmt.pct(s.occupancy())).append(" · ")
                    .append(fmt.moneyShort(s.roomRevenue(), h.getCurrency())).append("\n");
        }
        if (available > 0) {
            sb.append("\n<b>Jami</b>: bandlik ").append(fmt.pct((double) sold / available));
            totals.forEach((cur, sum) -> sb.append(" · ").append(fmt.moneyShort(sum, cur)));
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Qarzdorlik ro'yxati. */
    public String debtReport(List<Hotel> hotels) {
        StringBuilder sb = new StringBuilder("⚠️ <b>Qarzdorlik</b>\n\n");
        boolean any = false;
        for (Hotel h : hotels) {
            if (!kpiService.hasData(h)) {
                continue;
            }
            if (!kpiService.paymentsComplete(h)) {
                sb.append("• ").append(esc(h.getName())).append(" — <i>Exely PMS API ulanganda ko'rinadi</i>\n");
                any = true;
                continue;
            }
            TodaySnapshot t = kpiService.todaySnapshot(h);
            sb.append("• <b>").append(esc(h.getName())).append("</b> — ");
            if (t.debtorCount() == 0) {
                sb.append("qarz yo'q ✅\n");
            } else {
                sb.append(fmt.money(t.debt(), h.getCurrency())).append(" (").append(t.debtorCount()).append(" ta bron)\n");
            }
            any = true;
        }
        return any ? sb.toString() : "Hali ma'lumot yo'q.";
    }

    private static String paren(String change) {
        return change == null ? "" : " (" + change + ")";
    }

    /** Telegram HTML uchun maxsus belgilarni qochirish. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
