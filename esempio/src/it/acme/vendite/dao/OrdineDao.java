package it.acme.vendite.dao;

import java.sql.*;

public interface OrdineDao {
    long inserisci(long idCliente, double totale) throws SQLException;
    void chiudiScaduti(int giorni) throws SQLException;
    int contaPerStato(String stato) throws SQLException;
}
