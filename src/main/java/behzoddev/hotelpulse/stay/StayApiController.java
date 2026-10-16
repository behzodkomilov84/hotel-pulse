package behzoddev.hotelpulse.stay;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chorvoq Stay sayti API (avval PHP — api/telegram.php): bron, rezident va chat so'rovlari.
 * Sessiyasiz; himoya: faqat o'z saytimizdan (Origin), bitta IP'dan 10 daqiqada 5 tadan ortiq emas,
 * botlar uchun yashirin "_honey" maydoni, hajm 20 KB gacha.
 */
@RestController
@RequestMapping("/api/stay")
public class StayApiController {

    static final int MAX_BYTES = 20_000;
    static final int RATE_LIMIT = 5;
    static final long RATE_WINDOW_SECONDS = 600;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StayRequestService requests;
    private final Clock clock;
    private final java.util.Set<String> allowedOrigins;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public StayApiController(StayRequestService requests, Clock clock,
                             @Value("${app.stay.hosts:chorvoqstay.uz,www.chorvoqstay.uz}") String hosts,
                             @Value("${app.telegram.site-url:}") String siteUrl) {
        this.requests = requests;
        this.clock = clock;
        java.util.Set<String> origins = new java.util.HashSet<>();
        for (String h : hosts.split(",")) {
            if (!h.isBlank()) {
                origins.add("https://" + h.trim().toLowerCase(Locale.ROOT));
                origins.add("http://" + h.trim().toLowerCase(Locale.ROOT));
            }
        }
        // hotel-pulse.uz/stay/ — saytni domen ulanmasdan oldin tekshirish uchun.
        if (!siteUrl.isBlank()) {
            origins.add(siteUrl.replaceAll("/+$", "").toLowerCase(Locale.ROOT));
        }
        this.allowedOrigins = origins;
    }

    @PostMapping("/requests")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "origin"));
        }
        String ip = clientIp(request);
        if (!allow(ip)) {
            return ResponseEntity.status(429).body(Map.of("ok", false, "error", "rate"));
        }
        if (body == null || body.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "json"));
        }
        if (body.length > MAX_BYTES) {
            return ResponseEntity.status(413).body(Map.of("ok", false, "error", "size"));
        }
        Map<String, Object> data;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(body, Map.class);
            data = parsed;
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "json"));
        }
        Object honey = data.get("_honey");
        if (honey != null && !String.valueOf(honey).isBlank()) {
            return ResponseEntity.ok(Map.of("ok", true));   // bot — jimgina qabul qilingandek
        }
        long id = requests.save(data, ip);
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    /** nginx ortida: haqiqiy IP X-Real-IP'da (ilova faqat nginx'dan ochiq). */
    static String clientIp(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real.trim() : request.getRemoteAddr();
    }

    private boolean allow(String ip) {
        Instant now = Instant.now(clock);
        Deque<Instant> q = hits.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minusSeconds(RATE_WINDOW_SECONDS))) {
                q.pollFirst();
            }
            if (q.size() >= RATE_LIMIT) {
                return false;
            }
            q.addLast(now);
        }
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return true;
    }
}
