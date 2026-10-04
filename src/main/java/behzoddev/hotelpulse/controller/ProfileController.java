package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.UserService;
import behzoddev.hotelpulse.telegram.TelegramGateway;
import behzoddev.hotelpulse.telegram.TelegramLinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Foydalanuvchi profili: ism/telefon, parol, Telegram'ni ulash/uzish, kunlik hisobot. */
@Controller
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;
    private final TelegramLinkService linkService;
    private final TelegramGateway telegram;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    @GetMapping
    public String profile(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        // getById mehmonxonalar ro'yxatini ham yuklaydi (open-in-view o'chiq).
        User user = userService.getById(principal.getId());
        model.addAttribute("user", user);
        model.addAttribute("botUsername", telegram.botUsername());
        return "profile";
    }

    @PostMapping
    public String update(@AuthenticationPrincipal CustomUserDetails principal,
                         @RequestParam(required = false) String fullName,
                         @RequestParam(required = false) String phone,
                         HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes ra) {
        try {
            User user = userService.updateOwnProfile(principal.getId(), fullName, phone);
            refreshPrincipal(user, request, response);
            ra.addFlashAttribute("success", "Profil saqlandi");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            ra.addFlashAttribute("editOpen", true);
        }
        return "redirect:/profile";
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal CustomUserDetails principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 RedirectAttributes ra) {
        try {
            userService.changeOwnPassword(principal.getId(), currentPassword, newPassword, confirmPassword);
            ra.addFlashAttribute("success", "Parol o'zgartirildi");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            ra.addFlashAttribute("passwordOpen", true);
        }
        return "redirect:/profile";
    }

    /**
     * Sarlavhadagi ism sessiyadagi principal'dan olinadi — saqlangach yangilanmasa,
     * qayta kirguncha eski ism ko'rinib turardi.
     */
    private void refreshPrincipal(User user, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        CustomUserDetails fresh = new CustomUserDetails(user);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                fresh, current == null ? null : current.getCredentials(), fresh.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    /** Bir martalik havola yaratib, Telegram'ni ochadi (t.me/<bot>?start=<token>). */
    @PostMapping("/telegram/link")
    public String link(@AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes ra) {
        String bot = telegram.botUsername();
        if (bot == null) {
            ra.addFlashAttribute("error", "Telegram bot hali sozlanmagan");
            return "redirect:/profile";
        }
        String token = linkService.createToken(principal.getId());
        return "redirect:https://t.me/" + bot + "?start=" + token;
    }

    @PostMapping("/telegram/unlink")
    public String unlink(@AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes ra) {
        linkService.unlinkUser(principal.getId());
        ra.addFlashAttribute("success", "Telegram hisobingizdan uzildi");
        return "redirect:/profile";
    }

    @PostMapping("/telegram/daily-report")
    public String dailyReport(@AuthenticationPrincipal CustomUserDetails principal,
                              @RequestParam(defaultValue = "false") boolean enabled,
                              RedirectAttributes ra) {
        linkService.setDailyReport(principal.getId(), enabled);
        ra.addFlashAttribute("success", enabled ? "Kunlik hisobot yoqildi" : "Kunlik hisobot o'chirildi");
        return "redirect:/profile";
    }
}
