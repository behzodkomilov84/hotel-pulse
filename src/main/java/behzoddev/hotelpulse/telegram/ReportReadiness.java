package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.entity.Hotel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;

/**
 * Kunlik hisobot uchun kun "yopilganmi": UCS Shelter tun xizmatlarini (xona narxi) tungi yopishda yozadi.
 * Kecha band bo'lgan har bir xona-kechaga kamida bitta xona xizmati yozilgan bo'lsa — kun yopilgan.
 * Exely va boshqa manbalar xizmatlarni darhol yozadi — ular kutilmaydi.
 */
@Component
@RequiredArgsConstructor
public class ReportReadiness {

    private final JdbcTemplate jdbc;

    /** Ma'lumot Shelter agentidan keladigan mehmonxona (Exely ulanmagan). */
    public boolean waitsForNightAudit(Hotel hotel) {
        if (hotel.isExelyConnected()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from bookings where hotel_id = ? and origin = 'SHELTER')", Boolean.class, hotel.getId()));
    }

    /** {@code day} kechasi band xonalarning har biriga xona xizmati (kind 0) yozilganmi. */
    public boolean nightClosed(Hotel hotel, LocalDate day) {
        Long nights = jdbc.queryForObject("""
                select count(*) from bookings where hotel_id = ? and origin = 'SHELTER'
                  and status in ('CHECKED_IN', 'CHECKED_OUT') and arrival_date <= ? and departure_date > ?""",
                Long.class, hotel.getId(), Date.valueOf(day), Date.valueOf(day));
        if (nights == null || nights == 0) {
            return true;
        }
        Long charges = jdbc.queryForObject("""
                select count(*) from service_revenue where hotel_id = ? and external_id like 'sh:%'
                  and service_date = ? and kind = 0""", Long.class, hotel.getId(), Date.valueOf(day));
        return charges != null && charges >= nights;
    }
}
