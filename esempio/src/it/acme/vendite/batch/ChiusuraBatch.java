package it.acme.vendite.batch;

import it.acme.vendite.servizi.OrdineService;
import java.sql.*;

/** Batch notturno di chiusura ordini scaduti. */
public class ChiusuraBatch {
    public static void main(String[] args) throws Exception {
        Connection c = DriverManager.getConnection(args[0]);
        OrdineService s = new OrdineService(c);
        s.manutenzione();
    }
}
