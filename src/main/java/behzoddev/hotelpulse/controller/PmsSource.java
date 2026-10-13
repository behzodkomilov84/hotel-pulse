package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Hotel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shablonlar uchun ({@code @pms.name(hotel)}): mehmonxona qaysi PMS'ga ulangan — Exely (kalit bilan) yoki
 * UCS Shelter (tasdiqlangan agent orqali); hech biri bo'lmasa — null.
 */
@Component("pms")
@RequiredArgsConstructor
public class PmsSource {

    private final JdbcTemplate jdbc;

    public String name(Hotel hotel) {
        if (hotel == null) {
            return null;
        }
        if (hotel.isExelyConnected()) {
            return "Exely";
        }
        Integer agents = jdbc.queryForObject(
                "select count(*) from agents where hotel_id = ? and kind = 'SHELTER' and approved_at is not null",
                Integer.class, hotel.getId());
        return agents != null && agents > 0 ? "UCS Shelter" : null;
    }

    public boolean connected(Hotel hotel) {
        return name(hotel) != null;
    }
}
