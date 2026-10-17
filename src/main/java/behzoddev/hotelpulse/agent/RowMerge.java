package behzoddev.hotelpulse.agent;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Agent importlari bazaga o'zgarishlarnigina yozadi: yangi qator qo'shiladi, o'zgargani yangilanadi (id saqlanadi),
 * o'zgarmaganiga tegilmaydi. Yangi ma'lumotda yo'q qator faqat {@code mayDelete} rozi bo'lsa o'chiriladi (manba uni aniq
 * olib tashlagan) va bir martada juda ko'p o'chirish bo'lsa — umuman o'chirilmaydi (chala/xato ma'lumotdan himoya).
 */
final class RowMerge {

    /** Bir importda shundan ko'p (va {@link #MAX_DELETE_SHARE} ulushdan ko'p) o'chirish — shubhali, bajarilmaydi. */
    static final int MAX_DELETE_ROWS = 20;
    static final double MAX_DELETE_SHARE = 0.05;

    /** Natija: qo'shildi, yangilandi, o'chirildi; o'chirish to'xtatilgan bo'lsa — nechta o'chirilmay qoldi. */
    record Result(int inserted, int updated, int deleted, int deleteSkipped) {

        Result plus(Result o) {
            return new Result(inserted + o.inserted, updated + o.updated, deleted + o.deleted, deleteSkipped + o.deleteSkipped);
        }

        static final Result NONE = new Result(0, 0, 0, 0);
    }

    private RowMerge() {
    }

    /**
     * @param select     {@code id, external_id, ...qiymatlar} (hotel_id = ? sharti bilan)
     * @param fresh      {@code [external_id, ...qiymatlar]} — select'dagi qiymatlar tartibida
     * @param insertSql  {@code (hotel_id, external_id, ...qiymatlar)}
     * @param updateSql  {@code set ...qiymatlar where id = ?}
     * @param deleteSql  {@code where id = ?}
     * @param mayDelete  yangi ma'lumotda yo'q qatorni (external_id) o'chirishga dalil bormi
     * @param guarded    juda ko'p o'chirishni to'xtatish (to'liq nusxadan "yo'qolgan" qatorlar uchun)
     */
    static Result merge(JdbcTemplate jdbc, long hotelId, String select, List<Object[]> fresh, String insertSql,
                        String updateSql, String deleteSql, Predicate<String> mayDelete, boolean guarded) {
        Map<String, Long> ids = new HashMap<>();
        Map<String, String> old = new HashMap<>();
        jdbc.query(select, rs -> {
            int n = rs.getMetaData().getColumnCount();
            Object[] values = new Object[n - 2];
            for (int i = 3; i <= n; i++) {
                values[i - 3] = rs.getObject(i);
            }
            ids.put(rs.getString(2), rs.getLong(1));
            old.put(rs.getString(2), signature(values));
        }, hotelId);
        List<Object[]> insert = new ArrayList<>(), update = new ArrayList<>(), delete = new ArrayList<>();
        Set<String> freshIds = new HashSet<>();
        for (Object[] row : fresh) {
            String ext = (String) row[0];
            if (!freshIds.add(ext)) {
                continue;   // manbada takror kalit — birinchisi olinadi
            }
            Object[] values = Arrays.copyOfRange(row, 1, row.length);
            Long id = ids.get(ext);
            if (id == null) {
                insert.add(concat(new Object[]{hotelId}, row));
            } else if (!signature(values).equals(old.get(ext))) {
                update.add(concat(values, new Object[]{id}));
            }
        }
        ids.forEach((ext, id) -> {
            if (!freshIds.contains(ext) && mayDelete.test(ext)) {
                delete.add(new Object[]{id});
            }
        });
        int skipped = 0;
        if (guarded && delete.size() > MAX_DELETE_ROWS && delete.size() > ids.size() * MAX_DELETE_SHARE) {
            skipped = delete.size();
            delete.clear();
        }
        jdbc.batchUpdate(insertSql, insert);
        jdbc.batchUpdate(updateSql, update);
        jdbc.batchUpdate(deleteSql, delete);
        return new Result(insert.size(), update.size(), delete.size(), skipped);
    }

    /** Taqqoslash kaliti: bazadan o'qilgan va yangi qiymat bir xil ko'rinishda. */
    static String signature(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(norm(v)).append('\u0001');
        }
        return sb.toString();
    }

    private static String norm(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal b) {
            return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toString();
        }
        if (v instanceof LocalDateTime t) {
            return t.toString();
        }
        if (v instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof Number n) {
            return String.valueOf(n.longValue());
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "0";
        }
        return v.toString();
    }

    static Object[] concat(Object[] a, Object[] b) {
        Object[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
