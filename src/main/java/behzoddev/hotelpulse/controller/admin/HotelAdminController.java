package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelyException;
import behzoddev.hotelpulse.exely.ExelySyncService;
import behzoddev.hotelpulse.service.DemoDataService;
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
    private final DemoDataService demoDataService;
    private final ExelySyncService exelySyncService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("hotels", hotelService.findAll());
        return "admin/hotels";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("hotel", null);
        model.addAttribute("currencies", HotelService.SUPPORTED_CURRENCIES);
        model.addAttribute("syncRunning", false);
        return "admin/hotel-form";
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("hotel", hotelService.getById(id));
        model.addAttribute("currencies", HotelService.SUPPORTED_CURRENCIES);
        model.addAttribute("syncRunning", exelySyncService.isRunning(id));
        return "admin/hotel-form";
    }

    @PostMapping({"", "/{id}"})
    public String save(@PathVariable(required = false) Long id,
                       @RequestParam String name,
                       @RequestParam(required = false) String city,
                       @RequestParam(defaultValue = "0") int roomsCount,
                       @RequestParam(defaultValue = "UZS") String currency,
                       @RequestParam(required = false) String exelyPropertyId,
                       @RequestParam(required = false) String exelyClientId,
                       @RequestParam(required = false) String exelyClientSecret,
                       @RequestParam(required = false) String exelyPmsKey,
                       @RequestParam(defaultValue = "false") boolean active,
                       RedirectAttributes ra) {
        try {
            Hotel saved = hotelService.save(id, name, city, roomsCount, currency,
                    exelyPropertyId, exelyClientId, exelyClientSecret, exelyPmsKey, active);
            ra.addFlashAttribute("success", "Mehmonxona saqlandi");
            return "redirect:/admin/hotels/" + saved.getId();
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return id == null ? "redirect:/admin/hotels/new" : "redirect:/admin/hotels/" + id;
        }
    }

    /** Saqlangan kirish ma'lumotlari bilan Exely'ga ulanib ko'radi. */
    @PostMapping("/{id}/exely/test")
    public String testExely(@PathVariable Long id, RedirectAttributes ra) {
        Hotel hotel = hotelService.getById(id);
        if (!hotel.isExelyConnected()) {
            ra.addFlashAttribute("error", "Avval Exely PMS kalitini (yoki Connect ma'lumotlarini) kiriting va saqlang");
            return "redirect:/admin/hotels/" + id;
        }
        try {
            if (hotel.hasPmsKey()) {
                exelySyncService.testPmsKey(hotel.getExelyPmsKey());
                ra.addFlashAttribute("success", "Exely PMS bilan ulanish muvaffaqiyatli ✓");
            } else {
                exelySyncService.testConnection(hotel.getExelyPropertyId(), hotel.getExelyClientId(), hotel.getExelyClientSecret());
                ra.addFlashAttribute("success", "Exely bilan ulanish muvaffaqiyatli ✓");
            }
        } catch (ExelyException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/hotels/" + id;
    }

    @PostMapping("/{id}/exely/sync")
    public String syncExely(@PathVariable Long id, RedirectAttributes ra) {
        Hotel hotel = hotelService.getById(id);
        if (!hotel.isExelyConnected()) {
            ra.addFlashAttribute("error", "Exely ulanmagan");
        } else if (exelySyncService.startAsync(id)) {
            ra.addFlashAttribute("success", "Sinxronlash fonda boshlandi — natija shu sahifada ko'rinadi");
        } else {
            ra.addFlashAttribute("error", "Sinxronlash allaqachon ishlayapti");
        }
        return "redirect:/admin/hotels/" + id;
    }

    @PostMapping("/{id}/exely/reset")
    public String resetExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.resetExelySync(id);
        ra.addFlashAttribute("success", "Keyingi sinxronlash barcha bronlarni boshidan yuklaydi");
        return "redirect:/admin/hotels/" + id;
    }

    @PostMapping("/{id}/exely/disconnect")
    public String disconnectExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.disconnectExely(id);
        ra.addFlashAttribute("success", "Exely ulanishi o'chirildi (olingan bronlar saqlanib qoldi)");
        return "redirect:/admin/hotels/" + id;
    }

    /** Exely ulanmaguncha panelni sinab ko'rish uchun sinov ma'lumotlari. */
    @PostMapping("/{id}/demo-data")
    public String generateDemo(@PathVariable Long id, RedirectAttributes ra) {
        Hotel hotel = hotelService.getById(id);
        try {
            int count = demoDataService.generate(hotel);
            ra.addFlashAttribute("success", count + " ta sinov broni yaratildi");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/hotels/" + id;
    }

    @PostMapping("/{id}/demo-data/delete")
    public String deleteDemo(@PathVariable Long id, RedirectAttributes ra) {
        demoDataService.delete(hotelService.getById(id));
        ra.addFlashAttribute("success", "Sinov ma'lumotlari o'chirildi");
        return "redirect:/hotels/" + id;
    }
}
