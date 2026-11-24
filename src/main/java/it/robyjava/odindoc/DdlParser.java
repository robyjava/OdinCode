package it.robyjava.odindoc;

import it.robyjava.odindoc.Model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Legge gli script DDL Oracle e ricostruisce lo schema. Tollerante: quello che non capisce lo salta. */
final class DdlParser {

    private static final Set<String> EXT = new HashSet<>(Arrays.asList(
            "sql", "ddl", "tab", "vw", "seq", "trg", "pks", "pkb", "pck", "prc", "fnc", "idx", "con"));

    private static final String ID = "((?:\"[^\"]+\"|[A-Za-z0-9_$#]+)(?:\\.(?:\"[^\"]+\"|[A-Za-z0-9_$#]+))?)";
    private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+|PRIVATE\\s+TEMPORARY\\s+)?TABLE\\s+" + ID, Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE = Pattern.compile("^ALTER\\s+TABLE\\s+" + ID + "\\s+ADD\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_INDEX = Pattern.compile("^CREATE\\s+(UNIQUE\\s+|BITMAP\\s+)?INDEX\\s+" + ID + "\\s+ON\\s+" + ID + "\\s*\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT_TABLE = Pattern.compile("^COMMENT\\s+ON\\s+(?:TABLE|VIEW|MATERIALIZED\\s+VIEW)\\s+" + ID + "\\s+IS\\s+'(.*)'\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT_COLUMN = Pattern.compile("^COMMENT\\s+ON\\s+COLUMN\\s+((?:\"[^\"]+\"|[A-Za-z0-9_$#]+)(?:\\.(?:\"[^\"]+\"|[A-Za-z0-9_$#]+)){1,2})\\s+IS\\s+'(.*)'\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_VIEW = Pattern.compile("^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NO\\s+)?FORCE\\s+)?(?:EDITIONABLE\\s+|NONEDITIONABLE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+" + ID + "(.*?)\\bAS\\b(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_SEQ = Pattern.compile("^CREATE\\s+SEQUENCE\\s+" + ID + "(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_TRG = Pattern.compile("^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:EDITIONABLE\\s+|NONEDITIONABLE\\s+)?TRIGGER\\s+" + ID + "\\s+(.*?)\\s+ON\\s+" + ID, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_PLSQL = Pattern.compile("^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:EDITIONABLE\\s+|NONEDITIONABLE\\s+)?(PACKAGE\\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TYPE\\s+BODY|TYPE)\\s+" + ID, Pattern.CASE_INSENSITIVE);
    private static final Pattern PLSQL_START = Pattern.compile("^(CREATE\\s+(OR\\s+REPLACE\\s+)?(EDITIONABLE\\s+|NONEDITIONABLE\\s+)?(TRIGGER|PROCEDURE|FUNCTION|PACKAGE|TYPE)\\b|DECLARE\\b|BEGIN\\b)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> COL_STOP = new HashSet<>(Arrays.asList(
            "NOT", "NULL", "DEFAULT", "CONSTRAINT", "PRIMARY", "REFERENCES", "UNIQUE", "CHECK", "ENABLE", "DISABLE",
            "GENERATED", "COLLATE", "VISIBLE", "INVISIBLE", "ENCRYPT", "SORT", "IDENTITY"));

    Schema parse(List<Path> roots, boolean includeTests) throws IOException {
        Schema s = new Schema();
        List<Path> files = Util.files(roots, EXT, includeTests);
        s.files = files.size();
        for (Path f : files) {
            String text = Util.read(f);
            for (String st : split(text)) {
                try {
                    statement(s, st, f.getFileName().toString());
                } catch (RuntimeException e) {
                    s.warnings.add(f.getFileName() + ": istruzione non interpretata (" + Util.firstSentence(st) + ")");
                }
            }
        }
        // tabelle referenziate da viste e routine
        for (View v : s.views.values()) v.tables.addAll(SqlAnalyzer.analyze(v.sql, s).tables.keySet());
        for (Routine r : s.routines.values()) {
            if (r.body == null) continue;
            SqlUse u = SqlAnalyzer.analyze(r.body, s);
            r.tables.putAll(u.tables);
        }
        return s;
    }

    /** Divide uno script in istruzioni: ';' per il DDL normale, '/' su riga isolata per i blocchi PL/SQL. */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean sq = false, dq = false;
        int n = text.length();
        int i = 0;
        boolean lineStart = true;
        Boolean plsql = null;
        while (i < n) {
            char c = text.charAt(i);
            if (lineStart && !sq && !dq) {
                int e = text.indexOf('\n', i);
                if (e < 0) e = n;
                String line = text.substring(i, e).trim();
                if (line.equals("/")) {
                    flush(out, cur);
                    plsql = null;
                    i = e + 1;
                    continue;
                }
                String up = line.toUpperCase(Locale.ROOT);
                if (cur.toString().trim().isEmpty() && (up.startsWith("PROMPT") || up.startsWith("SET ") || up.startsWith("SPOOL") || up.startsWith("REM ") || up.startsWith("WHENEVER") || up.startsWith("@"))) {
                    i = e + 1;
                    continue;
                }
            }
            lineStart = false;
            if (!sq && !dq && c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                int e = text.indexOf('\n', i);
                i = e < 0 ? n : e;
                continue;
            }
            if (!sq && !dq && c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int e = text.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                cur.append(' ');
                continue;
            }
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            if (c == ';' && !sq && !dq && plsql == null) plsql = PLSQL_START.matcher(cur.toString().trim()).find();
            if (c == ';' && !sq && !dq && !plsql) {
                flush(out, cur);
                plsql = null;
                i++;
                continue;
            }
            cur.append(c);
            if (c == '\n') lineStart = true;
            i++;
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim();
        if (s.endsWith(";") && !PLSQL_START.matcher(s).find()) s = s.substring(0, s.length() - 1).trim();
        if (!s.isEmpty()) out.add(s);
        cur.setLength(0);
    }

    private void statement(Schema s, String st, String file) {
        Matcher m;
        String flat = Util.collapse(st);
        if ((m = CREATE_TABLE.matcher(flat)).find()) {
            Table t = new Table();
            t.name = Util.ident(m.group(1));
            t.sourceFile = file;
            t.temporary = flat.toUpperCase(Locale.ROOT).contains(" TEMPORARY ");
            int p = flat.indexOf('(', m.end());
            if (p < 0) return; // CREATE TABLE AS SELECT senza colonne: ignorato
            String inner = flat.substring(p + 1, matching(flat, p));
            for (String el : splitTop(inner)) element(t, el);
            Table old = s.tables.get(t.name);
            if (old != null) s.warnings.add("Tabella " + t.name + " definita più volte (" + old.sourceFile + ", " + file + ")");
            s.tables.put(t.name, t);
        } else if ((m = ALTER_TABLE.matcher(flat)).find()) {
            Table t = s.tables.get(Util.ident(m.group(1)));
            if (t == null) return;
            String rest = m.group(2).trim();
            if (rest.startsWith("(")) {
                for (String el : splitTop(rest.substring(1, matching(rest, 0)))) element(t, el);
            } else element(t, rest);
        } else if ((m = CREATE_INDEX.matcher(flat)).find()) {
            Table t = s.tables.get(Util.ident(m.group(3)));
            if (t == null) return;
            Index ix = new Index();
            ix.unique = m.group(1) != null && m.group(1).trim().equalsIgnoreCase("UNIQUE");
            ix.name = Util.ident(m.group(2));
            for (String c : splitTop(m.group(4))) ix.columns.add(cleanIndexCol(c));
            t.indexes.add(ix);
        } else if ((m = COMMENT_TABLE.matcher(flat)).find()) {
            String n = Util.ident(m.group(1));
            String txt = m.group(2).replace("''", "'");
            if (s.tables.containsKey(n)) s.tables.get(n).comment = txt;
            else if (s.views.containsKey(n)) s.views.get(n).comment = txt;
        } else if ((m = COMMENT_COLUMN.matcher(flat)).find()) {
            List<String> parts = splitDots(m.group(1));
            if (parts.size() < 2) return;
            Table t = s.tables.get(Util.ident(parts.get(parts.size() - 2)));
            if (t == null) return;
            Column c = t.column(Util.ident(parts.get(parts.size() - 1)));
            if (c != null) c.comment = m.group(2).replace("''", "'");
        } else if ((m = CREATE_VIEW.matcher(flat)).find()) {
            View v = new View();
            v.name = Util.ident(m.group(1));
            v.sql = m.group(3).trim();
            v.sourceFile = file;
            s.views.put(v.name, v);
        } else if ((m = CREATE_SEQ.matcher(flat)).find()) {
            Sequence q = new Sequence();
            q.name = Util.ident(m.group(1));
            q.details = m.group(2).trim();
            s.sequences.put(q.name, q);
        } else if ((m = CREATE_TRG.matcher(flat)).find()) {
            Trigger g = new Trigger();
            g.name = Util.ident(m.group(1));
            g.timing = Util.collapse(m.group(2));
            g.table = Util.ident(m.group(3));
            g.body = st;
            s.triggers.put(g.name, g);
        } else if ((m = CREATE_PLSQL.matcher(flat)).find()) {
            Routine r = new Routine();
            r.kind = Util.collapse(m.group(1)).toUpperCase(Locale.ROOT);
            r.name = Util.ident(m.group(2));
            r.body = st;
            r.sourceFile = file;
            String key = r.kind + " " + r.name;
            s.routines.put(key, r);
        }
    }

    /** Un elemento dentro CREATE TABLE ( ... ) o ALTER TABLE ADD: colonna o vincolo. */
    private void element(Table t, String el) {
        el = el.trim();
        if (el.isEmpty()) return;
        List<String> tok = tokens(el);
        if (tok.isEmpty()) return;
        String constraintName = null;
        int i = 0;
        if (up(tok, 0).equals("CONSTRAINT") && tok.size() > 2) {
            constraintName = Util.ident(tok.get(1));
            i = 2;
        }
        String k = up(tok, i);
        if (k.equals("PRIMARY")) {
            t.pk.clear();
            t.pk.addAll(cols(tok, i + 1));
            t.pkName = constraintName;
            return;
        }
        if (k.equals("FOREIGN")) {
            ForeignKey fk = new ForeignKey();
            fk.name = constraintName;
            fk.columns.addAll(cols(tok, i + 1));
            int r = indexOf(tok, "REFERENCES", i);
            if (r > 0 && r + 1 < tok.size()) {
                String ref = tok.get(r + 1);
                int par = ref.indexOf('(');
                if (par > 0) {
                    fk.refTable = Util.ident(ref.substring(0, par));
                    fk.refColumns.addAll(parenList(ref.substring(par)));
                } else {
                    fk.refTable = Util.ident(ref);
                    fk.refColumns.addAll(cols(tok, r + 2));
                }
            }
            int d = indexOf(tok, "DELETE", i);
            if (d > 0 && d + 1 < tok.size()) fk.onDelete = up(tok, d + 1).equals("SET") ? "SET NULL" : up(tok, d + 1);
            t.fks.add(fk);
            return;
        }
        if (k.equals("UNIQUE")) {
            t.uniques.add(cols(tok, i + 1));
            return;
        }
        if (k.equals("CHECK")) {
            t.checks.add(el.substring(el.toUpperCase(Locale.ROOT).indexOf("CHECK")));
            return;
        }
        if (k.equals("SUPPLEMENTAL") || k.equals("PERIOD") || k.equals("SCOPE") || k.equals("REF")) return;
        // colonna
        Column c = new Column();
        c.name = Util.ident(tok.get(i));
        StringBuilder type = new StringBuilder();
        int j = i + 1;
        while (j < tok.size() && !COL_STOP.contains(up(tok, j))) {
            if (type.length() > 0 && !tok.get(j).startsWith("(")) type.append(' ');
            type.append(tok.get(j));
            j++;
        }
        c.type = type.toString().toUpperCase(Locale.ROOT);
        for (; j < tok.size(); j++) {
            String w = up(tok, j);
            if (w.equals("NOT") && up(tok, j + 1).equals("NULL")) { c.nullable = false; j++; }
            else if (w.equals("DEFAULT") && j + 1 < tok.size()) {
                StringBuilder dv = new StringBuilder(tok.get(++j));
                while (j + 1 < tok.size() && !COL_STOP.contains(up(tok, j + 1))) dv.append(' ').append(tok.get(++j));
                c.defaultValue = dv.toString();
            } else if (w.equals("PRIMARY")) { t.pk.clear(); t.pk.add(c.name); c.nullable = false; }
            else if (w.equals("UNIQUE")) t.uniques.add(Collections.singletonList(c.name));
            else if (w.equals("REFERENCES") && j + 1 < tok.size()) {
                ForeignKey fk = new ForeignKey();
                fk.columns.add(c.name);
                String ref = tok.get(++j);
                int par = ref.indexOf('(');
                if (par > 0) {
                    fk.refTable = Util.ident(ref.substring(0, par));
                    fk.refColumns.addAll(parenList(ref.substring(par)));
                } else {
                    fk.refTable = Util.ident(ref);
                    if (j + 1 < tok.size() && tok.get(j + 1).startsWith("(")) fk.refColumns.addAll(parenList(tok.get(++j)));
                }
                t.fks.add(fk);
            } else if (w.equals("CHECK") && j + 1 < tok.size()) {
                t.checks.add(c.name + ": CHECK " + tok.get(++j));
            }
        }
        if (t.column(c.name) == null) t.columns.add(c);
    }

    /** Tokenizza tenendo insieme stringhe '...' e gruppi tra parentesi (attaccati al token precedente se senza spazio). */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int start = i;
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (s.charAt(i) == '\'' && (i + 1 >= n || s.charAt(i + 1) != '\'')) { i++; break; }
                    if (s.charAt(i) == '\'') i++;
                    i++;
                }
                out.add(s.substring(start, Math.min(i, n)));
                continue;
            }
            if (c == '(') {
                int e = matching(s, i);
                String grp = s.substring(i, e + 1);
                if (!out.isEmpty() && i > 0 && !Character.isWhitespace(s.charAt(i - 1)) && s.charAt(i - 1) != ',')
                    out.set(out.size() - 1, out.get(out.size() - 1) + grp);
                else out.add(grp);
                i = e + 1;
                continue;
            }
            if (c == ',') { out.add(","); i++; continue; }
            boolean dq = false;
            while (i < n) {
                char d = s.charAt(i);
                if (d == '"') dq = !dq;
                if (!dq && (Character.isWhitespace(d) || d == '(' || d == ',')) break;
                i++;
            }
            out.add(s.substring(start, i));
        }
        return out;
    }

    static int matching(String s, int open) {
        int depth = 0;
        boolean sq = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') sq = !sq;
            if (sq) continue;
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return s.length() - 1;
    }

    /** Divide per virgole al livello zero di parentesi. */
    static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean sq = false;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\'') sq = !sq;
            if (!sq) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) { out.add(cur.toString().trim()); cur.setLength(0); continue; }
            }
            cur.append(c);
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static List<String> cols(List<String> tok, int from) {
        for (int i = from; i < tok.size(); i++) {
            String t = tok.get(i);
            int p = t.indexOf('(');
            if (p >= 0) return parenList(t.substring(p));
        }
        return new ArrayList<>();
    }

    private static List<String> parenList(String grp) {
        List<String> l = new ArrayList<>();
        String in = grp.trim();
        if (in.startsWith("(")) in = in.substring(1, matching(in, 0));
        for (String x : splitTop(in)) l.add(Util.ident(x.trim().split("\\s+")[0]));
        return l;
    }

    private static String cleanIndexCol(String c) {
        String t = c.trim();
        String u = t.toUpperCase(Locale.ROOT);
        if (u.endsWith(" ASC")) t = t.substring(0, t.length() - 4);
        if (u.endsWith(" DESC")) t = t.substring(0, t.length() - 5);
        return t.matches("\"?[A-Za-z0-9_$#]+\"?") ? Util.ident(t) : t.toUpperCase(Locale.ROOT);
    }

    private static List<String> splitDots(String s) {
        List<String> l = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char c : s.toCharArray()) {
            if (c == '"') q = !q;
            if (c == '.' && !q) { l.add(cur.toString()); cur.setLength(0); } else cur.append(c);
        }
        l.add(cur.toString());
        return l;
    }

    private static String up(List<String> tok, int i) {
        return i >= 0 && i < tok.size() ? tok.get(i).toUpperCase(Locale.ROOT) : "";
    }

    private static int indexOf(List<String> tok, String w, int from) {
        for (int i = from; i < tok.size(); i++) if (up(tok, i).equals(w)) return i;
        return -1;
    }
}
