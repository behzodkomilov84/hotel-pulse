package behzoddev.hotelpulse.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 1C:Otel (konfiguratsiya "Hotel") agenti yuborgan jadvallardan mehmonxonaning bronlari, kunlik daromadi va to'lovlarini
 * qayta quradi (origin = ONEC, har safar to'liq almashtiriladi). Har sinxronlashdan keyin avtomatik ishlaydi.
 *
 * <p>Agent har bir jadvalni 1C XDTO JSON ko'rinishida yuboradi (bir qator — bitta bo'lak: {"#value":{"column":[...],"row":[...]}}).
 * Ikki nusxa: to'liq (tunda) va tezkor — "Recent." prefiksi bilan, oxirgi {@link #RECENT_DAYS} kun. Birlashtirish:
 * tezkor nusxa oralig'idagi qatorlar tezkordan, undan oldingilari to'liqdan.
 *
 * <ul>
 *   <li>Yashashlar — Document.Accommodation (holat: Catalog.AccommodationStatuses belgilari), hali kelmagan bronlar —
 *       Document.Reservation (Catalog.ReservationStatuses: bekor, kelmagan).</li>
 *   <li>Kunlik daromad — AccumulationRegister.Sales: RoomRevenue — yashash, qolgani — xizmatlar (nonushta/ovqat nomidan).</li>
 *   <li>To'lovlar — AccumulationRegister.Payments (SumReceipt − SumExpense).</li>
 *   <li>Xonalar — Catalog.Rooms (virtual, o'chirilgan va foydalanishdan chiqarilganlar sanalmaydi).</li>
 * </ul>
 */
@Slf4j
@Service
public class OneCImportService {

    /** Agentdagi RecentDays bilan bir xil. */
    static final int RECENT_DAYS = 90;
    static final String EMPTY_REF = "00000000-0000-0000-0000-000000000000";
    /** Bundan uzun bron — sana xato kiritilgan (masalan, 2324-yil), olinmaydi. */
    static final int MAX_NIGHTS = 400;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern MEALS = Pattern.compile("(?iu)завтрак|ужин|обед|питан|breakfast|dinner|lunch|meal|board|nonushta|ovqat");

    private final AgentService agents;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean autoImport;

    private final java.util.concurrent.ExecutorService queue = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "onec-import");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> queued = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public OneCImportService(AgentService agents, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                             @org.springframework.beans.factory.annotation.Value("${app.exely.scheduler-enabled:true}") boolean autoImport) {
        this.agents = agents;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.autoImport = autoImport;
    }

    /** Server ishga tushganda (har deploy'dan keyin) — 1C mehmonxonalari yangi kod bilan qayta hisoblanadi. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStartup() {
        if (!autoImport) {
            return;
        }
        jdbc.queryForList("""
                select distinct a.hotel_id from agents a
                where a.kind = '1C' and a.approved_at is not null and a.hotel_id is not null
                  and exists (select 1 from agent_tables t where t.hotel_id = a.hotel_id
                              and t.table_name in ('AccumulationRegister.Sales', 'Recent.AccumulationRegister.Sales'))""",
                Long.class).forEach(this::importAsync);
    }

    /** Navbatga qo'yadi (sinxronlash yakuni); shu mehmonxona navbatda bo'lsa — qo'shmaydi. */
    public boolean importAsync(long hotelId) {
        if (!queued.add(hotelId)) {
            return false;
        }
        queue.submit(() -> {
            try {
                importNow(hotelId);
            } finally {
                queued.remove(hotelId);
            }
        });
        return true;
    }

    /** Darhol (chaqiruvchi oqimda); Shelter importi bilan bir vaqtda ishlamaydi (xotira). */
    public String importNow(long hotelId) {
        ShelterImportService.IMPORT_LOCK.lock();
        String message;
        boolean ok;
        try {
            message = rebuild(hotelId);
            ok = true;
        } catch (RuntimeException e) {
            log.warn("1C import (mehmonxona {}) xatosi", hotelId, e);
            message = "1С маълумотини ўгиришда хато: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " — " + e.getMessage());
            ok = false;
        } finally {
            ShelterImportService.IMPORT_LOCK.unlock();
        }
        jdbc.update("update hotels set exely_last_sync_at = ?, exely_last_sync_ok = ?, exely_last_sync_message = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now(clock)), ok, message.length() > 500 ? message.substring(0, 500) : message, hotelId);
        log.info("1C import (mehmonxona {}): {}", hotelId, message);
        return message;
    }

    // ---------------------------------------------------------------- jadvallarni o'qish

    /** Bitta 1C jadvali qatori: ustun nomi → qiymat (ref — UUID satri, bo'sh ref/sana — null). */
    record Row(Map<String, Object> v) {

        String ref(String f) {
            Object o = v.get(f);
            return o == null ? null : o.toString();
        }

        String str(String f) {
            Object o = v.get(f);
            return o == null ? "" : o.toString();
        }

        BigDecimal num(String f) {
            Object o = v.get(f);
            if (o instanceof BigDecimal b) {
                return b;
            }
            try {
                return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }

        boolean bool(String f) {
            return Boolean.TRUE.equals(v.get(f));
        }

        LocalDateTime dateTime(String f) {
            Object o = v.get(f);
            if (o == null) {
                return null;
            }
            String s = o.toString();
            if (s.length() < 10 || s.startsWith("0001-01-01")) {
                return null;
            }
            try {
                return LocalDateTime.parse(s.length() == 10 ? s + "T00:00:00" : s);
            } catch (RuntimeException e) {
                return null;
            }
        }

        LocalDate date(String f) {
            LocalDateTime d = dateTime(f);
            return d == null ? null : d.toLocalDate();
        }
    }

    /** XDTO JSON bo'lagidan qatorlar (faqat kerakli ustunlar). */
    static void parseChunk(String line, Set<String> fields, Consumer<Row> sink) {
        JsonNode doc = JSON.readTree(line);
        JsonNode v = doc.has("#value") ? doc.get("#value") : doc;
        JsonNode cols = v.path("column");
        List<String> names = new ArrayList<>();
        for (JsonNode c : cols) {
            JsonNode n = c.path("Name");
            names.add(n.isObject() ? n.path("#value").asString() : n.asString());
        }
        for (JsonNode r : v.path("row")) {
            Map<String, Object> m = new HashMap<>();
            int i = 0;
            for (JsonNode cell : r) {
                String name = i < names.size() ? names.get(i) : null;
                i++;
                if (name == null || (fields != null && !fields.contains(name))) {
                    continue;
                }
                m.put(name, value(cell));
            }
            sink.accept(new Row(m));
        }
    }

    static Object value(JsonNode cell) {
        if (cell == null || cell.isNull()) {
            return null;
        }
        JsonNode val = cell.isObject() ? cell.get("#value") : cell;
        if (val == null || val.isNull()) {
            return null;
        }
        if (val.isBoolean()) {
            return val.asBoolean();
        }
        if (val.isNumber()) {
            return val.decimalValue();
        }
        if (val.isString()) {
            String s = val.asString();
            return s.equals(EMPTY_REF) ? null : s;
        }
        return null;   // ichki tuzilmalar kerak emas
    }

    /**
     * Jadval qatorlari: to'liq nusxa + tezkor ("Recent.") nusxa. dateField bo'lsa — tezkor oraliqdagi (since dan) qatorlar
     * tezkordan, oldingilari to'liqdan; bo'lmasa — tezkor nusxa bor bo'lsa u, aks holda to'liq.
     */
    void rows(long hotelId, Map<String, LocalDateTime> uploaded, String table, String dateField, Set<String> fields,
              Consumer<Row> sink) {
        Set<String> f = fields == null ? null : new HashSet<>(fields);
        if (f != null && dateField != null) {
            f.add(dateField);
        }
        LocalDateTime recentAt = uploaded.get("Recent." + table);
        boolean hasFull = uploaded.containsKey(table);
        if (dateField == null) {
            agents.forEachRow(hotelId, recentAt != null ? "Recent." + table : table, line -> parseChunk(line, f, sink));
            return;
        }
        LocalDate since = recentAt == null ? null : recentAt.toLocalDate().minusDays(RECENT_DAYS);
        if (hasFull) {
            agents.forEachRow(hotelId, table, line -> parseChunk(line, f, r -> {
                LocalDate d = r.date(dateField);
                if (since == null || d == null || d.isBefore(since)) {
                    sink.accept(r);
                }
            }));
        }
        if (recentAt != null) {
            agents.forEachRow(hotelId, "Recent." + table, line -> parseChunk(line, f, r -> {
                LocalDate d = r.date(dateField);
                if (!hasFull || d == null || !d.isBefore(since)) {
                    sink.accept(r);
                }
            }));
        }
    }

    /** Ma'lumotnoma: Ref → qator. */
    Map<String, Row> catalog(long hotelId, Map<String, LocalDateTime> uploaded, String name, Set<String> fields) {
        Map<String, Row> m = new HashMap<>();
        Set<String> f = new HashSet<>(fields);
        f.add("Ref");
        rows(hotelId, uploaded, "Catalog." + name, null, f, r -> {
            if (r.ref("Ref") != null) {
                m.put(r.ref("Ref"), r);
            }
        });
        return m;
    }

    // ---------------------------------------------------------------- qayta qurish

    record Stay(List<String> refs, String externalId, String number, String status, String guestName, String source,
                LocalDate arrival, LocalDate departure, int rooms, int guests, BigDecimal total, BigDecimal balance,
                LocalDateTime bookedAt, LocalDateTime cancelledAt, String roomTypeId, String roomId) {
    }

    String rebuild(long hotelId) {
        Map<String, LocalDateTime> uploaded = new HashMap<>();
        agents.tables(hotelId).forEach(t -> uploaded.put(t.name(), t.uploadedAt()));
        if (!uploaded.containsKey("Document.Accommodation") && !uploaded.containsKey("Recent.Document.Accommodation")) {
            throw new IllegalStateException("1С жадваллари ҳали келмаган (Document.Accommodation)");
        }
        LocalDate today = LocalDate.now(clock);
        Set<String> nameFields = Set.of("Description", "DeletionMark");

        Map<String, Row> accStatuses = catalog(hotelId, uploaded, "AccommodationStatuses",
                Set.of("Description", "IsActive", "IsInHouse", "IsCheckIn", "IsCheckOut", "IsRoomChange"));
        Map<String, Row> resStatuses = catalog(hotelId, uploaded, "ReservationStatuses",
                Set.of("Description", "IsActive", "IsCheckIn", "IsNoShow", "IsAnnulation", "IsInWaitingList"));
        Map<String, Row> customers = catalog(hotelId, uploaded, "Customers", nameFields);
        Map<String, Row> sources = catalog(hotelId, uploaded, "SourcesOfBusiness", nameFields);
        Map<String, Row> services = catalog(hotelId, uploaded, "Services", Set.of("Description", "IsRoomRevenue"));
        Map<String, Row> methods = catalog(hotelId, uploaded, "PaymentMethods", nameFields);
        Map<String, Row> roomTypes = catalog(hotelId, uploaded, "RoomTypes", nameFields);
        Map<String, Row> rooms = catalog(hotelId, uploaded, "Rooms",
                Set.of("Description", "DeletionMark", "IsFolder", "IsVirtual", "RoomType", "OperationEndDate"));

        // Savdo (kunlik daromad) — bron bo'yicha jami ham (bron narxi va qarz uchun).
        Map<String, BigDecimal> salesByDoc = new HashMap<>();
        Set<String> roomDocs = new HashSet<>();   // 1C xona sotgan hujjatlar (RoomsRented > 0) — bandlik 1C bilan bir xil
        List<Object[]> revenue = new ArrayList<>();
        LocalDate[] range = {null, null};
        rows(hotelId, uploaded, "AccumulationRegister.Sales", "Period",
                Set.of("Recorder", "LineNumber", "Active", "Service", "ParentDoc", "Sales", "RoomRevenue", "RoomsRented"), r -> {
                    if (!r.bool("Active")) {
                        return;
                    }
                    LocalDate day = r.date("Period");
                    if (day == null) {
                        return;
                    }
                    BigDecimal sales = r.num("Sales"), room = r.num("RoomRevenue");
                    String doc = r.ref("ParentDoc");
                    if (doc != null) {
                        salesByDoc.merge(doc, sales, BigDecimal::add);
                        if (r.num("RoomsRented").signum() > 0) {
                            roomDocs.add(doc);
                        }
                    }
                    Row svc = r.ref("Service") == null ? null : services.get(r.ref("Service"));
                    String svcName = svc == null ? null : cut(svc.str("Description"), 128);
                    String key = r.ref("Recorder") + ":" + r.num("LineNumber").toBigInteger();
                    if (room.signum() != 0) {
                        revenue.add(new Object[]{"oc:" + key, day, 0, svcName, null, room, doc});
                    }
                    BigDecimal rest = sales.subtract(room);
                    if (rest.signum() != 0) {
                        boolean meals = svcName != null && MEALS.matcher(svcName).find();
                        revenue.add(new Object[]{"oc:" + key + ":x", day, 1, svcName, meals ? "Meals" : "Other", rest, doc});
                    }
                    range[0] = range[0] == null || day.isBefore(range[0]) ? day : range[0];
                    range[1] = range[1] == null || day.isAfter(range[1]) ? day : range[1];
                });

        // To'lovlar.
        Map<String, BigDecimal> paidByDoc = new HashMap<>();
        List<Object[]> payments = new ArrayList<>();
        rows(hotelId, uploaded, "AccumulationRegister.Payments", "Period",
                Set.of("Recorder", "LineNumber", "Active", "PaymentMethod", "ParentDoc", "Sum", "SumReceipt", "SumExpense"), r -> {
                    if (!r.bool("Active")) {
                        return;
                    }
                    LocalDateTime at = r.dateTime("Period");
                    if (at == null) {
                        return;
                    }
                    BigDecimal amount = r.num("SumReceipt").subtract(r.num("SumExpense"));
                    if (amount.signum() == 0) {
                        amount = r.num("Sum");
                    }
                    if (amount.signum() == 0) {
                        return;
                    }
                    String doc = r.ref("ParentDoc");
                    if (doc != null) {
                        paidByDoc.merge(doc, amount, BigDecimal::add);
                    }
                    Row m = r.ref("PaymentMethod") == null ? null : methods.get(r.ref("PaymentMethod"));
                    payments.add(new Object[]{"oc:pay:" + r.ref("Recorder") + ":" + r.num("LineNumber").toBigInteger(), doc,
                            amount, m == null ? null : cut(m.str("Description"), 32), at});
                });

        Set<String> stayFields = Set.of("Ref", "DeletionMark", "Number", "Date", "Posted", "Customer", "Agent", "SourceOfBusiness",
                "CheckInDate", "CheckOutDate", "Room", "RoomType", "NumberOfPersons", "NumberOfRooms", "RoomQuantity",
                "GuestFullName", "AccommodationStatus", "ReservationStatus", "Reservation");

        // 1C har bir mehmonga alohida hujjat ochadi (qo'shimcha mehmonda NumberOfRooms = 0): bitta raqam, xona va
        // sanalardagi hujjatlar — bitta bron (xona bir marta sanaladi, mehmonlar qo'shiladi, summa jamlanadi).
        Map<String, List<Row>> accGroups = new java.util.LinkedHashMap<>();
        Set<String> converted = new HashSet<>();
        rows(hotelId, uploaded, "Document.Accommodation", "Date", stayFields, r -> {
            if (!r.bool("Posted") || r.bool("DeletionMark") || r.date("CheckInDate") == null || r.date("CheckOutDate") == null) {
                return;
            }
            if (r.ref("Reservation") != null) {
                converted.add(r.ref("Reservation"));
            }
            accGroups.computeIfAbsent(groupKey(r), k -> new ArrayList<>()).add(r);
        });
        List<Stay> stays = new ArrayList<>();
        for (List<Row> g : accGroups.values()) {
            Row main = main(g);
            LocalDate out = main.date("CheckOutDate");
            String status = accommodationStatus(accStatuses.get(main.ref("AccommodationStatus")), out, today);
            BigDecimal total = sum(g, salesByDoc), due = total.subtract(sum(g, paidByDoc));
            stays.add(stay(g, "acc", status, total, status.equals("CANCELLED") ? null : due.max(BigDecimal.ZERO), roomDocs,
                    customers, sources));
        }

        // Hali kelmagan bronlar (yashashga aylanmaganlari).
        Map<String, List<Row>> resGroups = new java.util.LinkedHashMap<>();
        rows(hotelId, uploaded, "Document.Reservation", "Date", stayFields, r -> {
            if (!r.bool("Posted") || r.bool("DeletionMark") || converted.contains(r.ref("Ref"))
                    || r.date("CheckInDate") == null || r.date("CheckOutDate") == null) {
                return;
            }
            if (ChronoUnit.DAYS.between(r.date("CheckInDate"), r.date("CheckOutDate")) > MAX_NIGHTS) {
                return;   // xato kiritilgan sana (masalan, 2324-yil)
            }
            resGroups.computeIfAbsent(groupKey(r), k -> new ArrayList<>()).add(r);
        });
        for (List<Row> g : resGroups.values()) {
            Row main = main(g);
            String status = reservationStatus(resStatuses.get(main.ref("ReservationStatus")), main.date("CheckInDate"), today);
            if (status == null) {
                continue;   // "Заезд" — yashash hujjati bilan sanaladi
            }
            stays.add(stay(g, "res", status, sum(g, salesByDoc), null, null, customers, sources));
        }

        // Sotiladigan xonalar.
        int roomsCount = 0;
        List<Object[]> raw = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(clock);
        for (Map.Entry<String, Row> e : roomTypes.entrySet()) {
            raw.add(new Object[]{hotelId, "room_type", compact(e.getKey()), JSON.writeValueAsString(Map.of("id", compact(e.getKey()),
                    "name", e.getValue().str("Description"))), Timestamp.valueOf(now)});
        }
        for (Map.Entry<String, Row> e : rooms.entrySet()) {
            Row r = e.getValue();
            if (r.bool("IsFolder") || r.bool("DeletionMark")) {
                continue;
            }
            LocalDate end = r.date("OperationEndDate");
            if (!r.bool("IsVirtual") && (end == null || end.isAfter(today))) {
                roomsCount++;
            }
            raw.add(new Object[]{hotelId, "room", compact(e.getKey()), JSON.writeValueAsString(Map.of("id", compact(e.getKey()),
                    "name", r.str("Description"), "roomTypeId", r.ref("RoomType") == null ? "" : compact(r.ref("RoomType")))), Timestamp.valueOf(now)});
        }

        if (stays.isEmpty()) {
            throw new IllegalStateException("1С'да яшашлар топилмади");
        }
        int finalRoomsCount = roomsCount;
        tx.executeWithoutResult(status -> {
            // Exely Connect ma'lumoti (agar bo'lsa) 1C bilan takrorlanmasin — 1C to'liq manba.
            jdbc.update("delete from payments where hotel_id = ? and origin in ('ONEC', 'EXELY')", hotelId);
            jdbc.update("delete from bookings where hotel_id = ? and origin in ('ONEC', 'EXELY')", hotelId);
            jdbc.update("delete from service_revenue where hotel_id = ? and external_id like 'oc:%'", hotelId);

            List<Object[]> b = new ArrayList<>();
            for (Stay s : stays) {
                b.add(new Object[]{hotelId, s.externalId(), s.source(), s.status(), s.guestName(), Date.valueOf(s.arrival()),
                        Date.valueOf(s.departure()), s.rooms(), s.guests(), s.total(), Timestamp.valueOf(s.bookedAt()),
                        s.cancelledAt() == null ? null : Timestamp.valueOf(s.cancelledAt()), s.balance(), s.roomTypeId(), s.roomId()});
            }
            jdbc.batchUpdate("""
                    insert into bookings (hotel_id, origin, external_id, source, status, guest_name, arrival_date,
                        departure_date, rooms, guests, total_amount, booked_at, cancelled_at, balance_due, currency,
                        room_type_id, room_id)
                    values (?, 'ONEC', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UZS', ?, ?)""", b);

            Map<String, Long> byExternal = new HashMap<>();
            jdbc.query("select external_id, id from bookings where hotel_id = ? and origin = 'ONEC'",
                    rs -> {
                        byExternal.put(rs.getString(1), rs.getLong(2));
                    }, hotelId);
            Map<String, Long> ids = new HashMap<>();   // 1C hujjat Ref → bookings.id
            Map<String, String> numberByRef = new HashMap<>();
            for (Stay s : stays) {
                for (String ref : s.refs()) {
                    ids.put(ref, byExternal.get(s.externalId()));
                    numberByRef.put(ref, s.number());
                }
            }

            List<Object[]> sv = new ArrayList<>();
            for (Object[] x : revenue) {
                String doc = (String) x[6];
                Long reservationId = doc == null ? null : ids.get(doc);
                sv.add(new Object[]{hotelId, x[0], Date.valueOf((LocalDate) x[1]), x[2], x[3], x[4], x[5],
                        doc == null ? null : numberByRef.get(doc), reservationId});
            }
            jdbc.batchUpdate("""
                    insert into service_revenue (hotel_id, external_id, service_date, kind, name, category, amount,
                        currency, booking_number, reservation_id)
                    values (?, ?, ?, ?, ?, ?, ?, 'UZS', ?, ?)""", sv);

            List<Object[]> pv = new ArrayList<>();
            for (Object[] p : payments) {
                String doc = (String) p[1];
                pv.add(new Object[]{hotelId, doc == null ? null : ids.get(doc), p[0], p[2], p[3],
                        Timestamp.valueOf((LocalDateTime) p[4])});
            }
            jdbc.batchUpdate("""
                    insert into payments (hotel_id, booking_id, origin, external_id, amount, method, paid_at)
                    values (?, ?, 'ONEC', ?, ?, ?, ?)""", pv);

            jdbc.update("delete from exely_raw where hotel_id = ? and kind in ('room_type', 'room')", hotelId);
            jdbc.batchUpdate("insert into exely_raw (hotel_id, kind, external_id, payload, fetched_at) values (?, ?, ?, ?, ?)", raw);

            jdbc.update("""
                    update hotels set pms_services_from = ?, pms_services_until = ?,
                        rooms_count = case when rooms_count_manual or ? = 0 then rooms_count else ? end
                    where id = ?""",
                    range[0] == null ? null : Date.valueOf(range[0]), range[1] == null ? null : Date.valueOf(range[1]),
                    finalRoomsCount, finalRoomsCount, hotelId);
        });
        long inHouse = stays.stream().filter(s -> s.status().equals("CHECKED_IN")).count();
        return "1С:Отель: " + stays.size() + " та брон/яшаш (ҳозир " + inHouse + " та яшаяпти), " + revenue.size()
                + " та хизмат қатори, " + payments.size() + " та тўлов; " + finalRoomsCount + " та хона";
    }

    /** Bitta bron: o'sha hujjat raqami, xona va sanalar (har mehmon — alohida hujjat). */
    static String groupKey(Row r) {
        return r.str("Number").trim() + "|" + r.str("Room") + "|" + r.date("CheckInDate") + "|" + r.date("CheckOutDate");
    }

    /** Asosiy mehmon hujjati — xona sanalgani (NumberOfRooms > 0), bo'lmasa birinchisi. */
    static Row main(List<Row> group) {
        return group.stream().filter(r -> r.num("NumberOfRooms").signum() > 0).findFirst().orElse(group.get(0));
    }

    private static BigDecimal sum(List<Row> group, Map<String, BigDecimal> byDoc) {
        BigDecimal s = BigDecimal.ZERO;
        for (Row r : group) {
            s = s.add(byDoc.getOrDefault(r.ref("Ref"), BigDecimal.ZERO));
        }
        return s;
    }

    /**
     * Yashash holati: "Аннуляция" (IsActive yo'q) — bekor; IsInHouse — mehmon xonada (ketish sanasi o'tib ketgan bo'lsa
     * ham, chiqarilmaguncha); qolganlari — ketgan (yoki boshqa xonaga ko'chgan). IsCheckOut bayrog'i "Заезд"da ham bor —
     * unga qaralmaydi.
     */
    static String accommodationStatus(Row st, LocalDate out, LocalDate today) {
        if (st == null) {
            return out.isAfter(today) ? "CHECKED_IN" : "CHECKED_OUT";
        }
        if (!st.bool("IsActive")) {
            return "CANCELLED";
        }
        // Ketish sanasi o'tgan, lekin 1C'da chiqarilmagan (xodim "Выезд" qilmagan) — ketgan deb olinadi.
        return st.bool("IsInHouse") && !out.isBefore(today) ? "CHECKED_IN" : "CHECKED_OUT";
    }

    /**
     * Bron holati; null — "Заезд" (yashash hujjati bilan sanaladi). "Незаезд" bazada IsNoShow'siz bo'lishi mumkin —
     * nomidan ham aniqlanadi.
     */
    static String reservationStatus(Row st, LocalDate in, LocalDate today) {
        if (st == null) {
            return in.isBefore(today) ? "NO_SHOW" : "CONFIRMED";
        }
        if (st.bool("IsCheckIn")) {
            return null;
        }
        String name = lower(st.str("Description"));
        if (st.bool("IsNoShow") || name.contains("незаезд") || name.contains("no show") || name.contains("no-show")) {
            return "NO_SHOW";
        }
        if (st.bool("IsAnnulation") || !st.bool("IsActive") || st.bool("IsInWaitingList")) {
            return "CANCELLED";
        }
        return in.isBefore(today) ? "NO_SHOW" : "CONFIRMED";
    }

    private static Stay stay(List<Row> group, String kind, String status, BigDecimal total, BigDecimal balance, Set<String> roomDocs,
                             Map<String, Row> customers, Map<String, Row> sources) {
        Row r = main(group);
        LocalDate in = r.date("CheckInDate"), out = r.date("CheckOutDate");
        if (!out.isAfter(in)) {
            out = in.plusDays(1);   // kunduzgi yashash — bitta kecha
        }
        int rooms = 0, guests = 0;
        List<String> refs = new ArrayList<>();
        for (Row x : group) {
            rooms += Math.max(0, x.num("NumberOfRooms").intValue());
            guests += Math.max(1, x.num("NumberOfPersons").intValue());
            refs.add(x.ref("Ref"));
        }
        if (roomDocs != null && total.signum() != 0) {
            // Yashash: 1C sotgan xonalar (RoomsRented) — qo'shimcha mehmon va xona sotmagan hujjatlar sanalmaydi.
            rooms = (int) refs.stream().filter(roomDocs::contains).count();
        }
        LocalDateTime booked = group.stream().map(x -> x.dateTime("Date")).filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(in.atStartOfDay());
        String number = r.str("Number").trim().replaceFirst("^0+(?=\\d)", "");
        return new Stay(refs, externalId(kind, number, r.ref("Ref")), number, status,
                blankToNull(cut(r.str("GuestFullName"), 150)), cut(source(r, customers, sources), 64), in, out,
                roomDocs != null ? rooms : Math.max(1, rooms), Math.max(1, guests), total,
                balance == null || balance.signum() == 0 ? null : balance, booked,
                status.equals("CANCELLED") ? booked : null, compact(r.ref("RoomType")), compact(r.ref("Room")));
    }

    /** "oc:{raqam}#{a|r}{ref}" — qarzdorlik sahifasi bron raqamini '#' gacha oladi; ref — takrorlanmaslik uchun. */
    static String externalId(String kind, String number, String ref) {
        return "oc:" + cut(number, 20) + "#" + kind.charAt(0) + compact(ref);
    }

    /** 1C UUID → 32 belgi (bookings.room_type_id / room_id — VARCHAR(32)). */
    static String compact(String ref) {
        return ref == null ? null : ref.replace("-", "");
    }

    /** Manba: agent → mijoz (korxona) → biznes manbai → to'g'ridan-to'g'ri. */
    static String source(Row r, Map<String, Row> customers, Map<String, Row> sources) {
        for (String f : new String[]{"Agent", "Customer"}) {
            Row c = r.ref(f) == null ? null : customers.get(r.ref(f));
            if (c != null && !c.str("Description").isBlank()) {
                return c.str("Description").trim();
            }
        }
        Row s = r.ref("SourceOfBusiness") == null ? null : sources.get(r.ref("SourceOfBusiness"));
        if (s != null && !s.str("Description").isBlank()) {
            return s.str("Description").trim();
        }
        return "Тўғридан-тўғри";
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
