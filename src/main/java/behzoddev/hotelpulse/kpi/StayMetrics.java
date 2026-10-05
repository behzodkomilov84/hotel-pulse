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
 * @param roomRevenue   yashash daromadi (Exely xizmatlar hisoboti bo'lsa — faqat yashash; aks holda bron narxi)
 * @param extrasRevenue nonushta va boshqa xizmatlar (faqat Exely xizmatlar hisoboti bo'lsa, aks holda 0)
 * @param mealsRevenue  shundan nonushta / ovqatlanish (Exely DRR "Выручка завтраков")
 */
public record StayMetrics(
        long availableRoomNights,
        long soldRoomNights,
        BigDecimal roomRevenue,
        BigDecimal extrasRevenue,
        BigDecimal mealsRevenue,
        double occupancy,
        BigDecimal adr,
        BigDecimal revpar,
        long noShows,
        double avgLengthOfStay,
        List<DailyPoint> daily,
        List<SourceShare> sources) {

    /** Jami daromad: yashash + xizmatlar (Exely'dagi "Выручка"). */
    public BigDecimal totalRevenue() {
        return roomRevenue.add(extrasRevenue);
    }

    /** @param revenue kunlik jami daromad (yashash + xizmatlar) */
    public record DailyPoint(LocalDate date, int roomsSold, double occupancy, BigDecimal revenue) {
    }

    /** @param share daromaddagi ulushi, 0..1 */
    public record SourceShare(String source, long roomNights, BigDecimal revenue, double share) {
    }
}
