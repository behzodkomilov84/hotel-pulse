package behzoddev.hotelpulse.agent;

import behzoddev.hotelpulse.security.AesGcmCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Mehmonxona kompyuteridagi agentlar (UCS Shelter, keyin 1C): juftlash, kalit, yuklangan fayllar.
 *
 * <p>Juftlash: agent POST /api/agent/pair → 6 xonali kod (30 daqiqa). Platforma egasi admin sahifada shu kodni
 * kiritib mehmonxonani tanlaydi → kalit yaratiladi; agent so'rov bilan uni BIR MARTA oladi (bazada vaqtincha shifrlangan,
 * berilgach o'chiriladi; doimiy saqlanadigani — faqat SHA-256 xeshi). Hech kim kalitni qo'lda ko'chirmaydi.
 */
@Service
@RequiredArgsConstructor
public class AgentService {

    static final Duration PAIRING_TTL = Duration.ofMinutes(30);
    static final int MAX_PENDING = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AesGcmCipher cipher;
    private final Clock clock;
    private final behzoddev.hotelpulse.service.HotelService hotelService;

    public record Pairing(String pairingId, String code, long expiresInSeconds) {
    }

    /** status: PENDING, APPROVED (key — faqat birinchi marta), EXPIRED, UNKNOWN. */
    public record PairingStatus(String status, String key) {
    }

    public record Agent(long id, String kind, String machine, String hotelHint, Long suggestedHotelId, Long hotelId, String hotelName, String pairingCode,
                        LocalDateTime pairingExpires, LocalDateTime approvedAt, LocalDateTime lastSeenAt,
                        LocalDateTime createdAt, long uploads, LocalDateTime lastSyncAt, String lastSyncMessage, long tables) {

        public boolean isApproved() {
            return approvedAt != null;
        }
    }

    public record Upload(long id, long agentId, Long hotelId, String hotelName, String kind, String name, long size,
                         LocalDateTime createdAt) {
    }

    /** Agent tasdiqlangan va kaliti to'g'ri bo'lsa. */
    public record Authenticated(long agentId, long hotelId, String kind) {
    }

    @Transactional
    public Pairing pair(String kind, String machine) {
        return pair(kind, machine, null);
    }

