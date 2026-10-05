package behzoddev.hotelpulse.entity;

public enum Role {
    /** Platforma egasi — barcha mehmonxonalarni ko'radi va boshqaradi. */
    OWNER("Platforma egasi"),
    /** Mehmonxona egasi — faqat o'ziga biriktirilgan mehmonxonalarni ko'radi. */
    HOTEL_OWNER("Mehmonxona egasi"),
    /** Boshqaruv kompaniyasi — bir nechta mehmonxonani boshqaradi, biriktirilganlarini ko'radi. */
    MANAGEMENT_COMPANY("Boshqaruv kompaniyasi"),
    /** Mehmonxona xodimi (boshqaruvchi, buxgalter) — faqat ko'rish. */
    HOTEL_STAFF("Mehmonxona xodimi");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
