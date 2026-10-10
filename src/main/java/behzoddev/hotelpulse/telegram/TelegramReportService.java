package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.controller.Formats;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.HotelKpi;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.kpi.PortfolioSummary;
import behzoddev.hotelpulse.kpi.StayMetrics;
import behzoddev.hotelpulse.kpi.TodaySnapshot;
import behzoddev.hotelpulse.service.KpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
        boolean hasExtras = s.extrasRevenue().signum() != 0;
        sb.append(hasExtras ? "💰 Yashash daromadi: <b>" : "💰 Xona daromadi: <b>")
                .append(fmt.moneyShort(s.roomRevenue(), cur)).append("</b>")
                .append(paren(fmt.change(s.roomRevenue(), ps.roomRevenue()))).append("\n");
        if (hasExtras) {
            sb.append("🍳 Nonushta: ").append(fmt.moneyShort(s.mealsRevenue(), cur))
                    .append(" · boshqa xizmatlar: ").append(fmt.moneyShort(s.extrasRevenue().subtract(s.mealsRevenue()), cur))
                    .append("\n");
        }
        sb.append("🏷 ADR: ").append(fmt.money(s.adr(), cur)).append(paren(fmt.change(s.adr(), ps.adr()))).append("\n");
        sb.append("📈 RevPAR: ").append(fmt.money(s.revpar(), cur)).append("\n");
        sb.append("\n<b>Daromad va tushum</b>\n");
        sb.append("💵 Daromad: <b>").append(fmt.moneyShort(s.totalRevenue(), cur)).append("</b>\n");
        sb.append("💳 Tushum (to'lovlar): <b>").append(fmt.moneyShort(kpi.paymentsReceived(), cur)).append("</b>")
                .append(paymentsComplete ? "" : " <i>(faqat oldindan)</i>").append("\n");
        BigDecimal gap = s.totalRevenue().subtract(kpi.paymentsReceived());
        sb.append("↔️ Farq: <b>").append(fmt.signedMoneyShort(gap, cur)).append("</b> — ")
                .append(fmt.gapNote(gap)).append("\n\n");
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

        for (Hotel h : hotels) {
            if (!kpiService.hasData(h)) {
                sb.append("• ").append(esc(h.getName())).append(" — <i>ma'lumot yo'q</i>\n");
                continue;
            }
            HotelKpi kpi = kpiService.report(h, period);
            StayMetrics s = kpi.stays();
            String cur = h.getCurrency();
            sb.append("• <b>").append(esc(h.getName())).append("</b> — ")
                    .append(fmt.pct(s.occupancy())).append(" · ADR ").append(fmt.moneyShort(s.adr(), cur)).append("\n")
                    .append("   daromad ").append(fmt.moneyShort(s.totalRevenue(), cur))
                    .append(" · tushum ").append(fmt.moneyShort(kpi.paymentsReceived(), cur))
                    .append(" · farq ").append(fmt.signedMoneyShort(s.totalRevenue().subtract(kpi.paymentsReceived()), cur))
                    .append("\n");
        }
        // Jami — faqat shu foydalanuvchiga ochiq mehmonxonalar bo'yicha (ro'yxat ruxsatlar bo'yicha keladi).
        for (PortfolioSummary p : kpiService.portfolio(hotels, period)) {
            String cur = p.currency();
            sb.append("\n<b>Jami</b> (").append(p.hotels()).append(" ta mehmonxona):\n")
                    .append("🛏 Bandlik: <b>").append(fmt.pct(p.occupancy())).append("</b>\n")
                    .append("🏷 O'rtacha ADR: <b>").append(fmt.money(p.adr(), cur)).append("</b>\n")
                    .append("📈 RevPAR: ").append(fmt.money(p.revpar(), cur)).append("\n")
                    .append("💵 Daromad: <b>").append(fmt.moneyShort(p.revenue(), cur)).append("</b>\n")
                    .append("💳 Tushum: <b>").append(fmt.moneyShort(p.payments(), cur)).append("</b>\n")
                    .append("↔️ Farq: <b>").append(fmt.signedMoneyShort(p.gap(), cur)).append("</b> — ")
                    .append(fmt.gapNote(p.gap())).append("\n");
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
