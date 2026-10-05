package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.security.PrincipalRefresher;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private static final List<Role> ASSIGNABLE_ROLES = List.of(Role.HOTEL_OWNER, Role.MANAGEMENT_COMPANY, Role.HOTEL_STAFF);

    private final UserService userService;
    private final HotelService hotelService;
    private final PrincipalRefresher principalRefresher;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", userService.findAll());
        return "admin/users";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        fillFormModel(model);
        model.addAttribute("user", null);
        model.addAttribute("selectedHotelIds", Set.of());
        return "admin/user-form";
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        fillFormModel(model);
        User user = userService.getById(id);
        model.addAttribute("user", user);
        model.addAttribute("selectedHotelIds",
                user.getHotels().stream().map(Hotel::getId).collect(Collectors.toSet()));
        return "admin/user-form";
    }

    @PostMapping
    public String create(@RequestParam String username,
                         @RequestParam String password,
                         @RequestParam(required = false) String fullName,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) Role role,
                         @RequestParam(required = false) List<Long> hotelIds,
                         RedirectAttributes ra) {
        try {
            userService.create(username, password, fullName, phone, role, hotelIds);
            ra.addFlashAttribute("success", "Foydalanuvchi qo'shildi");
            return "redirect:/admin/users";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users/new";
        }
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         HttpServletRequest request, HttpServletResponse response,
                         @RequestParam(required = false) String username,
                         @RequestParam(required = false) String fullName,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) Role role,
                         @RequestParam(defaultValue = "false") boolean enabled,
                         @RequestParam(required = false) List<Long> hotelIds,
                         @RequestParam(required = false) String newPassword,
                         RedirectAttributes ra) {
        try {
            User saved = userService.update(id, username, fullName, phone, role, enabled, hotelIds, newPassword);
            if (principal != null && principal.getId().equals(id)) {
                // O'z loginini o'zgartirgan bo'lsa — sessiya yangi ma'lumot bilan davom etadi.
                principalRefresher.refresh(saved, request, response);
            }
            ra.addFlashAttribute("success", "Saqlandi");
            return "redirect:/admin/users";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users/" + id;
        }
    }

    private void fillFormModel(Model model) {
        model.addAttribute("roles", ASSIGNABLE_ROLES);
        model.addAttribute("allHotels", hotelService.findAll());
    }
}
