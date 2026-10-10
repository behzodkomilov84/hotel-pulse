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
            "today", "Бугун",
            "yesterday", "Кеча",
            "7d", "Охирги 7 кун",
            "month", "Шу ой");

    private final KpiService kpiService;
    private final Formats fmt;

    public Period period(String key) {
        return Period.resolve(key, null, null, kpiService.today());
    }

    /** Bitta mehmonxona bo'yicha to'liq hisobot. */
    public String hotelReport(Hotel hotel, String periodKey) {
        Period period = period(periodKey);
        if (!kpiService.hasData(hotel)) {
            return "🏨 <b>" + esc(hotel.getName()) + "</b>\n\nҲали маълумот йўқ — Exely улангач шу ерда кўринади.";
        }
        HotelKpi kpi = kpiService.report(hotel, period);
        StayMetrics s = kpi.stays();
        StayMetrics ps = kpi.previousStays();
        String cur = hotel.getCurrency();
        boolean paymentsComplete = kpiService.paymentsComplete(hotel);

        StringBuilder sb = new StringBuilder();
        sb.append("🏨 <b>").append(esc(hotel.getName())).append("</b>\n");
        sb.append("📅 ").append(PERIOD_TITLES.getOrDefault(period.key(), "Давр"))
                .append(": ").append(period.label()).append("\n\n");

        sb.append("🛏 Бандлик: <b>").append(fmt.pct(s.occupancy())).append("</b>")
                .append(paren(fmt.pointChange(s.occupancy(), ps.occupancy()))).append("\n");
        boolean hasExtras = s.extrasRevenue().signum() != 0;
        sb.append(hasExtras ? "💰 Яшаш даромади: <b>" : "💰 Хона даромади: <b>")
                .append(fmt.moneyShort(s.roomRevenue(), cur)).append("</b>")
                .append(paren(fmt.change(s.roomRevenue(), ps.roomRevenue()))).append("\n");
        if (hasExtras) {
            sb.append("🍳 Нонушта: ").append(fmt.moneyShort(s.mealsRevenue(), cur))
                    .append(" · бошқа хизматлар: ").append(fmt.moneyShort(s.extrasRevenue().subtract(s.mealsRevenue()), cur))
                    .append("\n");
        }
        sb.append("🏷 ADR: ").append(fmt.money(s.adr(), cur)).append(paren(fmt.change(s.adr(), ps.adr()))).append("\n");
        sb.append("📈 RevPAR: ").append(fmt.money(s.revpar(), cur)).append("\n");
        sb.append("\n<b>Даромад ва тушум</b>\n");
        sb.append("💵 Даромад: <b>").append(fmt.moneyShort(s.totalRevenue(), cur)).append("</b>\n");
        sb.append("💳 Тушум (тўловлар): <b>").append(fmt.moneyShort(kpi.paymentsReceived(), cur)).append("</b>")
                .append(paymentsComplete ? "" : " <i>(фақат олдиндан)</i>").append("\n");
        BigDecimal gap = s.totalRevenue().subtract(kpi.paymentsReceived());
        sb.append("↔️ Фарқ: <b>").append(fmt.signedMoneyShort(gap, cur)).append("</b> — ")
                .append(fmt.gapNote(gap)).append("\n\n");
        sb.append("🆕 Янги бронлар: ").append(kpi.newBookings())
                .append(" · ❌ Бекор: ").append(kpi.cancellations()).append("\n");

        TodaySnapshot t = kpiService.todaySnapshot(hotel);
        sb.append("\n<b>Бугун</b>: 🛬 келади ").append(t.arrivals())
                .append(" · 🛫 кетади ").append(t.departures())
                .append(" · 🛏 банд ").append(t.inHouseRooms()).append("/").append(hotel.getRoomsCount()).append("\n");
        if (paymentsComplete && t.debtorCount() > 0) {
            sb.append("⚠️ Қарздорлик: <b>").append(fmt.moneyShort(t.debt(), cur)).append("</b> (")
                    .append(t.debtorCount()).append(" та брон)\n");
        }
        return sb.toString();
    }

    /** Bir nechta mehmonxona bo'yicha qisqa jadval. */
    public String summaryReport(List<Hotel> hotels, String periodKey) {
        Period period = period(periodKey);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Барча меҳмонхоналар</b>\n");
        sb.append("📅 ").append(PERIOD_TITLES.getOrDefault(period.key(), "Давр"))
                .append(": ").append(period.label()).append("\n\n");

        for (Hotel h : hotels) {
            if (!kpiService.hasData(h)) {
                sb.append("• ").append(esc(h.getName())).append(" — <i>маълумот йўқ</i>\n");
                continue;
            }
            HotelKpi kpi = kpiService.report(h, period);
            StayMetrics s = kpi.stays();
            String cur = h.getCurrency();
            sb.append("• <b>").append(esc(h.getName())).append("</b> — ")
                    .append(fmt.pct(s.occupancy())).append(" · ADR ").append(fmt.moneyShort(s.adr(), cur)).append("\n")
                    .append("   даромад ").append(fmt.moneyShort(s.totalRevenue(), cur))
                    .append(" · тушум ").append(fmt.moneyShort(kpi.paymentsReceived(), cur))
                    .append(" · фарқ ").append(fmt.signedMoneyShort(s.totalRevenue().subtract(kpi.paymentsReceived()), cur))
                    .append("\n");
        }
        // Jami — faqat shu foydalanuvchiga ochiq mehmonxonalar bo'yicha (ro'yxat ruxsatlar bo'yicha keladi).
        for (PortfolioSummary p : kpiService.portfolio(hotels, period)) {
            String cur = p.currency();
            sb.append("\n<b>Жами</b> (").append(p.hotels()).append(" та меҳмонхона):\n")
                    .append("🛏 Бандлик: <b>").append(fmt.pct(p.occupancy())).append("</b>\n")
                    .append("🏷 Ўртача ADR: <b>").append(fmt.money(p.adr(), cur)).append("</b>\n")
                    .append("📈 RevPAR: ").append(fmt.money(p.revpar(), cur)).append("\n")
                    .append("💵 Даромад: <b>").append(fmt.moneyShort(p.revenue(), cur)).append("</b>\n")
                    .append("💳 Тушум: <b>").append(fmt.moneyShort(p.payments(), cur)).append("</b>\n")
                    .append("↔️ Фарқ: <b>").append(fmt.signedMoneyShort(p.gap(), cur)).append("</b> — ")
                    .append(fmt.gapNote(p.gap())).append("\n");
        }
        return sb.toString();
    }

    /** Qarzdorlik ro'yxati. */
    public String debtReport(List<Hotel> hotels) {
        StringBuilder sb = new StringBuilder("⚠️ <b>Қарздорлик</b>\n\n");
        boolean any = false;
        for (Hotel h : hotels) {
            if (!kpiService.hasData(h)) {
                continue;
            }
            if (!kpiService.paymentsComplete(h)) {
                sb.append("• ").append(esc(h.getName())).append(" — <i>Exely PMS API уланганда кўринади</i>\n");
                any = true;
                continue;
            }
            TodaySnapshot t = kpiService.todaySnapshot(h);
            sb.append("• <b>").append(esc(h.getName())).append("</b> — ");
            if (t.debtorCount() == 0) {
                sb.append("қарз йўқ ✅\n");
            } else {
                sb.append(fmt.money(t.debt(), h.getCurrency())).append(" (").append(t.debtorCount()).append(" та брон)\n");
            }
            any = true;
        }
        return any ? sb.toString() : "Ҳали маълумот йўқ.";
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
