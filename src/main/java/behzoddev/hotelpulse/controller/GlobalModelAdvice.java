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
