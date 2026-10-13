package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;

/**
 * Бошқарув → Меҳмонхоналар: asosiy ma'lumotlar va Telegram hisoboti.
 * Ulanishlar (Exely, UCS Shelter, 1C, ЭСФ) — Бошқарув → Интеграциялар ({@link IntegrationController}).
 */
@Controller
@RequestMapping("/admin/hotels")
@RequiredArgsConstructor
public class HotelAdminController {

    private final HotelService hotelService;
    private final IntegrationStatus integrationStatus;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("hotels", hotelService.findAll());
        return "admin/hotels";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("hotel", null);
        model.addAttribute("currencies", HotelService.SUPPORTED_CURRENCIES);
        return "admin/hotel-form";
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        Hotel hotel = hotelService.getById(id);
        model.addAttribute("hotel", hotel);
        model.addAttribute("currencies", HotelService.SUPPORTED_CURRENCIES);
        model.addAttribute("integrations", integrationStatus.row(hotel));
        return "admin/hotel-form";
    }

    @PostMapping({"", "/{id}"})
    public String save(@PathVariable(required = false) Long id,
                       @RequestParam String name,
                       @RequestParam(required = false) String city,
                       @RequestParam(defaultValue = "0") int roomsCount,
                       @RequestParam(defaultValue = "false") boolean roomsCountManual,
                       @RequestParam(defaultValue = "UZS") String currency,
                       @RequestParam(defaultValue = "false") boolean active,
                       @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime dailyReportTime,
                       RedirectAttributes ra) {
        try {
            Hotel saved = hotelService.saveBasic(id, name, city, roomsCount, roomsCountManual, currency, active, dailyReportTime);
            ra.addFlashAttribute("success", id == null
                    ? "Меҳмонхона қўшилди — энди уни «Интеграциялар»да улашингиз мумкин"
                    : "Меҳмонхона сақланди");
            return "redirect:/admin/hotels/" + saved.getId();
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return id == null ? "redirect:/admin/hotels/new" : "redirect:/admin/hotels/" + id;
        }
    }
}
