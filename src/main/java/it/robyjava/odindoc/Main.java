package it.robyjava.odindoc;

import it.robyjava.odindoc.Model.*;

import java.io.Console;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * OdinDoc - documentazione di DDL Oracle + sorgenti Java (JDBC) in PDF, senza AI.
 *
 * Uso:
 *   java -jar odin-doc.jar genera    cliente.properties   -> crea il PDF
 *   java -jar odin-doc.jar pacchetto cliente.properties   -> prepara per l'AI solo le descrizioni mancanti o da rivedere
 *   java -jar odin-doc.jar conferma  cliente.properties [chiave]  -> segna come aggiornate le descrizioni da rivedere
 * Ogni chiave del file si può anche passare come --chiave=valore.
 */
public final class Main {

    static final String VERSION = "1.0";

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("ERRORE: " + e.getMessage());
            if (System.getenv("ODINDOC_DEBUG") != null) e.printStackTrace();
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("aiuto")) {
            usage();
            return 0;
        }
        String cmd = args[0];
        Properties cfg = new Properties();
        String key = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && a.contains("=")) cfg.setProperty(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            else if (a.endsWith(".properties")) {
                Properties f = new Properties();
                try (Reader r = Files.newBufferedReader(Paths.get(a), StandardCharsets.UTF_8)) { f.load(r); }
                for (String k : f.stringPropertyNames()) if (!cfg.containsKey(k)) cfg.setProperty(k, f.getProperty(k));
            } else key = a;
        }
        String ddl = req(cfg, "ddl"), src = req(cfg, "src");
        String descDir = cfg.getProperty("descrizioni", "descrizioni");
        String title = cfg.getProperty("titolo", "Documentazione applicazione");
        String client = cfg.getProperty("cliente");
        boolean tests = Boolean.parseBoolean(cfg.getProperty("includi.test", "false"));

        System.out.println("OdinDoc " + VERSION);
        System.out.println("Leggo le DDL da " + ddl + " ...");
        Schema schema = new DdlParser().parse(paths(ddl), tests);
        System.out.println("  " + schema.tables.size() + " tabelle, " + schema.views.size() + " viste, " + schema.routines.size() + " oggetti PL/SQL");
        System.out.println("Leggo i sorgenti Java da " + src + " ...");
        Project project = new JavaAnalyzer(schema).analyze(paths(src), tests);
        long q = project.allMethods().stream().mapToInt(m -> m.sql.size()).sum();
        System.out.println("  " + project.classes.size() + " classi, " + q + " query individuate");
        DescStore desc = new DescStore(Paths.get(descDir));

        switch (cmd) {
            case "genera": {
                profile(cfg, schema);
                String out = cfg.getProperty("output", "documentazione.pdf");
                byte[] pdf = new DocBuilder(schema, project, desc, title, client).pdf();
                Path p = Paths.get(out);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.write(p, pdf);
                System.out.println("PDF creato: " + p.toAbsolutePath() + " (" + pdf.length / 1024 + " KB)");
                return 0;
            }
            case "pacchetto": {
                profile(cfg, schema);
                int max = Integer.parseInt(cfg.getProperty("pacchetto.max", "0"));
                String out = cfg.getProperty("pacchetto", "da-descrivere.md");
                String md = new DocBuilder(schema, project, desc, title, client).packageForAi(max, descDir.replace('\\', '/'));
                Files.write(Paths.get(out), md.getBytes(StandardCharsets.UTF_8));
                System.out.println("Pacchetto per l'AI creato: " + Paths.get(out).toAbsolutePath() + " (" + md.length() / 1024 + " KB, circa " + md.length() / 4 / 1000 + "k token)");
                return 0;
            }
            case "conferma": {
                Map<String, String> stale = new DocBuilder(schema, project, desc, title, client).staleKeys();
                int n = 0;
                for (Map.Entry<String, String> e : stale.entrySet()) {
                    if (key != null && !e.getKey().equals(key) && !e.getKey().endsWith("/" + key)) continue;
                    desc.confirm(e.getKey(), e.getValue());
                    System.out.println("  confermata: " + e.getKey());
                    n++;
                }
                System.out.println(n == 0 ? "Nessuna descrizione da confermare." : n + " descrizioni confermate.");
                return 0;
            }
            default:
                usage();
                return 2;
        }
    }

    private static void profile(Properties cfg, Schema schema) throws Exception {
        String url = cfg.getProperty("db.url");
        if (url == null || url.trim().isEmpty() || Boolean.parseBoolean(cfg.getProperty("db.salta", "false"))) return;
        String user = req(cfg, "db.utente");
        String pwd = cfg.getProperty("db.password", System.getenv("ODINDOC_DB_PASSWORD"));
        if (pwd == null) {
            Console c = System.console();
            if (c == null) throw new IllegalArgumentException("Manca la password: usa db.password o la variabile ODINDOC_DB_PASSWORD");
            pwd = new String(c.readPassword("Password per %s: ", user));
        }
        Set<String> exclude = new HashSet<>();
        for (String s : cfg.getProperty("db.escludi", "").split("[,;\\s]+")) if (!s.isEmpty()) exclude.add(s.toUpperCase(Locale.ROOT));
        System.out.println("Leggo i dati dal database (sola lettura) ...");
        new DataProfiler(cfg.getProperty("db.driver.jar"), url, user, pwd,
                Long.parseLong(cfg.getProperty("db.soglia.righe", "2000000")), exclude,
                Integer.parseInt(cfg.getProperty("db.timeout", "60"))).profile(schema);
    }

    private static List<Path> paths(String s) {
        List<Path> l = new ArrayList<>();
        for (String p : s.split(";")) if (!p.trim().isEmpty()) l.add(Paths.get(p.trim()));
        return l;
    }

    private static String req(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Manca il parametro '" + k + "' (nel file .properties o come --" + k + "=...)");
        return v.trim();
    }

    private static void usage() {
        System.out.println("OdinDoc " + VERSION + " - documentazione da DDL Oracle e sorgenti Java\n\n"
                + "  java -jar odin-doc.jar genera    cliente.properties          crea il PDF\n"
                + "  java -jar odin-doc.jar pacchetto cliente.properties          prepara per l'AI le descrizioni mancanti o da rivedere\n"
                + "  java -jar odin-doc.jar conferma  cliente.properties [nome]   segna come aggiornate le descrizioni da rivedere\n\n"
                + "Ogni parametro del file si può passare anche come --chiave=valore. Vedi esempio.properties.");
    }
}
