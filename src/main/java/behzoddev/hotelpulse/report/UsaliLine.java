package behzoddev.hotelpulse.report;

import java.util.Arrays;
import java.util.List;

/**
 * USALI (11-nashr) xarajat moddalari — oyma-oy kiritiladi (hozir saytda, keyinchalik 1C'dan).
 * Daromad Exely'dan avtomatik olinadi; OTA komissiyalari ham Exely'dan (Rooms xarajatiga) qo'shiladi.
 */
public enum UsaliLine {
    ROOMS_PAYROLL(Dept.ROOMS, "Иш ҳақи ва боғлиқ харажатлар", true),
    ROOMS_OTHER(Dept.ROOMS, "Бошқа харажатлар (кир ювиш, жиҳоз, меҳмон буюмлари, ...)", false),
    FB_COST(Dept.FB, "Маҳсулот таннархи (Cost of sales)", false),
    FB_PAYROLL(Dept.FB, "Иш ҳақи ва боғлиқ харажатлар", true),
    FB_OTHER(Dept.FB, "Бошқа харажатлар", false),
    OTHER_COST(Dept.OTHER, "Бошқа бўлимлар: таннарх ва харажатлар", false),
    OTHER_PAYROLL(Dept.OTHER, "Бошқа бўлимлар: иш ҳақи", true),
    AG(Dept.UNDISTRIBUTED, "Маъмурий ва умумий (A&G)", false),
    AG_PAYROLL(Dept.UNDISTRIBUTED, "Маъмурий ходимлар иш ҳақи (A&G payroll)", true),
    ITS(Dept.UNDISTRIBUTED, "Ахборот ва телекоммуникация тизимлари (ITS)", false),
    SM(Dept.UNDISTRIBUTED, "Савдо ва маркетинг (S&M)", false),
    POM(Dept.UNDISTRIBUTED, "Бинога хизмат ва таъмирлаш (POM)", false),
    UTILITIES(Dept.UNDISTRIBUTED, "Коммунал хизматлар (Utilities)", false),
    MGMT_FEES(Dept.FEES, "Бошқарув ҳақи (Management fees)", false),
    RENT(Dept.NON_OPERATING, "Ижара", false),
    PROPERTY_TAX(Dept.NON_OPERATING, "Мулк ва ер солиғи", false),
    INSURANCE(Dept.NON_OPERATING, "Суғурта", false),
    OTHER_NON_OPERATING(Dept.NON_OPERATING, "Бошқа нооперацион харажатлар", false);

    public enum Dept {
        ROOMS("Rooms (яшаш) бўлими"),
        FB("Food & Beverage (овқатланиш)"),
        OTHER("Бошқа операцион бўлимлар"),
        UNDISTRIBUTED("Тақсимланмаган операцион харажатлар"),
        FEES("Бошқарув ҳақи"),
        NON_OPERATING("Нооперацион харажатлар");

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
