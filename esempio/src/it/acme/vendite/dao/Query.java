package it.acme.vendite.dao;

/** Query condivise. */
public final class Query {
    private Query() {}
    public static final String SEL_CLIENTE = "SELECT ID_CLIENTE, RAGIONE_SOCIALE, PARTITA_IVA, FLG_ATTIVO "
            + "FROM CLIENTE ";
    public static final String WHERE_ATTIVI = "WHERE FLG_ATTIVO = 'S'";
}
