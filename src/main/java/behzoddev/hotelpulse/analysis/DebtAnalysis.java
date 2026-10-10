package behzoddev.hotelpulse.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * Qarzdorlik hisobotining tahlili (qoidalar asosida, serverning o'zida — bepul, tashqi xizmatsiz).
 *
 * @param warning    ma'lumot to'liq bo'lmasa ogohlantirish (yo'q — null)
 * @param summary    asosiy xulosa (2–3 jumla)
 * @param risks      xavfli nuqtalar
 * @param actions    tavsiyalar — ustuvorlik bo'yicha
 * @param priorities birinchi navbatda tekshirish kerak bo'lgan bronlar
 */
public record DebtAnalysis(String warning, String summary, List<Point> risks, List<Point> actions,
                           List<Priority> priorities) {

    public enum Level { HIGH, MEDIUM, LOW }

    /**
     * @param title    qisqa sarlavha (qalin), text — izoh
     * @param listKey  tavsiyaga tegishli bronlar ro'yxati (DebtAnalyzer.listRows) — topshiriqqa ilova qilinadi; yo'q — null
     * @param listSize shu ro'yxatdagi yashashlar soni
     */
    public record Point(Level level, String title, String text, String listKey, int listSize) {
        public Point(Level level, String title, String text) {
            this(level, title, text, null, 0);
        }
    }

    public record Priority(String bookingNumber, String guestName, String source,
                           BigDecimal debt, BigDecimal paid, BigDecimal total, String reason) {
    }
}
