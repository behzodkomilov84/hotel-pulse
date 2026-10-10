package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.security.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Har bir sahifa sarlavhasida (header) joriy foydalanuvchi ko'rinishi uchun. */
@ControllerAdvice
public class GlobalModelAdvice {

    @ModelAttribute("currentUser")
    public CustomUserDetails currentUser(@AuthenticationPrincipal CustomUserDetails user) {
        return user;
    }

    /** Sessiyada oxirgi ochilgan mehmonxona (menyu havolalari shu mehmonxonaga olib boradi). */
    public static final String LAST_HOTEL = "lastHotelId";

    private static final java.util.regex.Pattern HOTEL_PATH = java.util.regex.Pattern.compile("^/hotels/(\\d+)(/.*)?$");

    /**
     * Joriy mehmonxona: /hotels/{id}... sahifasi yoki ?hotel= parametri; bo'lmasa — sessiyadagi oxirgisi.
     * "Hisobotlar" menyusidagi havolalar shu mehmonxona bilan ochiladi (boshqa mehmonxonaga o'tib ketmasin).
     */
    @ModelAttribute("currentHotelId")
    public Long currentHotelId(jakarta.servlet.http.HttpServletRequest request) {
        Long id = null;
        java.util.regex.Matcher m = HOTEL_PATH.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (m.matches()) {
            id = Long.valueOf(m.group(1));
        } else if (request.getParameter("hotel") != null && request.getParameter("hotel").matches("\\d{1,18}")) {
            id = Long.valueOf(request.getParameter("hotel"));
        }
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (id != null) {
            if (session != null) {
                session.setAttribute(LAST_HOTEL, id);
            }
            return id;
        }
        return session == null ? null : (Long) session.getAttribute(LAST_HOTEL);
    }

    /** "Hisobotlar" menyusi: guruh → mavjud hisobotlar (katalog o'zgarmas — har safar bir xil). */
    @ModelAttribute("reportMenu")
    public java.util.Map<String, java.util.List<behzoddev.hotelpulse.report.ReportCatalog.ReportDef>> reportMenu() {
        return MENU;
    }

    private static final java.util.Map<String, java.util.List<behzoddev.hotelpulse.report.ReportCatalog.ReportDef>> MENU;

    static {
        java.util.Map<String, java.util.List<behzoddev.hotelpulse.report.ReportCatalog.ReportDef>> m = new java.util.LinkedHashMap<>();
        behzoddev.hotelpulse.report.ReportCatalog.GROUPS.forEach(g -> m.put(g, behzoddev.hotelpulse.report.ReportCatalog.group(g)
                .stream().filter(behzoddev.hotelpulse.report.ReportCatalog.ReportDef::isAvailable).toList()));
        MENU = java.util.Collections.unmodifiableMap(m);
    }
}
