package it.robyjava.odindoc;

import it.robyjava.odindoc.Model.*;
import it.robyjava.odindoc.PdfWriter.Font;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Costruisce il PDF e il pacchetto per l'AI a partire dall'analisi. */
final class DocBuilder {

    private final Schema schema;
    private final Project project;
    private final DescStore desc;
    private final String title, client;

    /** tabella -> metodo -> operazioni */
    private final Map<String, Map<MethodInfo, Set<Character>>> usage = new TreeMap<>();
    private final Map<String, Set<MethodInfo>> unknown = new TreeMap<>();

    DocBuilder(Schema schema, Project project, DescStore desc, String title, String client) {
        this.schema = schema;
        this.project = project;
        this.desc = desc;
        this.title = title;
        this.client = client;
        for (MethodInfo m : project.allMethods())
            for (SqlUse u : m.sql) {
                u.tables.forEach((t, ops) -> usage.computeIfAbsent(t, k -> new LinkedHashMap<>()).computeIfAbsent(m, k -> new TreeSet<>()).addAll(ops));
                for (String t : u.unknownTables) unknown.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(m);
            }
    }

    // ================================================================ PDF

    /** Manuale del programmatore. */
    byte[] pdf() {
        PdfWriter b = new PdfWriter();
        overview(b);
        architecture(b);
        interfaceChapter(b);
        entryPoints(b);
        components(b);
        database(b);
        crud(b);
        attention(b);
        docStatus(b);
        return assemble(b, "Manuale del programmatore");
    }

    private byte[] assemble(PdfWriter b, String subtitle) {
        PdfWriter front = new PdfWriter();
        cover(front, subtitle);
        int tocPages = PdfWriter.tocPages(b.marks);
        int off = 1 + tocPages;
        front.newPage();
        front.text(PdfWriter.ML, front.y - 18, Font.BOLD, 18, "Indice", PdfWriter.ACCENT);
        front.y -= 40;
        for (PdfWriter.Mark m : b.marks) front.tocLine(m.level, m.title, m.page, String.valueOf(m.page + off + 1), m.y);
        return PdfWriter.build(front, b, subtitle + " - " + title + (client == null ? "" : " - " + client));
    }

    private void cover(PdfWriter p, String subtitle) {
        p.newPage();
        float top = PdfWriter.PH - 230;
        p.rect(0, top - 10, PdfWriter.PW, 150, PdfWriter.ACCENT, null, 0);
        p.text(PdfWriter.ML, top + 95, Font.REG, 11, subtitle, PdfWriter.ACCENT2);
        float y = top + 62;
        for (String l : PdfWriter.wrap(Font.BOLD, 26, title, PdfWriter.CW)) { p.text(PdfWriter.ML, y, Font.BOLD, 26, l, PdfWriter.WHITE); y -= 30; }
        if (client != null) p.text(PdfWriter.ML, top + 8, Font.REG, 13, client, PdfWriter.WHITE);
        p.y = top - 60;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Generata il", LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))});
        if (subtitle.startsWith("Manuale utente")) {
            rows.add(new String[]{"Schermate", String.valueOf(userScreens().size())});
        } else {
            rows.add(new String[]{"Tabelle", String.valueOf(schema.tables.size())});
            rows.add(new String[]{"Viste", String.valueOf(schema.views.size())});
            rows.add(new String[]{"Classi Java", String.valueOf(project.classes.size())});
            rows.add(new String[]{"Schermate", String.valueOf(project.screens.size())});
            rows.add(new String[]{"Query individuate", String.valueOf(project.allMethods().stream().mapToInt(m -> m.sql.size()).sum())});
        }
        p.table(null, new float[]{0.3f, 0.7f}, rows, new Font[]{Font.BOLD, Font.REG});
        p.y = PdfWriter.MB + 30;
        p.text(PdfWriter.ML, p.y, Font.IT, 8, "Generato automaticamente da OdinDoc a partire dal codice. Le parti descrittive provengono dai file in descrizioni/.", PdfWriter.GRAY);
    }

