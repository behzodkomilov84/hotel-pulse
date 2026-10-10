package behzoddev.hotelpulse.entity;

/** Topshiriq tarixidagi amal. */
public enum TaskAction {
    CREATED("Топшириқ берилди"),
    STARTED("Бажаришни бошлади"),
    COMPLETED("Бажарилди деб белгилади"),
    ACCEPTED("Бажарилганини тасдиқлади"),
    RETURNED("Қайта бажаришга қайтарди"),
    CANCELLED("Бекор қилди"),
    COMMENT("Изоҳ"),
    REMINDED("Муддат эслатмаси юборилди");

    private final String label;

    TaskAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
