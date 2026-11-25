package it.robyjava.odindoc;

import java.util.*;

/** Modello dati condiviso: schema del database e struttura del codice Java. */
public final class Model {
    private Model() {}

    // ------------------------------------------------------------------ DB

    public static final class Column {
        String name, type, defaultValue, comment;
        boolean nullable = true;
    }

    public static final class ForeignKey {
        String name, refTable, onDelete;
        final List<String> columns = new ArrayList<>();
        final List<String> refColumns = new ArrayList<>();
    }

    public static final class Index {
        String name;
        boolean unique;
        final List<String> columns = new ArrayList<>();
    }

    public static final class Table {
        String name, comment, sourceFile, pkName;
        boolean temporary;
        final List<Column> columns = new ArrayList<>();
        final List<String> pk = new ArrayList<>();
        final List<ForeignKey> fks = new ArrayList<>();
        final List<Index> indexes = new ArrayList<>();
        final List<List<String>> uniques = new ArrayList<>();
        final List<String> checks = new ArrayList<>();
        // dati letti dal database (facoltativi)
        long rowCount = -1;
        String dataNote;
        final Map<String, Long> nonNull = new LinkedHashMap<>();
        final Map<String, List<String[]>> values = new LinkedHashMap<>();
        final List<String> lookupHead = new ArrayList<>();
        final List<String[]> lookupRows = new ArrayList<>();

        Column column(String n) {
            for (Column c : columns) if (c.name.equals(n)) return c;
            return null;
        }

        /** Testo canonico usato per calcolare l'impronta della tabella. */
        String canonical() {
            StringBuilder sb = new StringBuilder(name).append('|');
            for (Column c : columns) sb.append(c.name).append(':').append(c.type).append(':').append(c.nullable).append(':').append(c.defaultValue).append(';');
            sb.append("PK").append(pk);
            for (ForeignKey f : fks) sb.append("FK").append(f.columns).append(f.refTable).append(f.refColumns);
            for (List<String> u : uniques) sb.append("UQ").append(u);
            return sb.toString();
        }
    }

    public static final class View {
        String name, sql, comment, sourceFile;
        final Set<String> tables = new TreeSet<>();
    }

    public static final class Sequence {
        String name, details;
    }

    public static final class Trigger {
        String name, table, timing, body;
    }

    public static final class Routine {
        String kind, name, body, sourceFile;
        final Map<String, Set<Character>> tables = new TreeMap<>();
    }

    public static final class Schema {
        final Map<String, Table> tables = new TreeMap<>();
        final Map<String, View> views = new TreeMap<>();
        final Map<String, Sequence> sequences = new TreeMap<>();
        final Map<String, Trigger> triggers = new TreeMap<>();
        final Map<String, Routine> routines = new TreeMap<>();
        final List<String> warnings = new ArrayList<>();
        /** estensione -> numero di file non letti */
        final Map<String, Integer> skipped = new TreeMap<>();
        int files, statements;

        Table table(String n) { return tables.get(n); }

        boolean knows(String n) { return tables.containsKey(n) || views.containsKey(n); }
    }

    // ---------------------------------------------------------------- Java

    public static final class SqlUse {
        String sql, kind;
        int line;
        boolean dynamic, selectStar;
        /** tabella -> operazioni (C,R,U,D) */
        final Map<String, Set<Character>> tables = new TreeMap<>();
        final Set<String> unknownTables = new TreeSet<>();
        final Set<String> routines = new TreeSet<>();
        final Set<String> columns = new TreeSet<>();
    }

    public static final class RawCall {
        final String name, typeHint;
        final int argc;
        RawCall(String name, int argc, String typeHint) { this.name = name; this.argc = argc; this.typeHint = typeHint; }
    }

    public static final class MethodInfo {
        String name, signature, javadoc, entryKind;
        int line, lines, argc;
        ClassInfo owner;
        final List<SqlUse> sql = new ArrayList<>();
        final List<RawCall> rawCalls = new ArrayList<>();
        final Set<MethodInfo> calls = new LinkedHashSet<>();
        final Set<MethodInfo> calledBy = new LinkedHashSet<>();

        String id() { return owner.name + "." + name + "(" + argc + ")"; }

        String shortId() { return owner.name + "." + name + "()"; }
    }

    public static final class ClassInfo {
        String pkg, name, fqcn, kind, javadoc, file, superName, hash;
        int lines;
        final List<String> interfaces = new ArrayList<>();
        final List<String> annotations = new ArrayList<>();
        final Map<String, String> fieldTypes = new HashMap<>();
        final List<MethodInfo> methods = new ArrayList<>();
        String source;

        boolean hasSql() {
            for (MethodInfo m : methods) if (!m.sql.isEmpty()) return true;
            return false;
        }
    }

    public static final class Project {
        final Map<String, ClassInfo> classes = new TreeMap<>();
        final List<String> parseErrors = new ArrayList<>();
        int files;

        List<MethodInfo> allMethods() {
            List<MethodInfo> l = new ArrayList<>();
            for (ClassInfo c : classes.values()) l.addAll(c.methods);
            return l;
        }
    }
}
