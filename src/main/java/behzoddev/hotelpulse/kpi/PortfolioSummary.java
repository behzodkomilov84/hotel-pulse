package behzoddev.hotelpulse.kpi;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bir nechta mehmonxona bo'yicha jami (bosh sahifa va Telegram'dagi "barcha mehmonxonalar" hisoboti).
 * O'rtacha ADR — oddiy o'rtacha emas, og'irlikli: jami yashash daromadi / jami sotilgan xona-kechalar
 * (katta mehmonxona natijaga xonalari soniga mos ta'sir qiladi). Bir xil valyutadagi mehmonxonalar uchun.
 *
 * @param revenue  jami daromad (yashash + nonushta + boshqa xizmatlar)
 * @param payments shu davrda tushgan to'lovlar (qaytarishlar ayirilgan)
 * @param gap      daromad − tushum: musbat — to'lanmagan qism (qarzga), manfiy — oldindan to'langan
 */
public record PortfolioSummary(String currency, int hotels, long soldRoomNights, long availableRoomNights,
                               BigDecimal roomRevenue, BigDecimal revenue, BigDecimal payments, BigDecimal gap) {

    public double occupancy() {
        return availableRoomNights == 0 ? 0 : (double) soldRoomNights / availableRoomNights;
    }

    /** O'rtacha ADR (og'irlikli). */
    public BigDecimal adr() {
        return soldRoomNights == 0 ? BigDecimal.ZERO
                : roomRevenue.divide(BigDecimal.valueOf(soldRoomNights), 0, RoundingMode.HALF_UP);
    }

    public BigDecimal revpar() {
        return availableRoomNights == 0 ? BigDecimal.ZERO
                : roomRevenue.divide(BigDecimal.valueOf(availableRoomNights), 0, RoundingMode.HALF_UP);
    }
}
