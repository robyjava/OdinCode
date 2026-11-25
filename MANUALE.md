# OdinDoc - Manuale di utilizzo

## In breve

OdinDoc legge il database e il codice Java di un'applicazione e scrive da solo un PDF che spiega com'è fatta.

Tu gli dai **due cartelle** e lui ti restituisce **un PDF**. Non serve altro.

---

## Cosa serve al programma

Al programma servono solo due cose, che hai già nel progetto del cliente:

1. **La cartella con gli script del database**: i file `.sql`, `.plsql`, `.pks`/`.pkb` e simili con i `CREATE TABLE`, le viste, i trigger e così via. Basta la cartella: il programma entra da solo in tutte le sottocartelle.
2. **La cartella con i sorgenti Java**: di solito è `src/main/java` del progetto.

Non serve collegarsi al database, non serve l'AI e non serve internet.

Sul notebook serve **Java 11 o superiore**.

---

## Installazione (una volta sola)

1. Crea la cartella `C:\odindoc`.
2. Vai sul repository GitHub **OdinCode** → **Releases** → apri l'ultima versione → scarica **odin-doc.jar**.
3. Metti `odin-doc.jar` in `C:\odindoc`.

Fatto.

---

## Preparare un cliente (una volta per cliente)

Il programma ha bisogno di sapere **dove sono** le due cartelle. Glielo dici con un piccolo file di testo.

1. Crea il file `C:\odindoc\acme.properties`, con il nome del cliente al posto di `acme`.
2. Aprilo con il Blocco note e scrivici queste righe, mettendo i tuoi percorsi:

```
titolo=Gestione Vendite
cliente=ACME S.p.A.
ddl=C:/clienti/acme/db
src=C:/clienti/acme/progetto/src/main/java
output=C:/odindoc/acme/documentazione.pdf
descrizioni=C:/odindoc/acme/descrizioni
pacchetto=C:/odindoc/acme/da-descrivere.md
```

Cosa vuol dire ogni riga:

- **titolo**: il nome dell'applicazione, va in copertina.
- **cliente**: il nome del cliente, va in copertina.
- **ddl**: la cartella con gli script del database.
- **src**: la cartella con i sorgenti Java.
- **output**: dove salvare il PDF.
- **descrizioni**: dove tenere le spiegazioni scritte dall'AI. Per ora ignorala, la usi più avanti.
- **pacchetto**: il file da dare all'AI. Anche questa per ora ignorala.

**Attenzione:** nei percorsi usa la barra normale `/`, non quella rovesciata `\`.

Nel repository, nella cartella `configurazioni/`, trovi questo file già pronto da copiare.

---

## Creare il PDF

Apri il **Prompt dei comandi** e scrivi:

```
cd C:\odindoc
java -jar odin-doc.jar genera acme.properties
```

Dopo qualche secondo vedi qualcosa del genere:

```
OdinDoc 1.1
Leggo le DDL da C:/clienti/acme/db ...
  letti 412 file, 1530 istruzioni
  120 tabelle, 8 viste, 3 oggetti PL/SQL
Leggo i sorgenti Java da C:/clienti/acme/progetto/src/main/java ...
  340 classi, 512 query individuate
PDF creato: C:\odindoc\acme\documentazione.pdf
```

Apri il PDF. **Questo è già il risultato.**

---

## Cosa trovi nel PDF

1. **Panoramica**: i numeri dell'applicazione.
2. **Architettura**: tutte le classi, divise per package.
3. **Punti di ingresso**: da dove parte il programma (main, servlet, batch) e quali tabelle tocca.
4. **Componenti**: una scheda per ogni classe, con i metodi e le query SQL che usa.
5. **Base dati**: una scheda per ogni tabella, con le colonne, le relazioni e chi la usa.
6. **Matrice CRUD**: chi legge e chi scrive ogni tabella.
7. **Punti di attenzione**: cose da controllare, per esempio tabelle mai usate o query a rischio.
8. **Stato delle descrizioni**: quali spiegazioni mancano (vedi sotto).

Ogni volta che il codice del cliente cambia, rilanci lo stesso comando e il PDF si aggiorna.

---

## Facoltativo: le spiegazioni scritte dall'AI

Il PDF descrive **com'è fatto** il programma, ma non spiega **a cosa serve** ogni classe con parole umane. Per quello si usa l'AI, una volta sola.

**Passo 1.** Prepara il materiale per l'AI:

```
java -jar odin-doc.jar pacchetto acme.properties
```

Crea il file `C:\odindoc\acme\da-descrivere.md`, che contiene solo le cose ancora da spiegare.

**Passo 2.** Apri Claude Code nella cartella `C:\odindoc` e scrivi:

> esegui le istruzioni del file acme/da-descrivere.md

Claude scrive le spiegazioni nella cartella `C:\odindoc\acme\descrizioni`.

**Passo 3.** Rilancia `genera`: ora il PDF contiene anche le spiegazioni.

### Quando il codice cambia

Il programma si accorge da solo di quali classi sono cambiate e le segna come **da rivedere** nel capitolo 8.

- Se la modifica è piccola e la spiegazione va ancora bene:
  ```
  java -jar odin-doc.jar conferma acme.properties
  ```
- Se la spiegazione va riscritta, rilanci `pacchetto`: dentro trovi **solo** le classi cambiate, quindi l'AI lavora poco.

---

## Facoltativo: leggere anche i dati dal database

Se colleghi il database, il PDF mostra anche quante righe ha ogni tabella, quali colonne sono sempre vuote e quali valori hanno i campi codice (stati, flag, tipi).

Aggiungi queste righe al tuo `acme.properties`:

```
db.url=jdbc:oracle:thin:@//server:1521/SERVIZIO
db.utente=LETTORE
db.driver.jar=C:/drivers/ojdbc11.jar
```

La password non scriverla nel file: te la chiede quando lanci il comando.

Il programma **legge e basta**, non modifica mai niente. Usa comunque un utente di sola lettura, meglio se su un ambiente di test.

Se ci sono tabelle con dati personali, escludile:

```
db.escludi=ANAGRAFICA_PERSONE,DOCUMENTI
```

---

## I tre comandi in sintesi

```
java -jar odin-doc.jar genera    acme.properties     crea il PDF
java -jar odin-doc.jar pacchetto acme.properties     prepara il materiale per l'AI
java -jar odin-doc.jar conferma  acme.properties     segna come ancora valide le spiegazioni
```

---

## Se qualcosa non va

- **"java non è riconosciuto"**: Java non è installato o non è nel PATH.
- **"Manca il parametro 'ddl'"**: nel file `.properties` manca la riga `ddl=`, oppure hai scritto male il nome del file.
- **"0 tabelle"**: guarda la riga *file non letti*: se gli script hanno un'estensione particolare, aggiungila con `ddl.estensioni=` nel file `.properties`.
- **"Cartella inesistente"**: il percorso è sbagliato. Controlla di aver usato la barra `/`.
- **Una query che sai esserci non compare nel PDF**: annota la classe e il metodo e segnalalo, così si migliora il programma.

Per i dettagli su tutti i parametri vedi `docs/CONFIGURAZIONE.md`.
