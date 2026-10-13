package behzoddev.hotelpulse.kpi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Qarzdorlik bo'yicha batafsil hisobot.
 *
 * @param rows    filtr va saralashdan keyingi qatorlar
 * @param summary toifalar bo'yicha jami (filtrdan oldin — kartochkalar doim to'liq raqamni ko'rsatadi)
 * @param aging     qarz yoshi bo'yicha (filtrdan oldin; korxona hisoblari ham)
 * @param companies korxona va guruh hisoblari qarzi (UCS Shelter to'lovchi folio'lari) — mehmon qarzidan alohida
 */
public record DebtReport(List<Row> rows, Summary summary, List<AgingBucket> aging, List<Company> companies) {

    public DebtReport(List<Row> rows, Summary summary, List<AgingBucket> aging) {
        this(rows, summary, aging, List.of());
    }

    /** Korxona hisoblari jami qarzi. */
    public BigDecimal companyTotal() {
        return companies.stream().map(Company::debt).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Qarz yoshi bo'limining jami summasi (mehmonlar + korxona hisoblari). */
    public BigDecimal agingTotal() {
        return aging.stream().map(AgingBucket::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * @param openedOn hisob ochilgan sana
     * @param ageDays  oxirgi ketishdan (bo'lmasa — hisob ochilganidan) bugungacha o'tgan kunlar; hali yashayotgan bo'lsa — 0
     * @param from     yashashlar davri, to — oxirgi ketish; services − paid = debt
     */
    public record Company(String name, BigDecimal debt, LocalDate openedOn, long ageDays,
                          BigDecimal services, BigDecimal paid, LocalDate from, LocalDate to, int rooms, int guests,
                          List<behzoddev.hotelpulse.service.CompanyDebtService.Stay> stays) {

        public Company(String name, BigDecimal debt, LocalDate openedOn, long ageDays) {
            this(name, debt, openedOn, ageDays, debt, BigDecimal.ZERO, null, null, 0, 0, List.of());
        }
    }

    /** Qarz toifasi. */
    public enum Category {
        /** Hozir yashayapti (ketish sanasi kelmagan). */
        IN_HOUSE("Яшаяпти"),
        /** Ketgan (PMS'da vyselenie qilingan). */
        CHECKED_OUT("Кетган"),
        /** Ketish sanasi o'tgan, lekin PMS'da vyselenie qilinmagan — tekshirish kerak. */
        NOT_CHECKED_OUT("Выселение қилинмаган");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * @param ageDays ketish sanasidan bugungacha o'tgan kunlar (yashayotganlar uchun 0)
     */
    public record Row(String bookingNumber, String guestName, String source,
                      LocalDate arrival, LocalDate departure, long nights,
                      Category category, BigDecimal total, BigDecimal paid, BigDecimal debt, long ageDays) {
    }

    public record Summary(BigDecimal total, long count,
                          BigDecimal inHouse, long inHouseCount,
                          BigDecimal checkedOut, long checkedOutCount,
                          BigDecimal notCheckedOut, long notCheckedOutCount) {
    }

    /** @param share jami qarzdagi ulushi, 0..1 */
    public record AgingBucket(String label, BigDecimal amount, long count, double share) {
    }
}
