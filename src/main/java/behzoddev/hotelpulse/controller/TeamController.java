package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Department;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** "Boshqaruv → Bo'limlar / Xodimlar": mehmonxona egasi va boshqaruv kompaniyasi o'z jamoasini boshqaradi. */
@Controller
@RequestMapping("/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    // ---------------------------------------------------------------- Bo'limlar

    @GetMapping("/departments")
    public String departments(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        model.addAttribute("groups", teamService.departments(user));
        return "team/departments";
    }

    @PostMapping("/departments")
    public String createDepartment(@AuthenticationPrincipal CustomUserDetails user, @RequestParam Long hotelId,
                                   @RequestParam String name, RedirectAttributes ra) {
        try {
            Department d = teamService.createDepartment(user, hotelId, name);
            ra.addFlashAttribute("success", "\"" + d.getName() + "\" bo'limi qo'shildi");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/team/departments";
    }

    @PostMapping("/departments/{id}/rename")
    public String renameDepartment(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id,
                                   @RequestParam String name, RedirectAttributes ra) {
        try {
            teamService.renameDepartment(user, id, name);
            ra.addFlashAttribute("success", "Bo'lim nomi o'zgartirildi");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/team/departments";
    }

    @PostMapping("/departments/{id}/delete")
    public String deleteDepartment(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id,
                                   RedirectAttributes ra) {
        String name = teamService.deleteDepartment(user, id);
        ra.addFlashAttribute("success", "\"" + name + "\" bo'limi o'chirildi");
        return "redirect:/team/departments";
    }

    // ---------------------------------------------------------------- Xodimlar

    @GetMapping("/staff")
    public String staff(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        model.addAttribute("staff", teamService.staff(user));
        model.addAttribute("hasDepartments", teamService.departments(user).stream().anyMatch(g -> !g.departments().isEmpty()));
        return "team/staff";
    }

    @GetMapping("/staff/new")
    public String newStaff(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        fillForm(user, model, null, Set.of());
        return "team/staff-form";
    }

    @GetMapping("/staff/{id}")
    public String editStaff(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, Model model) {
        User member = teamService.staffMember(user, id);
        fillForm(user, model, member, member.getDepartments().stream().map(Department::getId).collect(Collectors.toSet()));
        return "team/staff-form";
    }

    @PostMapping("/staff")
    public String createStaff(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam String username, @RequestParam String password,
                              @RequestParam(required = false) String fullName, @RequestParam(required = false) String phone,
                              @RequestParam(required = false) List<Long> departmentIds, RedirectAttributes ra) {
        try {
            User u = teamService.createStaff(user, username, password, fullName, phone, departmentIds);
            ra.addFlashAttribute("success", "Xodim qo'shildi: " + u.getUsername()
                    + ". Endi u saytga kirib, Profil → Telegram'ni ulashi mumkin — topshiriqlar botda ham keladi.");
            return "redirect:/team/staff";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            ra.addFlashAttribute("form", new StaffForm(username, fullName, phone, departmentIds));
            return "redirect:/team/staff/new";
        }
    }

    @PostMapping("/staff/{id}")
    public String updateStaff(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id,
                              @RequestParam(required = false) String username,
                              @RequestParam(required = false) String fullName, @RequestParam(required = false) String phone,
                              @RequestParam(defaultValue = "false") boolean enabled,
                              @RequestParam(required = false) List<Long> departmentIds,
                              @RequestParam(required = false) String newPassword, RedirectAttributes ra) {
        try {
            teamService.updateStaff(user, id, username, fullName, phone, enabled, departmentIds, newPassword);
            ra.addFlashAttribute("success", "Saqlandi");
            return "redirect:/team/staff";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/team/staff/" + id;
        }
    }

    /** Xato bo'lsa — kiritilgan ma'lumot formada qoladi (paroldan tashqari). */
    public record StaffForm(String username, String fullName, String phone, List<Long> departmentIds) {
    }

    private void fillForm(CustomUserDetails user, Model model, User member, Set<Long> selected) {
        model.addAttribute("groups", teamService.departments(user));
        model.addAttribute("member", member);
        Object form = model.getAttribute("form");
        if (form instanceof StaffForm f && f.departmentIds() != null) {
            model.addAttribute("selected", Set.copyOf(f.departmentIds()));
        } else {
            model.addAttribute("selected", selected);
        }
    }
}
