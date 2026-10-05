package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelyPmsClient;
import behzoddev.hotelpulse.exely.ExelyRawStore;
import behzoddev.hotelpulse.kpi.DebtReport.Category;
import behzoddev.hotelpulse.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Xizmatlar → Hisob-fakturalar: qarzdor yashashlar (Exely PMS qoldig'i) va ularning Exely hisoblari.
 *
 * Diqqat: Exely "/bookings/{raqam}/invoices" — rasmiy (soliq) hisob-faktura EMAS, Exely har bir bron uchun
 * avtomatik ochadigan hisob (счёт, folio): raqami bron raqamidan ("1264938911-01"), to'lovchi va qatorlar.
 * Shuning uchun bu yerda "hisob-faktura yozilganmi" aniqlanmaydi — rasmiy hisob-faktura manbasi
 * (qo'lda belgilash yoki Didox/Faktura.uz) tanlangach qo'shiladi.
 * Hisob yashashga roomStayId bo'yicha bog'lanadi; yashashi ko'rsatilmagan hisob butun bron uchun hisoblanadi.
 */
@Service
@RequiredArgsConstructor
public class InvoiceReportService {

    public static final int PAGE_SIZE = 50;

    private final BookingRepository bookingRepository;
    private final ExelyRawStore raw;
    private final behzoddev.hotelpulse.repository.ServiceRevenueRepository serviceRevenueRepository;
    private final Clock clock;

    /**
     * @param stayKey        yashash kaliti (bookings.external_id) — tafsilot oynasi uchun
     * @param accountNumbers Exely hisoblari (folio) raqamlari; accountTotal — ulardagi qatorlar summasi
     */
    public record Row(Long hotelId, String stayKey, String hotelName, String currency, String bookingNumber, String guestName,
                      String source, LocalDate arrival, LocalDate departure, Category category,
                      BigDecimal total, BigDecimal debt, long ageDays,
                      int accountCount, String accountNumbers, String payer, BigDecimal accountTotal) {
    }

    public record Summary(long count, BigDecimal debt) {
    }

    public record Result(List<Row> rows, Summary summary, long total, int page, int pages) {
    }

    /** Qidiruv va saralash qo'llangan barcha qatorlar (eksport uchun) + umumiy ko'rsatkichlar. */
    @Transactional(readOnly = true)
    public Result report(List<Hotel> hotels, String query, String sort, boolean desc, int page) {
        LocalDate today = LocalDate.now(clock);
        List<Row> all = new ArrayList<>();
        for (Hotel h : hotels) {
            all.addAll(rows(h, today));
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Row> filtered = all.stream()
                .filter(r -> q.isEmpty() || matches(r, q))
                .sorted(comparator(sort, desc))
                .toList();
        BigDecimal debt = filtered.stream().map(Row::debt).reduce(BigDecimal.ZERO, BigDecimal::add);
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), pages);
        return new Result(filtered, new Summary(filtered.size(), debt), filtered.size(), current, pages);
    }

    // ------------------------------------------------------------------ yashash tafsiloti (qarz bosilganda)

    /** @param account qaysi Exely hisobida ("1264192044-35" yoki "1264192044-34 (guruhning umumiy hisobi)"), bo'lmasa — null */
    public record Line(String name, int days, BigDecimal amount, String account) {
    }

    /** @param scope "xonaning o'z hisobi" / "guruhning umumiy hisobi" / "bron hisobi" */
    public record Account(String number, String payer, String scope, BigDecimal total) {
    }

    public record StayDetail(String hotelName, String currency, String bookingNumber, String guestName,
                             LocalDate arrival, LocalDate departure, Category category,
                             List<Line> lines, BigDecimal total, BigDecimal paid, BigDecimal debt,
                             List<Account> accounts, int roomsInBooking) {
    }

    /** Bitta yashash: xizmatlari (qaysi hisobda), narx, to'langan, qarz va Exely hisoblari. */
    @Transactional(readOnly = true)
    public java.util.Optional<StayDetail> detail(Hotel hotel, String stayKey) {
        return bookingRepository.findFirstByHotelIdAndExternalId(hotel.getId(), stayKey).map(b -> {
            String number = DebtService.bookingNumber(b);
            String stay = stayId(b);
            int rooms = bookingRepository.findByHotelIdAndOriginAndExternalIdStartingWith(
                    hotel.getId(), b.getOrigin(), stayKey.substring(0, stayKey.lastIndexOf('#') + 1)).size();
            String generalScope = rooms > 1 ? "guruhning umumiy hisobi" : "bron hisobi";

            // Exely hisoblari: xonaning o'zi va umumiy (xonasi ko'rsatilmagan).
            List<Account> accounts = new ArrayList<>();
            List<BigDecimal> ownItems = new ArrayList<>();
            List<String> own = new ArrayList<>();
            List<String> general = new ArrayList<>();
            String json = raw.payloads(hotel.getId(), ExelyRawStore.INVOICES).get(number);
            if (json != null) {
                for (JsonNode inv : ExelyPmsClient.tree(json)) {
                    String rs = text(inv.get("roomStayId"));
                    if (rs != null && stay != null && !rs.equals(stay)) {
                        continue;
                    }
                    boolean isOwn = rs != null;
                    BigDecimal sum = BigDecimal.ZERO;
                    for (JsonNode item : inv.path("items")) {
                        JsonNode t = item.get("total");
                        if (t != null && t.isNumber()) {
                            sum = sum.add(t.decimalValue());
                            if (isOwn) {
                                ownItems.add(t.decimalValue());
                            }
                        }
                    }
                    String n = text(inv.get("number"));
                    (isOwn ? own : general).add(n == null ? "—" : n);
                    accounts.add(new Account(n == null ? "—" : n, text(inv.path("payer").get("name")),
                            isOwn ? "xonaning o'z hisobi" : generalScope, sum));
                }
            }

            // Xizmatlar (kunlik daromad jadvalidan) — nom bo'yicha jamlanadi.
            Map<String, BigDecimal[]> byName = new java.util.LinkedHashMap<>();
            Long reservationId = stay == null ? null : parseLong(stay);
            if (reservationId != null) {
                for (behzoddev.hotelpulse.entity.ServiceRevenue s
                        : serviceRevenueRepository.findByHotelIdAndReservationIdOrderByServiceDate(hotel.getId(), reservationId)) {
                    BigDecimal[] v = byName.computeIfAbsent(label(s.getKind(), s.getName()),
                            k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    v[0] = v[0].add(BigDecimal.ONE);
                    v[1] = v[1].add(s.getAmount());
                }
            }
            List<Line> lines = new ArrayList<>();
            for (Map.Entry<String, BigDecimal[]> e : byName.entrySet()) {
                BigDecimal amount = e.getValue()[1];
                lines.add(new Line(e.getKey(), e.getValue()[0].intValue(), amount,
                        accountOf(amount, ownItems, own, general, generalScope)));
            }
            BigDecimal total = b.getTotalAmount();
            BigDecimal debt = b.getBalanceDue() == null ? BigDecimal.ZERO : b.getBalanceDue();
            return new StayDetail(hotel.getName(), hotel.getCurrency(), number, b.getGuestName(),
                    b.getArrivalDate(), b.getDepartureDate(), DebtService.pmsCategory(b, LocalDate.now(clock)),
                    lines, total, total.subtract(debt).max(BigDecimal.ZERO), debt, accounts, rooms);
        });
    }

    /**
     * Xizmat qaysi hisobda: xonaning o'z hisobida shu summadagi qator bo'lsa — o'sha hisob; aks holda umumiy hisob
     * (umumiy hisob qatorlarida xona ko'rsatilmaydi — aniq bog'lab bo'lmaydi).
     */
    static String accountOf(BigDecimal amount, List<BigDecimal> ownItems, List<String> own, List<String> general,
                            String generalScope) {
        for (int i = 0; i < ownItems.size(); i++) {
            if (ownItems.get(i).subtract(amount).abs().compareTo(BigDecimal.ONE) <= 0) {
                ownItems.remove(i);
                return String.join(", ", own) + " (xonaning o'z hisobi)";
            }
        }
        if (!general.isEmpty()) {
            return String.join(", ", general) + " (" + generalScope + ")";
        }
        return own.isEmpty() ? null : String.join(", ", own) + " (xonaning o'z hisobi)";
    }

    /** Exely xizmat turi → tushunarli nom (yashash, erta kirish, kech chiqish); qolganlari — Exely nomi. */
    static String label(int kind, String name) {
        return switch (kind) {
            case 0 -> "Yashash";
            case 3 -> "Erta kirish";
            case 4 -> "Kech chiqish";
            default -> {
                String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (n.contains("breakfast") || n.contains("завтрак")) {
                    yield "Nonushta";
                }
                yield name == null || name.isBlank() ? "Xizmat" : name;
            }
        };
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Joriy sahifadagi qatorlar. */
    public static List<Row> pageRows(Result r) {
        int from = (r.page() - 1) * PAGE_SIZE;
        return r.rows().subList(Math.min(from, r.rows().size()), Math.min(from + PAGE_SIZE, r.rows().size()));
    }

    private List<Row> rows(Hotel hotel, LocalDate today) {
        List<Booking> debtors = bookingRepository.findPmsDebtors(hotel.getId(), today);
        if (debtors.isEmpty()) {
            return List.of();
        }
        Map<String, String> accounts = raw.payloads(hotel.getId(), ExelyRawStore.INVOICES);
        List<Row> rows = new ArrayList<>();
        for (Booking b : debtors) {
            String number = DebtService.bookingNumber(b);
            Matched m = match(accounts.get(number), stayId(b));
            Category category = DebtService.pmsCategory(b, today);
            long age = category == Category.IN_HOUSE ? 0 : Math.max(0, ChronoUnit.DAYS.between(b.getDepartureDate(), today));
            rows.add(new Row(hotel.getId(), b.getExternalId(), hotel.getName(), hotel.getCurrency(), number, b.getGuestName(), b.getSource(),
                    b.getArrivalDate(), b.getDepartureDate(), category, b.getTotalAmount(), b.getBalanceDue(), age,
                    m.count, String.join(", ", m.numbers), String.join(", ", m.payers), m.total));
        }
        return rows;
    }

    record Matched(int count, Set<String> numbers, Set<String> payers, BigDecimal total) {
    }

    /** Yashashga tegishli Exely hisoblari (roomStayId mos yoki yashash ko'rsatilmagan). */
    static Matched match(String json, String stayId) {
        int count = 0;
        Set<String> numbers = new LinkedHashSet<>();
        Set<String> payers = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        if (json != null) {
            for (JsonNode inv : ExelyPmsClient.tree(json)) {
                String rs = text(inv.get("roomStayId"));
                if (rs != null && stayId != null && !rs.equals(stayId)) {
                    continue;
                }
                count++;
                String n = text(inv.get("number"));
                if (n != null) {
                    numbers.add(n);
                }
                String payer = text(inv.path("payer").get("name"));
                if (payer != null) {
                    payers.add(payer);
                }
                for (JsonNode item : inv.path("items")) {
                    JsonNode t = item.get("total");
                    if (t != null && t.isNumber()) {
                        total = total.add(t.decimalValue());
                    }
                }
            }
        }
        return new Matched(count, numbers, payers, total);
    }

    /** "pms:{raqam}#{roomStayId}" → roomStayId. */
    static String stayId(Booking b) {
        int hash = b.getExternalId().lastIndexOf('#');
        return hash > 0 ? b.getExternalId().substring(hash + 1) : null;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        String s = n.isValueNode() ? n.asString() : n.toString();
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean matches(Row r, String q) {
        return contains(r.bookingNumber(), q) || contains(r.guestName(), q) || contains(r.payer(), q)
                || contains(r.accountNumbers(), q) || contains(r.hotelName(), q) || contains(r.source(), q);
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    /** Saralash ustunlari. */
    public static final List<String> SORTS = List.of("hotel", "booking", "guest", "payer", "arrival", "departure",
            "category", "debt", "accountTotal", "age");

    static Comparator<Row> comparator(String sort, boolean desc) {
        Comparator<Row> c = switch (sort == null ? "" : sort) {
            case "hotel" -> Comparator.comparing(Row::hotelName, String.CASE_INSENSITIVE_ORDER);
            case "booking" -> Comparator.comparing(Row::bookingNumber);
            case "guest" -> Comparator.comparing(r -> r.guestName() == null ? "" : r.guestName(), String.CASE_INSENSITIVE_ORDER);
            case "payer" -> Comparator.comparing(Row::payer, String.CASE_INSENSITIVE_ORDER);
            case "arrival" -> Comparator.comparing(Row::arrival);
            case "departure" -> Comparator.comparing(Row::departure);
            case "category" -> Comparator.comparing(Row::category);
            case "accountTotal" -> Comparator.comparing(Row::accountTotal);
            case "age" -> Comparator.comparingLong(Row::ageDays);
            default -> Comparator.comparing(Row::debt);
        };
        if (desc) {
            c = c.reversed();
        }
        // Teng qiymatlarda — barqaror tartib.
        return c.thenComparing(Row::hotelName).thenComparing(Row::bookingNumber);
    }
}
