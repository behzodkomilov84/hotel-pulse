package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.agent.AgentService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Бошқарув → Агентлар: juftlash kodini tasdiqlash, agentlar va ular yuklagan fayllar (faqat platforma egasi). */
@Controller
@RequestMapping("/admin/agents")
@RequiredArgsConstructor
public class AgentAdminController {

    private final AgentService agents;
    private final HotelService hotelService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("agents", agents.agents());
        model.addAttribute("uploads", agents.uploads(50));
        model.addAttribute("hotels", hotelService.findAll());
        return "admin/agents";
    }

    @PostMapping("/approve")
    public String approve(@RequestParam String code, @RequestParam Long hotel, RedirectAttributes ra) {
        try {
            agents.approve(code, hotel);
            ra.addFlashAttribute("success", "Агент тасдиқланди — у калитни ўзи олади ва юклашни бошлайди");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/agents";
    }

    /** Ro'yxatdagi agentni tasdiqlash: hotel bo'sh — agent aytgan nom bo'yicha (yo'q bo'lsa yangi mehmonxona). */
    @PostMapping("/{id}/approve")
    public String approveAgent(@PathVariable long id, @RequestParam(required = false) String hotel, RedirectAttributes ra) {
        try {
            Long hotelId = hotel == null || hotel.isBlank() || hotel.equals("auto") ? null : Long.valueOf(hotel);
            long h = agents.approveAgent(id, hotelId);
            ra.addFlashAttribute("success", "Агент тасдиқланди («" + hotelService.getById(h).getName()
                    + "») — у калитни ўзи олади ва юклашни бошлайди");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/agents";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes ra) {
        agents.delete(id);
        ra.addFlashAttribute("success", "Агент ўчирилди (унинг калити энди ишламайди)");
        return "redirect:/admin/agents";
    }

    @GetMapping("/uploads/{id}")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        var upload = agents.upload(id).orElseThrow(() -> new behzoddev.hotelpulse.service.NotFoundException("Файл топилмади"));
        byte[] data = agents.payload(id).orElse(new byte[0]);
        String name = upload.name() != null ? upload.name() : upload.kind() + "-" + id + ".bin";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }
}
