package it.robyjava.odindoc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Descrizioni scritte a mano (o dall'AI) che il programma fonde nella documentazione.
 * Ogni file inizia con "hash: xxxx" = impronta del codice al momento in cui la descrizione è stata scritta.
 * Se il codice cambia, l'impronta non torna più e la descrizione viene segnalata "da rivedere".
 *
 *   descrizioni/_applicazione.md          panoramica generale (senza hash)
 *   descrizioni/classi/&lt;fqcn&gt;.md          una per classe
 *   descrizioni/tabelle/&lt;TABELLA&gt;.md      una per tabella
 */
final class DescStore {

    enum Status { MANCANTE, AGGIORNATA, DA_RIVEDERE }

    static final class Entry {
        String hash, text;
        Path file;
    }

    private final Path dir;
    private final Map<String, Entry> entries = new HashMap<>();
    String overview;

    DescStore(Path dir) throws IOException {
        this.dir = dir;
        if (dir == null) return;
        Path ov = dir.resolve("_applicazione.md");
        if (Files.exists(ov)) overview = Util.read(ov).trim();
        load("classi");
        load("tabelle");
        load("utente");
    }

    private void load(String sub) throws IOException {
        Path d = dir.resolve(sub);
        if (!Files.isDirectory(d)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.md")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                String key = sub + "/" + name.substring(0, name.length() - 3);
                Entry e = new Entry();
                e.file = p;
                String txt = Util.read(p);
                String[] lines = txt.split("\r?\n", -1);
                int start = 0;
                if (lines.length > 0 && lines[0].toLowerCase(Locale.ROOT).startsWith("hash:")) {
                    e.hash = lines[0].substring(5).trim();
                    start = 1;
                    if (lines.length > 1 && lines[1].trim().equals("---")) start = 2;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < lines.length; i++) sb.append(lines[i]).append('\n');
                e.text = sb.toString().trim();
                entries.put(key, e);
            }
        }
    }

    static String classKey(String fqcn) { return "classi/" + fqcn; }

    static String tableKey(String table) { return "tabelle/" + table; }

    /** swing/it.x.Finestra -> utente/swing_it.x.Finestra ; web/ordini/lista.jsp -> utente/web_ordini_lista.jsp */
    static String screenKey(String id) { return "utente/" + id.replaceAll("[/\\\\:]", "_"); }

    static final String USER_INTRO = "utente/_introduzione";

    Entry get(String key) { return entries.get(key); }

    String text(String key) {
        Entry e = entries.get(key);
        return e == null || e.text.isEmpty() ? null : e.text;
    }

    Status status(String key, String currentHash) {
        Entry e = entries.get(key);
        if (e == null || e.text.isEmpty()) return Status.MANCANTE;
        return currentHash.equals(e.hash) ? Status.AGGIORNATA : Status.DA_RIVEDERE;
    }

    Path fileFor(String key) {
        return dir.resolve(key + ".md");
    }

    /** Riscrive l'impronta lasciando il testo com'è (per confermare che una modifica al codice non cambia la descrizione). */
    void confirm(String key, String hash) throws IOException {
        Entry e = entries.get(key);
        if (e == null) return;
        write(key, hash, e.text);
        e.hash = hash;
    }

    void write(String key, String hash, String text) throws IOException {
        Path f = fileFor(key);
        Files.createDirectories(f.getParent());
        Files.write(f, ("hash: " + hash + "\n---\n" + text.trim() + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
