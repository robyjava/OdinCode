package it.robyjava.odindoc;

import it.robyjava.odindoc.Model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Analisi leggera di un'istruzione SQL: tipo, tabelle con operazioni CRUD, colonne, procedure chiamate. */
final class SqlAnalyzer {
    private SqlAnalyzer() {}

    private static final Pattern START = Pattern.compile(
            "^\\s*(\\{\\s*\\??\\s*=?\\s*CALL|SELECT|INSERT|UPDATE|DELETE|MERGE|WITH|CALL|BEGIN|DECLARE|TRUNCATE|LOCK\\s+TABLE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> CLAUSE_END = new HashSet<>(Arrays.asList(
            "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "MINUS", "INTERSECT", "CONNECT", "START", "FOR", "JOIN",
            "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON", "SET", "VALUES", "FETCH", "OFFSET", "RETURNING",
            "USING", "WHEN", "MODEL", "PIVOT", "UNPIVOT", "SELECT", "INTO", "LIMIT", ")", ";"));

    private static final Set<String> NOT_TABLE = new HashSet<>(Arrays.asList(
            "DUAL", "ON", "OF", "OR", "SET", "SELECT", "TABLE", "THE", "LATERAL", "ONLY", "WHERE", "VALUES", "EACH",
            "ROW", "BEFORE", "AFTER", "INSTEAD", "ALL", "FIRST", "WITH", "NOWAIT", "(", ")", ",", ";", "?", "=", "AS"));

    private static final Pattern PLSQL_CALL = Pattern.compile("(?:\\bBEGIN|;|:\\s*=|\\bCALL)\\s*([A-Z][A-Z0-9_$#]*(?:\\.[A-Z][A-Z0-9_$#]*){0,2})\\s*(\\(|;|$)");

    static boolean looksLikeSql(String s) {
        if (s == null || s.length() < 10) return false;
        String t = s.replace(String.valueOf(Util.DYN), " ").trim();
        if (!START.matcher(t).find()) return false;
        String u = t.toUpperCase(Locale.ROOT);
        if (u.startsWith("SELECT")) return u.contains(" FROM ") || u.contains("FROM ") || u.contains(" DUAL");
        if (u.startsWith("INSERT")) return u.contains("INTO");
        if (u.startsWith("UPDATE")) return u.contains(" SET ") || u.contains(String.valueOf(Util.DYN));
        if (u.startsWith("DELETE")) return u.split("\\s+").length >= 2;
        return true;
    }

    static SqlUse analyze(String sql, Schema schema) {
        SqlUse u = new SqlUse();
        u.sql = sql;
        u.dynamic = sql.indexOf(Util.DYN) >= 0;
        List<String> tok = tokenize(sql);
        if (tok.isEmpty()) return u;
        String first = tok.get(0);
        if (first.equals("{")) u.kind = "CALL";
        else if (first.equals("WITH")) u.kind = "SELECT";
        else if (first.equals("DECLARE")) u.kind = "BEGIN";
        else u.kind = first;

        boolean insertSeen = false, mergeSeen = false;
        Set<String> involved = new LinkedHashSet<>();
        for (int i = 0; i < tok.size(); i++) {
            String w = tok.get(i);
            String prev = i > 0 ? tok.get(i - 1) : "";
            switch (w) {
                case "INSERT": insertSeen = true; break;
                case "MERGE": mergeSeen = true; break;
                case "INTO":
                    if (mergeSeen && !insertSeen) { add(u, schema, next(tok, i), 'C', involved); add(u, schema, next(tok, i), 'U', involved); mergeSeen = false; }
                    else if (insertSeen) add(u, schema, next(tok, i), 'C', involved);
                    break;
                case "UPDATE":
                    if (!prev.equals("FOR") && !prev.equals("ON") && !prev.equals("OR") && !prev.equals("THEN") && !prev.equals("MATCHED"))
                        add(u, schema, next(tok, i), 'U', involved);
                    break;
                case "DELETE":
                    if (!prev.equals("ON") && !prev.equals("OR") && !prev.equals("THEN") && !prev.equals("MATCHED")) {
                        String n = next(tok, i);
                        if (n.equals("FROM")) n = next(tok, i + 1);
                        add(u, schema, n, 'D', involved);
                    }
                    break;
                case "TRUNCATE":
                    if (next(tok, i).equals("TABLE")) add(u, schema, next(tok, i + 1), 'D', involved);
                    break;
                case "USING":
                    add(u, schema, next(tok, i), 'R', involved);
                    break;
                case "JOIN":
                    add(u, schema, next(tok, i), 'R', involved);
                    break;
                case "FROM": {
                    if (prev.equals("DELETE")) break;
                    // lista separata da virgole: FROM A a, B b
                    int j = i + 1;
                    int depth = 0;
                    boolean expectTable = true;
                    while (j < tok.size()) {
                        String t = tok.get(j);
                        if (t.equals("(")) { depth++; if (depth == 1 && expectTable) expectTable = false; }
                        else if (t.equals(")")) { if (depth == 0) break; depth--; }
                        else if (depth == 0) {
                            if (CLAUSE_END.contains(t)) break;
                            if (t.equals(",")) expectTable = true;
                            else if (expectTable) { add(u, schema, t, 'R', involved); expectTable = false; }
                        }
                        j++;
                    }
                    break;
                }
                default:
            }
            if (w.equals("*") && (prev.equals("SELECT") || prev.equals(",") || prev.endsWith("."))) u.selectStar = true;
        }
        // colonne citate, cercate tra quelle delle tabelle coinvolte
        Set<String> words = new HashSet<>(tok);
        for (String tn : involved) {
            Table t = schema.table(tn);
            if (t == null) continue;
            for (Column c : t.columns) if (words.contains(c.name) || words.contains(tn + "." + c.name)) u.columns.add(tn + "." + c.name);
        }
        for (String w : tok) {
            int d = w.lastIndexOf('.');
            if (d > 0) {
                String col = w.substring(d + 1);
                for (String tn : involved) {
                    Table t = schema.table(tn);
                    if (t != null && t.column(col) != null) u.columns.add(tn + "." + col);
                }
            }
        }
        // procedure / package chiamati
        if (u.kind.equals("CALL") || u.kind.equals("BEGIN")) {
            String flat = String.join(" ", tok);
            Matcher m = PLSQL_CALL.matcher(flat);
            while (m.find()) {
                String r = m.group(1);
                if (!r.equals("BEGIN") && !r.equals("END") && !r.equals("NULL") && !r.equals("COMMIT") && !r.equals("ROLLBACK") && !r.equals("RETURN"))
                    u.routines.add(r);
            }
        }
        return u;
    }

    private static void add(SqlUse u, Schema s, String raw, char op, Set<String> involved) {
        if (raw == null || raw.isEmpty()) return;
        if (raw.indexOf(Util.DYN) >= 0) { u.dynamic = true; return; }
        String name = raw;
        int d = name.lastIndexOf('.');
        if (d >= 0) name = name.substring(d + 1);
        if (name.contains("@")) name = name.substring(0, name.indexOf('@'));
        if (name.isEmpty() || NOT_TABLE.contains(name) || name.startsWith(":") || !Character.isLetter(name.charAt(0))) return;
        if (s.knows(name)) {
            u.tables.computeIfAbsent(name, k -> new TreeSet<>()).add(op);
            involved.add(name);
        } else {
            u.unknownTables.add(name);
        }
    }

    private static String next(List<String> tok, int i) {
        return i + 1 < tok.size() ? tok.get(i + 1) : "";
    }

    /** Maiuscolo, senza commenti e stringhe, identificatori senza virgolette, punteggiatura separata. */
    static List<String> tokenize(String sql) {
        List<String> out = new ArrayList<>();
        int n = sql.length(), i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') { int e = sql.indexOf('\n', i); i = e < 0 ? n : e; continue; }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') { int e = sql.indexOf("*/", i + 2); i = e < 0 ? n : e + 2; continue; }
            if (c == '\'') {
                i++;
                while (i < n && !(sql.charAt(i) == '\'' && (i + 1 >= n || sql.charAt(i + 1) != '\''))) { if (sql.charAt(i) == '\'') i++; i++; }
                i++;
                out.add("''");
                continue;
            }
            if (c == Util.DYN) { out.add(String.valueOf(Util.DYN)); i++; continue; }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '"' || c == ':' || c == '@') {
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '"') {
                        int e = sql.indexOf('"', i + 1);
                        if (e < 0) e = n - 1;
                        sb.append(sql, i + 1, e);
                        i = e + 1;
                        continue;
                    }
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '$' || d == '#' || d == '.' || d == ':' || d == '@') {
                        sb.append(Character.toUpperCase(d));
                        i++;
                    } else if (d == Util.DYN) {
                        sb.append(d);
                        i++;
                    } else break;
                }
                out.add(sb.toString());
                continue;
            }
            if (c == ':' && i + 1 < n && sql.charAt(i + 1) == '=') { out.add(":="); i += 2; continue; }
            out.add(String.valueOf(c));
            i++;
        }
        return out;
    }

    /** Impaginazione leggibile di una query su più righe. */
    static String pretty(String sql) {
        String s = Util.collapse(Util.showSql(sql));
        String[] kw = {"SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "UNION ALL", "UNION", "MINUS",
                "LEFT OUTER JOIN", "RIGHT OUTER JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL OUTER JOIN", "CROSS JOIN",
                "JOIN", "SET", "VALUES", "AND", "OR", "WHEN MATCHED", "WHEN NOT MATCHED", "USING", "ON", "RETURNING", "CONNECT BY", "START WITH"};
        StringBuilder out = new StringBuilder();
        boolean sq = false;
        int depth = 0;
        int i = 0;
        outer:
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') sq = !sq;
            if (!sq) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (i > 0 && s.charAt(i - 1) == ' ') {
                    for (String k : kw) {
                        if (s.regionMatches(true, i, k, 0, k.length()) && (i + k.length() == s.length() || !Character.isLetterOrDigit(s.charAt(i + k.length())) && s.charAt(i + k.length()) != '_')) {
                            if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
                            int ind = Math.max(0, depth) * 4 + ((k.equals("AND") || k.equals("OR") || k.equals("ON")) ? 2 : 0);
                            out.append('\n');
                            for (int x = 0; x < ind; x++) out.append(' ');
                            out.append(s, i, i + k.length());
                            i += k.length();
                            continue outer;
                        }
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString().trim();
    }
}
