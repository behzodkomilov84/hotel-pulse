package behzoddev.hotelpulse.entity;

public enum BookingStatus {
    /** Tasdiqlangan, mehmon hali kelmagan. */
    CONFIRMED,
    /** Mehmon yashayapti. */
    CHECKED_IN,
    /** Mehmon ketgan. */
    CHECKED_OUT,
    CANCELLED,
    /** Mehmon kelmadi. */
    NO_SHOW;

    /** Xona-kechani band qiladi va daromadga kiradi. */
    public boolean isActive() {
        return this == CONFIRMED || this == CHECKED_IN || this == CHECKED_OUT;
    }
}
