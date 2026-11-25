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
        /** -1 = numero di argomenti sconosciuto (riferimento a metodo) */
        final int argc;
        RawCall(String name, int argc, String typeHint) { this.name = name; this.argc = argc; this.typeHint = typeHint; }
    }

    public static final class MethodInfo {
        String name, signature, javadoc, entryKind;
        final List<String> webPaths = new ArrayList<>();
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
        /** percorsi web dichiarati (@WebServlet, @Path, @RequestMapping sulla classe) */
        final List<String> webPaths = new ArrayList<>();
        String source;

        boolean hasSql() {
            for (MethodInfo m : methods) if (!m.sql.isEmpty()) return true;
            return false;
        }
    }

    // ---------------------------------------------------------------- interfaccia utente

    public static final class UiField {
        String label, name, kind;
    }

    public static final class UiAction {
        String label, kind, var;
        final List<RawCall> raw = new ArrayList<>();
        final Set<MethodInfo> targets = new LinkedHashSet<>();
        final Map<String, Set<Character>> directTables = new TreeMap<>();
        /** schermate aperte dall'azione (id) */
        final Set<String> opens = new TreeSet<>();
        String url;
        boolean closes;
    }

    public static final class MenuNode {
        String label, var;
        UiAction action;
        final List<MenuNode> children = new ArrayList<>();
    }

    /** Una schermata: finestra/pannello Swing oppure pagina web. */
    public static final class Screen {
        String id, kind, title, file, hash, source;
        ClassInfo cls;
        final List<String> headings = new ArrayList<>();
        final List<UiField> fields = new ArrayList<>();
        final List<UiAction> actions = new ArrayList<>();
        final Set<String> messages = new LinkedHashSet<>();
        final List<List<String>> columns = new ArrayList<>();
        final Set<String> links = new TreeSet<>();
        final List<MenuNode> menus = new ArrayList<>();
        final Set<String> linkedFrom = new TreeSet<>();
    }

    public static final class Project {
        final Map<String, ClassInfo> classes = new TreeMap<>();
        final List<String> parseErrors = new ArrayList<>();
        final Map<String, Screen> screens = new TreeMap<>();
        /** testi dei file .properties (bundle delle etichette) */
        final Map<String, String> bundle = new HashMap<>();
        int files, webFiles;

        List<MethodInfo> allMethods() {
            List<MethodInfo> l = new ArrayList<>();
            for (ClassInfo c : classes.values()) l.addAll(c.methods);
            return l;
        }
    }
}
