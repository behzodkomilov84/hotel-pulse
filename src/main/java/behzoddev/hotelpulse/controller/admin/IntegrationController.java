package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.agent.AgentService;
import behzoddev.hotelpulse.agent.ShelterImportService;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelyException;
import behzoddev.hotelpulse.exely.ExelyRawStore;
import behzoddev.hotelpulse.exely.ExelySyncService;
import behzoddev.hotelpulse.exely.ExelyVerifyService;
import behzoddev.hotelpulse.faktura.DidoxClient;
import behzoddev.hotelpulse.faktura.FakturaException;
import behzoddev.hotelpulse.faktura.FakturaService;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Бошқарув → Интеграциялар: har bir mehmonxona uchun barcha ulanish usullari bir joyda —
 * Exely PMS, Exely Connect, UCS Shelter agenti, 1C agenti, ЭСФ operatori (Didox / Faktura.uz).
 * Mehmonxona istaganini ulaydi; holati, olingan ma'lumotlar va solishtirish natijasi shu yerda.
 */
@Controller
@RequestMapping("/admin/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final HotelService hotelService;
    private final ExelySyncService exelySyncService;
    private final ExelyRawStore exelyRawStore;
    private final FakturaService fakturaService;
    private final AgentService agentService;
    private final ShelterImportService shelterImport;
    private final DidoxClient didoxClient;

    private final IntegrationStatus status;

    @GetMapping
    public String overview(Model model) {
        List<Hotel> hotels = hotelService.findAll();
        List<AgentService.Agent> agents = agentService.agents();
        model.addAttribute("hotels", hotels);
        model.addAttribute("rows", hotels.stream().map(h -> status.row(h, agents)).toList());
        model.addAttribute("hotel", null);
        return "admin/integrations";
    }

    @GetMapping("/{id}")
    public String hotel(@PathVariable Long id, Model model) {
        Hotel hotel = hotelService.getById(id);
        List<Hotel> hotels = hotelService.findAll();
        List<AgentService.Agent> agents = agentService.agents();
        model.addAttribute("hotels", hotels);
        model.addAttribute("hotel", hotel);
        model.addAttribute("row", status.row(hotel, agents));
        model.addAttribute("syncRunning", exelySyncService.isRunning(id));
        model.addAttribute("archive", exelyRawStore.counts(id));
        model.addAttribute("archiveLabels", ARCHIVE_LABELS);
        model.addAttribute("verify", ExelyVerifyService.read(hotel));
        model.addAttribute("faktura", fakturaService.account(id).orElse(null));
        model.addAttribute("fakturaRunning", fakturaService.isRunning(id));
        model.addAttribute("didoxConfigured", didoxClient.configured());
        model.addAttribute("shelter", shelterArchive(id));
        model.addAttribute("shelterStatus", shelterImport.status(id));
        model.addAttribute("shelterAgents", agents.stream().filter(a -> id.equals(a.hotelId()) && "SHELTER".equals(a.kind())).toList());
        model.addAttribute("onecAgents", agents.stream().filter(a -> id.equals(a.hotelId()) && "1C".equals(a.kind())).toList());
        return "admin/integrations";
    }

    // ---------------------------------------------------------------- Exely

    @PostMapping("/{id}/exely/pms")
    public String saveExelyPms(@PathVariable Long id, @RequestParam(required = false) String exelyPmsKey, RedirectAttributes ra) {
        try {
            hotelService.saveExelyPmsKey(id, exelyPmsKey);
            ra.addFlashAttribute("success", "Exely PMS калити сақланди — «Уланишни текшириш»ни босинг; синхронлаш 30 дақиқада бир ўзи бошланади");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back(id, "exely");
    }

    @PostMapping("/{id}/exely/connect")
    public String saveExelyConnect(@PathVariable Long id,
                                   @RequestParam(required = false) String exelyPropertyId,
                                   @RequestParam(required = false) String exelyClientId,
                                   @RequestParam(required = false) String exelyClientSecret,
                                   RedirectAttributes ra) {
        try {
            hotelService.saveExelyConnect(id, exelyPropertyId, exelyClientId, exelyClientSecret);
            ra.addFlashAttribute("success", "Exely Connect маълумотлари сақланди — «Уланишни текшириш»ни босинг");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back(id, "exely");
    }

    /** Saqlangan kirish ma'lumotlari bilan Exely'ga ulanib ko'radi. */
    @PostMapping("/{id}/exely/test")
    public String testExely(@PathVariable Long id, RedirectAttributes ra) {
        Hotel hotel = hotelService.getById(id);
        if (!hotel.isExelyConnected()) {
            ra.addFlashAttribute("error", "Аввал Exely PMS калитини (ёки Connect маълумотларини) киритинг ва сақланг");
            return back(id, "exely");
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
        return back(id, "exely");
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
        return back(id, "exely");
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
        return back(id, "exely");
    }

    @PostMapping("/{id}/exely/reset")
    public String resetExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.resetExelySync(id);
        if (exelySyncService.startAsync(id)) {
            ra.addFlashAttribute("success", "Exely'даги барча маълумотлар бошидан юкланмоқда — бир неча дақиқа давом этади");
        } else {
            ra.addFlashAttribute("success", "Жорий синхронлаш тугагач, кейингиси ҳаммасини бошидан юклайди");
        }
        return back(id, "exely");
    }

    @PostMapping("/{id}/exely/disconnect")
    public String disconnectExely(@PathVariable Long id, RedirectAttributes ra) {
        hotelService.disconnectExely(id);
        ra.addFlashAttribute("success", "Exely уланиши ўчирилди (олинган бронлар сақланиб қолди)");
        return back(id, "exely");
    }

    // ---------------------------------------------------------------- Agentlar (UCS Shelter, 1C)

    /** Mehmonxona kompyuteridagi agent ko'rsatgan 6 xonali kod — shu mehmonxonaga bog'lanadi. */
    @PostMapping("/{id}/agents/approve")
    public String approveAgent(@PathVariable Long id, @RequestParam String code, @RequestParam String anchor, RedirectAttributes ra) {
        hotelService.getById(id);
        try {
            agentService.approve(code, id);
            ra.addFlashAttribute("success", "Агент тасдиқланди — у калитни ўзи олади ва юклашни бошлайди");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back(id, "1c".equals(anchor) ? "1c" : "shelter");
    }

    @PostMapping("/{id}/shelter/import")
    public String importShelter(@PathVariable Long id, RedirectAttributes ra) {
        boolean started = shelterImport.importAsync(id);
        ra.addFlashAttribute(started ? "success" : "error", started
                ? "Қайта ҳисоблаш навбатга қўйилди — жараён шу саҳифада кўринади"
                : "Бу меҳмонхона аллақачон навбатда ёки ҳисобланмоқда");
        return back(id, "shelter");
    }

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
        return back(id, "faktura");
    }

    @PostMapping("/{id}/faktura/test")
    public String testFaktura(@PathVariable Long id, RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("success", fakturaService.test(id));
        } catch (FakturaException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back(id, "faktura");
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
        return back(id, "faktura");
    }

    @PostMapping("/{id}/faktura/disconnect")
    public String disconnectFaktura(@PathVariable Long id, RedirectAttributes ra) {
        fakturaService.disconnect(id);
        ra.addFlashAttribute("success", "ЭСФ оператори уланиши ўчирилди (олинган ҳужжатлар сақланиб қолди)");
        return back(id, "faktura");
    }

    private static String back(Long id, String anchor) {
        return "redirect:/admin/integrations/" + id + "#" + anchor;
    }

    // ---------------------------------------------------------------- Olingan ma'lumotlar (arxiv kartochkalari)

    /** Shelter agenti yuborgan asosiy jadvallar (kartochkalar — bosilsa jadval ochiladi). */
    public record ShelterArchive(List<ShelterTile> tiles, int tables, long rows, LocalDateTime syncedAt) {
    }

    public record ShelterTile(String table, String label, long rows) {
    }

    private ShelterArchive shelterArchive(Long hotelId) {
        List<AgentService.TableInfo> tables = agentService.tables(hotelId);
        if (tables.isEmpty()) {
            return null;
        }
        Map<String, Long> byName = new HashMap<>();
        long rows = 0;
        LocalDateTime synced = null;
        for (var t : tables) {
            byName.put(t.name(), t.rows());
            rows += t.rows();
            synced = synced == null || (t.uploadedAt() != null && t.uploadedAt().isAfter(synced)) ? t.uploadedAt() : synced;
        }
        List<ShelterTile> tiles = ShelterDataController.TABLES.values().stream()
                .filter(t -> byName.containsKey(t.key()))
                .map(t -> new ShelterTile(t.key(), t.title(), byName.get(t.key())))
                .toList();
        return new ShelterArchive(tiles, tables.size(), rows, synced);
    }

    /** Xom arxiv turlarining nomlari. */
    private static final Map<String, String> ARCHIVE_LABELS = Map.ofEntries(
            Map.entry(ExelyRawStore.BOOKING, "Бронлар"),
            Map.entry(ExelyRawStore.RESERVATION, "Яшашлар"),
            Map.entry(ExelyRawStore.SERVICE, "Хизматлар (кунлик)"),
            Map.entry(ExelyRawStore.SERVICE_CANCELLED, "Бекор қилинган хизматлар"),
            Map.entry(ExelyRawStore.PAYMENT, "Тўловлар"),
            Map.entry(ExelyRawStore.INVOICES, "Exely ҳисоблари (бронлар бўйича)"),
            Map.entry(ExelyRawStore.GUEST, "Меҳмонлар"),
            Map.entry(ExelyRawStore.CUSTOMER, "Тўловчилар"),
            Map.entry(ExelyRawStore.AGENT, "Агентлар"),
            Map.entry(ExelyRawStore.COMPANY, "Компаниялар"),
            Map.entry(ExelyRawStore.ROOM_TYPE, "Хона турлари"),
            Map.entry(ExelyRawStore.ROOM, "Хоналар"));
}
