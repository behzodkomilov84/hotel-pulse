package behzoddev.hotelpulse.controller;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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

    /** Ishorali qisqa summa: "+12,3 mln so'm" / "−4,1 mln so'm" (daromad va tushum farqi uchun). */
    public String signedMoneyShort(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() == 0) {
            return moneyShort(BigDecimal.ZERO, currency);
        }
        return (amount.signum() > 0 ? "+" : "−") + moneyShort(amount.abs(), currency);
    }

    /** Farq izohi: musbat — to'lanmagan qism, manfiy — oldindan to'langan. */
    public String gapNote(BigDecimal gap) {
        if (gap == null || gap.signum() == 0) {
            return "daromad to'liq to'langan";
        }
        return gap.signum() > 0 ? "hali to'lanmagan qism" : "oldindan to'lovlar ko'proq";
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

    /**
     * Jamining qismlari qisqa ko'rinishda ("753,3 mln so'm") — yaxlitlangandan keyin ham yig'indisi
     * {@link #moneyShort} ko'rsatgan jamiga aniq teng bo'ladi ("eng katta qoldiq" usuli).
     */
    public List<String> moneyShortParts(BigDecimal total, List<BigDecimal> parts, String currency) {
        BigDecimal abs = total == null ? BigDecimal.ZERO : total.abs();
        if (abs.compareTo(BigDecimal.valueOf(1_000_000)) < 0) {
            return parts.stream().map(p -> money(p, currency)).toList();
        }
        // Jami 1 mlrd'dan oshsa ham qismlar mln aniqligida (0,1 mln): "0,1 mlrd" emas, "118,4 mln".
        BigDecimal unit = BigDecimal.valueOf(1_000_000);
        long target = total.divide(unit, 1, RoundingMode.HALF_UP).movePointRight(1).longValueExact();
        double[] exact = parts.stream().mapToDouble(p -> p.divide(unit, MathContext.DECIMAL64).doubleValue() * 10).toArray();
        long[] tenths = allocate(exact, target);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < tenths.length; i++) {
            BigDecimal mln = BigDecimal.valueOf(tenths[i], 1);
            if (tenths[i] == 0 && parts.get(i).signum() == 0) {
                result.add(money(BigDecimal.ZERO, currency));
            } else if (mln.abs().compareTo(BigDecimal.valueOf(1000)) >= 0) {
                // 1 mlrd va undan katta qism — mlrd'da, 2 xona aniqlikda ("1,23 mlrd").
                result.add(new DecimalFormat("#,##0.00", SYMBOLS).format(mln.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP))
                        + " mlrd " + currencyLabel(currency));
            } else {
                result.add(decimal(mln) + " mln " + currencyLabel(currency));
            }
        }
        return result;
    }

    /** Ulushlar foizda ("53,1%") — yig'indisi aniq 100,0% bo'ladi (agar ulushlar yig'indisi 1 bo'lsa). */
    public List<String> pctParts(List<Double> shares) {
        double sum = shares.stream().mapToDouble(Double::doubleValue).sum();
        long[] tenths = allocate(shares.stream().mapToDouble(s -> s * 1000).toArray(), Math.round(sum * 1000));
        List<String> result = new ArrayList<>();
        for (long t : tenths) {
            result.add(new DecimalFormat("0.0", SYMBOLS).format(t / 10.0) + "%");
        }
        return result;
    }

    /** Aniq qiymatlarni butun songa yaxlitlaydi, yig'indisi target bo'lsin: avval pastga, qolganini eng katta kasr qismlarga. */
    static long[] allocate(double[] exact, long target) {
        int n = exact.length;
        long[] result = new long[n];
        long sum = 0;
        for (int i = 0; i < n; i++) {
            result[i] = (long) Math.floor(exact[i]);
            sum += result[i];
        }
        if (n == 0) {
            return result;
        }
        Integer[] byFraction = new Integer[n];
        for (int i = 0; i < n; i++) {
            byFraction[i] = i;
        }
        Arrays.sort(byFraction, Comparator.comparingDouble(i -> -(exact[i] - Math.floor(exact[i]))));
        for (int k = 0; sum < target; k = (k + 1) % n) {
            result[byFraction[k]]++;
            sum++;
        }
        for (int k = n - 1; sum > target; k = (k - 1 + n) % n) {
            if (result[byFraction[k]] > 0) {
                result[byFraction[k]]--;
                sum--;
            }
        }
        return result;
    }

    private static String decimal(BigDecimal v) {
        return new DecimalFormat("#,##0.#", SYMBOLS).format(v);
    }

    private static String sign(double delta) {
        return delta > 0.0005 ? "+" : delta < -0.0005 ? "−" : "";
    }
}
