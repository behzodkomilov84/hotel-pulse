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
}
