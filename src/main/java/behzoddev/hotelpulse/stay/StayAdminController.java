package behzoddev.hotelpulse.stay;

import behzoddev.hotelpulse.security.CustomUserDetails;
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

/** Бошқарув → Chorvoq Stay: chorvoqstay.uz saytidan kelgan so'rovlar (bron, rezident, qo'ng'iroq). */
@Controller
@RequestMapping("/admin/stay")
@RequiredArgsConstructor
public class StayAdminController {

    private static final int LIMIT = 300;

    private final StayRequestService requests;

    @GetMapping
    public String list(@RequestParam(defaultValue = "new") String show, Model model) {
        boolean all = "all".equals(show);
        model.addAttribute("requests", requests.list(all ? null : "NEW", LIMIT));
        model.addAttribute("countNew", requests.countNew());
        model.addAttribute("show", all ? "all" : "new");
        return "admin/stay-requests";
    }

    @PostMapping("/requests/{id}/done")
    public String done(@PathVariable long id, @RequestParam(defaultValue = "new") String show,
                       @AuthenticationPrincipal CustomUserDetails user, RedirectAttributes ra) {
        requests.markDone(id, user == null ? null : user.getUsername());
        ra.addFlashAttribute("success", "So'rov №" + id + " кўриб чиқилди деб белгиланди");
        return "redirect:/admin/stay?show=" + show;
    }
}
