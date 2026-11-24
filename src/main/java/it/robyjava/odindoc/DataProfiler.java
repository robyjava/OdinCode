package it.robyjava.odindoc;

import it.robyjava.odindoc.Model.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Interroga i DATI (mai la struttura, che arriva dalle DDL) per arricchire la documentazione:
 * righe, colonne vuote, valori delle colonne-codice, contenuto delle tabelle di decodifica.
 * Connessione in sola lettura, timeout su ogni query.
 */
final class DataProfiler {

    private static final Pattern CODE_NAME = Pattern.compile("^(FLG|FL|FLAG|STATO|STATUS|STATE|TIPO|TIPOLOGIA|TYPE|TP|COD|CODICE|CD|CATEGORIA|CAT|ESITO|LIVELLO|MODALITA|CAUSALE)(_.*)?$|.*_(FLG|FLAG|STATO|STATUS|TIPO|TYPE|TP|COD|ESITO|CAT)$");
    private static final Pattern SHORT_TYPE = Pattern.compile("^(CHAR|NCHAR|VARCHAR2|NVARCHAR2|VARCHAR)\\s*\\(\\s*([0-9]+).*|^NUMBER\\s*\\(\\s*([0-9]+)\\s*(,\\s*0\\s*)?\\)$");

    private final String driverJar, url, user, password;
    private final long maxRowsForDetail;
    private final Set<String> exclude;
    private final int timeout;

    DataProfiler(String driverJar, String url, String user, String password, long maxRowsForDetail, Set<String> exclude, int timeoutSec) {
        this.driverJar = driverJar;
        this.url = url;
        this.user = user;
        this.password = password;
        this.maxRowsForDetail = maxRowsForDetail;
        this.exclude = exclude;
        this.timeout = timeoutSec;
    }

    void profile(Schema schema) throws Exception {
        Driver driver = loadDriver();
        Properties p = new Properties();
        p.setProperty("user", user);
        if (password != null) p.setProperty("password", password);
        try (Connection c = driver.connect(url, p)) {
            if (c == null) throw new SQLException("Il driver non accetta l'URL " + url);
            c.setReadOnly(true);
            c.setAutoCommit(false);
            int i = 0;
            for (Table t : schema.tables.values()) {
                i++;
                if (exclude.contains(t.name)) { t.dataNote = "esclusa dalla lettura dati per configurazione"; continue; }
                System.out.println("  dati " + i + "/" + schema.tables.size() + ": " + t.name);
                try {
                    table(c, t);
                } catch (SQLException e) {
                    t.dataNote = "lettura dati non riuscita: " + Util.firstSentence(e.getMessage());
                }
            }
            c.rollback();
        }
    }

    private Driver loadDriver() throws Exception {
        if (driverJar != null && !driverJar.isEmpty()) {
            URLClassLoader cl = new URLClassLoader(new URL[]{Path.of(driverJar).toUri().toURL()}, getClass().getClassLoader());
            for (Driver d : ServiceLoader.load(Driver.class, cl)) if (d.acceptsURL(url)) return d;
            return (Driver) Class.forName("oracle.jdbc.OracleDriver", true, cl).getDeclaredConstructor().newInstance();
        }
        return DriverManager.getDriver(url);
    }

