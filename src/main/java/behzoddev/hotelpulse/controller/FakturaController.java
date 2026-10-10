package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.faktura.FakturaService;
import behzoddev.hotelpulse.faktura.FakturaService.Doc;
import behzoddev.hotelpulse.report.UsaliLine;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

/**
 * Хизматлар → Электрон ҳисоб-фактуралар (Didox yoki Faktura.uz): kiruvchi hisob-fakturalar (USALI moddasiga ajratish, yetkazib beruvchi qoidasi) va
 * chiquvchilar (qaysi bronga yozilgan). Egasi va boshqaruv kompaniyasi uchun.
 */
@Controller
@RequestMapping("/faktura")
@RequiredArgsConstructor
public class FakturaController {

    private final FakturaService faktura;
    private final HotelService hotelService;

    @GetMapping
    public String documents(@AuthenticationPrincipal CustomUserDetails user,
                            @RequestParam(required = false) Long hotel,
                            @RequestParam(defaultValue = "IN") String dir,
                            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                            @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "false") boolean open,
                            jakarta.servlet.http.HttpServletRequest request, Model model) {
        requireManager(user);
        model.addAttribute("back", request.getRequestURI() + (request.getQueryString() != null ? "?" + request.getQueryString() : ""));
        List<Hotel> hotels = hotelService.accessibleHotels(user);
        Hotel h = hotels.stream().filter(x -> x.getId().equals(hotel)).findFirst()
                .orElse(hotels.isEmpty() ? null : hotels.get(0));
        String direction = FakturaService.OUT.equals(dir) ? FakturaService.OUT : FakturaService.IN;
        model.addAttribute("hotels", hotels);
        model.addAttribute("hotel", h);
        model.addAttribute("dir", direction);
        model.addAttribute("month", month);
        model.addAttribute("q", q);
        model.addAttribute("open", open);
        model.addAttribute("lines", Arrays.asList(UsaliLine.values()));
        model.addAttribute("depts", UsaliLine.Dept.values());
        if (h != null) {
            List<Doc> docs = faktura.documents(h.getId(), direction, month, q, open);
            model.addAttribute("docs", docs);
            model.addAttribute("account", faktura.account(h.getId()).orElse(null));
            model.addAttribute("running", faktura.isRunning(h.getId()));
            model.addAttribute("rules", faktura.rules(h.getId()));
            model.addAttribute("total", docs.stream().filter(d -> !d.isVoid()).map(Doc::gross)
                    .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return "services/faktura";
    }

    @PostMapping("/{id}/line")
    public String assignLine(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long id,
                             @RequestParam Long hotel, @RequestParam(required = false) String line,
                             @RequestParam(defaultValue = "false") boolean remember,
                             @RequestParam(required = false) String back, RedirectAttributes ra) {
        requireManager(user);
        Hotel h = hotelService.getAccessible(user, hotel);
        try {
            UsaliLine l = line == null || line.isBlank() ? null : UsaliLine.valueOf(line);
            faktura.assignLine(h.getId(), id, l, remember);
            ra.addFlashAttribute("success", l == null ? "Модда олиб ташланди"
                    : "Модда белгиланди: " + l.getLabel() + (remember ? " (шу етказиб берувчи учун эслаб қолинди)" : ""));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + TaskController.safeBack(back);
    }

    @PostMapping("/{id}/booking")
    public String linkBooking(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long id,
                              @RequestParam Long hotel, @RequestParam(required = false) String booking,
                              @RequestParam(required = false) String back, RedirectAttributes ra) {
        requireManager(user);
        Hotel h = hotelService.getAccessible(user, hotel);
        try {
            faktura.linkBooking(h.getId(), id, booking);
            ra.addFlashAttribute("success", booking == null || booking.isBlank() ? "Брондан ажратилди" : "Бронга боғланди: " + booking.trim());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + TaskController.safeBack(back);
    }

    @PostMapping("/sync")
    public String sync(@AuthenticationPrincipal CustomUserDetails user, @RequestParam Long hotel,
                       @RequestParam(required = false) String back, RedirectAttributes ra) {
        requireManager(user);
        Hotel h = hotelService.getAccessible(user, hotel);
        if (!faktura.enabled(h.getId())) {
            ra.addFlashAttribute("error", "ЭСФ оператори уланмаган — Бошқарув → Меҳмонхоналар саҳифасида уланади");
        } else if (faktura.startAsync(h.getId())) {
            ra.addFlashAttribute("success", "Ҳужжатлар олинмоқда — бир оздан сўнг саҳифани янгиланг");
        } else {
            ra.addFlashAttribute("error", "Синхронлаш аллақачон ишлаяпти");
        }
        return "redirect:" + TaskController.safeBack(back);
    }

    private static void requireManager(CustomUserDetails user) {
        if (!TaskService.canAssign(user)) {
            throw new AccessDeniedException("Электрон ҳисоб-фактуралар эгаси ёки бошқарув компанияси учун");
        }
    }
}
