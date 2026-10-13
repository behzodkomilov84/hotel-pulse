package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelyException;
import behzoddev.hotelpulse.exely.ExelyRawStore;
import behzoddev.hotelpulse.exely.ExelySyncService;
import behzoddev.hotelpulse.exely.ExelyVerifyService;
import behzoddev.hotelpulse.faktura.FakturaException;
import behzoddev.hotelpulse.faktura.FakturaService;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;
import java.util.List;

@Controller
@RequestMapping("/admin/hotels")
@RequiredArgsConstructor
public class HotelAdminController {

    private final HotelService hotelService;
    private final ExelySyncService exelySyncService;
    private final ExelyRawStore exelyRawStore;
    private final FakturaService fakturaService;
    private final behzoddev.hotelpulse.agent.AgentService agentService;
    private final behzoddev.hotelpulse.agent.ShelterImportService shelterImport;
    private final behzoddev.hotelpulse.faktura.DidoxClient didoxClient;

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
        model.addAttribute("faktura", fakturaService.account(id).orElse(null));
        model.addAttribute("shelter", shelterArchive(id));
        model.addAttribute("shelterStatus", shelterImport.status(id));
        model.addAttribute("fakturaRunning", fakturaService.isRunning(id));
        model.addAttribute("didoxConfigured", didoxClient.configured());
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
            ra.addFlashAttribute("success", "Меҳмонхона сақланди");
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
            ra.addFlashAttribute("error", "Аввал Exely PMS калитини (ёки Connect маълумотларини) киритинг ва сақланг");
            return "redirect:/admin/hotels/" + id;
        }
        try {
            if (hotel.hasPmsKey()) {
                exelySyncService.testPmsKey(hotel.getExelyPmsKey());
                ra.addFlashAttribute("success", "Exely PMS билан уланиш муваффақиятли ✓");
            } else {
                exelySyncService.testConnection(hotel.getExelyPropertyId(), hotel.getExelyClientId(), hotel.getExelyClientSecret());
                ra.addFlashAttribute("success", "Exely билан уланиш муваффақиятли ✓");
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
            ra.addFlashAttribute("error", "Exely уланмаган");
        } else if (exelySyncService.startAsync(id)) {
            ra.addFlashAttribute("success", "Синхронлаш фонда бошланди — натижа шу саҳифада кўринади");
        } else {
            ra.addFlashAttribute("error", "Синхронлаш аллақачон ишлаяпти");
        }
        return "redirect:/admin/hotels/" + id;
    }

    @PostMapping("/{id}/exely/verify")
    public String verifyExely(@PathVariable Long id, RedirectAttributes ra) {
        Hotel hotel = hotelService.getById(id);
        if (!hotel.hasPmsKey()) {
            ra.addFlashAttribute("error", "Солиштириш учун Exely PMS калити керак");
        } else if (exelySyncService.startVerifyAsync(id)) {
            ra.addFlashAttribute("success", "Синхронлаш ва Exely билан солиштириш фонда бошланди — натижа шу саҳифада кўринади");
        } else {
            ra.addFlashAttribute("error", "Синхронлаш ишлаяпти — тугагач қайта уриниб кўринг");
        }
        return "redirect:/admin/hotels/" + id;
    }

    @PostMapping("/{id}/exely/reset")
    public String resetExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.resetExelySync(id);
        if (exelySyncService.startAsync(id)) {
            ra.addFlashAttribute("success", "Exely'даги барча маълумотлар бошидан юкланмоқда — бир неча дақиқа давом этади");
        } else {
            ra.addFlashAttribute("success", "Жорий синхронлаш тугагач, кейингиси ҳаммасини бошидан юклайди");
        }
        return "redirect:/admin/hotels/" + id;
    }

    /** Shelter agenti yuborgan asosiy jadvallar: nom → qatorlar soni (bo'lmasa — null). */
    public record ShelterArchive(java.util.Map<String, Long> tiles, int tables, long rows, java.time.LocalDateTime syncedAt) {
    }

    private static final java.util.Map<String, String> SHELTER_LABELS = new java.util.LinkedHashMap<>();

    static {
        SHELTER_LABELS.put("ROOM_SCHEDULE", "Бронлар ва яшашлар");
        SHELTER_LABELS.put("GROUP_SCHEDULE", "Гуруҳ бронлари");
        SHELTER_LABELS.put("GUESTS", "Меҳмонлар ва тўловчилар");
        SHELTER_LABELS.put("ENTERPRISEDEBTOR", "Корхоналар (агентлар)");
        SHELTER_LABELS.put("COMPANY_CONTRACTS", "Шартномалар");
        SHELTER_LABELS.put("FOLIO", "Фолиолар (ҳисоблар)");
        SHELTER_LABELS.put("FOLIO_CONTENTS", "Хизматлар ва тўловлар");
        SHELTER_LABELS.put("INVOICES", "Касса чеклари");
        SHELTER_LABELS.put("CASH_DOCUMENTS", "Касса ҳужжатлари");
        SHELTER_LABELS.put("ROOMS", "Хоналар");
        SHELTER_LABELS.put("ROOM_KINDS", "Хона турлари");
        SHELTER_LABELS.put("OPERATIONS", "Хизмат турлари");
    }

    private ShelterArchive shelterArchive(Long hotelId) {
        List<behzoddev.hotelpulse.agent.AgentService.TableInfo> tables = agentService.tables(hotelId);
        if (tables.isEmpty()) {
            return null;
        }
        java.util.Map<String, Long> byName = new java.util.HashMap<>();
        long rows = 0;
        java.time.LocalDateTime synced = null;
        for (var t : tables) {
            byName.put(t.name(), t.rows());
            rows += t.rows();
            synced = synced == null || (t.uploadedAt() != null && t.uploadedAt().isAfter(synced)) ? t.uploadedAt() : synced;
        }
        java.util.Map<String, Long> tiles = new java.util.LinkedHashMap<>();
        SHELTER_LABELS.forEach((table, label) -> {
            if (byName.containsKey(table)) {
                tiles.put(label, byName.get(table));
            }
        });
        return new ShelterArchive(tiles, tables.size(), rows, synced);
    }

    /** Xom arxiv turlarining nomlari (admin sahifasi). */
    private static final java.util.Map<String, String> ARCHIVE_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry(ExelyRawStore.BOOKING, "Бронлар"),
            java.util.Map.entry(ExelyRawStore.RESERVATION, "Яшашлар"),
            java.util.Map.entry(ExelyRawStore.SERVICE, "Хизматлар (кунлик)"),
            java.util.Map.entry(ExelyRawStore.SERVICE_CANCELLED, "Бекор қилинган хизматлар"),
            java.util.Map.entry(ExelyRawStore.PAYMENT, "Тўловлар"),
            java.util.Map.entry(ExelyRawStore.INVOICES, "Exely ҳисоблари (бронлар бўйича)"),
            java.util.Map.entry(ExelyRawStore.GUEST, "Меҳмонлар"),
            java.util.Map.entry(ExelyRawStore.CUSTOMER, "Тўловчилар"),
            java.util.Map.entry(ExelyRawStore.AGENT, "Агентлар"),
            java.util.Map.entry(ExelyRawStore.COMPANY, "Компаниялар"),
            java.util.Map.entry(ExelyRawStore.ROOM_TYPE, "Хона турлари"),
            java.util.Map.entry(ExelyRawStore.ROOM, "Хоналар"));

    // ---------------------------------------------------------------- ЭСФ оператори (Didox / Faktura.uz)

    @PostMapping("/{id}/faktura")
    public String saveFaktura(@PathVariable Long id,
                              @RequestParam(defaultValue = "DIDOX") String fakturaProvider,
                              @RequestParam(required = false) String fakturaInn,
                              @RequestParam(required = false) String fakturaUsername,
                              @RequestParam(required = false) String fakturaPassword,
                              @RequestParam(required = false) String fakturaClientId,
                              @RequestParam(required = false) String fakturaClientSecret,
                              RedirectAttributes ra) {
        hotelService.getById(id);
        try {
            fakturaService.saveAccount(id, fakturaProvider, fakturaInn, fakturaUsername, fakturaPassword, fakturaClientId, fakturaClientSecret);
            ra.addFlashAttribute("success", "ЭСФ оператори маълумотлари сақланди — «Уланишни текшириш»ни босинг");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/hotels/" + id + "#faktura";
    }

    @PostMapping("/{id}/faktura/test")
    public String testFaktura(@PathVariable Long id, RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("success", fakturaService.test(id));
        } catch (FakturaException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/hotels/" + id + "#faktura";
    }

    @PostMapping("/{id}/faktura/sync")
    public String syncFaktura(@PathVariable Long id, RedirectAttributes ra) {
        if (!fakturaService.enabled(id)) {
            ra.addFlashAttribute("error", "ЭСФ оператори уланмаган");
        } else if (fakturaService.startAsync(id)) {
            ra.addFlashAttribute("success", "Ҳужжатлар олинмоқда — бир оздан сўнг саҳифани янгиланг");
        } else {
            ra.addFlashAttribute("error", "Синхронлаш аллақачон ишлаяпти");
        }
        return "redirect:/admin/hotels/" + id + "#faktura";
    }

    @PostMapping("/{id}/faktura/disconnect")
    public String disconnectFaktura(@PathVariable Long id, RedirectAttributes ra) {
        fakturaService.disconnect(id);
        ra.addFlashAttribute("success", "ЭСФ оператори уланиши ўчирилди (олинган ҳужжатлар сақланиб қолди)");
        return "redirect:/admin/hotels/" + id + "#faktura";
    }

    @PostMapping("/{id}/exely/disconnect")
    public String disconnectExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.disconnectExely(id);
        ra.addFlashAttribute("success", "Exely уланиши ўчирилди (олинган бронлар сақланиб қолди)");
        return "redirect:/admin/hotels/" + id;
    }
}