    private void table(Connection c, Table t) throws SQLException {
        String tn = q(t.name);
        t.rowCount = scalar(c, "SELECT COUNT(*) FROM " + tn);
        if (t.rowCount == 0) return;
        if (t.rowCount > maxRowsForDetail) {
            t.dataNote = "tabella grande: rilevato solo il numero di righe";
            return;
        }
        // colonne valorizzate, un solo passaggio
        StringBuilder sb = new StringBuilder("SELECT ");
        List<Column> cols = new ArrayList<>();
        for (Column col : t.columns) {
            if (isLob(col)) continue;
            if (!cols.isEmpty()) sb.append(", ");
            sb.append("COUNT(").append(q(col.name)).append(')');
            cols.add(col);
        }
        if (!cols.isEmpty()) {
            sb.append(" FROM ").append(tn);
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(timeout);
                try (ResultSet rs = st.executeQuery(sb.toString())) {
                    if (rs.next()) for (int i = 0; i < cols.size(); i++) t.nonNull.put(cols.get(i).name, rs.getLong(i + 1));
                }
            }
        }
        // tabella di decodifica: la si riporta intera
        boolean lookup = t.rowCount <= 60 && t.columns.size() <= 7;
        if (lookup) {
            List<Column> show = new ArrayList<>();
            for (Column col : t.columns) if (!isLob(col)) show.add(col);
            StringBuilder s = new StringBuilder("SELECT ");
            for (int i = 0; i < show.size(); i++) { if (i > 0) s.append(", "); s.append(q(show.get(i).name)); t.lookupHead.add(show.get(i).name); }
            s.append(" FROM ").append(tn);
            if (!t.pk.isEmpty()) { s.append(" ORDER BY "); for (int i = 0; i < t.pk.size(); i++) { if (i > 0) s.append(", "); s.append(q(t.pk.get(i))); } }
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(timeout);
                try (ResultSet rs = st.executeQuery(s.toString())) {
                    while (rs.next()) {
                        String[] row = new String[show.size()];
                        for (int i = 0; i < show.size(); i++) row[i] = text(rs.getString(i + 1));
                        t.lookupRows.add(row);
                    }
                }
            }
            return;
        }
        // colonne-codice: valori distinti con conteggio
        for (Column col : t.columns) {
            if (!isCodeColumn(t, col)) continue;
            Long nn = t.nonNull.get(col.name);
            if (nn != null && nn == 0) continue;
            String sql = "SELECT * FROM (SELECT " + q(col.name) + ", COUNT(*) N FROM " + tn + " GROUP BY " + q(col.name) + " ORDER BY 2 DESC) WHERE ROWNUM <= 16";
            List<String[]> vals = new ArrayList<>();
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(timeout);
                try (ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) vals.add(new String[]{rs.getString(1) == null ? "(vuoto)" : text(rs.getString(1)), String.valueOf(rs.getLong(2))});
                }
            }
            // se quasi ogni valore è diverso non è un codice
            if (vals.size() == 16 && Long.parseLong(vals.get(0)[1]) <= 1) continue;
            t.values.put(col.name, vals);
        }
    }

    private static boolean isCodeColumn(Table t, Column col) {
        if (t.pk.contains(col.name)) return false;
        for (ForeignKey fk : t.fks) if (fk.columns.contains(col.name)) return false;
        String type = col.type == null ? "" : col.type.toUpperCase(Locale.ROOT);
        java.util.regex.Matcher m = SHORT_TYPE.matcher(type);
        boolean shortType = false;
        if (m.find()) {
            String len = m.group(2) != null ? m.group(2) : m.group(3);
            int l = Integer.parseInt(len);
            shortType = m.group(2) != null ? l <= 3 : l <= 2;
        }
        return shortType || CODE_NAME.matcher(col.name).matches() && !type.startsWith("DATE") && !type.startsWith("TIMESTAMP") && !isLob(col);
    }

    private static boolean isLob(Column c) {
        String t = c.type == null ? "" : c.type.toUpperCase(Locale.ROOT);
        return t.startsWith("CLOB") || t.startsWith("BLOB") || t.startsWith("NCLOB") || t.startsWith("LONG") || t.startsWith("XMLTYPE") || t.startsWith("BFILE") || t.startsWith("RAW");
    }

    private long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(timeout);
            try (ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static String q(String ident) {
        return ident.matches("[A-Z][A-Z0-9_$#]*") ? ident : "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String text(String s) {
        if (s == null) return "";
        s = Util.collapse(s);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
