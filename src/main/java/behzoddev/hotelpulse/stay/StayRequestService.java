package behzoddev.hotelpulse.stay;

import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.telegram.TelegramGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chorvoq Stay sayti so'rovlari (bron, rezident, chat): bazaga yoziladi va Telegram'ga yuboriladi.
 * Telegram ishlamasa ham so'rov yo'qolmaydi — admin'da (Бошқарув → Chorvoq Stay) ko'rinadi.
 */
@Slf4j
@Service
public class StayRequestService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final JdbcTemplate jdbc;
    private final TelegramGateway telegram;
    private final UserRepository users;
    private final Clock clock;
    private final List<Long> chatIds;

    public StayRequestService(JdbcTemplate jdbc, TelegramGateway telegram, UserRepository users, Clock clock,
                              @Value("${app.stay.telegram-chat-ids:}") String chatIds) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.users = users;
        this.clock = clock;
        this.chatIds = Arrays.stream(chatIds.split(","))
                .map(String::trim)
                .filter(s -> s.matches("-?\\d+"))
                .map(Long::valueOf)
                .toList();
    }

    /** status: NEW — yangi, DONE — ko'rib chiqilgan. */
    public record StayRequest(long id, String kind, String name, String phone, String subject,
                              Map<String, String> fields, String lang, String status,
                              LocalDateTime createdAt, LocalDateTime handledAt, String handledBy) {

        public boolean isNew() {
            return "NEW".equals(status);
        }
    }

    /**
     * @param data sayt yuborgan maydonlar ("_" bilan boshlanganlari — xizmat maydonlari: _subject, _honey)
     * @return yangi so'rov ID'si
     */
    public long save(Map<String, Object> data, String ip) {
        Map<String, String> fields = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (k == null || k.isBlank() || k.startsWith("_") || fields.size() >= 40) {
                return;
            }
            String value = v instanceof List<?> list ? String.join(", ", list.stream().map(String::valueOf).toList())
                    : v == null ? "" : String.valueOf(v);
            fields.put(cut(k.trim(), 60), cut(value.trim(), 1000));
        });
        String subject = cut(str(data.get("_subject")), 255);
        String kind = cut(fields.get("So'rov turi"), 64);
        String name = cut(first(fields, "Ism", "F.I.Sh.", "Имя", "ФИО"), 150);
        String phone = cut(first(fields, "Telefon", "Телефон"), 40);
        String lang = cut(fields.get("Til"), 8);
        LocalDateTime now = LocalDateTime.now(clock);

        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into stay_requests (kind, name, phone, subject, payload, lang, ip, status, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'NEW', ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, kind);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setString(4, subject);
            ps.setString(5, JSON.writeValueAsString(fields));
            ps.setString(6, lang);
            ps.setString(7, cut(ip, 64));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, key);
        long id = key.getKey() == null ? 0 : key.getKey().longValue();
        notify(id, subject, fields, now);
        return id;
    }

    /** Telegram xabari; xato bo'lsa so'rov baribir saqlangan — faqat logga yoziladi. */
    private void notify(long id, String subject, Map<String, String> fields, LocalDateTime at) {
        StringBuilder sb = new StringBuilder("🏖 <b>").append(esc(subject == null || subject.isBlank()
                ? "Chorvoq Stay — yangi so'rov" : subject)).append("</b>\n\n");
        fields.forEach((k, v) -> sb.append("<b>").append(esc(k)).append(":</b> ").append(esc(v)).append("\n"));
        sb.append("\n🕒 ").append(at.format(TIME)).append(" · chorvoqstay.uz · №").append(id);
        String text = sb.length() > 4000 ? sb.substring(0, 4000) : sb.toString();
        for (Long chat : recipients()) {
            try {
                telegram.sendMessage(chat, text, null);
            } catch (RuntimeException e) {
                log.warn("Chorvoq Stay so'rovi №{} Telegram'ga yuborilmadi: {}", id, e.getMessage());
            }
        }
    }

    /** Sozlamadagi chat_id'lar; bo'lmasa — Telegram'ni ulagan platforma egalari. */
    List<Long> recipients() {
        if (!chatIds.isEmpty()) {
            return chatIds;
        }
        List<Long> result = new ArrayList<>();
        for (User u : users.findAllByOrderByIdAsc()) {
            if (u.getRole() == Role.OWNER && u.isEnabled() && u.getTelegramChatId() != null) {
                result.add(u.getTelegramChatId());
            }
        }
        return result;
    }

    public List<StayRequest> list(String status, int limit) {
        String where = status == null ? "" : "where status = ? ";
        Object[] args = status == null ? new Object[]{limit} : new Object[]{status, limit};
        return jdbc.query("select id, kind, name, phone, subject, payload, lang, status, created_at, handled_at, handled_by "
                + "from stay_requests " + where + "order by created_at desc, id desc limit ?", (rs, i) -> new StayRequest(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                fields(rs.getString(6)), rs.getString(7), rs.getString(8),
                rs.getTimestamp(9).toLocalDateTime(),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toLocalDateTime(),
                rs.getString(11)), args);
    }

    public long countNew() {
        Long n = jdbc.queryForObject("select count(*) from stay_requests where status = 'NEW'", Long.class);
        return n == null ? 0 : n;
    }

    public void markDone(long id, String by) {
        jdbc.update("update stay_requests set status = 'DONE', handled_at = ?, handled_by = ? where id = ? and status = 'NEW'",
                Timestamp.valueOf(LocalDateTime.now(clock)), cut(by, 100), id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fields(String json) {
        try {
            return JSON.readValue(json, LinkedHashMap.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String first(Map<String, String> fields, String... keys) {
        for (String k : keys) {
            String v = fields.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
