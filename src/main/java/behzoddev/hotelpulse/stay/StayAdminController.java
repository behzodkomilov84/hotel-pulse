package behzoddev.hotelpulse.stay;

import behzoddev.hotelpulse.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Бошқарув → Chorvoq Stay: saytdan kelgan so'rovlar, toifalar va narxlar, kvartiralar.
 */
@Controller
@RequestMapping("/admin/stay")
@RequiredArgsConstructor
public class StayAdminController {

    private static final int LIMIT = 300;

    private final StayRequestService requests;
    private final StayCatalogService catalog;
    private final StayOwnerService owners;

    // ---------------------------------------------------------------- So'rovlar

    @GetMapping
    public String list(@RequestParam(defaultValue = "new") String show, Model model) {
        boolean all = "all".equals(show);
        model.addAttribute("requests", requests.list(all ? null : "NEW", LIMIT));
        model.addAttribute("countNew", requests.countNew());
        model.addAttribute("show", all ? "all" : "new");
        model.addAttribute("tab", "requests");
        return "admin/stay-requests";
    }

    @PostMapping("/requests/{id}/done")
    public String done(@PathVariable long id, @RequestParam(defaultValue = "new") String show,
                       @AuthenticationPrincipal CustomUserDetails user, RedirectAttributes ra) {
        requests.markDone(id, user == null ? null : user.getUsername());
        ra.addFlashAttribute("success", "So'rov №" + id + " кўриб чиқилди деб белгиланди");
        return "redirect:/admin/stay?show=" + show;
    }

    // ---------------------------------------------------------------- Toifalar va narxlar

    @GetMapping("/catalog")
    public String catalog(Model model) {
        model.addAttribute("categories", catalog.categories());
        model.addAttribute("periods", catalog.periods());
        model.addAttribute("hotel", catalog.hotel());
        model.addAttribute("countNew", requests.countNew());
        model.addAttribute("tab", "catalog");
        return "admin/stay-catalog";
    }

    @PostMapping("/categories/{id}")
    public String saveCategory(@PathVariable long id, @RequestParam String name,
                               @RequestParam(required = false) String areaText,
                               @RequestParam int capacity, @RequestParam(defaultValue = "0") int extraBeds,
                               @RequestParam BigDecimal basePrice, @RequestParam(required = false) BigDecimal minPrice,
                               @RequestParam(defaultValue = "false") boolean active, RedirectAttributes ra) {
        try {
            catalog.updateCategory(id, name, areaText, capacity, extraBeds, basePrice, minPrice, active);
            ra.addFlashAttribute("success", "«" + name.trim() + "» сақланди — сайтда дарҳол кўринади");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/stay/catalog";
    }

    @PostMapping("/periods")
    public String addPeriod(@RequestParam long categoryId,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam BigDecimal price, @RequestParam(required = false) String note,
                            RedirectAttributes ra) {
        try {
            catalog.addPeriod(categoryId, from, to, price, note);
            ra.addFlashAttribute("success", "Нарх даври қўшилди");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/stay/catalog#periods";
    }

    @PostMapping("/periods/{id}/delete")
    public String deletePeriod(@PathVariable long id, RedirectAttributes ra) {
        catalog.deletePeriod(id);
        ra.addFlashAttribute("success", "Нарх даври ўчирилди");
        return "redirect:/admin/stay/catalog#periods";
    }

    // ---------------------------------------------------------------- Kvartiralar

    @GetMapping("/units")
    public String units(@RequestParam(required = false) Long edit, Model model) {
        var units = catalog.units();
        model.addAttribute("units", units);
        model.addAttribute("categories", catalog.categories());
        model.addAttribute("edit", edit == null ? null : units.stream().filter(u -> u.id() == edit).findFirst().orElse(null));
        model.addAttribute("countNew", requests.countNew());
        model.addAttribute("tab", "units");
        return "admin/stay-units";
    }

    @PostMapping({"/units", "/units/{id}"})
    public String saveUnit(@PathVariable(required = false) Long id, @RequestParam long categoryId,
                           @RequestParam String building, @RequestParam String number,
                           @RequestParam(required = false) BigDecimal area, @RequestParam(required = false) Integer floor,
                           @RequestParam String status, @RequestParam(required = false) String ownerName,
                           @RequestParam(required = false) String ownerPhone, @RequestParam(required = false) String note,
                           RedirectAttributes ra) {
        try {
            catalog.saveUnit(id, categoryId, building, number, area, floor, status, ownerName, ownerPhone, note);
            ra.addFlashAttribute("success", building.trim() + "-уй, " + number.trim() + "-квартира сақланди");
            return "redirect:/admin/stay/units";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return id == null ? "redirect:/admin/stay/units" : "redirect:/admin/stay/units?edit=" + id;
        }
    }

    @PostMapping("/units/{id}/delete")
    public String deleteUnit(@PathVariable long id, RedirectAttributes ra) {
        catalog.deleteUnit(id);
        ra.addFlashAttribute("success", "Квартира ўчирилди");
        return "redirect:/admin/stay/units";
    }

    // ---------------------------------------------------------------- Mulkdorlar

    @GetMapping("/owners")
    public String owners(@RequestParam(required = false) String status, @RequestParam(required = false) Long edit, Model model) {
        String st = status == null || !StayOwnerService.STATUSES.containsKey(status) ? null : status;
        model.addAttribute("owners", owners.list(st));
        model.addAttribute("ownerCounts", owners.counts());
        model.addAttribute("statuses", StayOwnerService.STATUSES);
        model.addAttribute("status", st);
        model.addAttribute("edit", edit == null ? null : owners.get(edit));
        model.addAttribute("countNew", requests.countNew());
        model.addAttribute("tab", "owners");
        return "admin/stay-owners";
    }

    @PostMapping({"/owners", "/owners/{id}/edit"})
    public String saveOwner(@PathVariable(required = false) Long id, @RequestParam String name,
                            @RequestParam(required = false) String phone, @RequestParam(required = false) String building,
                            @RequestParam(required = false) String apartment, @RequestParam(required = false) BigDecimal area,
                            @RequestParam(required = false) String renovation, @RequestParam(required = false) String note,
                            @AuthenticationPrincipal CustomUserDetails user, RedirectAttributes ra) {
        String by = user == null ? null : user.getUsername();
        try {
            if (id == null) {
                long newId = owners.add(name, phone, building, apartment, area, renovation, note, by);
                ra.addFlashAttribute("success", "Мулкдор №" + newId + " қўшилди");
            } else {
                owners.edit(id, name, phone, building, apartment, area, renovation, note, by);
                ra.addFlashAttribute("success", "Мулкдор №" + id + " сақланди");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return id == null ? "redirect:/admin/stay/owners#owner-form" : "redirect:/admin/stay/owners?edit=" + id + "#owner-form";
        }
        return "redirect:/admin/stay/owners";
    }

    @PostMapping("/owners/{id}/status")
    public String ownerStatus(@PathVariable long id, @RequestParam String status, @RequestParam(required = false) String comment,
                              @RequestParam(required = false) String filter,
                              @AuthenticationPrincipal CustomUserDetails user, RedirectAttributes ra) {
        try {
            owners.update(id, status, comment, user == null ? null : user.getUsername());
            ra.addFlashAttribute("success", "Мулкдор №" + id + ": " + StayOwnerService.STATUSES.get(status));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/stay/owners" + (filter == null || filter.isBlank() ? "" : "?status=" + filter) + "#owner-" + id;
    }
}
