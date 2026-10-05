package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelyPmsClient;
import behzoddev.hotelpulse.exely.ExelyRawStore;
import behzoddev.hotelpulse.exely.ExelyRawView;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Exely'dan olingan xom ma'lumotlar: tur bo'yicha ro'yxat, yozuv tafsiloti va Excel (CSV) eksport. */
@Controller
@RequestMapping("/admin/hotels/{id}/exely/data")
@RequiredArgsConstructor
public class ExelyDataController {

    private static final int PAGE_SIZE = 50;
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final HotelService hotelService;
    private final ExelyRawStore raw;
    private final Clock clock;

    /** Jadval qatori: ustun qiymatlari va to'liq JSON (tafsilot uchun). */
    public record RowView(long id, List<String> cells, String json, String fetchedAt) {
    }

    @GetMapping("/{kind}")
    public String list(@PathVariable Long id, @PathVariable String kind,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        Hotel hotel = hotelService.getById(id);
        ExelyRawView.Kind k = kind(kind);
        long total = raw.count(id, kind, q);
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), pages);
        List<RowView> rows = new ArrayList<>();
        for (ExelyRawStore.Stored s : raw.page(id, kind, q, (current - 1) * PAGE_SIZE, PAGE_SIZE)) {
            JsonNode root = ExelyPmsClient.tree(s.json());
            List<String> cells = new ArrayList<>();
            for (ExelyRawView.Column c : k.columns()) {
                cells.add(ExelyRawView.value(c, root, s.bookingNumber(), s.externalId()));
            }
            rows.add(new RowView(s.id(), cells, PRETTY.writeValueAsString(root),
                    s.fetchedAt() == null ? "" : s.fetchedAt().format(TIME)));
        }
        model.addAttribute("hotel", hotel);
        model.addAttribute("kind", k);
        model.addAttribute("kinds", ExelyRawView.KINDS.values());
        model.addAttribute("counts", raw.counts(id));
        model.addAttribute("rows", rows);
        model.addAttribute("total", total);
        model.addAttribute("page", current);
        model.addAttribute("pages", pages);
        model.addAttribute("q", q);
        return "admin/exely-data";
    }

    /**
     * Excel uchun CSV: qidiruvga mos BARCHA yozuvlar va BARCHA maydonlar (ichma-ich maydonlar "a.b", "c[0].d"
     * ko'rinishidagi alohida ustunlar). UTF-8 BOM va ";" — Excel o'zbekcha/ruscha matnni to'g'ri ochadi.
     */
    @GetMapping(value = "/{kind}.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export(@PathVariable Long id, @PathVariable String kind,
                                         @RequestParam(required = false) String q) {
        Hotel hotel = hotelService.getById(id);
        ExelyRawView.Kind k = kind(kind);
        List<Map<String, String>> rows = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>();
        raw.forEach(id, kind, q, s -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Kalit", s.externalId());
            row.put("Bron raqami", s.bookingNumber() == null ? "" : s.bookingNumber());
            row.put("Sana", s.refDate() == null ? "" : s.refDate().format(DAY));
            row.put("Olingan", s.fetchedAt() == null ? "" : s.fetchedAt().format(TIME));
            row.putAll(ExelyRawView.flatten(ExelyPmsClient.tree(s.json())));
            columns.addAll(row.keySet());
            rows.add(row);
        });
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(";", columns.stream().map(ExelyDataController::csv).toList())).append('\n');
        for (Map<String, String> row : rows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (String c : columns) {
                cells.add(csv(row.getOrDefault(c, "")));
            }
            sb.append(String.join(";", cells)).append('\n');
        }
        String file = "exely-" + kind.replace('_', '-') + "-" + slug(hotel.getName()) + "-" + LocalDate.now(clock) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file, StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ExelyRawView.Kind kind(String kind) {
        ExelyRawView.Kind k = ExelyRawView.KINDS.get(kind);
        if (k == null) {
            throw new ResponseStatusException(NOT_FOUND, "Noma'lum ma'lumot turi");
        }
        return k;
    }

    /** CSV qiymati; formula sifatida bajarilmasligi uchun (CSV injection) — sonlardan tashqari. */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
            s = "'" + s;
        }
        return s.contains(";") || s.contains("\n") || s.contains("\r") || s.contains("\"") ? "\"" + s + "\"" : s;
    }

    private static String slug(String name) {
        String s = name == null ? "hotel" : name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "hotel" : s;
    }
}
