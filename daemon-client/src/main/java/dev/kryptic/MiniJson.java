package dev.kryptic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately tiny JSON reader/writer so the SDK stays zero-dependency.
 * Supports exactly what daemon/PROTOCOL.md needs: objects, arrays, strings,
 * numbers, booleans and null.
 */
final class MiniJson {

    private MiniJson() {
    }

    // ---------- writing ----------

    static String writeObject(Map<String, Object> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(out, entry.getKey());
            out.append(':');
            Object value = entry.getValue();
            if (value instanceof String s) writeString(out, s);
            else out.append(value);
        }
        return out.append('}').toString();
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    // ---------- reading ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        if (!(value instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            if (c == '{') return parseObjectValue();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return parseNumber();
        }

        private Map<String, Object> parseObjectValue() {
            Map<String, Object> result = new LinkedHashMap<>();
            position++; // {
            skipWhitespace();
            if (peek() == '}') { position++; return result; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (text.charAt(position++) != ':') throw new IllegalArgumentException("expected ':'");
                result.put(key, parseValue());
                skipWhitespace();
                char c = text.charAt(position++);
                if (c == '}') return result;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            position++; // [
            skipWhitespace();
            if (peek() == ']') { position++; return result; }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char c = text.charAt(position++);
                if (c == ']') return result;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        private String parseString() {
            if (text.charAt(position++) != '"') throw new IllegalArgumentException("expected '\"'");
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = text.charAt(position++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape: \\" + escaped);
                    }
                } else {
                    out.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = position;
            while (position < text.length() && "-+.eE0123456789".indexOf(text.charAt(position)) >= 0) position++;
            String raw = text.substring(start, position);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) return Double.parseDouble(raw);
            return Long.parseLong(raw);
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, position)) throw new IllegalArgumentException("expected " + literal);
            position += literal.length();
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private char peek() {
            return text.charAt(position);
        }
    }
}
