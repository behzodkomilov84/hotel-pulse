package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.HotelKpi;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.kpi.StayMetrics;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.KpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class PageController {

    private static final DateTimeFormatter CHART_DATE = DateTimeFormatter.ofPattern("dd.MM");

    private final HotelService hotelService;
    private final KpiService kpiService;

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
        return "dashboard";
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
        Period p = Period.resolve(period, from, to, today);
        HotelKpi kpi = kpiService.report(hotel, p);
        model.addAttribute("kpi", kpi);
        model.addAttribute("today", kpiService.todaySnapshot(hotel));

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
