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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Xizmatlar → Hisob-fakturalar: qarzdor bronlar (Exely PMS qoldig'i) va ularning Exely hisoblari — bron bo'yicha
 * guruhlangan; har bir bron ichida qarzdor xonalari (yashashlar).
 *
 * Diqqat: Exely "/bookings/{raqam}/invoices" — rasmiy (soliq) hisob-faktura EMAS, Exely har bir bron uchun
 * avtomatik ochadigan hisob (счёт, folio): raqami bron raqamidan ("1264938911-01"), to'lovchi va qatorlar.
 * Guruh bronida bitta umumiy hisob barcha xonalarni qamraydi — shuning uchun hisob summasi bron darajasida
 * ko'rsatiladi (xonalarga bo'linmaydi); xona qatorida faqat shu xonaga bog'langan (roomStayId) hisoblar.
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
     * Qarzdor xona (yashash).
     *
     * @param stayKey        yashash kaliti (bookings.external_id) — tafsilot oynasi uchun
     * @param accountNumbers faqat shu xonaga bog'langan Exely hisoblari; accountTotal — ulardagi summa
     */
    public record Row(Long hotelId, String stayKey, String hotelName, String currency, String bookingNumber, String guestName,
                      String source, LocalDate arrival, LocalDate departure, Category category,
                      BigDecimal total, BigDecimal debt, long ageDays,
                      int accountCount, String accountNumbers, String payer, BigDecimal accountTotal) {
    }

    /**
     * Qarzdor bron — xonalari bilan.
     *
     * @param debtorRooms qarzdor xonalar soni; totalRooms — bronning (bekor qilinmagan) jami xonalari
     * @param accountNumbers bronning barcha Exely hisoblari; accountTotal — ulardagi summa (guruh hisobi ham)
     */
    public record BookingRow(Long hotelId, String hotelName, String currency, String bookingNumber, String guestName,
                             String source, LocalDate arrival, LocalDate departure, List<Category> categories,
                             BigDecimal debt, long ageDays, int debtorRooms, int totalRooms, List<Row> stays,
                             int accountCount, String accountNumbers, String payer, BigDecimal accountTotal) {

        public String key() {
            return hotelId + ":" + bookingNumber;
        }
    }

    public record Summary(long bookings, long stays, BigDecimal debt) {
    }

    public record Result(List<BookingRow> rows, Summary summary, long total, int page, int pages) {

        /** Barcha xonalar (eksport — xonalar bo'yicha). */
        public List<Row> stays() {
            return rows.stream().flatMap(b -> b.stays().stream()).toList();
        }
    }

    /** Qidiruv va saralash qo'llangan barcha bronlar (eksport uchun) + umumiy ko'rsatkichlar. */
    @Transactional(readOnly = true)
    public Result report(List<Hotel> hotels, String query, String sort, boolean desc, int page) {
        LocalDate today = LocalDate.now(clock);
        List<BookingRow> all = new ArrayList<>();
        for (Hotel h : hotels) {
            all.addAll(bookings(h, today));
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<BookingRow> filtered = all.stream()
                .filter(b -> q.isEmpty() || matches(b, q))
                .sorted(comparator(sort, desc))
                .toList();
        BigDecimal debt = filtered.stream().map(BookingRow::debt).reduce(BigDecimal.ZERO, BigDecimal::add);
        long stays = filtered.stream().mapToLong(BookingRow::debtorRooms).sum();
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), pages);
        return new Result(filtered, new Summary(filtered.size(), stays, debt), filtered.size(), current, pages);
    }

    /** Joriy sahifadagi bronlar. */
    public static List<BookingRow> pageRows(Result r) {
        int from = (r.page() - 1) * PAGE_SIZE;
        return r.rows().subList(Math.min(from, r.rows().size()), Math.min(from + PAGE_SIZE, r.rows().size()));
    }

    private List<BookingRow> bookings(Hotel hotel, LocalDate today) {
        List<Booking> debtors = bookingRepository.findPmsDebtors(hotel.getId(), today);
        if (debtors.isEmpty()) {
            return List.of();
        }
        Map<String, String> accounts = raw.payloads(hotel.getId(), ExelyRawStore.INVOICES);
        Map<String, Long> roomsByBooking = new java.util.HashMap<>();
        for (Object[] r : bookingRepository.countActiveStaysByBooking(hotel.getId())) {
            roomsByBooking.put(DebtService.bookingNumber((String) r[0]), ((Number) r[1]).longValue());
        }

        // Xonalar — bron raqami bo'yicha guruhlanadi.
        Map<String, List<Row>> byBooking = new LinkedHashMap<>();
        for (Booking b : debtors) {
            String number = DebtService.bookingNumber(b);
            Matched own = match(accounts.get(number), stayId(b), true);
            Category category = DebtService.pmsCategory(b, today);
            long age = category == Category.IN_HOUSE ? 0 : Math.max(0, ChronoUnit.DAYS.between(b.getDepartureDate(), today));
            byBooking.computeIfAbsent(number, k -> new ArrayList<>()).add(new Row(hotel.getId(), b.getExternalId(),
                    hotel.getName(), hotel.getCurrency(), number, b.getGuestName(), b.getSource(),
                    b.getArrivalDate(), b.getDepartureDate(), category, b.getTotalAmount(), b.getBalanceDue(), age,
                    own.count, String.join(", ", own.numbers), String.join(", ", own.payers), own.total));
        }

        List<BookingRow> result = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : byBooking.entrySet()) {
            List<Row> stays = e.getValue().stream()
                    .sorted(Comparator.comparing(Row::debt).reversed().thenComparing(Row::stayKey)).toList();
            Matched all = match(accounts.get(e.getKey()), null, false);
            Row first = stays.get(0);
            Set<Category> categories = new TreeSet<>();
            BigDecimal debt = BigDecimal.ZERO;
            long age = 0;
            LocalDate arrival = first.arrival();
            LocalDate departure = first.departure();
            for (Row r : stays) {
                categories.add(r.category());
                debt = debt.add(r.debt());
                age = Math.max(age, r.ageDays());
                arrival = r.arrival().isBefore(arrival) ? r.arrival() : arrival;
                departure = r.departure().isAfter(departure) ? r.departure() : departure;
            }
            int totalRooms = (int) Math.max(stays.size(), roomsByBooking.getOrDefault(e.getKey(), 0L));
            result.add(new BookingRow(hotel.getId(), hotel.getName(), hotel.getCurrency(), e.getKey(),
                    first.guestName(), first.source(), arrival, departure, List.copyOf(categories), debt, age,
                    stays.size(), totalRooms, stays,
                    all.count, String.join(", ", all.numbers), String.join(", ", all.payers), all.total));
        }
        return result;
    }

    record Matched(int count, Set<String> numbers, Set<String> payers, BigDecimal total) {
    }

    /**
     * Exely hisoblari: stayId null — bronning barcha hisoblari; aks holda — shu xonaga tegishlilari
     * (ownOnly — faqat roomStayId mos kelganlari; aks holda xonasi ko'rsatilmaganlari ham).
     */
    static Matched match(String json, String stayId, boolean ownOnly) {
        int count = 0;
        Set<String> numbers = new LinkedHashSet<>();
        Set<String> payers = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        if (json != null) {
            for (JsonNode inv : ExelyPmsClient.tree(json)) {
                String rs = text(inv.get("roomStayId"));
                if (stayId != null && (rs == null ? ownOnly : !rs.equals(stayId))) {
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
            Map<String, BigDecimal[]> byName = new LinkedHashMap<>();
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

    // ------------------------------------------------------------------ yordamchilar

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static boolean matches(BookingRow b, String q) {
        return contains(b.bookingNumber(), q) || contains(b.payer(), q) || contains(b.accountNumbers(), q)
                || contains(b.hotelName(), q) || contains(b.source(), q)
                || b.stays().stream().anyMatch(r -> contains(r.guestName(), q));
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    /** Saralash ustunlari. */
    public static final List<String> SORTS = List.of("hotel", "booking", "guest", "payer", "arrival", "departure",
            "category", "rooms", "debt", "accountTotal", "age");

    static Comparator<BookingRow> comparator(String sort, boolean desc) {
        Comparator<BookingRow> c = switch (sort == null ? "" : sort) {
            case "hotel" -> Comparator.comparing(BookingRow::hotelName, String.CASE_INSENSITIVE_ORDER);
            case "booking" -> Comparator.comparing(BookingRow::bookingNumber);
            case "guest" -> Comparator.comparing(b -> b.guestName() == null ? "" : b.guestName(), String.CASE_INSENSITIVE_ORDER);
            case "payer" -> Comparator.comparing(BookingRow::payer, String.CASE_INSENSITIVE_ORDER);
            case "arrival" -> Comparator.comparing(BookingRow::arrival);
            case "departure" -> Comparator.comparing(BookingRow::departure);
            case "category" -> Comparator.comparing(b -> b.categories().get(0));
            case "rooms" -> Comparator.comparingInt(BookingRow::debtorRooms);
            case "accountTotal" -> Comparator.comparing(BookingRow::accountTotal);
            case "age" -> Comparator.comparingLong(BookingRow::ageDays);
            default -> Comparator.comparing(BookingRow::debt);
        };
        if (desc) {
            c = c.reversed();
        }
        // Teng qiymatlarda — barqaror tartib.
        return c.thenComparing(BookingRow::hotelName).thenComparing(BookingRow::bookingNumber);
    }
}
