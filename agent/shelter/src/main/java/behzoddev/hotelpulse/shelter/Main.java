package behzoddev.hotelpulse.shelter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * HotelPulse Shelter agenti.
 * <pre>
 *   pair     — HotelPulse'ga ulash: ekranda 6 xonali kod chiqadi, admin sahifada tasdiqlanadi, kalit agent.key'ga yoziladi
 *   discover — baza tuzilishini aniqlash (shelter-discovery.json); ulangan bo'lsa — HotelPulse'ga yuklaydi
 * </pre>
 * Umumiy parametrlar: --server https://hotel-pulse.uz, --hotel "Aydarko'l Resort".
 */
public final class Main {

    static final Path KEY_FILE = Path.of("agent.key");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "discover";
        String[] rest = args.length > 0 && !args[0].startsWith("--") ? Arrays.copyOfRange(args, 1, args.length) : args;
        String server = option(rest, "server", "https://hotel-pulse.uz");
        switch (command) {
            case "pair" -> pair(server, option(rest, "hotel", null));
            case "discover" -> {
                Path out = Discover.run(rest);
                if (out != null && Files.exists(KEY_FILE)) {
                    upload(server, "discovery", out);
                } else if (out != null) {
                    System.out.println("Agent HotelPulse'ga ulanmagan (agent.key yo'q) — avval: pair");
                }
            }
            default -> {
                System.out.println("Buyruqlar: pair | discover");
                System.exit(1);
            }
        }
    }

    // ------------------------------------------------------------------ juftlash

    static void pair(String server, String hotel) throws Exception {
        HttpClient http = client();
        String machine = InetAddress.getLocalHost().getHostName();
        String url = server + "/api/agent/pair?kind=SHELTER&machine=" + enc(machine) + (hotel == null ? "" : "&hotel=" + enc(hotel));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            System.out.println("XATO: HotelPulse javobi " + r.statusCode() + ": " + r.body());
            System.exit(3);
        }
        String pairingId = field(r.body(), "pairingId");
        String code = field(r.body(), "code");
        if (pairingId == null || code == null) {
            System.out.println("XATO: HotelPulse javobi tushunarsiz — server manzilini tekshiring: " + server);
            System.exit(3);
        }
        long expires = Long.parseLong(field(r.body(), "expiresInSeconds"));
        System.out.println();
        System.out.println("  ==========================================");
        System.out.println("     HotelPulse ulash kodi:   " + code);
        System.out.println("  ==========================================");
        System.out.println("  HotelPulse -> Boshqaruv -> Agentlar sahifasida shu agentni tasdiqlang.");
        System.out.println("  Kutilmoqda (" + expires / 60 + " daqiqa)...");
        long until = System.currentTimeMillis() + expires * 1000;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(3000);
            HttpResponse<String> p = http.send(HttpRequest.newBuilder(URI.create(server + "/api/agent/pair/" + pairingId))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            String status = field(p.body(), "status");
            if ("APPROVED".equals(status)) {
                String key = field(p.body(), "key");
                if (key == null) {
                    System.out.println("XATO: kalit allaqachon berilgan — qaytadan pair qiling.");
                    System.exit(4);
                }
                Files.writeString(KEY_FILE, key, StandardCharsets.UTF_8);
                System.out.println("  Tasdiqlandi! Kalit saqlandi: " + KEY_FILE.toAbsolutePath());
                return;
            }
            if ("EXPIRED".equals(status) || "UNKNOWN".equals(status)) {
                break;
            }
        }
        System.out.println("Kod muddati o'tdi — qaytadan pair qiling.");
        System.exit(5);
    }

    // ------------------------------------------------------------------ yuklash

    static void upload(String server, String kind, Path file) throws Exception {
        String key = Files.readString(KEY_FILE, StandardCharsets.UTF_8).trim();
        byte[] gz = gzip(Files.readAllBytes(file));
        String url = server + "/api/agent/upload?kind=" + enc(kind) + "&name=" + enc(file.getFileName().toString());
        HttpResponse<String> r = client().send(HttpRequest.newBuilder(URI.create(url))
                .header("X-Agent-Key", key).header("Content-Type", "application/octet-stream")
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(gz)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) {
            System.out.println("HotelPulse'ga yuklandi: " + file.getFileName() + " (" + gz.length / 1024 + " KB siqilgan)");
        } else {
            System.out.println("XATO: yuklab bo'lmadi (" + r.statusCode() + "): " + r.body());
            System.exit(6);
        }
    }

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ yordamchilar

    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"]*)\"|([0-9]+))").matcher(json == null ? "" : json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : null;
    }

    static String option(String[] args, String name, String def) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--" + name)) {
                return args[i + 1];
            }
        }
        return def;
    }

    static String[] without(String[] args, String... names) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (Arrays.asList(names).contains(args[i].replaceFirst("^--", "")) && i + 1 < args.length) {
                i++;
                continue;
            }
            r.add(args[i]);
        }
        return r.toArray(String[]::new);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
