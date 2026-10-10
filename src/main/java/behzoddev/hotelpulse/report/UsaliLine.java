package behzoddev.hotelpulse.report;

import java.util.Arrays;
import java.util.List;

/**
 * USALI (11-nashr) xarajat moddalari — oyma-oy kiritiladi (hozir saytda, keyinchalik 1C'dan).
 * Daromad Exely'dan avtomatik olinadi; OTA komissiyalari ham Exely'dan (Rooms xarajatiga) qo'shiladi.
 */
public enum UsaliLine {
    ROOMS_PAYROLL(Dept.ROOMS, "Ish haqi va bog'liq xarajatlar", true),
    ROOMS_OTHER(Dept.ROOMS, "Boshqa xarajatlar (kir yuvish, jihoz, mehmon buyumlari, ...)", false),
    FB_COST(Dept.FB, "Mahsulot tannarxi (Cost of sales)", false),
    FB_PAYROLL(Dept.FB, "Ish haqi va bog'liq xarajatlar", true),
    FB_OTHER(Dept.FB, "Boshqa xarajatlar", false),
    OTHER_COST(Dept.OTHER, "Boshqa bo'limlar: tannarx va xarajatlar", false),
    OTHER_PAYROLL(Dept.OTHER, "Boshqa bo'limlar: ish haqi", true),
    AG(Dept.UNDISTRIBUTED, "Ma'muriy va umumiy (A&G)", false),
    AG_PAYROLL(Dept.UNDISTRIBUTED, "Ma'muriy xodimlar ish haqi (A&G payroll)", true),
    ITS(Dept.UNDISTRIBUTED, "Axborot va telekommunikatsiya tizimlari (ITS)", false),
    SM(Dept.UNDISTRIBUTED, "Savdo va marketing (S&M)", false),
    POM(Dept.UNDISTRIBUTED, "Binoga xizmat va ta'mirlash (POM)", false),
    UTILITIES(Dept.UNDISTRIBUTED, "Kommunal xizmatlar (Utilities)", false),
    MGMT_FEES(Dept.FEES, "Boshqaruv haqi (Management fees)", false),
    RENT(Dept.NON_OPERATING, "Ijara", false),
    PROPERTY_TAX(Dept.NON_OPERATING, "Mulk va yer solig'i", false),
    INSURANCE(Dept.NON_OPERATING, "Sug'urta", false),
    OTHER_NON_OPERATING(Dept.NON_OPERATING, "Boshqa nooperatsion xarajatlar", false);

    public enum Dept {
        ROOMS("Rooms (yashash) bo'limi"),
        FB("Food & Beverage (ovqatlanish)"),
        OTHER("Boshqa operatsion bo'limlar"),
        UNDISTRIBUTED("Taqsimlanmagan operatsion xarajatlar"),
        FEES("Boshqaruv haqi"),
        NON_OPERATING("Nooperatsion xarajatlar");

        private final String label;

        Dept(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final Dept dept;
    private final String label;
    /** Ish haqi moddasi (payroll % hisobi uchun). */
    private final boolean payroll;

    UsaliLine(Dept dept, String label, boolean payroll) {
        this.dept = dept;
        this.label = label;
        this.payroll = payroll;
    }

    public Dept getDept() {
        return dept;
    }

    public String getLabel() {
        return label;
    }

    public boolean isPayroll() {
        return payroll;
    }

    public static List<UsaliLine> of(Dept dept) {
        return Arrays.stream(values()).filter(l -> l.dept == dept).toList();
    }
}
