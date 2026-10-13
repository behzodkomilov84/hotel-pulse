package behzoddev.hotelpulse.shelter;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Butun Shelter bazasini (jurnal jadvallarisiz) HotelPulse'ga yuboradi: har bir jadval — gzip JSON Lines,
 * katta jadval — ~6 MB lik qismlarga bo'linadi. FAQAT O'QIYDI (read-only tranzaksiya, faqat SELECT).
 * Ikkilik BLOB ustunlar (rasm, skan) yuborilmaydi; matnli BLOB — matn sifatida.
 */
final class Sync {

    /** Jurnal, sessiya va vaqtinchalik jadvallar — KPI uchun kerak emas (egasi bilan kelishilgan). */
    static final Pattern SKIP = Pattern.compile(
            "^(LOG\\$.*|SYS\\$.*|HISTORY_.*|TMP_.*|RDB\\$.*|MON\\$.*|STATS|LOGS|USER_ACTIONS|XX_.*)$");
    static final int PART_BYTES = 6 * 1024 * 1024;

    private Sync() {
    }

    static void run(String[] args, String server) throws Exception {
        String host = Main.option(args, "host", "localhost");
        String port = Main.option(args, "port", "3050");
        String db = Main.option(args, "db", null);
        String user = Main.option(args, "user", "SYSDBA");
        String charset = Main.option(args, "charset", "WIN1251");
        if (db == null || !Files.exists(Main.KEY_FILE)) {
            System.out.println(db == null ? "Ishlatish: sync --host ... --db ... --user ..." : "Agent ulanmagan (agent.key yo'q) — avval: pair");
            System.exit(1);
        }
        String key = Files.readString(Main.KEY_FILE, StandardCharsets.UTF_8).trim();
        // Rejalashtiruvchidan (sync.ps1) — parol stdin orqali keladi (DPAPI bilan shifrlangan fayldan).
        char[] password;
        String fromEnv = System.getenv("HOTELPULSE_FB_PASSWORD");
        if (java.util.Arrays.asList(args).contains("--password-env") && fromEnv != null) {
            // sync.ps1 — DPAPI'dan ochilgan parol faqat shu jarayon muhitida (Unicode, buyruq satrida ko'rinmaydi).
            password = fromEnv.toCharArray();
        } else if (java.util.Arrays.asList(args).contains("--password-stdin")) {
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            password = line == null ? new char[0] : line.toCharArray();
        } else {
            password = Discover.readPassword("Firebird paroli (" + user + "): ");
        }
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", new String(password));
        p.setProperty("encoding", charset);
        java.util.Arrays.fill(password, ' ');

        String syncId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        int tables = 0;
        long rows = 0;
        List<String> errors = new ArrayList<>();
        HttpClient http = Main.client();
        System.out.println(LocalDateTime.now().withNano(0) + " sinxronlash boshlandi: " + host + ":" + db);
        try (Connection c = DriverManager.getConnection("jdbc:firebird://" + host + ":" + port + "/" + db, p)) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            List<String> names = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "select trim(rdb$relation_name) from rdb$relations where coalesce(rdb$system_flag, 0) = 0 "
                            + "and rdb$view_blr is null order by 1")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            for (String t : names) {
                if (SKIP.matcher(t).matches()) {
                    continue;
                }
                try {
                    rows += table(c, http, server, key, syncId, t);
                    tables++;
                } catch (Exception e) {
                    errors.add(t + ": " + e.getMessage());
                    System.out.println("  XATO " + t + ": " + e.getMessage());
                }
            }
            c.rollback();
        }
        String message = tables + " та жадвал, " + rows + " та қатор, " + (System.currentTimeMillis() - started) / 1000 + " с"
                + (errors.isEmpty() ? "" : "; хатолар: " + errors.size() + " (" + String.join("; ", errors) + ")");
        post(http, server + "/api/agent/sync-done?message=" + enc(message.length() > 450 ? message.substring(0, 450) : message), key, new byte[0]);
        System.out.println(LocalDateTime.now().withNano(0) + " tayyor: " + message);
        if (!errors.isEmpty()) {
            System.exit(7);
        }
    }

    /** @return qatorlar soni */
    private static long table(Connection c, HttpClient http, String server, String key, String syncId, String table) throws Exception {
        List<byte[]> parts = new ArrayList<>();
        List<Integer> partRows = new ArrayList<>();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int inPart = 0;
        long total = 0;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select * from \"" + table.replace("\"", "\"\"") + "\"")) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder line = new StringBuilder();
            while (rs.next()) {
                line.setLength(0);
                line.append('{');
                boolean first = true;
                for (int i = 1; i <= n; i++) {
                    Object v = value(rs, md, i);
                    if (v == null) {
                        continue;
                    }
                    if (!first) {
                        line.append(',');
                    }
                    first = false;
                    Json.string(line, md.getColumnLabel(i));
                    line.append(':');
                    if (v instanceof Number num) {
                        double d = num.doubleValue();
                        line.append(Double.isFinite(d) ? (v instanceof Double || v instanceof Float ? Json.number(d) : v.toString()) : "null");
                    } else {
                        Json.string(line, v.toString());
                    }
                }
                line.append("}\n");
                raw.write(line.toString().getBytes(StandardCharsets.UTF_8));
                inPart++;
                total++;
                if (raw.size() >= PART_BYTES) {
                    parts.add(Main.gzip(raw.toByteArray()));
                    partRows.add(inPart);
                    raw.reset();
                    inPart = 0;
                }
            }
        }
        if (raw.size() > 0 || parts.isEmpty()) {
            parts.add(Main.gzip(raw.toByteArray()));
            partRows.add(inPart);
        }
        for (int i = 0; i < parts.size(); i++) {
            String url = server + "/api/agent/table?name=" + enc(table) + "&part=" + i + "&parts=" + parts.size()
                    + "&rows=" + partRows.get(i) + "&syncId=" + syncId;
            post(http, url, key, parts.get(i));
        }
        System.out.println("  " + table + ": " + total);
        return total;
    }

    private static Object value(ResultSet rs, ResultSetMetaData md, int i) throws SQLException {
        int type = md.getColumnType(i);
        switch (type) {
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                return null;   // rasm, skan va h.k. — yuborilmaydi
            }
            case Types.CLOB, Types.LONGVARCHAR, Types.NCLOB -> {
                String s = rs.getString(i);
                return s;
            }
            default -> {
                Object o = rs.getObject(i);
                if (o instanceof Blob || o instanceof Clob) {
                    return null;
                }
                if (o instanceof String str) {
                    String t = str.strip();
                    return t.isEmpty() ? null : t;
                }
                return o;
            }
        }
    }

    private static void post(HttpClient http, String url, String key, byte[] body) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("X-Agent-Key", key).header("Content-Type", "application/octet-stream")
                        .timeout(Duration.ofMinutes(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
                last = new IllegalStateException("HotelPulse " + r.statusCode() + ": " + r.body());
                if (r.statusCode() == 401 || r.statusCode() == 400) {
                    break;
                }
            } catch (java.io.IOException e) {
                last = e;
            }
            Thread.sleep(5000L * attempt);
        }
        throw last;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
