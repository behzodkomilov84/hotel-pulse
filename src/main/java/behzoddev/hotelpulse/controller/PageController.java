package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final HotelService hotelService;

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** Bosh sahifa — foydalanuvchi ko'ra oladigan mehmonxonalar ro'yxati. */
    @GetMapping("/")
    public String dashboard(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        model.addAttribute("hotels", hotelService.accessibleHotels(user));
        return "dashboard";
    }

    /** Bitta mehmonxona sahifasi — KPI'lar 3-bosqichda qo'shiladi. */
    @GetMapping("/hotels/{id}")
    public String hotel(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, Model model) {
        model.addAttribute("hotel", hotelService.getAccessible(user, id));
        return "hotel";
    }
}
