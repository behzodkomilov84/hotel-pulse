package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Hotel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shablonlar uchun ({@code @pms.name(hotel)}): mehmonxona qaysi PMS'ga ulangan — Exely PMS (kalit bilan), tasdiqlangan
 * agent orqali UCS Shelter yoki 1С:Отель, yoki Exely Connect; hech biri bo'lmasa — null. Agent Connect'dan ustun:
 * Connect faqat kanal menejeri, PMS esa agentdagi dastur.
 */
@Component("pms")
@RequiredArgsConstructor
public class PmsSource {

    private final JdbcTemplate jdbc;

    public String name(Hotel hotel) {
        if (hotel == null) {
            return null;
        }
        if (hotel.hasPmsKey()) {
            return "Exely";
        }
        List<String> kinds = jdbc.queryForList(
                "select distinct kind from agents where hotel_id = ? and approved_at is not null", String.class, hotel.getId());
        if (kinds.contains("1C")) {
            return "1С:Отель";
        }
        if (kinds.contains("SHELTER")) {
            return "UCS Shelter";
        }
        return hotel.usesConnectApi() ? "Exely" : null;
    }

    public boolean connected(Hotel hotel) {
        return name(hotel) != null;
    }
}
