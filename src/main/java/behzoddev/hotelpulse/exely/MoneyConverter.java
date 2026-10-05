package behzoddev.hotelpulse.exely;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Exely summasini (o'z valyutasida) mehmonxona valyutasiga o'giradi. */
@FunctionalInterface
public interface MoneyConverter {

    /** O'girmaydi — valyuta mehmonxonaniki bilan bir xil deb hisoblanadi. */
    MoneyConverter NONE = (amount, currency, date) -> amount;

    BigDecimal convert(BigDecimal amount, String currency, LocalDate date);
}
