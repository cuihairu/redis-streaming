package io.github.cuihairu.redis.streaming.cdc.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Simple include/exclude matcher for CDC tables.
 *
 * Supports '*' wildcard in patterns. A pattern can match either:
 * - "table"
 * - "schema.table" / "database.table"
 */
final class TableFilter {

    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    private TableFilter(List<Pattern> includes, List<Pattern> excludes) {
        this.includes = includes;
        this.excludes = excludes;
    }

    static TableFilter from(List<String> includePatterns, List<String> excludePatterns) {
        return new TableFilter(toRegex(includePatterns), toRegex(excludePatterns));
    }

    boolean allowed(String databaseOrSchema, String table) {
        String db = databaseOrSchema == null ? "" : databaseOrSchema;
        String t = table == null ? "" : table;
        String full = db.isEmpty() ? t : (db + "." + t);

        if (!includes.isEmpty() && !matchesAny(includes, full, t)) {
            return false;
        }
        return !matchesAny(excludes, full, t);
    }

    private static boolean matchesAny(List<Pattern> patterns, String full, String table) {
        for (Pattern p : patterns) {
            if (p.matcher(full).matches() || p.matcher(table).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> toRegex(List<String> patterns) {
        List<Pattern> out = new ArrayList<>();
        if (patterns == null) return out;
        for (String raw : patterns) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty()) continue;
            out.add(Pattern.compile("^" + toWildcardRegex(s) + "$"));
        }
        return out;
    }

    private static String toWildcardRegex(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                out.append(".*");
                continue;
            }
            if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
