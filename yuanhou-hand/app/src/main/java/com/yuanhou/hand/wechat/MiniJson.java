package com.yuanhou.hand.wechat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 很小的 JSON 读写（纯 Java，不依赖 android.org.json，方便 jvmcheck 在桌面 JVM 上验证微信协议）。
 * 读：对象 → LinkedHashMap，数组 → ArrayList，字符串 → String，数字 → Long/Double，true/false → Boolean，null → null。
 * 写：只提供字符串转义；请求体由 WechatApi 手工拼。
 */
public final class MiniJson {
    private MiniJson() {}

    public static final class ParseException extends RuntimeException {
        ParseException(String m) { super(m); }
    }

    public static Object parse(String s) {
        if (s == null) throw new ParseException("null");
        P p = new P(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new ParseException("trailing data at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

    /** 字符串字段；数字也按字符串返回（Mika FlexibleStringSerializer 的做法，message_id 可能是数字）。 */
    public static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        if (v instanceof Long || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return null;
    }

    public static long num(Map<String, Object> m, String k, long def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) return (long) (double) (Double) v;
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        char peek() {
            if (i >= s.length()) throw new ParseException("unexpected end");
            return s.charAt(i);
        }

        Object value(int depth) {
            if (depth > 64) throw new ParseException("too deep");
            char c = peek();
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return null;
                default: return number();
            }
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) throw new ParseException("bad literal at " + i);
            i += w.length();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new ParseException("expected key at " + i);
                String k = string();
                ws();
                if (peek() != ':') throw new ParseException("expected : at " + i);
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new ParseException("expected , at " + (i - 1));
            }
        }

        List<Object> array(int depth) {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == ']') return a;
                if (c != ',') throw new ParseException("expected , at " + (i - 1));
            }
        }

        String string() {
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = peek();
                i++;
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new ParseException("bad \\u");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new ParseException("bad escape");
                }
            }
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw new ParseException("unexpected char at " + st);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
            } catch (NumberFormatException ignored) {}
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { throw new ParseException("bad number"); }
        }
    }
}
