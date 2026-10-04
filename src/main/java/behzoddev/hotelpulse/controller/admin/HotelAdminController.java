package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/hotels")
@RequiredArgsConstructor
public class HotelAdminController {

    private final HotelService hotelService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("hotels", hotelService.findAll());
        return "admin/hotels";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("hotel", null);
        return "admin/hotel-form";
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("hotel", hotelService.getById(id));
        return "admin/hotel-form";
    }

    @PostMapping({"", "/{id}"})
    public String save(@PathVariable(required = false) Long id,
                       @RequestParam String name,
                       @RequestParam(required = false) String city,
                       @RequestParam(defaultValue = "0") int roomsCount,
                       @RequestParam(required = false) String exelyPropertyId,
                       @RequestParam(required = false) String exelyApiKey,
                       @RequestParam(defaultValue = "false") boolean active,
                       RedirectAttributes ra) {
        try {
            hotelService.save(id, name, city, roomsCount, exelyPropertyId, exelyApiKey, active);
            ra.addFlashAttribute("success", "Mehmonxona saqlandi");
            return "redirect:/admin/hotels";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return id == null ? "redirect:/admin/hotels/new" : "redirect:/admin/hotels/" + id;
        }
    }

    @PostMapping("/{id}/remove-key")
    public String removeKey(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.removeExelyKey(id);
        ra.addFlashAttribute("success", "Exely kaliti o'chirildi");
        return "redirect:/admin/hotels/" + id;
    }
}
