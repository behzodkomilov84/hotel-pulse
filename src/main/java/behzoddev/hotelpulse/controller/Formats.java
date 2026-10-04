package behzoddev.hotelpulse.controller;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Shablonlarda raqamlarni o'zbekcha ko'rinishda chiqarish: ${@fmt.money(...)}.
 * Minglik ajratuvchi — bo'sh joy, kasr — vergul: "12 345 000 so'm", "72,4%".
 */
@Component("fmt")
public class Formats {

    private static final DecimalFormatSymbols SYMBOLS = new DecimalFormatSymbols(Locale.ROOT);

    static {
        SYMBOLS.setGroupingSeparator(' ');
        SYMBOLS.setDecimalSeparator(',');
    }

    public String money(BigDecimal amount, String currency) {
        String number = new DecimalFormat("#,##0", SYMBOLS).format(amount == null ? BigDecimal.ZERO : amount);
        return number + " " + currencyLabel(currency);
    }

    /** Katta summalar uchun qisqa ko'rinish: "12,3 mln so'm". */
    public String moneyShort(BigDecimal amount, String currency) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        BigDecimal abs = amount.abs();
        if (abs.compareTo(BigDecimal.valueOf(1_000_000_000)) >= 0) {
            return decimal(amount.divide(BigDecimal.valueOf(1_000_000_000), 1, RoundingMode.HALF_UP)) + " mlrd " + currencyLabel(currency);
        }
        if (abs.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            return decimal(amount.divide(BigDecimal.valueOf(1_000_000), 1, RoundingMode.HALF_UP)) + " mln " + currencyLabel(currency);
        }
        return money(amount, currency);
    }

    public String pct(double ratio) {
        return new DecimalFormat("0.0", SYMBOLS).format(ratio * 100) + "%";
    }

    public String number(double value) {
        return new DecimalFormat("#,##0.#", SYMBOLS).format(value);
    }

    /** Foiz o'zgarishi: "+12,4%" / "−3,1%"; oldingi qiymat 0 bo'lsa — null. */
    public String change(double current, double previous) {
        if (previous == 0) {
            return null;
        }
        double delta = (current - previous) / Math.abs(previous) * 100;
        return sign(delta) + new DecimalFormat("0.0", SYMBOLS).format(Math.abs(delta)) + "%";
    }

    public String change(BigDecimal current, BigDecimal previous) {
        return change(current.doubleValue(), previous.doubleValue());
    }

    /** Bandlik kabi ulushlar uchun foiz punkti farqi: "+4,2 p.p." */
    public String pointChange(double currentRatio, double previousRatio) {
        double delta = (currentRatio - previousRatio) * 100;
        return sign(delta) + new DecimalFormat("0.0", SYMBOLS).format(Math.abs(delta)) + " p.p.";
    }

    /** O'zgarish yo'nalishi CSS klassi uchun: up / down / flat. */
    public String trend(double current, double previous) {
        double eps = Math.max(1e-9, Math.abs(previous) * 0.005);
        if (current - previous > eps) {
            return "up";
        }
        if (previous - current > eps) {
            return "down";
        }
        return "flat";
    }

    public String trend(BigDecimal current, BigDecimal previous) {
        return trend(current.doubleValue(), previous.doubleValue());
    }

    public String currencyLabel(String currency) {
        if (currency == null || currency.equals("UZS")) {
            return "so'm";
        }
        return currency;
    }

    private static String decimal(BigDecimal v) {
        return new DecimalFormat("#,##0.#", SYMBOLS).format(v);
    }

    private static String sign(double delta) {
        return delta > 0.0005 ? "+" : delta < -0.0005 ? "−" : "";
    }
}
