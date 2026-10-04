package behzoddev.hotelpulse.exely;

/** Exely bilan ishlashdagi xatolik — xabari foydalanuvchiga ko'rsatiladi (o'zbekcha). */
public class ExelyException extends RuntimeException {

    /** API so'rovlar limiti (HTTP 429) — sinxronlashni to'xtatib, keyingi safar davom ettirish kerak. */
    private final boolean rateLimited;

    public ExelyException(String message) {
        this(message, null, false);
    }

    public ExelyException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ExelyException(String message, Throwable cause, boolean rateLimited) {
        super(message, cause);
        this.rateLimited = rateLimited;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }
}
