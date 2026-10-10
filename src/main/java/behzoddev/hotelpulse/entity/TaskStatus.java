package behzoddev.hotelpulse.entity;

import java.util.EnumSet;
import java.util.Set;

/** Topshiriq holati. */
public enum TaskStatus {
    /** Berildi, xodim hali boshlamagan. */
    NEW("Yangi"),
    /** Xodim boshladi. */
    IN_PROGRESS("Bajarilmoqda"),
    /** Xodim "bajarildi" dedi — topshiriq beruvchi tekshirishi kerak. */
    REVIEW("Tekshiruvda"),
    /** Topshiriq beruvchi bajarilganini tasdiqladi. */
    DONE("Tasdiqlangan"),
    /** Tekshiruvdan qaytarildi — xodim qayta bajarishi kerak. */
    RETURNED("Qaytarilgan"),
    CANCELLED("Bekor qilingan");

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
