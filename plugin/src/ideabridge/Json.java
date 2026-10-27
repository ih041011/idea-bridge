package ideabridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A dependency-free JSON reader and writer.
 *
 * Hand-rolled on purpose: the bridge has to run inside whatever IDE build the user has, and a
 * library that is present in one build can be absent or shadowed in another. A few hundred lines
 * importing nothing beyond java.base is worth more here than a full-featured parser.
 *
 * Mapping: object to LinkedHashMap, array to ArrayList, string to String, true/false to Boolean,
 * integer to Long, real to Double, null to null.
 */
public final class Json {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(512);
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Boolean flag) {
            out.append(flag.booleanValue());
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                out.append("null");
            } else {
                out.append(number);
            }
        } else if (value instanceof Number number) {
            out.append(number.longValue());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map);
        } else if (value instanceof Iterable<?> items) {
            writeArray(out, items);
        } else if (value instanceof Object[] array) {
            writeArray(out, Arrays.asList(array));
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Iterable<?> items) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(out, item);
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        writeUnicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /**
     * Emitted as separate character appends rather than through a format string: the Java lexer
     * processes unicode escapes before it tokenises, so a literal backslash-u in source is a trap
     * that silently becomes the character it names.
     */
    private static void writeUnicodeEscape(StringBuilder out, char c) {
        out.append('\\').append('u');
        out.append(HEX[(c >> 12) & 0xF]);
        out.append(HEX[(c >> 8) & 0xF]);
        out.append(HEX[(c >> 4) & 0xF]);
        out.append(HEX[c & 0xF]);
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private final int length;
        private int position;

        Parser(String text) {
            this.text = text;
            this.length = text.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + position);
        }

        void skipWhitespace() {
            while (position < length) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (position >= length) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(position);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, position)) {
                throw error("expected " + literal);
            }
            position += literal.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (position < length && text.charAt(position) == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (position >= length || text.charAt(position) != '"') {
                    throw error("expected a key string");
                }
                String key = readString();
                skipWhitespace();
                if (position >= length || text.charAt(position) != ':') {
                    throw error("expected ':' after key " + key);
                }
                position++;
                skipWhitespace();
                result.put(key, readValue());
                skipWhitespace();
                if (position >= length) {
                    throw error("unterminated object");
                }
                char c = text.charAt(position);
                if (c == ',') {
                    position++;
                    continue;
                }
                if (c == '}') {
                    position++;
                    return result;
                }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<>();
            position++;
            skipWhitespace();
            if (position < length && text.charAt(position) == ']') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(readValue());
                skipWhitespace();
                if (position >= length) {
                    throw error("unterminated array");
                }
                char c = text.charAt(position);
                if (c == ',') {
                    position++;
                    continue;
                }
                if (c == ']') {
                    position++;
                    return result;
                }
                throw error("expected ',' or ']'");
            }
        }

        private String readString() {
            position++;
            StringBuilder out = new StringBuilder(64);
            while (true) {
                if (position >= length) {
                    throw error("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (position >= length) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (position + 4 > length) {
                            throw error("truncated unicode escape");
                        }
                        String hex = text.substring(position, position + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("bad unicode escape " + hex);
                        }
                        position += 4;
                        break;
                    default:
                        throw error("unsupported escape '\\" + escape + "'");
                }
            }
        }

        private Object readNumber() {
            int start = position;
            if (position < length && text.charAt(position) == '-') {
                position++;
            }
            boolean real = false;
            while (position < length) {
                char c = text.charAt(position);
                if (c >= '0' && c <= '9') {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    real = true;
                    position++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, position);
            try {
                return real ? Double.valueOf(literal) : Long.valueOf(literal);
            } catch (NumberFormatException e) {
                throw error("bad number " + literal);
            }
        }
    }

    // ------------------------------------------------------------------ accessors

    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> put(Map<String, Object> target, String key, Object value) {
        target.put(key, value);
        return target;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    public static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** A blank string counts as absent: a caller sending "" means "I did not set this". */
    public static String strOr(Map<String, Object> source, String key, String fallback) {
        String value = str(source.get(key));
        return value == null || value.isBlank() ? fallback : value;
    }

    public static Integer intOr(Map<String, Object> source, String key, Integer fallback) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean boolOr(Map<String, Object> source, String key, boolean fallback) {
        Object value = source.get(key);
        if (value instanceof Boolean flag) {
            return flag.booleanValue();
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return fallback;
    }

    public static List<Object> listOr(Map<String, Object> source, String key) {
        List<Object> list = asArray(source.get(key));
        return list == null ? List.of() : list;
    }
}