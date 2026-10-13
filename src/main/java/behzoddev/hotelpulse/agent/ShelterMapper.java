package behzoddev.hotelpulse.agent;

import behzoddev.hotelpulse.entity.BookingStatus;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UCS Shelter jadvallari → HotelPulse bronlari, kunlik daromad va to'lovlar (sof o'girish, bazaga yozmaydi).
 *
 * <p>ROOM_SCHEDULE: har bir bron juft yozuvdan iborat — "sarlavha" (STATE 70 — yakka, 72 — guruh; FSTATE — joriy holat)
 * va yashash yozuvi (STATE 50 — kelmagan yakka, 52 — kelmagan guruh, 300 — yashayapti, 400 — chiqib ketgan,
 * 305 — zaselenie bekor qilingan; QUANTCANCEL > 0 — bekor qilingan). Sarlavhalar, 51 (xonasiz so'rov) va 100 (xona bloki)
 * olinmaydi. Sanalar — Delphi TDateTime (1899-12-30 dan beri kunlar). Daromad — FOLIO_CONTENTS'dagi xizmatlar
 * (turistik soliq va bekor qilinganlarsiz), to'lovlar — xizmat bo'lmagan yozuvlar (folio o'tkazmalarisiz).
 */
public final class ShelterMapper {

    static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    /** Yashash: OPERATION_CATEGORIES 101 (yashash, erta kirish, kech chiqish, bron, qo'shimcha joy) va eski 2. */
    static final Set<Integer> ROOM_CATEGORIES = Set.of(101, 2);
    /** Ovqatlanish: nonushta (102) va restoran/bar (1002). */
    static final Set<Integer> MEAL_CATEGORIES = Set.of(102, 1002);
    /** Turistik soliq — daromad emas. */
    static final Set<Integer> TAX_CATEGORIES = Set.of(1004, 1005, 1006);
    /** Folio o'rtasidagi o'tkazmalar — daromad ham, to'lov ham emas. */
    static final Set<Long> TRANSFER_OPERATIONS = Set.of(1000000101L, 1000000102L);

    private ShelterMapper() {
    }

    public record Stay(long id, String externalId, String bookingNumber, BookingStatus status, String guestName,
                       String source, LocalDate arrival, LocalDate departure, int guests, BigDecimal total,
                       BigDecimal balance, LocalDateTime bookedAt, LocalDateTime cancelledAt, String roomTypeId,
                       String roomId, long folio) {
    }

    public record Service(String externalId, LocalDate date, int kind, String name, String category,
                          BigDecimal amount, String bookingNumber, Long reservationId) {
    }

    public record Pay(String externalId, String bookingExternalId, BigDecimal amount, String method, LocalDateTime paidAt) {
    }

    public record Result(List<Stay> stays, List<Service> services, List<Pay> payments, int roomsCount,
                         LocalDate servicesFrom, LocalDate servicesUntil) {
    }

    public static Result map(List<JsonNode> schedule, List<JsonNode> folioContents, List<JsonNode> operations,
                             List<JsonNode> guests, List<JsonNode> debtors, List<JsonNode> rooms, LocalDate today) {
        Map<Long, JsonNode> ops = index(operations);
        Map<Long, JsonNode> guestById = index(guests);
        Map<Long, String> debtorName = new HashMap<>();
        for (JsonNode d : debtors) {
            JsonNode owner = guestById.get(l(d, "OWNER"));
            if (owner != null) {
                debtorName.put(l(d, "ID"), name(owner));
            }
        }

        // Folio bo'yicha xizmatlar va to'lovlar yig'indisi.
        Map<Long, BigDecimal> servicesByFolio = new HashMap<>();
        Map<Long, BigDecimal> paymentsByFolio = new HashMap<>();
        List<JsonNode> serviceRows = new ArrayList<>();
        List<JsonNode> paymentRows = new ArrayList<>();
        for (JsonNode fc : folioContents) {
            // CANCELED=1 qatorlar ham olinadi: Shelter bekor qilishni teskari (manfiy) yozuv bilan qiladi — ikkalasi nolga tushadi.
            if (l(fc, "ENABLED") != 1 || TRANSFER_OPERATIONS.contains(l(fc, "OPERATION"))) {
                continue;
            }
            JsonNode op = ops.get(l(fc, "OPERATION"));
            int category = op == null ? 0 : (int) l(op, "CATEGORY");
            BigDecimal cost = d(fc, "COST");
            long folio = l(fc, "FOLIO");
            if (l(fc, "ISSERVICE") == 1) {
                if (TAX_CATEGORIES.contains(category)) {
                    continue;
                }
                serviceRows.add(fc);
                servicesByFolio.merge(folio, cost, BigDecimal::add);
            } else {
                paymentRows.add(fc);
                paymentsByFolio.merge(folio, cost, BigDecimal::add);
            }
        }

        // Yashashlar.
        List<Stay> stays = new ArrayList<>();
        Map<Long, Stay> stayByFolio = new HashMap<>();
        Set<Long> soldKinds = new HashSet<>();
        for (JsonNode r : schedule) {
            int state = (int) l(r, "STATE");
            if (l(r, "ENABLED") != 1 || !(state == 50 || state == 52 || state == 300 || state == 305 || state == 400)) {
                continue;
            }
            LocalDate arrival = date(r, "ARRIVAL");
            LocalDate departure = date(r, "DEPARTURE");
            if (arrival == null || departure == null) {
                continue;
            }
            if (!departure.isAfter(arrival)) {
                departure = arrival.plusDays(1);   // shu kuni kelib-ketgan — bitta kecha (Shelter ham sutka hisoblaydi)
            }
            BookingStatus status = status(state, l(r, "QUANTCANCEL") > 0, arrival, today);
            if (state == 300 || state == 400) {
                soldKinds.add(l(r, "ROOMKIND"));
            }
            long folio = l(r, "FOLIO");
            BigDecimal total = servicesByFolio.getOrDefault(folio, BigDecimal.ZERO);
            BigDecimal paid = paymentsByFolio.getOrDefault(folio, BigDecimal.ZERO);
            if (total.signum() == 0 && d(r, "COST").signum() > 0 && status != BookingStatus.CHECKED_OUT) {
                total = d(r, "COST").multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(arrival, departure)));
            }
            String number = String.valueOf(l(r, "RESID") > 0 ? l(r, "RESID") : l(r, "ID"));
            JsonNode guest = guestById.get(l(r, "GUEST"));
            Stay s = new Stay(l(r, "ID"), "sh:" + number + "#" + l(r, "ID"), number, status,
                    guest == null ? null : cut(name(guest), 150), cut(source(r, debtorName), 64), arrival, departure,
                    (int) Math.max(1, l(r, "Q_GUESTS") + l(r, "Q_CHILDS")), total,
                    folio > 0 ? total.subtract(paid) : null, dateTime(r, "CREATIONDATE"),
                    status == BookingStatus.CANCELLED ? dateTime(r, "UPDATEDATE") : null,
                    l(r, "ROOMKIND") > 0 ? String.valueOf(l(r, "ROOMKIND")) : null,
                    l(r, "IDROOM") > 0 ? String.valueOf(l(r, "IDROOM")) : null, folio);
            stays.add(s);
            if (folio > 0) {
                stayByFolio.merge(folio, s, (a, b) -> rank(a) >= rank(b) ? a : b);
            }
        }

        // Kunlik xizmatlar.
        List<Service> services = new ArrayList<>();
        LocalDate from = null, until = null;
        for (JsonNode fc : serviceRows) {
            LocalDate day = date(fc, "SHIFTDATE");
            if (day == null) {
                continue;
            }
            JsonNode op = ops.get(l(fc, "OPERATION"));
            int category = op == null ? 0 : (int) l(op, "CATEGORY");
            String code = op == null ? "" : text(op, "CODE");
            int kind = ROOM_CATEGORIES.contains(category) ? ("102".equals(code) ? 3 : "101".equals(code) ? 4 : 0) : 1;
            Stay s = stayByFolio.get(l(fc, "FOLIO"));
            services.add(new Service("sh:fc:" + l(fc, "ID"), day, kind, op == null ? null : cut(text(op, "NAME"), 128),
                    MEAL_CATEGORIES.contains(category) ? "Meals" : kind == 1 ? "Other" : null, d(fc, "COST"),
                    s == null ? null : s.bookingNumber(), s == null ? null : s.id()));
            from = from == null || day.isBefore(from) ? day : from;
            until = until == null || day.isAfter(until) ? day : until;
        }

        // To'lovlar.
        List<Pay> payments = new ArrayList<>();
        for (JsonNode fc : paymentRows) {
            LocalDateTime at = dateTime(fc, "CREATIONDATE");
            if (at == null) {
                continue;
            }
            JsonNode op = ops.get(l(fc, "OPERATION"));
            Stay s = stayByFolio.get(l(fc, "FOLIO"));
            payments.add(new Pay("sh:pay:" + l(fc, "ID"), s == null ? null : s.externalId(), d(fc, "COST"),
                    op == null ? null : cut(text(op, "NAME"), 32), at));
        }

        int roomsCount = 0;
        for (JsonNode room : rooms) {
            if (l(room, "ENABLED") == 1 && soldKinds.contains(l(room, "ROOMKIND"))) {
                roomsCount++;
            }
        }
        return new Result(stays, services, payments, roomsCount, from, until);
    }

    static BookingStatus status(int state, boolean cancelled, LocalDate arrival, LocalDate today) {
        return switch (state) {
            case 300 -> BookingStatus.CHECKED_IN;
            case 400 -> BookingStatus.CHECKED_OUT;
            case 305 -> BookingStatus.CANCELLED;
            default -> cancelled ? BookingStatus.CANCELLED
                    : arrival.isBefore(today) ? BookingStatus.NO_SHOW : BookingStatus.CONFIRMED;
        };
    }

    /** Bir folio bir nechta yozuvda bo'lsa — haqiqiy yashash ustun. */
    private static int rank(Stay s) {
        return switch (s.status()) {
            case CHECKED_IN, CHECKED_OUT -> 3;
            case CONFIRMED -> 2;
            case NO_SHOW -> 1;
            default -> 0;
        };
    }

    /** Manba: sayohat agentligi → agent → to'lovchi korxona → to'g'ridan-to'g'ri. */
    static String source(JsonNode r, Map<Long, String> debtorName) {
        for (String f : new String[]{"TRAVELAGENT", "AGENTREF", "DEBTORREF", "PAYER"}) {
            String n = debtorName.get(l(r, f));
            if (n != null && !n.isBlank()) {
                return n;
            }
        }
        return "Тўғридан-тўғри";
    }

    static String name(JsonNode g) {
        String full = text(g, "FULLNAME");
        if (!full.isBlank()) {
            return full;
        }
        return String.join(" ", List.of(text(g, "NAME"), text(g, "NAME1"), text(g, "NAME2"))).trim().replaceAll("\\s+", " ");
    }

    static LocalDate date(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isNumber() || v.asDouble() < 1) {
            return null;
        }
        return EPOCH.plusDays((long) Math.floor(v.asDouble()));
    }

    static LocalDateTime dateTime(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isNumber() || v.asDouble() < 1) {
            return null;
        }
        double x = v.asDouble();
        long days = (long) Math.floor(x);
        long seconds = Math.round((x - days) * 86_400);
        return EPOCH.plusDays(days).atStartOfDay().plusSeconds(seconds);
    }

    private static Map<Long, JsonNode> index(List<JsonNode> rows) {
        Map<Long, JsonNode> m = new HashMap<>();
        for (JsonNode r : rows) {
            m.put(l(r, "ID"), r);
        }
        return m;
    }

    static long l(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || !v.isNumber() ? 0 : v.asLong();
    }

    static BigDecimal d(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || !v.isNumber() ? BigDecimal.ZERO : v.decimalValue();
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? "" : v.asString().trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
