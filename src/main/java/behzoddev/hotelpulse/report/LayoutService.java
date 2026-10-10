package behzoddev.hotelpulse.report;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Foydalanuvchining hisobotlar ekrani (mehmonxona sahifasi): bloklar tarkibi va tartibi. Saqlanmagan — standart. */
@Service
@RequiredArgsConstructor
public class LayoutService {

    static final int MAX_BLOCKS = 40;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<String> get(Long userId) {
        List<String> saved = jdbc.query("select block_keys from user_report_layouts where user_id = ?",
                (rs, i) -> rs.getString(1), userId);
        if (saved.isEmpty()) {
            return ReportCatalog.DEFAULT_LAYOUT;
        }
        return clean(Arrays.asList(saved.get(0).split(",")));
    }

    @Transactional(readOnly = true)
    public boolean isCustom(Long userId) {
        Integer n = jdbc.queryForObject("select count(*) from user_report_layouts where user_id = ?", Integer.class, userId);
        return n != null && n > 0;
    }

    /** Tartib saqlanadi; noma'lum va takrorlangan kalitlar tashlanadi. Bo'sh ro'yxat ham mumkin. */
    @Transactional
    public List<String> save(Long userId, List<String> keys) {
        List<String> clean = clean(keys == null ? List.of() : keys);
        jdbc.update("""
                insert into user_report_layouts (user_id, block_keys, updated_at) values (?, ?, ?)
                on duplicate key update block_keys = values(block_keys), updated_at = values(updated_at)
                """, userId, String.join(",", clean), Timestamp.valueOf(LocalDateTime.now(clock)));
        return clean;
    }

    @Transactional
    public void reset(Long userId) {
        jdbc.update("delete from user_report_layouts where user_id = ?", userId);
    }

    static List<String> clean(List<String> keys) {
        List<String> result = new ArrayList<>(new LinkedHashSet<>(keys.stream()
                .map(String::trim).filter(ReportCatalog::isPlaceable).toList()));
        return result.size() > MAX_BLOCKS ? result.subList(0, MAX_BLOCKS) : result;
    }
}