    private void overview(PdfWriter b) {
        b.heading(1, "1. Panoramica");
        if (desc.overview != null) markdown(b, desc.overview);
        else b.note("Manca la panoramica generale. Scrivila nel file descrizioni/_applicazione.md (il comando 'pacchetto' prepara il materiale per farla scrivere all'AI).", PdfWriter.LIGHT, PdfWriter.GRAY);
        b.heading(2, "Numeri del progetto");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"File DDL letti", String.valueOf(schema.files)});
        rows.add(new String[]{"Tabelle / viste / sequence / trigger", schema.tables.size() + " / " + schema.views.size() + " / " + schema.sequences.size() + " / " + schema.triggers.size()});
        rows.add(new String[]{"Package, procedure e funzioni PL/SQL", String.valueOf(schema.routines.size())});
        rows.add(new String[]{"File Java letti", String.valueOf(project.files)});
        rows.add(new String[]{"Classi / metodi", project.classes.size() + " / " + project.allMethods().size()});
        rows.add(new String[]{"Classi che accedono al database", String.valueOf(project.classes.values().stream().filter(ClassInfo::hasSql).count())});
        rows.add(new String[]{"Punti di ingresso", String.valueOf(entries().size())});
        b.table(null, new float[]{0.45f, 0.55f}, rows, new Font[]{Font.BOLD, Font.REG});
        int[] st = statusCounts();
        b.heading(2, "Stato delle descrizioni");
        b.para("Descrizioni aggiornate: " + st[0] + "   -   da rivedere (codice cambiato): " + st[1] + "   -   mancanti: " + st[2] + ". Il dettaglio è nell'ultimo capitolo.");
    }

    private void architecture(PdfWriter b) {
        b.heading(1, "2. Architettura del codice");
        b.para("Classi raggruppate per package. La colonna DB indica le classi che eseguono direttamente istruzioni SQL.", Font.IT, 8.5f, PdfWriter.GRAY);
        Map<String, List<ClassInfo>> byPkg = new TreeMap<>();
        for (ClassInfo c : project.classes.values()) byPkg.computeIfAbsent(c.pkg.isEmpty() ? "(package di default)" : c.pkg, k -> new ArrayList<>()).add(c);
        for (Map.Entry<String, List<ClassInfo>> e : byPkg.entrySet()) {
            b.heading(3, "Package " + e.getKey());
            List<String[]> rows = new ArrayList<>();
            for (ClassInfo c : e.getValue())
                rows.add(new String[]{"**" + c.name, c.kind, String.valueOf(c.lines), c.hasSql() ? "sì" : "", Util.firstSentence(classText(c))});
            b.table(new String[]{"Classe", "Tipo", "Righe", "DB", "Descrizione"}, new float[]{0.24f, 0.13f, 0.07f, 0.05f, 0.51f}, rows, null);
        }
    }

    private void entryPoints(PdfWriter b) {
        b.heading(1, "4. Punti di ingresso");
        b.para("Metodi da cui parte l'esecuzione (main, servlet, servizi REST, job). Per ognuno le tabelle raggiunte seguendo le chiamate tra classi: C=inserimento, R=lettura, U=modifica, D=cancellazione.", Font.IT, 8.5f, PdfWriter.GRAY);
        List<MethodInfo> eps = entries();
        if (eps.isEmpty()) { b.para("Nessun punto di ingresso riconosciuto automaticamente."); return; }
        List<String[]> rows = new ArrayList<>();
        for (MethodInfo m : eps) rows.add(new String[]{"**" + m.shortId(), m.entryKind, tablesText(reach(m))});
        b.table(new String[]{"Punto di ingresso", "Tipo", "Tabelle raggiunte"}, new float[]{0.32f, 0.22f, 0.46f}, rows, null);
    }

    private void components(PdfWriter b) {
        b.heading(1, "5. Componenti");
        for (ClassInfo c : project.classes.values()) {
            b.heading(2, c.name);
            StringBuilder info = new StringBuilder(c.kind + " - " + (c.pkg.isEmpty() ? "package di default" : c.pkg) + " - " + c.lines + " righe");
            if (c.superName != null) info.append(" - estende ").append(c.superName);
            if (!c.interfaces.isEmpty()) info.append(" - implementa ").append(String.join(", ", c.interfaces));
            if (!c.annotations.isEmpty()) info.append(" - @").append(String.join(" @", c.annotations));
            b.para(info.toString(), Font.IT, 8.3f, PdfWriter.GRAY);
            describe(b, DescStore.classKey(c.fqcn), c.hash, c.javadoc);

            if (!c.methods.isEmpty()) {
                List<String[]> rows = new ArrayList<>();
                for (MethodInfo m : c.methods) {
                    String note = m.entryKind != null ? "Punto di ingresso: " + m.entryKind + ". " : "";
                    note += Util.firstSentence(m.javadoc);
                    Map<String, Set<Character>> t = new TreeMap<>();
                    for (SqlUse u : m.sql) u.tables.forEach((k, v) -> t.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
                    rows.add(new String[]{"**" + m.name + "(" + (m.argc == 0 ? "" : "…") + ")", String.valueOf(m.lines), note, tablesText(t)});
                }
                b.table(new String[]{"Metodo", "Righe", "Note", "Tabelle"}, new float[]{0.25f, 0.07f, 0.4f, 0.28f}, rows, null);
            }
            for (MethodInfo m : c.methods) {
                if (m.sql.isEmpty()) continue;
                b.heading(3, "SQL in " + m.name + "()");
                b.para(m.signature, Font.MONO, 7.6f, PdfWriter.GRAY);
                for (SqlUse u : m.sql) {
                    StringBuilder h = new StringBuilder("Riga " + u.line + " - " + u.kind);
                    if (!u.tables.isEmpty()) h.append(" - ").append(tablesText(u.tables));
                    if (!u.routines.isEmpty()) h.append(" - chiama ").append(String.join(", ", u.routines));
                    b.para(h.toString(), Font.BOLD, 8.3f, PdfWriter.BLACK);
                    b.code(SqlAnalyzer.pretty(u.sql));
                    if (u.dynamic) b.para("Le parti <...> sono calcolate a runtime (parametri o valori concatenati).", Font.IT, 7.8f, PdfWriter.GRAY);
                    if (!u.unknownTables.isEmpty()) b.para("Oggetti non presenti nelle DDL: " + String.join(", ", u.unknownTables), Font.IT, 7.8f, PdfWriter.WARN);
                }
            }
            Set<String> calls = new TreeSet<>(), callers = new TreeSet<>();
            for (MethodInfo m : c.methods) {
                for (MethodInfo t : m.calls) if (t.owner != c) calls.add(t.owner.name);
                for (MethodInfo t : m.calledBy) if (t.owner != c) callers.add(t.owner.name);
            }
            if (!calls.isEmpty() || !callers.isEmpty()) {
                b.space(2);
                if (!calls.isEmpty()) b.para("Usa: " + String.join(", ", calls), Font.REG, 8.5f, PdfWriter.BLACK);
                if (!callers.isEmpty()) b.para("Usata da: " + String.join(", ", callers), Font.REG, 8.5f, PdfWriter.BLACK);
            }
        }
    }

    private void database(PdfWriter b) {
        b.heading(1, "6. Base dati");
        Map<String, List<String>> children = new TreeMap<>();
        for (Table t : schema.tables.values())
            for (ForeignKey fk : t.fks) children.computeIfAbsent(fk.refTable, k -> new ArrayList<>()).add(t.name);
        for (Table t : schema.tables.values()) {
            b.heading(2, t.name);
            b.para((t.temporary ? "tabella temporanea - " : "") + t.columns.size() + " colonne - definita in " + t.sourceFile
                    + (t.rowCount >= 0 ? " - " + String.format(Locale.ITALY, "%,d", t.rowCount) + " righe" : "")
                    + (t.dataNote != null ? " (" + t.dataNote + ")" : ""), Font.IT, 8.3f, PdfWriter.GRAY);
            describe(b, DescStore.tableKey(t.name), Util.hash(t.canonical()), t.comment);
            List<String[]> rows = new ArrayList<>();
            for (Column c : t.columns) {
                List<String> key = new ArrayList<>();
                if (t.pk.contains(c.name)) key.add("PK");
                for (ForeignKey fk : t.fks) if (fk.columns.contains(c.name)) key.add("FK " + fk.refTable);
                for (List<String> u : t.uniques) if (u.contains(c.name)) { key.add("UQ"); break; }
                String fill = "";
                if (t.nonNull.containsKey(c.name) && t.rowCount > 0) {
                    long nn = Math.min(t.rowCount, t.nonNull.get(c.name));
                    fill = nn == 0 ? "**vuota" : nn == t.rowCount ? "100%" : Math.max(1, Math.round(nn * 100.0 / t.rowCount)) + "%";
                }
                rows.add(new String[]{"**" + c.name, c.type, c.nullable ? "" : "no", c.defaultValue == null ? "" : c.defaultValue, String.join(", ", key), fill, c.comment == null ? "" : c.comment});
            }
            b.table(new String[]{"Colonna", "Tipo", "Null", "Default", "Chiave", "Pieno", "Descrizione"}, new float[]{0.19f, 0.16f, 0.05f, 0.1f, 0.14f, 0.07f, 0.29f}, rows, new Font[]{Font.REG, Font.MONO});
            if (!t.lookupRows.isEmpty()) {
                b.heading(3, "Contenuto (tabella di decodifica)");
                float[] fr = new float[t.lookupHead.size()];
                Arrays.fill(fr, 1f / fr.length);
                b.table(t.lookupHead.toArray(new String[0]), fr, t.lookupRows, null);
            }
            if (!t.values.isEmpty()) {
                b.heading(3, "Valori presenti nelle colonne codice");
                for (Map.Entry<String, List<String[]>> v : t.values.entrySet())
                    b.bullet(v.getKey() + ": " + v.getValue().stream().map(x -> x[0] + " (" + x[1] + ")").collect(Collectors.joining(", ")), 0);
            }
            if (!t.indexes.isEmpty()) {
                b.heading(3, "Indici");
                for (Index ix : t.indexes) b.bullet(ix.name + (ix.unique ? " (univoco)" : "") + ": " + String.join(", ", ix.columns), 0);
            }
            if (!t.checks.isEmpty()) {
                b.heading(3, "Vincoli di controllo");
                for (String c : t.checks) b.bullet(Util.collapse(c), 0);
            }
            List<String> parents = t.fks.stream().map(f -> f.refTable).distinct().collect(Collectors.toList());
            List<String> ch = children.getOrDefault(t.name, Collections.emptyList()).stream().distinct().collect(Collectors.toList());
            if (!parents.isEmpty() || !ch.isEmpty()) {
                b.need(PdfWriter.relationsHeight(parents.size(), ch.size()) + 40);
                b.heading(3, "Relazioni");
                b.relations(t.name, parents, ch);
                for (ForeignKey fk : t.fks)
                    b.bullet(String.join(", ", fk.columns) + " -> " + fk.refTable + (fk.refColumns.isEmpty() ? "" : "(" + String.join(", ", fk.refColumns) + ")")
                            + (fk.onDelete != null ? " - on delete " + fk.onDelete.toLowerCase(Locale.ROOT) : ""), 0);
            }
            Map<MethodInfo, Set<Character>> us = usage.get(t.name);
            b.heading(3, "Usata da");
            if (us == null) b.para("Nessun accesso trovato nel codice Java.", Font.IT, 8.5f, PdfWriter.GRAY);
            else {
                List<String[]> r = new ArrayList<>();
                for (Map.Entry<MethodInfo, Set<Character>> e : us.entrySet()) r.add(new String[]{e.getKey().shortId(), Util.ops(e.getValue())});
                b.table(new String[]{"Classe.metodo", "Operazioni"}, new float[]{0.75f, 0.25f}, r, null);
            }
            List<String> trg = schema.triggers.values().stream().filter(g -> t.name.equals(g.table)).map(g -> g.name + " (" + g.timing.toLowerCase(Locale.ROOT) + ")").collect(Collectors.toList());
            if (!trg.isEmpty()) b.para("Trigger: " + String.join(", ", trg), Font.REG, 8.5f, PdfWriter.BLACK);
        }
        if (!schema.views.isEmpty()) {
            b.heading(2, "Viste");
            for (View v : schema.views.values()) {
                b.heading(3, v.name);
                if (v.comment != null) b.para(v.comment);
                b.para("Tabelle: " + String.join(", ", v.tables), Font.REG, 8.5f, PdfWriter.BLACK);
                b.code(SqlAnalyzer.pretty(v.sql));
            }
        }
        if (!schema.sequences.isEmpty()) {
            b.heading(2, "Sequence");
            List<String[]> r = new ArrayList<>();
            for (Sequence s : schema.sequences.values()) r.add(new String[]{"**" + s.name, Util.collapse(s.details)});
            b.table(new String[]{"Nome", "Parametri"}, new float[]{0.35f, 0.65f}, r, null);
        }
        if (!schema.triggers.isEmpty()) {
            b.heading(2, "Trigger");
            List<String[]> r = new ArrayList<>();
            for (Trigger g : schema.triggers.values()) r.add(new String[]{"**" + g.name, g.table, g.timing.toLowerCase(Locale.ROOT)});
            b.table(new String[]{"Nome", "Tabella", "Quando"}, new float[]{0.35f, 0.25f, 0.4f}, r, null);
        }
        if (!schema.routines.isEmpty()) {
            b.heading(2, "Codice PL/SQL");
            List<String[]> r = new ArrayList<>();
            for (Routine x : schema.routines.values()) r.add(new String[]{"**" + x.name, x.kind.toLowerCase(Locale.ROOT), tablesText(x.tables)});
            b.table(new String[]{"Nome", "Tipo", "Tabelle usate"}, new float[]{0.3f, 0.18f, 0.52f}, r, null);
        }
    }

    private void crud(PdfWriter b) {
        b.heading(1, "7. Matrice CRUD");
        b.para("Per ogni tabella, le classi che la usano e con quali operazioni (C=inserimento, R=lettura, U=modifica, D=cancellazione).", Font.IT, 8.5f, PdfWriter.GRAY);
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, Map<MethodInfo, Set<Character>>> e : usage.entrySet()) {
            Map<String, Set<Character>> byClass = new TreeMap<>();
            e.getValue().forEach((m, ops) -> byClass.computeIfAbsent(m.owner.name, k -> new TreeSet<>()).addAll(ops));
            boolean first = true;
            for (Map.Entry<String, Set<Character>> c : byClass.entrySet()) {
                Set<Character> o = c.getValue();
                rows.add(new String[]{first ? "**" + e.getKey() : "", c.getKey(), o.contains('C') ? "C" : "", o.contains('R') ? "R" : "", o.contains('U') ? "U" : "", o.contains('D') ? "D" : ""});
                first = false;
            }
        }
        if (rows.isEmpty()) b.para("Nessun accesso al database individuato.");
        else b.table(new String[]{"Tabella", "Classe", "C", "R", "U", "D"}, new float[]{0.3f, 0.46f, 0.06f, 0.06f, 0.06f, 0.06f}, rows, null);
    }

    private void attention(PdfWriter b) {
        b.heading(1, "8. Punti di attenzione");
        b.para("Segnalazioni automatiche da verificare: non sono per forza errori.", Font.IT, 8.5f, PdfWriter.GRAY);
        List<String> unused = new ArrayList<>();
        for (Table t : schema.tables.values()) {
            boolean viaView = schema.views.values().stream().anyMatch(v -> v.tables.contains(t.name) && usage.containsKey(v.name));
            boolean viaPlsql = schema.routines.values().stream().anyMatch(r -> r.tables.containsKey(t.name)) || schema.triggers.values().stream().anyMatch(g -> g.body != null && SqlAnalyzer.analyze(g.body, schema).tables.containsKey(t.name));
            if (!usage.containsKey(t.name) && !viaView) unused.add(t.name + (viaPlsql ? " (usata solo da PL/SQL)" : ""));
        }
        section(b, "Tabelle mai usate dal codice Java", unused);
        List<String> unk = new ArrayList<>();
        unknown.forEach((t, ms) -> unk.add(t + " - in " + ms.stream().map(MethodInfo::shortId).collect(Collectors.joining(", "))));
        section(b, "Oggetti usati nel codice ma assenti nelle DDL", unk);
        List<String> nopk = schema.tables.values().stream().filter(t -> t.pk.isEmpty() && !t.temporary).map(t -> t.name).collect(Collectors.toList());
        section(b, "Tabelle senza chiave primaria", nopk);
        List<String> fkNoIdx = new ArrayList<>();
        for (Table t : schema.tables.values())
            for (ForeignKey fk : t.fks) if (!indexed(t, fk.columns)) fkNoIdx.add(t.name + "(" + String.join(", ", fk.columns) + ") -> " + fk.refTable);
        section(b, "Chiavi esterne senza indice (rischio lock e lentezza su delete/update del padre)", fkNoIdx);
        List<String> star = new ArrayList<>(), dyn = new ArrayList<>();
        for (MethodInfo m : project.allMethods())
            for (SqlUse u : m.sql) {
                if (u.selectStar) star.add(m.shortId() + " riga " + u.line);
                if (u.dynamic) dyn.add(m.shortId() + " riga " + u.line);
            }
        section(b, "Query con SELECT * (fragili se cambia la tabella)", star);
        section(b, "Query con parti concatenate a runtime (verificare l'uso di parametri ? contro SQL injection)", dyn);
        List<String> deadCols = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<String> starTables = new HashSet<>();
        for (MethodInfo m : project.allMethods())
            for (SqlUse u : m.sql) { used.addAll(u.columns); if (u.selectStar) starTables.addAll(u.tables.keySet()); }
        for (Table t : schema.tables.values()) {
            if (!usage.containsKey(t.name) || starTables.contains(t.name)) continue;
            for (Column c : t.columns) if (!used.contains(t.name + "." + c.name) && !t.pk.contains(c.name)) deadCols.add(t.name + "." + c.name);
        }
        section(b, "Colonne mai citate nelle query Java (possibili colonne inutilizzate)", deadCols);
        if (schema.tables.values().stream().anyMatch(t -> t.rowCount >= 0)) {
            List<String> empty = new ArrayList<>(), emptyCols = new ArrayList<>();
            for (Table t : schema.tables.values()) {
                if (t.rowCount == 0) empty.add(t.name);
                if (t.rowCount > 0) t.nonNull.forEach((c, nn) -> { if (nn == 0) emptyCols.add(t.name + "." + c); });
            }
            section(b, "Tabelle vuote nel database", empty);
            section(b, "Colonne sempre vuote nel database", emptyCols);
        }
        List<String> warn = new ArrayList<>(schema.warnings);
        warn.addAll(project.parseErrors);
        section(b, "Avvisi di lettura dei sorgenti", warn);
    }

    private void section(PdfWriter b, String title, List<String> items) {
        b.heading(3, title + " (" + items.size() + ")");
        if (items.isEmpty()) b.para("Nessuna.", Font.IT, 8.5f, PdfWriter.GRAY);
        else for (String s : items) b.bullet(s, 0);
    }

    private static boolean indexed(Table t, List<String> cols) {
        List<List<String>> idx = new ArrayList<>();
        idx.add(t.pk);
        idx.addAll(t.uniques);
        for (Index i : t.indexes) idx.add(i.columns);
        for (List<String> ix : idx) {
            if (ix.size() < cols.size()) continue;
            if (new HashSet<>(ix.subList(0, cols.size())).equals(new HashSet<>(cols))) return true;
        }
        return false;
    }

    private void docStatus(PdfWriter b) {
        b.heading(1, "9. Stato delle descrizioni");
        b.para("Le descrizioni funzionali si trovano nella cartella descrizioni/. Ognuna porta l'impronta del codice per cui è stata scritta: se il codice cambia, la descrizione finisce qui come da rivedere. Il comando 'pacchetto' prepara per l'AI solo questi elementi.", Font.IT, 8.5f, PdfWriter.GRAY);
        List<String[]> rows = new ArrayList<>();
        for (ClassInfo c : project.classes.values()) {
            DescStore.Status s = desc.status(DescStore.classKey(c.fqcn), c.hash);
            if (s != DescStore.Status.AGGIORNATA) rows.add(new String[]{"classe", c.fqcn, label(s)});
        }
        for (Table t : schema.tables.values()) {
            DescStore.Status s = desc.status(DescStore.tableKey(t.name), Util.hash(t.canonical()));
            if (s != DescStore.Status.AGGIORNATA) rows.add(new String[]{"tabella", t.name, label(s)});
        }
        for (Screen sc : userScreens()) {
            DescStore.Status s = desc.status(DescStore.screenKey(sc.id), sc.hash);
            if (s != DescStore.Status.AGGIORNATA) rows.add(new String[]{"schermata", screenTitle(sc), label(s)});
        }
        if (rows.isEmpty()) b.note("Tutte le descrizioni sono aggiornate.", PdfWriter.OK_BG, PdfWriter.OK);
        else b.table(new String[]{"Tipo", "Elemento", "Stato"}, new float[]{0.12f, 0.68f, 0.2f}, rows, null);
    }

    private static String label(DescStore.Status s) {
        return s == DescStore.Status.MANCANTE ? "mancante" : s == DescStore.Status.DA_RIVEDERE ? "**da rivedere" : "aggiornata";
    }

    // ------------------------------------------------------------ supporto

    private void describe(PdfWriter b, String key, String hash, String fallback) {
        DescStore.Status s = desc.status(key, hash);
        if (s == DescStore.Status.MANCANTE) {
            if (fallback != null && !fallback.trim().isEmpty()) b.para(Util.collapse(fallback));
            else b.note("Descrizione funzionale non ancora scritta.", PdfWriter.LIGHT, PdfWriter.GRAY);
            return;
        }
        if (s == DescStore.Status.DA_RIVEDERE)
            b.note("Attenzione: il codice è cambiato dopo che questa descrizione è stata scritta. Va ricontrollata.", PdfWriter.WARN_BG, PdfWriter.WARN);
        markdown(b, desc.text(key));
        b.space(4);
    }

    /** Markdown essenziale: paragrafi, elenchi puntati, titoletti, blocchi di codice. */
    static void markdown(PdfWriter b, String md) {
        StringBuilder para = new StringBuilder();
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        for (String raw : md.split("\r?\n")) {
            String l = raw.trim();
            if (l.startsWith("```")) {
                if (inCode) { b.code(code.toString()); code.setLength(0); }
                else flushPara(b, para);
                inCode = !inCode;
                continue;
            }
            if (inCode) { code.append(raw).append('\n'); continue; }
            if (l.isEmpty()) { flushPara(b, para); continue; }
            if (l.startsWith("#")) {
                flushPara(b, para);
                b.heading(3, inline(l.replaceFirst("^#+\\s*", "")));
            } else if (l.startsWith("- ") || l.startsWith("* ") || l.matches("^\\d+[.)] .*")) {
                flushPara(b, para);
                int indent = raw.indexOf(l.charAt(0)) >= 2 ? 1 : 0;
                b.bullet(inline(l.replaceFirst("^(- |\\* |\\d+[.)] )", "")), indent);
            } else {
                if (para.length() > 0) para.append(' ');
                para.append(l);
            }
        }
        if (inCode) b.code(code.toString());
        flushPara(b, para);
    }

    private static void flushPara(PdfWriter b, StringBuilder p) {
        if (p.length() > 0) { b.para(inline(p.toString())); p.setLength(0); }
    }

    private static String inline(String s) {
        return s.replace("**", "").replace("__", "").replace("`", "");
    }

    private String classText(ClassInfo c) {
        String t = desc.text(DescStore.classKey(c.fqcn));
        if (t != null) return t.replaceAll("(?m)^#.*$", "").trim();
        return c.javadoc == null ? "" : c.javadoc;
    }

    private List<MethodInfo> entries() {
        return project.allMethods().stream().filter(m -> m.entryKind != null).collect(Collectors.toList());
    }

    /** Tabelle raggiunte da un metodo seguendo le chiamate. */
    Map<String, Set<Character>> reach(MethodInfo start) {
        Map<String, Set<Character>> out = new TreeMap<>();
        Deque<MethodInfo> todo = new ArrayDeque<>();
        Set<MethodInfo> seen = new HashSet<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            MethodInfo m = todo.poll();
            if (!seen.add(m)) continue;
            for (SqlUse u : m.sql) {
                u.tables.forEach((t, o) -> out.computeIfAbsent(t, k -> new TreeSet<>()).addAll(o));
                // procedure e package PL/SQL chiamati: si aggiungono le tabelle che usano
                for (String r : u.routines) {
                    String pkg = r.contains(".") ? r.substring(0, r.indexOf('.')) : r;
                    for (Routine x : schema.routines.values())
                        if (x.name.equals(pkg) || x.name.equals(r))
                            x.tables.forEach((t, o) -> out.computeIfAbsent(t, k -> new TreeSet<>()).addAll(o));
                }
            }
            todo.addAll(m.calls);
        }
        return out;
    }

    private static String tablesText(Map<String, Set<Character>> t) {
        if (t.isEmpty()) return "";
        return t.entrySet().stream().map(e -> e.getKey() + " (" + Util.ops(e.getValue()) + ")").collect(Collectors.joining(", "));
    }

    private int[] statusCounts() {
        int[] r = new int[3];
        for (ClassInfo c : project.classes.values()) r[desc.status(DescStore.classKey(c.fqcn), c.hash).ordinal() == 1 ? 0 : desc.status(DescStore.classKey(c.fqcn), c.hash).ordinal() == 2 ? 1 : 2]++;
        for (Table t : schema.tables.values()) {
            DescStore.Status s = desc.status(DescStore.tableKey(t.name), Util.hash(t.canonical()));
            r[s == DescStore.Status.AGGIORNATA ? 0 : s == DescStore.Status.DA_RIVEDERE ? 1 : 2]++;
        }
        for (Screen sc : userScreens()) {
            DescStore.Status s = desc.status(DescStore.screenKey(sc.id), sc.hash);
            r[s == DescStore.Status.AGGIORNATA ? 0 : s == DescStore.Status.DA_RIVEDERE ? 1 : 2]++;
        }
        return r;
    }

    // ================================================================ interfaccia (programmatore)

    private void interfaceChapter(PdfWriter b) {
        b.heading(1, "3. Interfaccia utente");
        if (project.screens.isEmpty()) {
            b.para("Nessuna schermata Swing o pagina web individuata.", Font.IT, 8.5f, PdfWriter.GRAY);
            return;
        }
        b.para("Schermate Swing e pagine web, con i comandi che offrono, i metodi che li gestiscono e le tabelle che raggiungono. È la mappa schermata -> codice -> dati.", Font.IT, 8.5f, PdfWriter.GRAY);
        List<String[]> rows = new ArrayList<>();
        for (Screen sc : project.screens.values())
            rows.add(new String[]{"**" + screenTitle(sc), sc.kind, sc.cls != null ? sc.cls.fqcn : relFile(sc), String.valueOf(sc.fields.size()), String.valueOf(sc.actions.size())});
        b.table(new String[]{"Schermata", "Tipo", "Classe o file", "Campi", "Comandi"}, new float[]{0.27f, 0.14f, 0.43f, 0.08f, 0.08f}, rows, null);
        for (Screen sc : project.screens.values()) {
            if (sc.actions.isEmpty()) continue;
            b.heading(2, screenTitle(sc));
            b.para(sc.kind + " - " + (sc.cls != null ? sc.cls.fqcn : relFile(sc)), Font.IT, 8.3f, PdfWriter.GRAY);
            List<String[]> r = new ArrayList<>();
            for (UiAction a : sc.actions) {
                String handlers = a.targets.stream().map(MethodInfo::shortId).collect(Collectors.joining(", "));
                if (handlers.isEmpty() && a.url != null) handlers = a.url;
                if (handlers.isEmpty() && !a.opens.isEmpty()) handlers = "apre " + a.opens.stream().map(project.screens::get).filter(Objects::nonNull).map(this::screenTitle).collect(Collectors.joining(", "));
                if (handlers.isEmpty() && a.closes) handlers = "chiude la finestra";
                r.add(new String[]{"**" + a.label, handlers, tablesText(actionTables(a))});
            }
            b.table(new String[]{"Comando", "Gestito da", "Tabelle raggiunte"}, new float[]{0.25f, 0.37f, 0.38f}, r, null);
        }
    }

    // ================================================================ manuale utente

    byte[] userPdf() {
        PdfWriter b = new PdfWriter();
        List<Screen> screens = userScreens();
        b.heading(1, "1. Introduzione");
        String intro = desc.text(DescStore.USER_INTRO);
        if (intro != null) markdown(b, intro);
        else if (desc.overview != null) markdown(b, desc.overview);
        else b.note("Introduzione ancora da scrivere: il comando 'pacchetto' la prepara per l'AI (file descrizioni/utente/_introduzione.md).", PdfWriter.LIGHT, PdfWriter.GRAY);
        b.para("Questo manuale descrive " + screens.size() + (screens.size() == 1 ? " schermata" : " schermate") + " dell'applicazione: per ognuna trovi a cosa serve, i campi da compilare, i comandi disponibili e i messaggi che possono comparire.");

        b.heading(1, "2. Come ci si muove");
        boolean any = false;
        for (Screen sc : screens) {
            if (sc.menus.isEmpty()) continue;
            any = true;
            b.heading(3, "Menu di " + screenTitle(sc));
            for (MenuNode mn : sc.menus) menu(b, mn, 0);
        }
        List<Screen> web = screens.stream().filter(x -> x.id.startsWith("web/")).collect(Collectors.toList());
        if (!web.isEmpty()) {
            any = true;
            b.heading(3, "Pagine");
            List<Screen> starts = web.stream().filter(x -> x.linkedFrom.isEmpty()).collect(Collectors.toList());
            if (starts.isEmpty()) starts = web;
            for (Screen sc : starts) {
                String to = sc.links.stream().map(project.screens::get).filter(Objects::nonNull).map(this::screenTitle).collect(Collectors.joining(", "));
                b.bullet(screenTitle(sc) + (to.isEmpty() ? "" : ": da qui si va a " + to), 0);
            }
        }
        List<Screen> swingOpen = screens.stream().filter(x -> x.id.startsWith("swing/") && !x.links.isEmpty()).collect(Collectors.toList());
        for (Screen sc : swingOpen) {
            any = true;
            String to = sc.links.stream().map(project.screens::get).filter(Objects::nonNull).map(this::screenTitle).collect(Collectors.joining(", "));
            b.bullet("Da " + screenTitle(sc) + " si apre: " + to, 0);
        }
        if (!any) b.para("La navigazione tra le schermate non è stata ricavata automaticamente dal codice.", Font.IT, 8.5f, PdfWriter.GRAY);

        b.heading(1, "3. Le schermate");
        for (Screen sc : screens) {
            b.heading(2, screenTitle(sc));
            b.para(capital(sc.kind), Font.IT, 8.3f, PdfWriter.GRAY);
            String key = DescStore.screenKey(sc.id);
            DescStore.Status st = desc.status(key, sc.hash);
            if (st != DescStore.Status.MANCANTE) {
                if (st == DescStore.Status.DA_RIVEDERE)
                    b.note("La schermata è cambiata dopo che questa descrizione è stata scritta: va ricontrollata.", PdfWriter.WARN_BG, PdfWriter.WARN);
                markdown(b, desc.text(key));
                b.space(4);
            } else {
                b.para(autoIntro(sc));
            }
            if (!sc.headings.isEmpty()) {
                b.heading(3, "Sezioni");
                for (String h : sc.headings) b.bullet(h, 0);
            }
            List<UiField> fields = sc.fields.stream().filter(f -> f.label != null && !f.kind.equals("tabella")).collect(Collectors.toList());
            if (!fields.isEmpty()) {
                b.heading(3, "Campi");
                List<String[]> r = new ArrayList<>();
                for (UiField f : fields) r.add(new String[]{"**" + f.label, f.kind});
                b.table(new String[]{"Campo", "Tipo"}, new float[]{0.6f, 0.4f}, r, null);
            }
            for (List<String> cols : sc.columns) {
                b.heading(3, "Elenco");
                b.para("Colonne mostrate: " + String.join(", ", cols));
            }
            List<UiAction> cmds = sc.actions.stream().filter(a -> !a.kind.equals("caricamento")).collect(Collectors.toList());
            if (!cmds.isEmpty()) {
                b.heading(3, "Comandi");
                for (UiAction a : cmds) {
                    String eff = actionEffect(a);
                    b.bullet(a.label + (a.kind.equals("voce di menu") ? " (menu)" : "") + (eff.isEmpty() ? "" : ": " + eff), 0);
                }
            }
            if (!sc.messages.isEmpty()) {
                b.heading(3, "Messaggi che possono comparire");
                for (String m : sc.messages) b.bullet("«" + m + "»", 0);
            }
            if (!sc.linkedFrom.isEmpty()) {
                b.para("Ci si arriva da: " + sc.linkedFrom.stream().map(project.screens::get).filter(Objects::nonNull).map(this::screenTitle).collect(Collectors.joining(", ")), Font.REG, 8.8f, PdfWriter.BLACK);
            }
            Map<String, Set<Character>> data = new TreeMap<>();
            for (UiAction a : sc.actions) actionTables(a).forEach((t, o) -> data.computeIfAbsent(t, k -> new TreeSet<>()).addAll(o));
            b.note("Per il supporto: " + (sc.cls != null ? "classe " + sc.cls.fqcn : "file " + relFile(sc))
                    + (data.isEmpty() ? "" : " - dati: " + tablesText(data)), PdfWriter.LIGHT, PdfWriter.GRAY);
        }

        int ch = 4;
        List<String[]> gloss = glossary();
        if (!gloss.isEmpty()) {
            b.heading(1, ch++ + ". Glossario dei codici");
            b.para("Significato dei codici, stati e indicatori che si incontrano nelle schermate.", Font.IT, 8.5f, PdfWriter.GRAY);
            b.table(new String[]{"Dato", "Codice", "Significato"}, new float[]{0.3f, 0.2f, 0.5f}, gloss, null);
        }
        List<String[]> msgs = new ArrayList<>();
        for (Screen sc : screens) for (String m : sc.messages) msgs.add(new String[]{m, screenTitle(sc)});
        if (!msgs.isEmpty()) {
            b.heading(1, ch++ + ". Indice dei messaggi");
            b.para("Tutti i messaggi che il programma può mostrare, con la schermata in cui compaiono. Utile per il supporto.", Font.IT, 8.5f, PdfWriter.GRAY);
            msgs.sort(Comparator.comparing(x -> x[0].toLowerCase(Locale.ROOT)));
            b.table(new String[]{"Messaggio", "Schermata"}, new float[]{0.65f, 0.35f}, msgs, null);
        }
        return assemble(b, "Manuale utente");
    }

    private void menu(PdfWriter b, MenuNode mn, int level) {
        String eff = mn.action == null ? "" : actionEffect(mn.action);
        b.bullet(mn.label + (eff.isEmpty() ? "" : ": " + eff), Math.min(level, 3));
        for (MenuNode c : mn.children) menu(b, c, level + 1);
    }

    List<Screen> userScreens() {
        // prima le schermate da cui si parte (menu principale, pagine iniziali), poi le altre
        return project.screens.values().stream().filter(s -> !s.kind.equals("frammento di pagina"))
                .sorted(Comparator.comparingInt((Screen s) -> s.menus.isEmpty() ? 1 : 0)
                        .thenComparingInt(s -> s.linkedFrom.isEmpty() ? 0 : 1)
                        .thenComparingInt(s -> s.id.startsWith("swing/") ? 0 : 1)
                        .thenComparing(this::screenTitle))
                .collect(Collectors.toList());
    }

    String screenTitle(Screen s) {
        if (s.title != null && !s.title.isEmpty()) return s.title;
        if (!s.headings.isEmpty()) return s.headings.get(0);
        if (s.cls != null) {
            String n = s.cls.name.replaceAll("(Frame|Dialog|Panel|Form|Window|View|Gui|UI|Ui)$", "");
            String h = UiAnalyzer.humanize(n.isEmpty() ? s.cls.name : n);
            return h == null ? s.cls.name : h;
        }
        String f = s.id.substring(s.id.lastIndexOf('/') + 1).replaceAll("\\.[a-z]+$", "");
        String h = UiAnalyzer.humanize(f);
        return h == null ? f : h;
    }

    private String relFile(Screen s) {
        return s.id.startsWith("web/") ? s.id.substring(4) : s.file;
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String autoIntro(Screen sc) {
        Map<String, Set<Character>> data = new TreeMap<>();
        for (UiAction a : sc.actions) actionTables(a).forEach((t, o) -> data.computeIfAbsent(t, k -> new TreeSet<>()).addAll(o));
        StringBuilder sb = new StringBuilder("Questa " + (sc.kind.startsWith("pannello") ? "sezione" : sc.kind.contains("pagina") ? "pagina" : "finestra"));
        if (!data.isEmpty()) sb.append(" lavora su: ").append(data.keySet().stream().map(this::friendlyTable).collect(Collectors.joining(", "))).append('.');
        else sb.append(" fa parte dell'interfaccia dell'applicazione.");
        sb.append(" La descrizione completa di come si usa non è ancora stata scritta.");
        return sb.toString();
    }

    /** Descrizione in parole semplici di cosa fa un comando. */
    String actionEffect(UiAction a) {
        List<String> parts = new ArrayList<>();
        Map<String, Set<Character>> t = actionTables(a);
        String[][] verbs = {{"C", "registra"}, {"U", "aggiorna"}, {"D", "elimina"}, {"R", "consulta"}};
        for (String[] v : verbs) {
            List<String> names = new ArrayList<>();
            t.forEach((tb, ops) -> { if (ops.contains(v[0].charAt(0))) names.add(friendlyTable(tb)); });
            if (!names.isEmpty()) parts.add(v[1] + " " + joinAnd(names));
        }
        for (String o : a.opens) if (project.screens.containsKey(o)) parts.add("apre " + screenTitle(project.screens.get(o)));
        if (a.closes) parts.add("chiude la finestra");
        String s = String.join("; ", parts);
        return s;
    }

    private static String joinAnd(List<String> l) {
        if (l.size() <= 1) return String.join("", l);
        return String.join(", ", l.subList(0, l.size() - 1)) + " e " + l.get(l.size() - 1);
    }

    Map<String, Set<Character>> actionTables(UiAction a) {
        Map<String, Set<Character>> out = new TreeMap<>();
        a.directTables.forEach((k, v) -> out.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        for (MethodInfo m : a.targets) reach(m).forEach((k, v) -> out.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        return out;
    }

    /** Nome comprensibile di una tabella: commento breve se c'è, altrimenti il nome in minuscolo. */
    String friendlyTable(String t) {
        Table tb = schema.table(t);
        String d = desc.text(DescStore.tableKey(t));
        String c = tb != null && tb.comment != null ? tb.comment : null;
        String cand = c != null ? c : d != null ? Util.firstSentence(d.replaceAll("(?m)^#.*$", "")) : null;
        if (cand != null && cand.length() <= 45 && !cand.contains("\n")) return cand.replaceAll("\\.$", "").toLowerCase(Locale.ITALIAN);
        return t.replaceFirst("^(V|T|TB|TAB|VW|ANA|ANAG)_(?=[A-Z])", "").toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Codici e significati: tabelle di decodifica, valori letti dal DB, commenti tipo "S = attivo, N = disattivo". */
    private List<String[]> glossary() {
        List<String[]> out = new ArrayList<>();
        java.util.regex.Pattern code = java.util.regex.Pattern.compile("(\\w{1,4})\\s*[=:]\\s*([^,;=]{2,40})");
        for (Table t : schema.tables.values()) {
            if (!t.lookupRows.isEmpty() && t.lookupHead.size() >= 2) {
                boolean first = true;
                for (String[] r : t.lookupRows) {
                    out.add(new String[]{first ? "**" + friendlyTable(t.name) : "", r[0], r.length > 1 ? r[1] : ""});
                    first = false;
                }
            }
            for (Column c : t.columns) {
                if (c.comment == null) continue;
                java.util.regex.Matcher m = code.matcher(c.comment);
                List<String[]> found = new ArrayList<>();
                while (m.find()) found.add(new String[]{m.group(1), m.group(2).trim()});
                if (found.size() < 2) continue;
                boolean first = true;
                String label = UiAnalyzer.humanize(c.name.toLowerCase(Locale.ROOT).replace("flg_", "").replace("cod_", ""));
                for (String[] f : found) {
                    out.add(new String[]{first ? "**" + label + " (" + friendlyTable(t.name) + ")" : "", f[0], f[1]});
                    first = false;
                }
            }
        }
        return out;
    }

    /** Metodi raggiungibili da un insieme di metodi seguendo le chiamate. */
    static Set<MethodInfo> closure(Collection<MethodInfo> start) {
        Set<MethodInfo> seen = new LinkedHashSet<>();
        Deque<MethodInfo> todo = new ArrayDeque<>(start);
        while (!todo.isEmpty()) {
            MethodInfo m = todo.poll();
            if (seen.add(m)) todo.addAll(m.calls);
        }
        return seen;
    }

    private List<String> userItems(String descDir) {
        List<String> items = new ArrayList<>();
        if (desc.text(DescStore.USER_INTRO) == null) {
            StringBuilder sb = new StringBuilder("## Introduzione al manuale utente - DA SCRIVERE\n\n");
            sb.append("- File da scrivere: `").append(descDir).append('/').append(DescStore.USER_INTRO).append(".md` (senza riga hash)\n");
            sb.append("- Scrivi una pagina per chi usa il programma: a cosa serve l'applicazione, chi la usa, quali sono le attività principali e da quale schermata si parte.\n");
            sb.append("- Schermate: ").append(userScreens().stream().map(this::screenTitle).collect(Collectors.joining(", "))).append('\n');
            items.add(sb.toString());
        }
        List<Screen> list = new ArrayList<>(userScreens());
        list.sort(Comparator.comparingInt((Screen x) -> desc.status(DescStore.screenKey(x.id), x.hash) == DescStore.Status.DA_RIVEDERE ? 0 : 1));
        for (Screen sc : list) {
            String key = DescStore.screenKey(sc.id);
            DescStore.Status st = desc.status(key, sc.hash);
            if (st == DescStore.Status.AGGIORNATA) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("## Schermata \"").append(screenTitle(sc)).append("\" - ").append(st == DescStore.Status.MANCANTE ? "DA SCRIVERE" : "DA RIVEDERE").append(" (manuale utente)\n\n");
            sb.append("- File da scrivere: `").append(descDir).append('/').append(key).append(".md`\n");
            sb.append("- Prima riga del file: `hash: ").append(sc.hash).append("`\n");
            sb.append("- Tipo: ").append(sc.kind).append('\n');
            if (!sc.fields.isEmpty()) sb.append("- Campi: ").append(sc.fields.stream().map(f -> f.label + " (" + f.kind + ")").collect(Collectors.joining("; "))).append('\n');
            for (UiAction a : sc.actions) {
                String eff = actionEffect(a);
                sb.append("- Comando \"").append(a.label).append("\"").append(eff.isEmpty() ? "" : ": " + eff).append('\n');
            }
            if (!sc.messages.isEmpty()) sb.append("- Messaggi: ").append(sc.messages.stream().map(m -> "«" + m + "»").collect(Collectors.joining("; "))).append('\n');
            if (st == DescStore.Status.DA_RIVEDERE) sb.append("\nDescrizione attuale da aggiornare:\n\n> ").append(desc.text(key).replace("\n", "\n> ")).append('\n');
            String src = sc.source == null ? "" : sc.source;
            String[] lines = src.split("\n");
            if (lines.length > 400) src = String.join("\n", Arrays.copyOf(lines, 400)) + "\n... (tagliato)";
            sb.append("\nCodice della schermata:\n\n```").append(sc.id.startsWith("web/") ? "jsp" : "java").append('\n').append(src).append("\n```\n");
            // codice dei metodi che gestiscono i comandi (fuori dalla schermata)
            Set<ClassInfo> extra = new LinkedHashSet<>();
            for (UiAction a : sc.actions) for (MethodInfo m : a.targets) if (m.owner != sc.cls) extra.add(m.owner);
            for (ClassInfo c : extra) {
                if (extra.size() > 4) break;
                sb.append("\nCodice di ").append(c.name).append(" (gestisce i comandi):\n\n```java\n").append(c.source).append("\n```\n");
            }
            items.add(sb.toString());
        }
        return items;
    }

    // ================================================================ pacchetto per l'AI

    /** Prepara un file markdown con SOLO gli elementi da descrivere, da dare in pasto all'AI. */
    String packageForAi(int max, String descDir) {
        return packageForAi(max, descDir, "tutti");
    }

    String packageForAi(int max, String descDir, String which) {
        List<String> items = new ArrayList<>();
        boolean user = !which.startsWith("prog"), prog = !which.startsWith("ut");
        if (user) items.addAll(userItems(descDir));
        if (prog) {
        // prima le classi da rivedere, poi le mancanti con SQL, poi le altre mancanti, poi le tabelle
        List<ClassInfo> cls = new ArrayList<>(project.classes.values());
        cls.sort(Comparator.comparingInt((ClassInfo c) -> desc.status(DescStore.classKey(c.fqcn), c.hash) == DescStore.Status.DA_RIVEDERE ? 0 : 1)
                .thenComparingInt(c -> c.hasSql() ? 0 : 1));
        for (ClassInfo c : cls) {
            String key = DescStore.classKey(c.fqcn);
            DescStore.Status s = desc.status(key, c.hash);
            if (s == DescStore.Status.AGGIORNATA) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("## Classe ").append(c.fqcn).append(" - ").append(s == DescStore.Status.MANCANTE ? "DA SCRIVERE" : "DA RIVEDERE").append("\n\n");
            sb.append("- File da scrivere: `").append(descDir).append('/').append(key).append(".md`\n");
            sb.append("- Prima riga del file: `hash: ").append(c.hash).append("`\n");
            Map<String, Set<Character>> t = new TreeMap<>();
            for (MethodInfo m : c.methods) for (SqlUse u : m.sql) u.tables.forEach((k, v) -> t.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
            if (!t.isEmpty()) sb.append("- Tabelle usate: ").append(tablesText(t)).append('\n');
            Set<String> callers = new TreeSet<>();
            for (MethodInfo m : c.methods) for (MethodInfo x : m.calledBy) if (x.owner != c) callers.add(x.owner.name);
            if (!callers.isEmpty()) sb.append("- Usata da: ").append(String.join(", ", callers)).append('\n');
            if (s == DescStore.Status.DA_RIVEDERE) sb.append("\nDescrizione attuale da aggiornare:\n\n> ").append(desc.text(key).replace("\n", "\n> ")).append('\n');
            sb.append("\n```java\n").append(c.source).append("\n```\n");
            items.add(sb.toString());
        }
        for (Table tb : schema.tables.values()) {
            String key = DescStore.tableKey(tb.name);
            String h = Util.hash(tb.canonical());
            DescStore.Status s = desc.status(key, h);
            if (s == DescStore.Status.AGGIORNATA) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("## Tabella ").append(tb.name).append(" - ").append(s == DescStore.Status.MANCANTE ? "DA SCRIVERE" : "DA RIVEDERE").append("\n\n");
            sb.append("- File da scrivere: `").append(descDir).append('/').append(key).append(".md`\n");
            sb.append("- Prima riga del file: `hash: ").append(h).append("`\n");
            if (tb.comment != null) sb.append("- Commento nel DB: ").append(tb.comment).append('\n');
            sb.append("- Colonne: ");
            sb.append(tb.columns.stream().map(c -> c.name + " " + c.type + (c.comment != null ? " (" + c.comment + ")" : "")).collect(Collectors.joining("; "))).append('\n');
            if (!tb.fks.isEmpty()) sb.append("- Collegata a: ").append(tb.fks.stream().map(f -> f.refTable).distinct().collect(Collectors.joining(", "))).append('\n');
            if (tb.rowCount >= 0) sb.append("- Righe nel database: ").append(tb.rowCount).append('\n');
            tb.values.forEach((c, v) -> sb.append("- Valori di ").append(c).append(": ").append(v.stream().map(x -> x[0] + " (" + x[1] + ")").collect(Collectors.joining(", "))).append('\n'));
            if (!tb.lookupRows.isEmpty()) {
                sb.append("- Contenuto completo (").append(String.join(" | ", tb.lookupHead)).append("):\n");
                for (String[] r : tb.lookupRows) sb.append("  - ").append(String.join(" | ", r)).append('\n');
            }
            Map<MethodInfo, Set<Character>> us = usage.get(tb.name);
            if (us != null) sb.append("- Usata da: ").append(us.entrySet().stream().map(e -> e.getKey().shortId() + " " + Util.ops(e.getValue())).collect(Collectors.joining(", "))).append('\n');
            if (s == DescStore.Status.DA_RIVEDERE) sb.append("\nDescrizione attuale da aggiornare:\n\n> ").append(desc.text(key).replace("\n", "\n> ")).append('\n');
            items.add(sb.toString());
        }
        }
        int total = items.size();
        if (max > 0 && items.size() > max) items = items.subList(0, max);

        StringBuilder out = new StringBuilder();
        out.append("# Descrizioni da scrivere - ").append(title).append(client == null ? "" : " (" + client + ")").append("\n\n");
        out.append("Elementi in questo pacchetto: ").append(items.size()).append(" su ").append(total).append(" da sistemare.\n\n");
        out.append("## Istruzioni per l'AI\n\n");
        out.append("Per ogni elemento qui sotto crea (o sovrascrivi) il file indicato. Il file deve contenere:\n\n");
        out.append("1. prima riga: esattamente la riga `hash: ...` indicata per quell'elemento\n");
        out.append("2. seconda riga: `---`\n");
        out.append("3. poi la descrizione, in italiano chiaro e semplice, in Markdown.\n\n");
        out.append("Per una classe spiega: a cosa serve nel funzionamento del programma (non riassumere riga per riga), ");
        out.append("i metodi principali e cosa fanno dal punto di vista funzionale, quali dati legge e scrive e in quali casi, ");
        out.append("eventuali regole di business o casi particolari che si capiscono dal codice. ");
        out.append("Per una tabella spiega: cosa rappresenta un suo record, il significato delle colonne meno ovvie (codici, flag, stati e i loro valori), come si collega alle altre tabelle.\n");
        out.append("Per una SCHERMATA scrivi per il manuale utente, rivolgendoti a chi usa il programma e non lo conosce: a cosa serve e quando si usa, come si compila (campi importanti, obbligatori, controlli e regole che si capiscono dal codice), cosa fa ogni comando, cosa significano i messaggi e cosa fare. Niente nomi di classi, tabelle o termini tecnici. Usa titoletti brevi ed elenchi.\n");
        out.append("Non inventare: se qualcosa non si capisce dal codice, scrivi che va chiarito. Per gli elementi DA RIVEDERE parti dalla descrizione attuale e correggi solo quello che il codice nuovo ha cambiato.\n\n");
        if (desc.overview == null) {
            out.append("Se riesci, scrivi anche `").append(descDir).append("/_applicazione.md` (senza riga hash): una panoramica di una pagina su cosa fa l'applicazione, i suoi moduli e i punti di ingresso.\n\n");
            out.append("Punti di ingresso: ").append(entries().stream().map(m -> m.shortId() + " [" + m.entryKind + "]").collect(Collectors.joining(", "))).append("\n\n");
        }
        out.append("---\n\n");
        for (String it : items) out.append(it).append("\n---\n\n");
        return out.toString();
    }

    /** Elenco chiavi da rivedere con la loro impronta attuale (per il comando conferma). */
    Map<String, String> staleKeys() {
        Map<String, String> m = new TreeMap<>();
        for (Screen sc : userScreens()) {
            String k = DescStore.screenKey(sc.id);
            if (desc.status(k, sc.hash) == DescStore.Status.DA_RIVEDERE) m.put(k, sc.hash);
        }
        for (ClassInfo c : project.classes.values()) {
            String k = DescStore.classKey(c.fqcn);
            if (desc.status(k, c.hash) == DescStore.Status.DA_RIVEDERE) m.put(k, c.hash);
        }
        for (Table t : schema.tables.values()) {
            String k = DescStore.tableKey(t.name);
            String h = Util.hash(t.canonical());
            if (desc.status(k, h) == DescStore.Status.DA_RIVEDERE) m.put(k, h);
        }
        return m;
    }
}
