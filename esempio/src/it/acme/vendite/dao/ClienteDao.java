package it.acme.vendite.dao;

import java.sql.*;
import java.util.*;

/**
 * Accesso ai dati dei clienti.
 */
public class ClienteDao {

    private final Connection conn;

    public ClienteDao(Connection conn) { this.conn = conn; }

    /** Restituisce i clienti attivi. */
    public List<String> elencoAttivi() throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(Query.SEL_CLIENTE + Query.WHERE_ATTIVI + " ORDER BY RAGIONE_SOCIALE")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(rs.getString(2));
        }
        return out;
    }

    public void disattiva(long id) throws SQLException {
        String sql = "UPDATE CLIENTE SET FLG_ATTIVO = 'N' WHERE ID_CLIENTE = ?";
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setLong(1, id);
        ps.executeUpdate();
    }

    /** Ricerca per ragione sociale: attenzione, concatena il filtro. */
    public List<String> cerca(String filtro, String tipo) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM CLIENTE c ");
        sb.append("WHERE c.RAGIONE_SOCIALE LIKE '%").append(filtro).append("%' ");
        if (tipo != null) {
            sb.append(" AND c.TIPO_CLIENTE = ?");
        }
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sb.toString());
        return new ArrayList<>();
    }
}
