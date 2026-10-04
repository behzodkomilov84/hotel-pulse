package behzoddev.hotelpulse.kpi;

import java.math.BigDecimal;

/**
 * Bitta mehmonxonaning tanlangan davr bo'yicha to'liq hisoboti
 * (taqqoslash uchun oldingi davr bilan birga).
 */
public record HotelKpi(
        Period period,
        StayMetrics stays,
        BigDecimal paymentsReceived,
        long newBookings,
        long cancellations,
        Period previousPeriod,
        StayMetrics previousStays,
        BigDecimal previousPaymentsReceived) {
}
