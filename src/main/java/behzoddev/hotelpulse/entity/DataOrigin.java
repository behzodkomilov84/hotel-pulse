package behzoddev.hotelpulse.entity;

/** Ma'lumot manbai. */
public enum DataOrigin {
    /** Sinov uchun generatsiya qilingan — istalgan payt o'chirish mumkin. */
    DEMO,
    /** Exely Connect — Read Reservation API (faqat oldindan to'lovni beradi). */
    EXELY,
    /** Exely PMS Universal API — bronlar, haqiqiy holat, to'lovlar va qoldiq. */
    EXELY_PMS,
    /** UCS Shelter (mehmonxona kompyuteridagi agent orqali, Firebird bazasidan). */
    SHELTER,
    /** 1С:Отель (mehmonxona kompyuteridagi agent orqali, COM ulanish). */
    ONEC,
    MANUAL
}
