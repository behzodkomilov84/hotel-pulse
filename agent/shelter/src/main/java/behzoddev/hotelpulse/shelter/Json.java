package behzoddev.hotelpulse.shelter;

import java.util.Collection;
import java.util.Map;

/** Kichik JSON yozuvchi (agent tashqi kutubxonalarsiz bo'lsin). */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                string(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            boolean flat = c.stream().allMatch(x -> x == null || x instanceof String || x instanceof Number || x instanceof Boolean);
            sb.append('[');
            int i = 0;
            for (Object x : c) {
                if (!flat) {
                    sb.append('\n');
                    pad(sb, indent + 1);
                }
                write(sb, x, indent + 1);
                if (++i < c.size()) {
                    sb.append(flat ? ", " : ",");
                }
            }
            if (!flat && !c.isEmpty()) {
                sb.append('\n');
                pad(sb, indent);
            }
            sb.append(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else {
            string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }
}
