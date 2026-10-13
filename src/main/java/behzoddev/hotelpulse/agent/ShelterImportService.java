package behzoddev.hotelpulse.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent yuborgan UCS Shelter jadvallaridan (agent_tables) mehmonxonaning bronlari, kunlik daromadi va to'lovlarini
 * qayta quradi (origin = SHELTER, har safar to'liq almashtiriladi). Har sinxronlashdan keyin avtomatik ishlaydi.
 * Xona turlari va xonalar nomlari hisobotlar uchun exely_raw'ga (room_type / room) yoziladi.
 */
@Slf4j
@Service
public class ShelterImportService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentService agents;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShelterVerifyService verifier;
    private final Clock clock;
    private final boolean autoImport;

    /** Barcha importlar bitta navbatda (xotira bir vaqtda faqat bitta import uchun sarflanadi). */
    private final java.util.concurrent.ExecutorService queue = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shelter-import");
        t.setDaemon(true);
        return t;
    });
    /** Mehmonxona → holat: navbatda (since = null) yoki hisoblanmoqda (since — boshlangan vaqt). */
    private final Map<Long, Status> status = new java.util.concurrent.ConcurrentHashMap<>();

    /** @param since hisoblash boshlangan vaqt; null — navbatda kutmoqda */
    public record Status(LocalDateTime queuedAt, LocalDateTime since) {

        public boolean running() {
            return since != null;
        }
    }

    public ShelterImportService(AgentService agents, JdbcTemplate jdbc, TransactionTemplate tx, ShelterVerifyService verifier, Clock clock,
                                @org.springframework.beans.factory.annotation.Value("${app.exely.scheduler-enabled:true}") boolean autoImport) {
        this.agents = agents;
        this.jdbc = jdbc;
        this.tx = tx;
        this.verifier = verifier;
        this.clock = clock;
        this.autoImport = autoImport;
    }

    /** Server ishga tushganda (har deploy'dan keyin) — Shelter mehmonxonalari yangi kod bilan qayta hisoblanadi. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStartup() {
        if (!autoImport) {
            return;
        }
        List<Long> hotels = jdbc.queryForList("""
                select distinct a.hotel_id from agents a
                where a.kind = 'SHELTER' and a.approved_at is not null and a.hotel_id is not null
                  and exists (select 1 from agent_tables t where t.hotel_id = a.hotel_id and t.table_name = 'ROOM_SCHEDULE')""",
                Long.class);
        hotels.forEach(this::importAsync);
        if (!hotels.isEmpty()) {
            log.info("Shelter import navbatga qo'yildi (ishga tushish): {}", hotels);
        }
    }

    /** Navbatga qo'yadi (sinxronlash yakuni, tugma, ishga tushish); shu mehmonxona navbatda/ishlayotgan bo'lsa — qo'shmaydi. */
    public boolean importAsync(long hotelId) {
        if (status.putIfAbsent(hotelId, new Status(LocalDateTime.now(clock), null)) != null) {
            return false;
        }
        queue.submit(() -> {
            try {
                status.put(hotelId, new Status(status.get(hotelId).queuedAt(), LocalDateTime.now(clock)));
                doImport(hotelId);
            } finally {
                status.remove(hotelId);
            }
        });
        return true;
    }

    /** Joriy holat (navbatda / hisoblanmoqda) yoki null. */
    public Status status(long hotelId) {
        return status.get(hotelId);
    }

    /** Darhol (chaqiruvchi oqimda) — testlar uchun; navbatdagi import bilan bir vaqtda ishlamaydi. */
    public String importNow(long hotelId) {
        IMPORT_LOCK.lock();
        try {
            return doImport(hotelId);
        } finally {
            IMPORT_LOCK.unlock();
        }
    }

    private static final java.util.concurrent.locks.ReentrantLock IMPORT_LOCK = new java.util.concurrent.locks.ReentrantLock();

    private String doImport(long hotelId) {
        IMPORT_LOCK.lock();
        try {
            return doImportLocked(hotelId);
        } finally {
            IMPORT_LOCK.unlock();
        }
    }

    private String doImportLocked(long hotelId) {
        String message;
        boolean ok;
        try {
            message = rebuild(hotelId);
            ok = true;
        } catch (RuntimeException e) {
            log.warn("Shelter import (mehmonxona {}) xatosi", hotelId, e);
            message = "Shelter маълумотини ўгиришда хато: " + e.getClass().getSimpleName();
            ok = false;
        }
        jdbc.update("update hotels set exely_last_sync_at = ?, exely_last_sync_ok = ?, exely_last_sync_message = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now(clock)), ok, message.length() > 500 ? message.substring(0, 500) : message, hotelId);
        if (ok) {
            var report = verifier.verify(hotelId);   // Shelter'ning o'z yig'indilari bilan solishtirish
            log.info("Shelter solishtirish (mehmonxona {}): {}", hotelId, report.ok() ? "mos" : "farq bor");
        }
        return message;
    }

    String rebuild(long hotelId) {
        LocalDate today = LocalDate.now(clock);
        // Xotira tejash: har qatordan faqat kerakli ustunlar; mehmonlardan — faqat bron/korxonalarda uchraydiganlari.
        List<JsonNode> schedule = rows(hotelId, "ROOM_SCHEDULE", SCHEDULE_FIELDS, null);
        List<JsonNode> debtors = rows(hotelId, "ENTERPRISEDEBTOR", Set.of("ID", "OWNER"), null);
        List<JsonNode> folios = rows(hotelId, "FOLIO", Set.of("ID", "BALANCE", "KIND", "OWNER", "CREATIONDATE"), null);
        Set<Long> guestIds = new java.util.HashSet<>();
        schedule.forEach(n -> guestIds.add(ShelterMapper.l(n, "GUEST")));
        debtors.forEach(n -> guestIds.add(ShelterMapper.l(n, "OWNER")));
        folios.forEach(n -> {
            if (ShelterMapper.l(n, "KIND") == 1) {
                guestIds.add(ShelterMapper.l(n, "OWNER"));
            }
        });
        List<JsonNode> guests = rows(hotelId, "GUESTS", Set.of("ID", "FULLNAME", "NAME", "NAME1", "NAME2"), guestIds);
        List<JsonNode> rooms = rows(hotelId, "ROOMS", Set.of("ID", "ROOMKIND", "ENABLED", "NUMBER", "NAME"), null);
        List<JsonNode> roomKinds = rows(hotelId, "ROOM_KINDS", Set.of("ID", "NAME"), null);
        ShelterMapper.Result r = ShelterMapper.map(schedule,
                rows(hotelId, "FOLIO_CONTENTS", FOLIO_FIELDS, null),
                rows(hotelId, "OPERATIONS", Set.of("ID", "CODE", "NAME", "CATEGORY", "ISSERVICE"), null),
                guests, debtors, rooms, folios, today);
        schedule = null;
        guests = null;
        if (r.stays().isEmpty()) {
            throw new IllegalStateException("ROOM_SCHEDULE bo'sh — agent hali sinxronlamagan");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from payments where hotel_id = ? and origin = 'SHELTER'", hotelId);
            jdbc.update("delete from bookings where hotel_id = ? and origin = 'SHELTER'", hotelId);
            jdbc.update("delete from service_revenue where hotel_id = ? and external_id like 'sh:%'", hotelId);
            jdbc.update("delete from company_debts where hotel_id = ?", hotelId);
            List<Object[]> cd = new ArrayList<>();
            for (ShelterMapper.CompanyDebt c : r.companyDebts()) {
                List<Map<String, Object>> stays = new ArrayList<>();
                for (ShelterMapper.CompanyStay s : c.stays()) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("room", s.room());
                    m.put("guest", s.guest());
                    m.put("arrival", s.arrival() == null ? null : s.arrival().toString());
                    m.put("departure", s.departure() == null ? null : s.departure().toString());
                    m.put("guests", s.guests());
                    m.put("amount", s.amount());
                    stays.add(m);
                }
                cd.add(new Object[]{hotelId, c.externalId(), c.name(), c.amount(),
                        c.openedOn() == null ? null : Date.valueOf(c.openedOn()), Timestamp.valueOf(now),
                        c.services(), c.paid(), c.from() == null ? null : Date.valueOf(c.from()),
                        c.to() == null ? null : Date.valueOf(c.to()), c.rooms(), c.guests(), JSON.writeValueAsString(stays)});
            }
            jdbc.batchUpdate("""
                    insert into company_debts (hotel_id, external_id, name, amount, opened_on, updated_at,
                        services, paid, period_from, period_to, rooms, guests, stays_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", cd);

            List<Object[]> b = new ArrayList<>();
            for (ShelterMapper.Stay s : r.stays()) {
                LocalDateTime booked = s.bookedAt() != null ? s.bookedAt() : s.arrival().atStartOfDay();
                b.add(new Object[]{hotelId, s.externalId(), s.source(), s.status().name(), s.guestName(),
                        Date.valueOf(s.arrival()), Date.valueOf(s.departure()), s.guests(), s.total(),
                        Timestamp.valueOf(booked), s.cancelledAt() == null ? null : Timestamp.valueOf(s.cancelledAt()),
                        s.balance(), s.roomTypeId(), s.roomId()});
            }
            jdbc.batchUpdate("""
                    insert into bookings (hotel_id, origin, external_id, source, status, guest_name, arrival_date,
                        departure_date, rooms, guests, total_amount, booked_at, cancelled_at, balance_due, currency,
                        room_type_id, room_id)
                    values (?, 'SHELTER', ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 'UZS', ?, ?)""", b);

            Map<String, Long> ids = new HashMap<>();
            jdbc.query("select external_id, id from bookings where hotel_id = ? and origin = 'SHELTER'",
                    rs -> {
                        ids.put(rs.getString(1), rs.getLong(2));
                    }, hotelId);

            List<Object[]> sv = new ArrayList<>();
            for (ShelterMapper.Service s : r.services()) {
                sv.add(new Object[]{hotelId, s.externalId(), Date.valueOf(s.date()), s.kind(), s.name(), s.category(),
                        s.amount(), s.bookingNumber(), s.reservationId()});
            }
            jdbc.batchUpdate("""
                    insert into service_revenue (hotel_id, external_id, service_date, kind, name, category, amount,
                        currency, booking_number, reservation_id)
                    values (?, ?, ?, ?, ?, ?, ?, 'UZS', ?, ?)""", sv);

            List<Object[]> pv = new ArrayList<>();
            for (ShelterMapper.Pay p : r.payments()) {
                pv.add(new Object[]{hotelId, p.bookingExternalId() == null ? null : ids.get(p.bookingExternalId()),
                        p.externalId(), p.amount(), p.method(), Timestamp.valueOf(p.paidAt())});
            }
            jdbc.batchUpdate("""
                    insert into payments (hotel_id, booking_id, origin, external_id, amount, method, paid_at)
                    values (?, ?, 'SHELTER', ?, ?, ?, ?)""", pv);

            // Hisobotlarda xona turi va xona nomlari (Exely bilan bir xil joydan o'qiladi).
            jdbc.update("delete from exely_raw where hotel_id = ? and kind in ('room_type', 'room')", hotelId);
            List<Object[]> raw = new ArrayList<>();
            for (JsonNode k : roomKinds) {
                raw.add(new Object[]{hotelId, "room_type", String.valueOf(ShelterMapper.l(k, "ID")),
                        JSON.writeValueAsString(Map.of("id", String.valueOf(ShelterMapper.l(k, "ID")),
                                "name", ShelterMapper.text(k, "NAME"))), Timestamp.valueOf(now)});
            }
            for (JsonNode room : rooms) {
                String number = ShelterMapper.text(room, "NUMBER");
                raw.add(new Object[]{hotelId, "room", String.valueOf(ShelterMapper.l(room, "ID")),
                        JSON.writeValueAsString(Map.of("id", String.valueOf(ShelterMapper.l(room, "ID")),
                                "name", number.isEmpty() ? ShelterMapper.text(room, "NAME") : number,
                                "roomTypeId", String.valueOf(ShelterMapper.l(room, "ROOMKIND")))), Timestamp.valueOf(now)});
            }
            jdbc.batchUpdate("insert into exely_raw (hotel_id, kind, external_id, payload, fetched_at) values (?, ?, ?, ?, ?)", raw);

            jdbc.update("""
                    update hotels set pms_services_from = ?, pms_services_until = ?,
                        rooms_count = case when rooms_count_manual or ? = 0 then rooms_count else ? end
                    where id = ?""",
                    r.servicesFrom() == null ? null : Date.valueOf(r.servicesFrom()),
                    r.servicesUntil() == null ? null : Date.valueOf(r.servicesUntil()),
                    r.roomsCount(), r.roomsCount(), hotelId);
        });
        String msg = "UCS Shelter: " + r.stays().size() + " та яшаш, " + r.services().size() + " та хизмат, "
                + r.payments().size() + " та тўлов; " + r.roomsCount() + " та сотиладиган хона; "
                + r.companyDebts().size() + " та корхона ҳисобида қарз";
        log.info("Shelter import (mehmonxona {}): {}", hotelId, msg);
        return msg;
    }

    static final Set<String> SCHEDULE_FIELDS = Set.of("ID", "RESID", "STATE", "ENABLED", "ARRIVAL", "DEPARTURE", "GUEST",
            "Q_GUESTS", "Q_CHILDS", "FOLIO", "ROOMKIND", "IDROOM", "TRAVELAGENT", "AGENTREF", "DEBTORREF", "PAYER",
            "CREATIONDATE", "UPDATEDATE", "QUANTCANCEL", "COST", "IDPAR");
    static final Set<String> FOLIO_FIELDS = Set.of("ID", "FOLIO", "OPERATION", "ISSERVICE", "ENABLED", "COST", "COSTALL", "CLAIM",
            "SHIFTDATE", "CREATIONDATE");

    /**
     * Jadvalni oqim bilan o'qiydi va har qatordan faqat {@code fields} ustunlarni qoldiradi.
     *
     * @param onlyIds null bo'lmasa — faqat shu ID'li qatorlar
     */
    private List<JsonNode> rows(long hotelId, String table, Set<String> fields, Set<Long> onlyIds) {
        List<JsonNode> result = new ArrayList<>();
        agents.forEachRow(hotelId, table, line -> {
            JsonNode full = JSON.readTree(line);
            if (onlyIds != null && !onlyIds.contains(ShelterMapper.l(full, "ID"))) {
                return;
            }
            result.add(project(full, fields));
        });
        return result;
    }

    /** Qatordan faqat {@code fields} ustunlar (null'lar tashlanadi). */
    static JsonNode project(JsonNode full, Set<String> fields) {
        tools.jackson.databind.node.ObjectNode slim = JSON.createObjectNode();
        for (String f : fields) {
            JsonNode v = full.get(f);
            if (v != null && !v.isNull()) {
                slim.set(f, v);
            }
        }
        return slim;
    }
}
