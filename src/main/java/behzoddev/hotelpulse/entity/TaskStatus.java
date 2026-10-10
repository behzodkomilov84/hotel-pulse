package behzoddev.hotelpulse.entity;

import java.util.EnumSet;
import java.util.Set;

/** Topshiriq holati. */
public enum TaskStatus {
    /** Berildi, xodim hali boshlamagan. */
    NEW("Янги"),
    /** Xodim boshladi. */
    IN_PROGRESS("Бажарилмоқда"),
    /** Xodim "bajarildi" dedi — topshiriq beruvchi tekshirishi kerak. */
    REVIEW("Текширувда"),
    /** Topshiriq beruvchi bajarilganini tasdiqladi. */
    DONE("Тасдиқланган"),
    /** Tekshiruvdan qaytarildi — xodim qayta bajarishi kerak. */
    RETURNED("Қайтарилган"),
    CANCELLED("Бекор қилинган");

    /** Xodimda ochiq turgan (bajarilishi kerak bo'lgan) holatlar — muddat va eslatma shular uchun. */
    public static final Set<TaskStatus> OPEN = EnumSet.of(NEW, IN_PROGRESS, RETURNED);

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isFinished() {
        return this == DONE || this == CANCELLED;
    }
}
