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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Clock clock;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public ShelterImportService(AgentService agents, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.agents = agents;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** Fonda (sinxronlash yakunidan keyin); shu mehmonxona uchun allaqachon ishlayotgan bo'lsa — o'tkazib yuboriladi. */
    public void importAsync(long hotelId) {
        if (!running.add(hotelId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                importNow(hotelId);
            } finally {
                running.remove(hotelId);
            }
        });
    }

    /** @return natija xabari (mehmonxonaning sinxronlash holatiga ham yoziladi) */
    public String importNow(long hotelId) {
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
        return message;
    }

    String rebuild(long hotelId) {
        LocalDate today = LocalDate.now(clock);
        ShelterMapper.Result r = ShelterMapper.map(rows(hotelId, "ROOM_SCHEDULE"), rows(hotelId, "FOLIO_CONTENTS"),
                rows(hotelId, "OPERATIONS"), rows(hotelId, "GUESTS"), rows(hotelId, "ENTERPRISEDEBTOR"),
                rows(hotelId, "ROOMS"), today);
        List<JsonNode> roomKinds = rows(hotelId, "ROOM_KINDS");
        List<JsonNode> rooms = rows(hotelId, "ROOMS");
        if (r.stays().isEmpty()) {
            throw new IllegalStateException("ROOM_SCHEDULE bo'sh — agent hali sinxronlamagan");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from payments where hotel_id = ? and origin = 'SHELTER'", hotelId);
            jdbc.update("delete from bookings where hotel_id = ? and origin = 'SHELTER'", hotelId);
            jdbc.update("delete from service_revenue where hotel_id = ? and external_id like 'sh:%'", hotelId);

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
                + r.payments().size() + " та тўлов; " + r.roomsCount() + " та сотиладиган хона";
        log.info("Shelter import (mehmonxona {}): {}", hotelId, msg);
        return msg;
    }

    private List<JsonNode> rows(long hotelId, String table) {
        List<JsonNode> result = new ArrayList<>();
        for (String line : agents.tableRows(hotelId, table)) {
            result.add(JSON.readTree(line));
        }
        return result;
    }
}
