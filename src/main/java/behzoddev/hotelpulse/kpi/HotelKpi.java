package behzoddev.hotelpulse.kpi;

import java.math.BigDecimal;

/**
 * Bitta mehmonxonaning tanlangan davr bo'yicha to'liq hisoboti
 * (taqqoslash uchun oldingi davr bilan birga).
 *
 * @param newBookings            yangi bronlar — xonalar bo'yicha (ko'p xonali bron har xonasi bilan)
 * @param cancellations          bekor qilinganlar — xonalar bo'yicha
 * @param newReservations        yangi bronlar soni (ko'p xonali bron — bitta)
 * @param cancelledReservations  bekor qilingan bronlar soni (ko'p xonali bron — bitta)
 */
public record HotelKpi(
        Period period,
        StayMetrics stays,
        BigDecimal paymentsReceived,
        long newBookings,
        long cancellations,
        Period previousPeriod,
        StayMetrics previousStays,
        BigDecimal previousPaymentsReceived,
        long newReservations,
        long cancelledReservations) {

    public HotelKpi(Period period, StayMetrics stays, BigDecimal paymentsReceived, long newBookings, long cancellations,
                    Period previousPeriod, StayMetrics previousStays, BigDecimal previousPaymentsReceived) {
        this(period, stays, paymentsReceived, newBookings, cancellations, previousPeriod, previousStays,
                previousPaymentsReceived, newBookings, cancellations);
    }
}
