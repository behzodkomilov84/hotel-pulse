package behzoddev.hotelpulse.kpi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Yashash (xona-kecha) asosidagi ko'rsatkichlar.
 *
 * @param occupancy bandlik, 0..1 (ortiqcha bron bo'lsa 1 dan oshishi mumkin)
 * @param adr       sotilgan bitta xona-kechaning o'rtacha narxi
 * @param revpar    mavjud bitta xona-kechaga to'g'ri keladigan daromad
 */
public record StayMetrics(
        long availableRoomNights,
        long soldRoomNights,
        BigDecimal roomRevenue,
        double occupancy,
        BigDecimal adr,
        BigDecimal revpar,
        long noShows,
        double avgLengthOfStay,
        List<DailyPoint> daily,
        List<SourceShare> sources) {

    public record DailyPoint(LocalDate date, int roomsSold, double occupancy, BigDecimal revenue) {
    }

    /** @param share daromaddagi ulushi, 0..1 */
    public record SourceShare(String source, long roomNights, BigDecimal revenue, double share) {
    }
}
