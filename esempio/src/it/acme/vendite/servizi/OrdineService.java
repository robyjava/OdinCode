package it.acme.vendite.servizi;

import it.acme.vendite.dao.*;
import java.sql.*;

/** Logica di business degli ordini. */
public class OrdineService {
    private final OrdineDao ordineDao;
    private final ClienteDao clienteDao;

    public OrdineService(Connection c) {
        this.ordineDao = new OrdineDaoImpl(c);
        this.clienteDao = new ClienteDao(c);
    }

    /** Crea un ordine per un cliente attivo. */
    public long nuovoOrdine(long idCliente, double totale) throws SQLException {
        clienteDao.elencoAttivi();
        return ordineDao.inserisci(idCliente, totale);
    }

    public void manutenzione() throws SQLException {
        ordineDao.chiudiScaduti(30);
        ordineDao.contaPerStato("IN");
    }
}
