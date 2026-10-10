package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.report.LayoutService;
import behzoddev.hotelpulse.report.ReportCatalog;
import behzoddev.hotelpulse.report.ReportCatalog.ReportDef;
import behzoddev.hotelpulse.report.ReportService;
import behzoddev.hotelpulse.report.ReportTable;
import behzoddev.hotelpulse.report.UsaliExpenseService;
import behzoddev.hotelpulse.report.UsaliLine;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.KpiService;
import behzoddev.hotelpulse.service.NotFoundException;
import behzoddev.hotelpulse.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Hisobotlar" menyusi: barcha hisobotlar katalogi (Exely PMS + USALI), bitta hisobot sahifasi va Excel,
 * mehmonxona ekranining tarkibi/tartibi (har foydalanuvchiga), USALI xarajatlarini oyma-oy kiritish.
 */
@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final LayoutService layoutService;
    private final UsaliExpenseService expenseService;
    private final HotelService hotelService;
    private final KpiService kpiService;

    @GetMapping
    public String catalog(@AuthenticationPrincipal CustomUserDetails user, @RequestParam(required = false) Long hotel,
                          Model model) {
        List<Hotel> hotels = hotelService.accessibleHotels(user);
        Map<String, List<ReportDef>> groups = new LinkedHashMap<>();
        ReportCatalog.GROUPS.forEach(g -> groups.put(g, ReportCatalog.group(g)));
        model.addAttribute("groups", groups);
        model.addAttribute("hotels", hotels);
        model.addAttribute("hotel", selected(hotels, hotel));
        model.addAttribute("layout", layoutService.get(user.getId()));
        model.addAttribute("canManage", TaskService.canAssign(user));
        long available = ReportCatalog.all().stream().filter(ReportDef::isAvailable).count();
        model.addAttribute("availableCount", available);
        model.addAttribute("totalCount", ReportCatalog.all().size());
        return "reports/catalog";
    }

    @GetMapping("/{key}")
    public String report(@AuthenticationPrincipal CustomUserDetails user, @PathVariable String key,
                         @RequestParam(required = false) Long hotel,
                         @RequestParam(required = false) String period,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         Model model) {
        ReportDef def = ReportCatalog.find(key).orElseThrow(() -> new NotFoundException("Hisobot topilmadi"));
        List<Hotel> hotels = hotelService.accessibleHotels(user);
        Hotel h = selected(hotels, hotel);
        if (def.kind() == ReportCatalog.Kind.WIDGET && h != null) {
            // Kartalar va grafiklar — mehmonxona ekranida.
            return "redirect:/hotels/" + h.getId() + "#block-" + key;
        }
        model.addAttribute("def", def);
        model.addAttribute("hotels", hotels);
        model.addAttribute("hotel", h);
        model.addAttribute("inLayout", layoutService.get(user.getId()).contains(key));
        model.addAttribute("periodOptions", Period.OPTIONS);
        if (h != null && def.isTable()) {
            Period p = Period.resolve(period, from, to, kpiService.today());
            model.addAttribute("period", p);
            model.addAttribute("table", reportService.build(key, h, p));
        }
        return "reports/report";
    }

    @GetMapping(value = "/{key}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal CustomUserDetails user, @PathVariable String key,
                                      @RequestParam Long hotel,
                                      @RequestParam(required = false) String period,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportDef def = ReportCatalog.find(key).filter(ReportDef::isTable)
                .orElseThrow(() -> new NotFoundException("Hisobot topilmadi"));
        Hotel h = hotelService.getAccessible(user, hotel);
        Period p = Period.resolve(period, from, to, kpiService.today());
        ReportTable t = reportService.build(key, h, p);
        String name = def.key() + "-" + p.from() + "_" + p.to() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(t.toCsv().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- Ekran tarkibi (har foydalanuvchiga)

    /** Tahrirlash rejimidan: bloklar tartibi ("today,kpi,flow"). */
    @PostMapping("/layout")
    public String saveLayout(@AuthenticationPrincipal CustomUserDetails user, @RequestParam(defaultValue = "") String keys,
                             @RequestParam(required = false) String back, RedirectAttributes ra) {
        layoutService.save(user.getId(), Arrays.asList(keys.split(",")));
        ra.addFlashAttribute("success", "Hisobotlar tartibi saqlandi");
        return "redirect:" + TaskController.safeBack(back);
    }

    /** Ro'yxatdan yangi hisobot qo'shish (oxiriga) — oldin tahrirlangan tartib (keys) ham saqlanadi. */
    @PostMapping("/layout/add")
    public String addToLayout(@AuthenticationPrincipal CustomUserDetails user, @RequestParam String key,
                              @RequestParam(required = false) String keys, @RequestParam(required = false) String back,
                              RedirectAttributes ra) {
        if (!ReportCatalog.isPlaceable(key)) {
            ra.addFlashAttribute("error", "Bu hisobotni qo'shib bo'lmaydi");
            return "redirect:" + TaskController.safeBack(back);
        }
        List<String> current = new ArrayList<>(keys != null ? Arrays.asList(keys.split(",")) : layoutService.get(user.getId()));
        current.remove(key);
        current.add(key);
        layoutService.save(user.getId(), current);
        ra.addFlashAttribute("success", "«" + ReportCatalog.find(key).orElseThrow().title() + "» ekranga qo'shildi");
        return "redirect:" + TaskController.safeBack(back) + "#block-" + key;
    }

    @PostMapping("/layout/reset")
    public String resetLayout(@AuthenticationPrincipal CustomUserDetails user, @RequestParam(required = false) String back,
                              RedirectAttributes ra) {
        layoutService.reset(user.getId());
        ra.addFlashAttribute("success", "Standart tarkib tiklandi");
        return "redirect:" + TaskController.safeBack(back);
    }

    // ---------------------------------------------------------------- USALI xarajatlari

    @GetMapping("/usali/expenses")
    public String expenses(@AuthenticationPrincipal CustomUserDetails user, @RequestParam(required = false) Long hotel,
                           @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                           Model model) {
        requireManager(user);
        List<Hotel> hotels = hotelService.accessibleHotels(user);
        Hotel h = selected(hotels, hotel);
        YearMonth m = month != null ? month : YearMonth.from(kpiService.today()).minusMonths(1);
        model.addAttribute("hotels", hotels);
        model.addAttribute("hotel", h);
        model.addAttribute("month", m);
        model.addAttribute("depts", UsaliLine.Dept.values());
        model.addAttribute("lines", Arrays.asList(UsaliLine.values()));
        if (h != null) {
            model.addAttribute("values", expenseService.month(h.getId(), m));
        }
        return "reports/usali-expenses";
    }

    @PostMapping("/usali/expenses")
    public String saveExpenses(@AuthenticationPrincipal CustomUserDetails user, @RequestParam Long hotel,
                               @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                               HttpServletRequest request, RedirectAttributes ra) {
        requireManager(user);
        Hotel h = hotelService.getAccessible(user, hotel);
        Map<UsaliLine, BigDecimal> values = new EnumMap<>(UsaliLine.class);
        try {
            for (UsaliLine l : UsaliLine.values()) {
                String raw = request.getParameter("line_" + l.name());
                if (raw != null && !raw.isBlank()) {
                    values.put(l, new BigDecimal(raw.replaceAll("[\\s  ]", "").replace(',', '.')));
                }
            }
            expenseService.save(h.getId(), month, values, user.getId());
            ra.addFlashAttribute("success", h.getName() + ": " + month + " xarajatlari saqlandi");
        } catch (NumberFormatException e) {
            ra.addFlashAttribute("error", "Summalar faqat raqam bo'lsin (masalan: 12 500 000)");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/reports/usali/expenses?hotel=" + h.getId() + "&month=" + month;
    }

    // ---------------------------------------------------------------- yordamchilar

    private static Hotel selected(List<Hotel> hotels, Long id) {
        if (hotels.isEmpty()) {
            return null;
        }
        return hotels.stream().filter(h -> h.getId().equals(id)).findFirst().orElse(hotels.get(0));
    }

    private static void requireManager(CustomUserDetails user) {
        if (!TaskService.canAssign(user)) {
            throw new AccessDeniedException("USALI xarajatlarini egasi yoki boshqaruv kompaniyasi kiritadi");
        }
    }
}
