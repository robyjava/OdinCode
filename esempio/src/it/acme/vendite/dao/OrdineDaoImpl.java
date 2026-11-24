package it.acme.vendite.dao;

import java.sql.*;

/** Implementazione JDBC di {@link OrdineDao}. */
public class OrdineDaoImpl implements OrdineDao {

    private static final String INS_ORDINE =
        "INSERT INTO ORDINE (ID_ORDINE, ID_CLIENTE, DT_ORDINE, COD_STATO, TOTALE) " +
        "VALUES (SEQ_ORDINE.NEXTVAL, ?, SYSDATE, 'IN', ?)";

    private final Connection conn;

    public OrdineDaoImpl(Connection c) { this.conn = c; }

    @Override
    public long inserisci(long idCliente, double totale) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INS_ORDINE)) {
            ps.setLong(1, idCliente);
            ps.setDouble(2, totale);
            ps.executeUpdate();
        }
        String righe = "INSERT INTO RIGA_ORDINE (ID_ORDINE, NUM_RIGA, ID_PRODOTTO, QUANTITA, PREZZO_UNIT) "
                     + "SELECT SEQ_ORDINE.CURRVAL, 1, p.ID_PRODOTTO, 1, p.PREZZO FROM PRODOTTO p WHERE p.CODICE = ?";
        conn.prepareStatement(righe).executeUpdate();
        return 0;
    }

    @Override
    public void chiudiScaduti(int giorni) throws SQLException {
        try (CallableStatement cs = conn.prepareCall("{call PKG_ORDINI.CHIUDI_SCADUTI(?)}")) {
            cs.setInt(1, giorni);
            cs.execute();
        }
    }

    @Override
    public int contaPerStato(String stato) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ORDINE o, STATO_ORDINE s WHERE s.COD_STATO = o.COD_STATO";
        sql += " AND o.COD_STATO = ?";
        PreparedStatement ps = conn.prepareStatement(sql);
        sql = "DELETE FROM ORDINE WHERE COD_STATO = 'AN' AND DT_ORDINE < SYSDATE - 365";
        conn.createStatement().executeUpdate(sql);
        String vista = "SELECT * FROM V_ORDINI_APERTI WHERE TOTALE > 1000";
        conn.createStatement().executeQuery(vista);
        String storico = "select count(*) from STORICO_ORDINI";
        conn.createStatement().executeQuery(storico);
        return 0;
    }
}
