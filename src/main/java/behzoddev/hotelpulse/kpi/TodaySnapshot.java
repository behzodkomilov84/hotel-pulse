package behzoddev.hotelpulse.kpi;

import java.math.BigDecimal;

/**
 * "Bugun" kartochkasi va qarzdorlik.
 *
 * @param inHouseRooms hozir band xonalar soni
 * @param debt         kelgan mehmonlarning to'lanmagan qoldig'i (korxona va guruh hisoblari bilan)
 * @param debtorCount  to'liq to'lanmagan bronlar va korxona hisoblari soni
 * @param companyCount shulardan korxona va guruh hisoblari soni (UCS Shelter)
 */
public record TodaySnapshot(
        long arrivals,
        long departures,
        long inHouseRooms,
        double occupancy,
        BigDecimal debt,
        long debtorCount,
        long companyCount) {

    public TodaySnapshot(long arrivals, long departures, long inHouseRooms, double occupancy, BigDecimal debt, long debtorCount) {
        this(arrivals, departures, inHouseRooms, occupancy, debt, debtorCount, 0);
    }

    /** Qarzdorlar soni: bronlar va korxona hisoblari alohida. */
    public long bookingDebtors() {
        return debtorCount - companyCount;
    }
}
