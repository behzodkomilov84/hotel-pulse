package behzoddev.hotelpulse.entity;

/** Topshiriq tarixidagi amal. */
public enum TaskAction {
    CREATED("Topshiriq berildi"),
    STARTED("Bajarishni boshladi"),
    COMPLETED("Bajarildi deb belgiladi"),
    ACCEPTED("Bajarilganini tasdiqladi"),
    RETURNED("Qayta bajarishga qaytardi"),
    CANCELLED("Bekor qildi"),
    COMMENT("Izoh"),
    REMINDED("Muddat eslatmasi yuborildi");

    private final String label;

    TaskAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
