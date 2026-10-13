package behzoddev.hotelpulse.shelter;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * UCS Shelter (Firebird) bazasi tuzilishini aniqlash. FAQAT O'QIYDI: ulanish va tranzaksiya read-only, faqat SELECT.
 *
 * <p>Natija — shelter-discovery.json: Firebird versiyasi, jadvallar (ustunlar, turlari, yozuvlar soni,
 * sana ustunlarining oralig'i), view va protseduralar nomlari. Kichik ma'lumotnoma jadvallari (≤ 100 qator) qiymatlari
 * ham yoziladi (xona turlari, to'lov turlari, holatlar) — lekin mehmon/xodim/shaxsga oid jadval va ustunlar
 * (ism, telefon, pasport, manzil, parol...) hech qachon o'qilmaydi. Parol faqat so'raladi, faylga yozilmaydi.
 *
 * <p>Ishga tushirish: run-discover.bat (ichida: --host 172.10.0.2 --db D:\Shelter\BDATA\SHELTER_TEST.FDB --user SYSDBA).
 */
public final class Discover {

    private static final int DICTIONARY_MAX_ROWS = 100;
    private static final Pattern PERSONAL_TABLE = Pattern.compile(
            ".*(GUEST|GOST|CLIENT|KLIENT|PERSON|PASSPORT|PASPORT|CUSTOMER|EMPLOY|SOTRUD|USER|STAFF|CONTACT|PHONE|CARD|VISA|DOC|ANKET|PEOPLE|HUMAN|FIO|MIGR|REGISTR|AUDIT|LOG).*");
    private static final Pattern PERSONAL_COLUMN = Pattern.compile(
            ".*(PHONE|TEL|MAIL|PASS|PWD|DOC|BIRTH|ADDR|ADRES|FIO|SURNAME|FAMIL|LASTNAME|FIRSTNAME|OTCH|PATRON|CARD|LOGIN|INN|PINFL|SERIA|SERIES|NOMER_DOC|IMAGE|PHOTO|BLOB|NOTE|COMMENT|REMARK).*");

    private Discover() {
    }

    /** @return yozilgan fayl yoki null (xato) */
    static Path run(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        String host = a.getOrDefault("host", "localhost");
        String port = a.getOrDefault("port", "3050");
        String db = a.get("db");
        String user = a.getOrDefault("user", "SYSDBA");
        String charset = a.getOrDefault("charset", "WIN1251");
        Path out = Path.of(a.getOrDefault("out", "shelter-discovery.json"));
        if (db == null) {
            System.out.println("Ishlatish: run-discover.bat  (yoki java -jar shelter-agent.jar --host 172.10.0.2 --db D:\\Shelter\\BDATA\\SHELTER.FDB --user SYSDBA)");
            return null;
        }
        char[] password = readPassword("Firebird paroli (" + user + "): ");

        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", new String(password));
        p.setProperty("encoding", charset);
        p.setProperty("defaultIsolation", "TRANSACTION_READ_COMMITTED");
        java.util.Arrays.fill(password, ' ');
        String url = "jdbc:firebird://" + host + ":" + port + "/" + db;

        System.out.println("Ulanmoqda: " + host + ":" + port + " " + db + " ...");
        try (Connection c = DriverManager.getConnection(url, p)) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            p.remove("password");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("generated", LocalDateTime.now().withNano(0).toString());
            result.put("database", db);
            result.put("engineVersion", scalar(c, "select rdb$get_context('SYSTEM', 'ENGINE_VERSION') from rdb$database"));
            result.put("databaseCharset", scalar(c, "select rdb$character_set_name from rdb$database"));

            List<String> tables = list(c, "select trim(rdb$relation_name) from rdb$relations "
                    + "where coalesce(rdb$system_flag, 0) = 0 and rdb$view_blr is null order by 1");
            System.out.println("Jadvallar: " + tables.size());
            List<Map<String, Object>> tableInfo = new ArrayList<>();
            int i = 0;
            for (String t : tables) {
                i++;
                System.out.print("\r  " + i + "/" + tables.size() + " " + t + "                    ");
                tableInfo.add(table(c, t));
            }
            System.out.println();
            result.put("tables", tableInfo);
            result.put("views", list(c, "select trim(rdb$relation_name) from rdb$relations "
                    + "where coalesce(rdb$system_flag, 0) = 0 and rdb$view_blr is not null order by 1"));
            result.put("procedures", list(c, "select trim(rdb$procedure_name) from rdb$procedures "
                    + "where coalesce(rdb$system_flag, 0) = 0 order by 1"));
            c.rollback();

            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                w.write(Json.write(result));
            }
            System.out.println("Tayyor: " + out.toAbsolutePath());
            System.out.println("Shu faylni HotelPulse dasturchisiga yuboring (unda parol va mehmonlar ma'lumoti yo'q).");
            return out;
        } catch (SQLException e) {
            System.out.println();
            System.out.println("XATO: " + e.getMessage());
            System.out.println("Tekshiring: server manzili (host), baza yo'li (db), Firebird foydalanuvchisi va paroli, 3050 port ochiqligi.");
            return null;
        }
    }

    private static Map<String, Object> table(Connection c, String table) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", table);
        List<Map<String, Object>> columns = new ArrayList<>();
        List<String> dateColumns = new ArrayList<>();
        List<String> textColumns = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select trim(rf.rdb$field_name), f.rdb$field_type, f.rdb$field_sub_type, f.rdb$field_length,
                       f.rdb$field_scale, coalesce(rf.rdb$null_flag, 0), f.rdb$character_length
                from rdb$relation_fields rf join rdb$fields f on f.rdb$field_name = rf.rdb$field_source
                where rf.rdb$relation_name = ? order by rf.rdb$field_position""")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String type = typeName(rs.getInt(2), rs.getInt(3), rs.getInt(5), rs.getInt(7));
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", name);
                    col.put("type", type);
                    if (rs.getInt(6) == 1) {
                        col.put("notNull", true);
                    }
                    columns.add(col);
                    if (type.equals("DATE") || type.equals("TIMESTAMP")) {
                        dateColumns.add(name);
                    }
                    if (type.startsWith("VARCHAR") || type.startsWith("CHAR")) {
                        textColumns.add(name);
                    }
                }
            }
        } catch (SQLException e) {
            t.put("error", e.getMessage());
            return t;
        }
        t.put("columns", columns);
        t.put("primaryKey", list(c, "select trim(s.rdb$field_name) from rdb$relation_constraints rc "
                + "join rdb$index_segments s on s.rdb$index_name = rc.rdb$index_name "
                + "where rc.rdb$relation_name = '" + table + "' and rc.rdb$constraint_type = 'PRIMARY KEY' order by s.rdb$field_position"));
        t.put("foreignKeys", list(c, "select trim(s.rdb$field_name) || ' -> ' || trim(rc2.rdb$relation_name) "
                + "from rdb$relation_constraints rc join rdb$ref_constraints ref on ref.rdb$constraint_name = rc.rdb$constraint_name "
                + "join rdb$relation_constraints rc2 on rc2.rdb$constraint_name = ref.rdb$const_name_uq "
                + "join rdb$index_segments s on s.rdb$index_name = rc.rdb$index_name "
                + "where rc.rdb$relation_name = '" + table + "' and rc.rdb$constraint_type = 'FOREIGN KEY'"));

        Long rows = count(c, table);
        t.put("rows", rows);
        Map<String, Object> ranges = new LinkedHashMap<>();
        for (String d : dateColumns) {
            Object[] mm = minMax(c, table, d);
            if (mm != null) {
                ranges.put(d, List.of(String.valueOf(mm[0]), String.valueOf(mm[1])));
            }
        }
        if (!ranges.isEmpty()) {
            t.put("dateRanges", ranges);
        }
        // Kichik ma'lumotnomalar (shaxsga oid bo'lmaganlar) — qiymatlari bilan.
        if (rows != null && rows > 0 && rows <= DICTIONARY_MAX_ROWS && !PERSONAL_TABLE.matcher(table).matches()) {
            List<String> safe = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                String n = (String) col.get("name");
                String type = (String) col.get("type");
                if (!PERSONAL_COLUMN.matcher(n).matches() && !type.startsWith("BLOB")) {
                    safe.add(n);
                }
            }
            if (!safe.isEmpty() && safe.size() <= 12) {
                t.put("values", values(c, table, safe));
            }
        }
        return t;
    }

    private static List<List<String>> values(Connection c, String table, List<String> cols) {
        List<List<String>> rows = new ArrayList<>();
        String sql = "select first " + DICTIONARY_MAX_ROWS + " " + String.join(", ", cols.stream().map(Discover::q).toList())
                + " from " + q(table);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rows.add(cols);
            while (rs.next()) {
                List<String> r = new ArrayList<>();
                for (int i = 1; i <= cols.size(); i++) {
                    String v = rs.getString(i);
                    r.add(v == null ? null : v.trim());
                }
                rows.add(r);
            }
        } catch (SQLException e) {
            rows.add(List.of("error: " + e.getMessage()));
        }
        return rows;
    }

    private static Long count(Connection c, String table) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select count(*) from " + q(table))) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static Object[] minMax(Connection c, String table, String col) {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select min(" + q(col) + "), max(" + q(col) + ") from " + q(table))) {
            return rs.next() && rs.getObject(1) != null ? new Object[]{rs.getObject(1), rs.getObject(2)} : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static String typeName(int type, int subType, int scale, int charLength) {
        return switch (type) {
            case 7 -> scale < 0 ? "NUMERIC(4," + -scale + ")" : "SMALLINT";
            case 8 -> scale < 0 ? "NUMERIC(9," + -scale + ")" : "INTEGER";
            case 16 -> scale < 0 ? "NUMERIC(18," + -scale + ")" : "BIGINT";
            case 10 -> "FLOAT";
            case 27 -> "DOUBLE";
            case 12 -> "DATE";
            case 13 -> "TIME";
            case 35 -> "TIMESTAMP";
            case 14 -> "CHAR(" + charLength + ")";
            case 37 -> "VARCHAR(" + charLength + ")";
            case 261 -> subType == 1 ? "BLOB TEXT" : "BLOB";
            case 23 -> "BOOLEAN";
            default -> "TYPE" + type;
        };
    }

    private static String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static Object scalar(Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) == null ? null : rs.getString(1).trim() : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static List<String> list(Connection c, String sql) {
        List<String> r = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                r.add(rs.getString(1));
            }
        } catch (SQLException e) {
            r.add("error: " + e.getMessage());
        }
        return r;
    }

    static char[] readPassword(String prompt) throws Exception {
        Console console = System.console();
        if (console != null) {
            char[] p = console.readPassword(prompt);
            return p == null ? new char[0] : p;
        }
        System.out.print(prompt);
        String line = new BufferedReader(new InputStreamReader(System.in)).readLine();
        return line == null ? new char[0] : line.toCharArray();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) {
                m.put(args[i].substring(2), args[++i]);
            }
        }
        return m;
    }
}
