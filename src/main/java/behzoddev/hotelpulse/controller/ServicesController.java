package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.InvoiceReportService;
import behzoddev.hotelpulse.service.InvoiceReportService.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** "Xizmatlar" menyusi: hisob-fakturalar — qarzdor yashashlar va ularning Exely hisoblari (folio). */
@Controller
@RequestMapping("/services")
@RequiredArgsConstructor
public class ServicesController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final HotelService hotelService;
    private final InvoiceReportService invoices;
    private final Clock clock;

    @GetMapping("/invoices")
    public String invoices(@AuthenticationPrincipal CustomUserDetails user,
                           @RequestParam(required = false) Long hotel,
                           @RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "debt") String sort,
                           @RequestParam(defaultValue = "desc") String dir,
                           @RequestParam(defaultValue = "1") int page,
                           Model model) {
        List<Hotel> accessible = hotelService.accessibleHotels(user);
        String s = InvoiceReportService.SORTS.contains(sort) ? sort : "debt";
        boolean desc = !"asc".equals(dir);
        InvoiceReportService.Result result = invoices.report(selected(accessible, hotel), q, s, desc, page);
        model.addAttribute("hotels", accessible);
        model.addAttribute("hotel", hotel);
        model.addAttribute("q", q);
        model.addAttribute("sort", s);
        model.addAttribute("dir", desc ? "desc" : "asc");
        model.addAttribute("result", result);
        model.addAttribute("rows", InvoiceReportService.pageRows(result));
        model.addAttribute("showHotel", hotel == null && accessible.size() > 1);
        return "services/invoices";
    }

    /** Qarz bosilganda: yashash tafsiloti (xizmatlar, qaysi hisobda, narx, to'langan, qarz) — HTML parcha. */
    @GetMapping("/invoices/detail")
    public String invoiceDetail(@AuthenticationPrincipal CustomUserDetails user,
                                @RequestParam Long hotel, @RequestParam String stay, Model model) {
        Hotel h = hotelService.getAccessible(user, hotel);
        model.addAttribute("d", invoices.detail(h, stay).orElse(null));
        return "services/invoice-detail :: detail";
    }

    /** Excel uchun CSV — filtr va saralashga mos BARCHA qatorlar (sahifalashsiz). */
    @GetMapping(value = "/invoices.csv", produces = "text/csv")
    public ResponseEntity<byte[]> invoicesCsv(@AuthenticationPrincipal CustomUserDetails user,
                                              @RequestParam(required = false) Long hotel,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "debt") String sort,
                                              @RequestParam(defaultValue = "desc") String dir) {
        List<Hotel> accessible = hotelService.accessibleHotels(user);
        String s = InvoiceReportService.SORTS.contains(sort) ? sort : "debt";
        InvoiceReportService.Result result = invoices.report(selected(accessible, hotel), q, s, !"asc".equals(dir), 1);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Mehmonxona;Bron raqami;Mehmon;Manba;Kelish;Ketish;Holat;Yashash narxi;Qarz;Qarz yoshi (kun);"
                + "Exely hisoblari soni;Exely hisob raqamlari;To'lovchi;Exely hisob summasi;Valyuta\n");
        for (Row r : result.rows()) {
            sb.append(csv(r.hotelName())).append(';')
                    .append(csv(r.bookingNumber())).append(';')
                    .append(csv(r.guestName())).append(';')
                    .append(csv(r.source())).append(';')
                    .append(r.arrival().format(DAY)).append(';')
                    .append(r.departure().format(DAY)).append(';')
                    .append(csv(r.category().getLabel())).append(';')
                    .append(r.total().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(r.debt().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(r.ageDays()).append(';')
                    .append(r.accountCount()).append(';')
                    .append(csv(r.accountNumbers())).append(';')
                    .append(csv(r.payer())).append(';')
                    .append(r.accountTotal().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(csv(r.currency())).append('\n');
        }
        String file = "hisob-fakturalar-" + LocalDate.now(clock) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Tanlangan mehmonxona (faqat ruxsat etilganlar ichidan) yoki hammasi. */
    private static List<Hotel> selected(List<Hotel> accessible, Long hotelId) {
        if (hotelId == null) {
            return accessible;
        }
        return accessible.stream().filter(h -> h.getId().equals(hotelId)).toList();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(";") || s.contains("\n") || s.contains("\"") ? "\"" + s + "\"" : s;
    }
}
