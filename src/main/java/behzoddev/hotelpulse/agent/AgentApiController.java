package behzoddev.hotelpulse.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Agentlar API (sessiyasiz, CSRF'siz): juftlash va fayl yuklash. Yuklash — faqat "X-Agent-Key" bilan.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentApiController {

    /** Ochilgan (gzip'dan chiqarilgan) fayl chegarasi. */
    static final int MAX_UPLOAD = 200 * 1024 * 1024;

    private final AgentService agents;

    @PostMapping("/pair")
    public ResponseEntity<?> pair(@RequestParam(defaultValue = "SHELTER") String kind,
                                  @RequestParam(required = false) String machine,
                                  @RequestParam(required = false) String hotel) {
        try {
            AgentService.Pairing p = agents.pair(kind, machine, hotel);
            return ResponseEntity.ok(Map.of("pairingId", p.pairingId(), "code", p.code(), "expiresInSeconds", p.expiresInSeconds()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pair/{pairingId}")
    public Map<String, Object> poll(@PathVariable String pairingId) {
        AgentService.PairingStatus s = agents.poll(pairingId);
        return s.key() == null ? Map.of("status", s.status()) : Map.of("status", s.status(), "key", s.key());
    }

    /** Fayl yuklash; tana gzip bo'lsa (1f 8b) — ochib saqlanadi. */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestHeader(value = "X-Agent-Key", required = false) String key,
                                    @RequestParam String kind, @RequestParam(required = false) String name,
                                    @RequestBody byte[] body) {
        var agent = agents.authenticate(key);
        if (agent.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "agent kaliti noto'g'ri yoki tasdiqlanmagan"));
        }
        byte[] data;
        try {
            data = gunzipIfNeeded(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "gzip ochilmadi"));
        }
        if (data.length > MAX_UPLOAD) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "fayl juda katta"));
        }
        long id = agents.saveUpload(agent.get(), kind, name, data);
        return ResponseEntity.ok(Map.of("id", id, "size", data.length));
    }

    /** Jadval qismi: tana — gzip JSON Lines (siqilgan holda saqlanadi). */
    @PostMapping("/table")
    public ResponseEntity<?> table(@RequestHeader(value = "X-Agent-Key", required = false) String key,
                                   @RequestParam String name, @RequestParam(defaultValue = "0") int part,
                                   @RequestParam(defaultValue = "1") int parts, @RequestParam(defaultValue = "0") int rows,
                                   @RequestParam(required = false) String syncId, @RequestBody byte[] body) {
        var agent = agents.authenticate(key);
        if (agent.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "agent kaliti noto'g'ri yoki tasdiqlanmagan"));
        }
        if (body.length < 2 || (body[0] & 0xff) != 0x1f || (body[1] & 0xff) != 0x8b || part < 0 || parts < 1 || part >= parts) {
            return ResponseEntity.badRequest().body(Map.of("error", "gzip tana va to'g'ri part/parts kerak"));
        }
        agents.saveTablePart(agent.get(), name, part, parts, rows, syncId, body);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Sinxronlash yakuni: agent qisqa xabar yuboradi (nechta jadval/qator, xatolar). */
    @PostMapping("/sync-done")
    public ResponseEntity<?> syncDone(@RequestHeader(value = "X-Agent-Key", required = false) String key,
                                      @RequestParam(required = false) String message) {
        var agent = agents.authenticate(key);
        if (agent.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "kalit noto'g'ri"));
        }
        agents.finishSync(agent.get(), message);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/ping")
    public ResponseEntity<?> ping(@RequestHeader(value = "X-Agent-Key", required = false) String key) {
        return agents.authenticate(key)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(Map.of("hotelId", a.hotelId())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "kalit noto'g'ri")));
    }

    static byte[] gunzipIfNeeded(byte[] body) throws IOException {
        if (body.length < 2 || (body[0] & 0xff) != 0x1f || (body[1] & 0xff) != 0x8b) {
            return body;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] out = in.readNBytes(MAX_UPLOAD + 1);
            return out;
        }
    }
}
