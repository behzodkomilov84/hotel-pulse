package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.agent.AgentService;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelyRawStore;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mehmonxona bo'yicha PMS'dan olingan barcha ma'lumotlar bir sahifada — Exely arxivi, UCS Shelter va 1С:Отель
 * jadvallari tugmalar ko'rinishida; bosilsa jadvalning o'zi ochiladi.
 */
@Controller
@RequiredArgsConstructor
public class PmsDataController {

    private final HotelService hotelService;
    private final ExelyRawStore exelyRawStore;
    private final AgentService agentService;

    public record Tile(String href, String label, String hint, long rows) {
    }

    public record HotelSources(Hotel hotel, long exelyRows, long agentTables, long agentRows, String pms) {
    }

    /** Barcha mehmonxonalar — har biri tugma: bosilsa shu mehmonxonaning PMS ma'lumotlari ochiladi. */
    @GetMapping("/admin/data")
    public String index(Model model) {
        List<HotelSources> rows = hotelService.findAll().stream().map(h -> {
            long exelyRows = exelyRawStore.counts(h.getId()).values().stream().mapToLong(Long::longValue).sum();
            List<AgentService.TableInfo> tables = agentService.tables(h.getId());
            long agentRows = tables.stream().mapToLong(AgentService.TableInfo::rows).sum();
            String pms = !OneCDataController.groups(tables).isEmpty() ? "1С:Отель"
                    : !tables.isEmpty() ? "UCS Shelter" : exelyRows > 0 ? "Exely" : null;
            return new HotelSources(h, exelyRows, tables.size(), agentRows, pms);
        }).toList();
        model.addAttribute("rows", rows);
        return "admin/pms-data-index";
    }

    @GetMapping("/admin/hotels/{id}/data")
    public String hub(@PathVariable Long id, Model model) {
        Hotel hotel = hotelService.getById(id);
        List<AgentService.TableInfo> tables = agentService.tables(id);
        List<OneCDataController.Group> onec = OneCDataController.groups(tables);
        Map<String, Long> exely = exelyRawStore.counts(id);
        if (!onec.isEmpty()) {
            exely.keySet().removeAll(List.of(ExelyRawStore.ROOM, ExelyRawStore.ROOM_TYPE));   // 1C importi yozgan
        }
        List<Tile> exelyTiles = exely.entrySet().stream()
                .map(e -> new Tile("/admin/hotels/" + id + "/exely/data/" + e.getKey(),
                        IntegrationController.ARCHIVE_LABELS.getOrDefault(e.getKey(), e.getKey()), e.getKey(), e.getValue()))
                .toList();

        Map<String, Long> byName = new HashMap<>();
        LocalDateTime synced = null;
        for (var t : tables) {
            byName.put(t.name(), t.rows());
            synced = synced == null || (t.uploadedAt() != null && t.uploadedAt().isAfter(synced)) ? t.uploadedAt() : synced;
        }
        List<Tile> shelterTiles = ShelterDataController.TABLES.values().stream()
                .filter(t -> byName.containsKey(t.key()))
                .map(t -> new Tile("/admin/hotels/" + id + "/shelter/data/" + t.key(), t.title(), t.key(), byName.get(t.key())))
                .toList();

        model.addAttribute("hotel", hotel);
        model.addAttribute("exelyTiles", exelyTiles);
        model.addAttribute("shelterTiles", shelterTiles);
        model.addAttribute("onecGroups", onec);
        model.addAttribute("agentSyncedAt", synced);
        return "admin/pms-data";
    }
}
