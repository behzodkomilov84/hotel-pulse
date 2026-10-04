package behzoddev.hotelpulse.kpi;

import java.math.BigDecimal;

/**
 * "Bugun" kartochkasi va qarzdorlik.
 *
 * @param inHouseRooms hozir band xonalar soni
 * @param debt         kelgan mehmonlarning to'lanmagan qoldig'i
 * @param debtorCount  to'liq to'lanmagan bronlar soni
 */
public record TodaySnapshot(
        long arrivals,
        long departures,
        long inHouseRooms,
        double occupancy,
        BigDecimal debt,
        long debtorCount) {
}
