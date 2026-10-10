package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.analysis.DebtAnalyzer;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.DebtReport;
import behzoddev.hotelpulse.kpi.HotelKpi;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.kpi.StayMetrics;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.DebtService;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.KpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class PageController {

    private static final DateTimeFormatter CHART_DATE = DateTimeFormatter.ofPattern("dd.MM");
    private static final List<String> SOURCE_PALETTE =
            List.of("#2457d6", "#7fb3a3", "#8b5cf6", "#f59e0b", "#ef4444", "#64748b");

    private final HotelService hotelService;
    private final KpiService kpiService;
    private final DebtService debtService;
    private final DebtAnalyzer debtAnalyzer;

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** Bosh sahifa — foydalanuvchi ko'ra oladigan mehmonxonalar va ularning shu oydagi qisqa ko'rsatkichlari. */
    @GetMapping("/")
    public String dashboard(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        List<Hotel> hotels = hotelService.accessibleHotels(user);
        model.addAttribute("hotels", hotels);
        model.addAttribute("summaries", kpiService.monthSummaries(hotels));
        // Jami va o'rtacha ADR — faqat foydalanuvchiga ochiq mehmonxonalar bo'yicha.
        model.addAttribute("portfolio", kpiService.portfolio(hotels, Period.resolve("month", null, null, kpiService.today())));
        return "dashboard";
    }

    /** Qarzdorlik bo'yicha batafsil hisobot. */
    @GetMapping("/hotels/{id}/debts")
    public String debts(@AuthenticationPrincipal CustomUserDetails user,
                        @PathVariable Long id,
                        @RequestParam(required = false) DebtReport.Category category,
                        @RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "debt") String sort,
                        Model model) {
        Hotel hotel = hotelService.getAccessible(user, id);
        model.addAttribute("hotel", hotel);
        model.addAttribute("report", debtService.report(hotel, category, q, sort));
        model.addAttribute("category", category);
        model.addAttribute("categories", DebtReport.Category.values());
        model.addAttribute("q", q);
        model.addAttribute("sort", sort);
        model.addAttribute("paymentsComplete", kpiService.paymentsComplete(hotel));
        return "debts";
    }

    /**
     * Qarzdorlik tahlili — faqat "Tahlil qilish" tugmasi bosilganda (sahifa fetch qiladi), HTML parcha qaytaradi.
     * Har doim to'liq hisobot bo'yicha (sahifadagi filtrdan qat'i nazar).
     */
    @GetMapping("/hotels/{id}/debts/analysis")
    public String debtAnalysis(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, Model model) {
        Hotel hotel = hotelService.getAccessible(user, id);
        DebtReport report = debtService.report(hotel, null, null, "debt");
        model.addAttribute("hotel", hotel);
        model.addAttribute("analysis", debtAnalyzer.analyze(report, hotel.getCurrency(), kpiService.paymentsComplete(hotel)));
        return "fragments/analysis :: analysis";
    }

    /** Qarzdorlik — Excel uchun CSV (UTF-8 BOM, ";" ajratuvchi — Excel o'zbekcha/ruscha matnni to'g'ri ochadi). */
    @GetMapping(value = "/hotels/{id}/debts.csv", produces = "text/csv")
    public ResponseEntity<byte[]> debtsCsv(@AuthenticationPrincipal CustomUserDetails user,
                                           @PathVariable Long id,
                                           @RequestParam(required = false) DebtReport.Category category,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "debt") String sort) {
        Hotel hotel = hotelService.getAccessible(user, id);
        DebtReport report = debtService.report(hotel, category, q, sort);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Bron raqami;Mehmon;Manba;Kelish;Ketish;Kechalar;Holat;Narx;To'langan;Qarz;Kun o'tdi\n");
        DateTimeFormatter d = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        for (DebtReport.Row r : report.rows()) {
            sb.append(csv(r.bookingNumber())).append(';')
                    .append(csv(r.guestName())).append(';')
                    .append(csv(r.source())).append(';')
                    .append(r.arrival().format(d)).append(';')
                    .append(r.departure().format(d)).append(';')
                    .append(r.nights()).append(';')
                    .append(csv(r.category().getLabel())).append(';')
                    .append(r.total().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(r.paid().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(r.debt().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(r.ageDays()).append('\n');
        }
        String file = "qarzdorlik-" + kpiService.today() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        // Formula sifatida bajarilib ketmasligi uchun (CSV injection).
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(";") || s.contains("\n") || s.contains("\"") ? "\"" + s + "\"" : s;
    }

    @GetMapping("/hotels/{id}")
    public String hotel(@AuthenticationPrincipal CustomUserDetails user,
                        @PathVariable Long id,
                        @RequestParam(required = false) String period,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        Model model) {
        Hotel hotel = hotelService.getAccessible(user, id);
        model.addAttribute("hotel", hotel);

        boolean hasData = kpiService.hasData(hotel);
        model.addAttribute("hasData", hasData);
        if (!hasData) {
            return "hotel";
        }

        LocalDate today = kpiService.today();
        model.addAttribute("todayDate", today);
        model.addAttribute("periodOptions", Period.OPTIONS);
        // Diagramma va jadvaldagi rang belgilari bir xil bo'lishi uchun bitta ro'yxat.
        model.addAttribute("palette", SOURCE_PALETTE);
        Period p = Period.resolve(period, from, to, today);
        HotelKpi kpi = kpiService.report(hotel, p);
        model.addAttribute("kpi", kpi);
        model.addAttribute("today", kpiService.todaySnapshot(hotel));
        model.addAttribute("paymentsComplete", kpiService.paymentsComplete(hotel));

        StayMetrics s = kpi.stays();
        model.addAttribute("chartLabels", s.daily().stream().map(d -> d.date().format(CHART_DATE)).toList());
        model.addAttribute("chartOccupancy", s.daily().stream()
                .map(d -> Math.round(d.occupancy() * 1000) / 10.0).toList());
        model.addAttribute("chartRevenue", s.daily().stream()
                .map(d -> d.revenue().setScale(0, RoundingMode.HALF_UP)).toList());
        model.addAttribute("sourceLabels", s.sources().stream().map(StayMetrics.SourceShare::source).toList());
        model.addAttribute("sourceRevenue", s.sources().stream()
                .map(x -> x.revenue().setScale(0, RoundingMode.HALF_UP)).map(BigDecimal::longValue).toList());
        return "hotel";
    }
}
