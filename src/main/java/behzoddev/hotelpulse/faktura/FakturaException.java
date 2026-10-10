package behzoddev.hotelpulse.faktura;

/** Faktura.uz bilan ishlashdagi xato — xabari foydalanuvchiga ko'rsatiladi (parol/token kirmaydi). */
public class FakturaException extends RuntimeException {
    public FakturaException(String message) {
        super(message);
    }

    public FakturaException(String message, Throwable cause) {
        super(message, cause);
    }
}
