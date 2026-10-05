package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelyException;
import behzoddev.hotelpulse.exely.ExelyRawStore;
import behzoddev.hotelpulse.exely.ExelySyncService;
import behzoddev.hotelpulse.exely.ExelyVerifyService;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;

@Controller
@RequestMapping("/admin/hotels")
@RequiredArgsConstructor
public class HotelAdminController {

    private final HotelService hotelService;
    private final ExelySyncService exelySyncService;
    private final ExelyRawStore exelyRawStore;

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
        model.addAttribute("archive", exelyRawStore.counts(id));
        model.addAttribute("verify", ExelyVerifyService.read(hotelService.getById(id)));
        model.addAttribute("archiveLabels", ARCHIVE_LABELS);
        return "admin/hotel-form";
    }

    @PostMapping({"", "/{id}"})
    public String save(@PathVariable(required = false) Long id,
                       @RequestParam String name,
                       @RequestParam(required = false) String city,
                       @RequestParam(defaultValue = "0") int roomsCount,
                       @RequestParam(defaultValue = "false") boolean roomsCountManual,
                       @RequestParam(defaultValue = "UZS") String currency,
                       @RequestParam(required = false) String exelyPropertyId,
                       @RequestParam(required = false) String exelyClientId,
                       @RequestParam(required = false) String exelyClientSecret,
                       @RequestParam(required = false) String exelyPmsKey,
                       @RequestParam(defaultValue = "false") boolean active,
                       @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime dailyReportTime,
                       RedirectAttributes ra) {
        try {
            Hotel saved = hotelService.save(id, name, city, roomsCount, roomsCountManual, currency,
                    exelyPropertyId, exelyClientId, exelyClientSecret, exelyPmsKey, active, dailyReportTime);
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

    @PostMapping("/{id}/exely/verify")
    public String verifyExely(@PathVariable Long id, RedirectAttributes ra) {
        Hotel hotel = hotelService.getById(id);
        if (!hotel.hasPmsKey()) {
            ra.addFlashAttribute("error", "Solishtirish uchun Exely PMS kaliti kerak");
        } else if (exelySyncService.startVerifyAsync(id)) {
            ra.addFlashAttribute("success", "Sinxronlash va Exely bilan solishtirish fonda boshlandi — natija shu sahifada ko'rinadi");
        } else {
            ra.addFlashAttribute("error", "Sinxronlash ishlayapti — tugagach qayta urinib ko'ring");
        }
        return "redirect:/admin/hotels/" + id;
    }

    @PostMapping("/{id}/exely/reset")
    public String resetExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.resetExelySync(id);
        if (exelySyncService.startAsync(id)) {
            ra.addFlashAttribute("success", "Exely'dagi barcha ma'lumotlar boshidan yuklanmoqda — bir necha daqiqa davom etadi");
        } else {
            ra.addFlashAttribute("success", "Joriy sinxronlash tugagach, keyingisi hammasini boshidan yuklaydi");
        }
        return "redirect:/admin/hotels/" + id;
    }

    /** Xom arxiv turlarining nomlari (admin sahifasi). */
    private static final java.util.Map<String, String> ARCHIVE_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry(ExelyRawStore.BOOKING, "Bronlar"),
            java.util.Map.entry(ExelyRawStore.RESERVATION, "Yashashlar"),
            java.util.Map.entry(ExelyRawStore.SERVICE, "Xizmatlar (kunlik)"),
            java.util.Map.entry(ExelyRawStore.SERVICE_CANCELLED, "Bekor qilingan xizmatlar"),
            java.util.Map.entry(ExelyRawStore.PAYMENT, "To'lovlar"),
            java.util.Map.entry(ExelyRawStore.INVOICES, "Hisob-fakturalar"),
            java.util.Map.entry(ExelyRawStore.GUEST, "Mehmonlar"),
            java.util.Map.entry(ExelyRawStore.CUSTOMER, "To'lovchilar"),
            java.util.Map.entry(ExelyRawStore.AGENT, "Agentlar"),
            java.util.Map.entry(ExelyRawStore.COMPANY, "Kompaniyalar"),
            java.util.Map.entry(ExelyRawStore.ROOM_TYPE, "Xona turlari"),
            java.util.Map.entry(ExelyRawStore.ROOM, "Xonalar"));

    @PostMapping("/{id}/exely/disconnect")
    public String disconnectExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.disconnectExely(id);
        ra.addFlashAttribute("success", "Exely ulanishi o'chirildi (olingan bronlar saqlanib qoldi)");
        return "redirect:/admin/hotels/" + id;
    }
}
