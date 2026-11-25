# OdinDoc

> **Per iniziare leggi il [Manuale di utilizzo](MANUALE.md).**

Genera il **manuale utente** e il **manuale del programmatore** in PDF di un'applicazione partendo da:

- gli **script DDL** Oracle (tutti in una cartella): tabelle, colonne, chiavi, indici, commenti, viste, sequence, trigger, package
- i **sorgenti Java** che usano il database con **JDBC puro**
- facoltativamente, i **dati** del database (via ojdbc, in sola lettura)

Il programma non usa l'AI e non consuma token: lo rilanci quante volte vuoi.
L'AI serve solo per le descrizioni funzionali, e solo per le parti nuove o cambiate.

## Compilare

Serve Java 11 o superiore e Maven.

```
mvn package
```

Ottieni `target/odin-doc.jar`, un unico jar con tutto dentro.

## I tre comandi

```
java -jar odin-doc.jar genera    acme.properties             crea il PDF
java -jar odin-doc.jar pacchetto acme.properties             prepara per l'AI solo le descrizioni mancanti o da rivedere
java -jar odin-doc.jar conferma  acme.properties [nome]      la modifica al codice non cambia la descrizione: la segna come aggiornata
```

La configurazione di un cliente sta in un file `.properties`: parti da uno degli esempi nella cartella `configurazioni/` (vedi `docs/CONFIGURAZIONE.md`).

## Come si lavora

**La prima volta per un cliente**

1. `genera`: esce subito il PDF con tutta la parte tecnica (architettura, punti di ingresso, query, tabelle, matrice CRUD, punti di attenzione).
2. `pacchetto`: crea `da-descrivere.md` con le istruzioni per l'AI e solo il materiale necessario.
3. Dai il pacchetto a Claude Code (Claudia), ad esempio: *"esegui le istruzioni del file da-descrivere.md"*. Scrive i file nella cartella `descrizioni/`.
4. `genera` di nuovo: le descrizioni entrano nel PDF.

Se il pacchetto è troppo grande, usa `pacchetto.max=30` e fallo a rate.

**Quando il codice cambia**

1. `genera`: il capitolo 9 del manuale del programmatore elenca le descrizioni da rivedere, cioè quelle il cui codice è cambiato.
2. Se la modifica non tocca il senso della descrizione: `conferma` (tutte) o `conferma NomeClasse` (una sola).
3. Altrimenti `pacchetto`: dentro ci sono solo le classi e le tabelle cambiate, quindi pochi token.

Cambiare solo commenti o formattazione **non** rende una descrizione da rivedere.

## La cartella descrizioni

```
descrizioni/
  _applicazione.md                      panoramica generale (testo libero)
  classi/it.acme.vendite.dao.OrdineDao.md
  tabelle/ORDINE.md
```

Ogni file inizia con l'impronta del codice per cui è stato scritto:

```
hash: 0a39cfc23688
---
Testo in Markdown: paragrafi, elenchi con "- ", titoletti con "#", blocchi ```.
```

Conviene tenerla sotto git insieme al progetto del cliente.

## Collegamento al database (facoltativo)

La struttura arriva **sempre e solo dalle DDL**. Il database serve a leggere i dati:

- numero di righe per tabella
- percentuale di valori pieni per colonna, colonne sempre vuote
- valori presenti nelle colonne-codice (flag, stati, tipi) con il loro conteggio
- contenuto completo delle tabelle di decodifica (poche righe e poche colonne)

Questi valori finiscono anche nel pacchetto per l'AI, così le descrizioni dei codici sono corrette.

Sicurezza: connessione in sola lettura, timeout su ogni query, soglia di righe oltre la quale si limita al conteggio, elenco di tabelle escluse (`db.escludi`) per i dati personali. La password è meglio non scriverla nel file: se manca viene chiesta all'avvio, oppure si legge da `ODINDOC_DB_PASSWORD`. Il driver ojdbc si indica con `db.driver.jar` e non è una dipendenza del progetto.

Le query usano `ROWNUM`, quindi funzionano anche su Oracle vecchi.

## Cosa riconosce nel codice

- query in stringhe, concatenazioni `+`, `+=`, `StringBuilder`/`StringBuffer`, costanti (anche di altre classi), `String.format`, text block
- `prepareStatement`, `prepareCall`, `executeQuery`/`executeUpdate`/`execute`, ma anche qualunque metodo a cui passi una stringa SQL o che la restituisce
- chiamate a procedure e package (`{call PKG.PROC(?)}`, blocchi `BEGIN ... END`)
- punti di ingresso: `main`, servlet, annotazioni REST/Spring, job Quartz, `Runnable`, listener
- chiamate tra classi, anche tramite interfacce (DAO + implementazione)

**Limiti noti**: se una query viene costruita in un metodo e passata come parametro a un altro, è attribuita al metodo che la costruisce. Le parti calcolate a runtime compaiono come `<...>`. Non risolve framework che generano SQL da soli (Hibernate, MyBatis XML).

## Esempio

La cartella `esempio/` contiene un piccolo progetto finto. Dalla radice del progetto:

```
java -jar target/odin-doc.jar genera esempio/esempio.properties
```