    /** @param hotelHint agent o'zini tanishtiradigan mehmonxona nomi — tasdiqlashda shu mehmonxona avtomatik tanlanadi */
    @Transactional
    public Pairing pair(String kind, String machine, String hotelHint) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("delete from agents where approved_at is null and pairing_expires < ?", Timestamp.valueOf(now));
        Integer pending = jdbc.queryForObject("select count(*) from agents where approved_at is null", Integer.class);
        if (pending != null && pending >= MAX_PENDING) {
            throw new IllegalStateException("Жуфтлаш сўровлари жуда кўп — кейинроқ уриниб кўринг");
        }
        String pairingId = HexFormat.of().formatHex(bytes(24));
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("""
                insert into agents (kind, machine, hotel_hint, pairing_id, pairing_code, pairing_expires, created_at)
                values (?, ?, ?, ?, ?, ?, ?)""",
                cut(clean(kind, "SHELTER"), 16), cut(machine, 128),
                hotelHint == null || hotelHint.isBlank() ? null : cut(hotelHint.trim(), 150), pairingId, code,
                Timestamp.valueOf(now.plus(PAIRING_TTL)), Timestamp.valueOf(now));
        return new Pairing(pairingId, code, PAIRING_TTL.toSeconds());
    }

    /** Agent kodni tasdiqlanishini kutadi; tasdiqlangach kalit bir marta beriladi. */
    @Transactional
    public PairingStatus poll(String pairingId) {
        List<Object[]> rows = jdbc.query("select id, approved_at, pending_key, pairing_expires from agents where pairing_id = ?",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getTimestamp(2), rs.getString(3), rs.getTimestamp(4)}, pairingId);
        if (rows.isEmpty()) {
            return new PairingStatus("UNKNOWN", null);
        }
        Object[] r = rows.get(0);
        if (r[1] == null) {
            boolean expired = ((Timestamp) r[3]).toLocalDateTime().isBefore(LocalDateTime.now(clock));
            return new PairingStatus(expired ? "EXPIRED" : "PENDING", null);
        }
        String pending = (String) r[2];
        if (pending == null) {
            return new PairingStatus("APPROVED", null);   // kalit allaqachon berilgan
        }
        jdbc.update("update agents set pending_key = null where id = ?", r[0]);
        return new PairingStatus("APPROVED", cipher.decrypt(pending));
    }

    /** Platforma egasi: kodni tasdiqlab mehmonxonaga bog'laydi. */
    @Transactional
    public void approve(String code, long hotelId) {
        String c = code == null ? "" : code.replaceAll("\\D", "");
        List<Long> ids = jdbc.queryForList("select id from agents where pairing_code = ? and approved_at is null and pairing_expires >= ?",
                Long.class, c, Timestamp.valueOf(LocalDateTime.now(clock)));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Бундай код топилмади ёки муддати ўтган — агентда кодни янгиланг");
        }
        approveAgent(ids.get(0), hotelId);
    }

    /**
     * Ro'yxatdagi kutayotgan agentni tasdiqlaydi. hotelId null bo'lsa — agent aytgan nom (hotel_hint) bo'yicha mavjud
     * mehmonxona, topilmasa — shu nom bilan yangi mehmonxona yaratiladi.
     */
    @Transactional
    public long approveAgent(long agentId, Long hotelId) {
        List<String> hint = jdbc.queryForList("select hotel_hint from agents where id = ? and approved_at is null and pairing_expires >= ?",
                String.class, agentId, Timestamp.valueOf(LocalDateTime.now(clock)));
        if (hint.isEmpty()) {
            throw new IllegalArgumentException("Агент топилмади ёки коди муддати ўтган — агентни қайта ишга туширинг");
        }
        long hotel;
        if (hotelId != null) {
            hotel = hotelId;
        } else {
            String name = hint.get(0);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Агент меҳмонхона номини юбормаган — рўйхатдан танланг");
            }
            hotel = matchHotel(name).orElseGet(() -> hotelService.save(null, name, null, 0, null, "UZS",
                    null, null, null, null, true).getId());
        }
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32));
        jdbc.update("update agents set hotel_id = ?, key_hash = ?, pending_key = ?, approved_at = ? where id = ?",
                hotel, sha256(key), cipher.encrypt(key), Timestamp.valueOf(LocalDateTime.now(clock)), agentId);
        return hotel;
    }

    /** Nom bo'yicha mavjud mehmonxona (katta-kichik harf, apostrof va bo'shliqlarga qaramay). */
    @Transactional(readOnly = true)
    public Optional<Long> matchHotel(String name) {
        String n = normalize(name);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        return hotelService.findAll().stream().filter(h -> normalize(h.getName()).equals(n))
                .map(behzoddev.hotelpulse.entity.Hotel::getId).findFirst();
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    @Transactional
    public Optional<Authenticated> authenticate(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        List<Authenticated> found = jdbc.query("select id, hotel_id, kind from agents where key_hash = ? and approved_at is not null",
                (rs, i) -> new Authenticated(rs.getLong(1), rs.getLong(2), rs.getString(3)), sha256(key.trim()));
        found.stream().findFirst().ifPresent(a -> jdbc.update("update agents set last_seen_at = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now(clock)), a.agentId()));
        return found.stream().findFirst();
    }

    @Transactional
    public long saveUpload(Authenticated a, String kind, String name, byte[] payload) {
        jdbc.update("insert into agent_uploads (agent_id, hotel_id, kind, name, size_bytes, payload, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                a.agentId(), a.hotelId(), cut(clean(kind, "file"), 32), cut(name, 128), payload.length, payload,
                Timestamp.valueOf(LocalDateTime.now(clock)));
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        return id == null ? 0 : id;
    }

    // ------------------------------------------------------------------ jadval sinxronlashi

    /** Jadval qismini saqlaydi (gzip JSON Lines). 0-qism kelganda — shu jadvalning eski qismlari o'chiriladi. */
    @Transactional
    public void saveTablePart(Authenticated a, String table, int part, int parts, int rows, String syncId, byte[] gzip) {
        String t = cut(clean(table, "UNKNOWN"), 64);
        if (part == 0) {
            jdbc.update("delete from agent_tables where hotel_id = ? and table_name = ?", a.hotelId(), t);
        }
        jdbc.update("""
                insert into agent_tables (hotel_id, table_name, part, parts, row_count, sync_id, payload, uploaded_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on duplicate key update parts = values(parts), row_count = values(row_count), sync_id = values(sync_id),
                    payload = values(payload), uploaded_at = values(uploaded_at)""",
                a.hotelId(), t, part, parts, rows, cut(syncId, 40), gzip, Timestamp.valueOf(LocalDateTime.now(clock)));
    }

    /** Sinxronlash yakuni (agent xabari). */
    @Transactional
    public void finishSync(Authenticated a, String message) {
        jdbc.update("update agents set last_sync_at = ?, last_sync_message = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now(clock)), cut(message, 500), a.agentId());
    }

    public record TableInfo(String name, int parts, long rows, long bytes, LocalDateTime uploadedAt) {
    }

    @Transactional(readOnly = true)
    public List<TableInfo> tables(long hotelId) {
        return jdbc.query("""
                select table_name, max(parts), sum(row_count), sum(length(payload)), max(uploaded_at)
                from agent_tables where hotel_id = ? group by table_name order by table_name""",
                (rs, i) -> new TableInfo(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), ts(rs.getTimestamp(5))),
                hotelId);
    }

    /** Jadvalning barcha qatorlari (JSON Lines, ochilgan) — mapper va tekshirish uchun. */
    @Transactional(readOnly = true)
    public List<String> tableRows(long hotelId, String table) {
        List<byte[]> parts = jdbc.query("select payload from agent_tables where hotel_id = ? and table_name = ? order by part",
                (rs, i) -> rs.getBytes(1), hotelId, table);
        List<String> rows = new java.util.ArrayList<>();
        for (byte[] p : parts) {
            try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(p))) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank()) {
                        rows.add(line);
                    }
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("agent_tables " + table + ": gzip o'qilmadi", e);
            }
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<Agent> agents() {
        return jdbc.query("""
                select a.id, a.kind, a.machine, a.hotel_hint, a.hotel_id, h.name, a.pairing_code, a.pairing_expires, a.approved_at,
                       a.last_seen_at, a.created_at, (select count(*) from agent_uploads u where u.agent_id = a.id),
                       a.last_sync_at, a.last_sync_message,
                       (select count(distinct t.table_name) from agent_tables t where t.hotel_id = a.hotel_id)
                from agents a left join hotels h on h.id = a.hotel_id
                order by a.approved_at is null desc, a.created_at desc""",
                (rs, i) -> new Agent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), null, (Long) rs.getObject(5, Long.class),
                        rs.getString(6), rs.getString(7), ts(rs.getTimestamp(8)), ts(rs.getTimestamp(9)),
                        ts(rs.getTimestamp(10)), ts(rs.getTimestamp(11)), rs.getLong(12),
                        ts(rs.getTimestamp(13)), rs.getString(14), rs.getLong(15)))
                .stream().map(a -> a.hotelHint() == null ? a : new Agent(a.id(), a.kind(), a.machine(), a.hotelHint(),
                        matchHotel(a.hotelHint()).orElse(null), a.hotelId(), a.hotelName(), a.pairingCode(), a.pairingExpires(),
                        a.approvedAt(), a.lastSeenAt(), a.createdAt(), a.uploads(), a.lastSyncAt(), a.lastSyncMessage(),
                        a.tables())).toList();
    }

    @Transactional(readOnly = true)
    public List<Upload> uploads(int limit) {
        return jdbc.query("""
                select u.id, u.agent_id, u.hotel_id, h.name, u.kind, u.name, u.size_bytes, u.created_at
                from agent_uploads u left join hotels h on h.id = u.hotel_id order by u.created_at desc limit ?""",
                (rs, i) -> new Upload(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3, Long.class), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7), ts(rs.getTimestamp(8))), limit);
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> payload(long uploadId) {
        return jdbc.query("select payload from agent_uploads where id = ?", (rs, i) -> rs.getBytes(1), uploadId)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Upload> upload(long uploadId) {
        return uploads(10_000).stream().filter(u -> u.id() == uploadId).findFirst();
    }

    @Transactional
    public void delete(long agentId) {
        jdbc.update("delete from agents where id = ?", agentId);
    }

    // ------------------------------------------------------------------ yordamchilar

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String clean(String s, String fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String c = s.replaceAll("[^A-Za-z0-9_.-]", "");
        return c.isEmpty() ? fallback : c;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
