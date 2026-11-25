# OdinDoc - Guida alla configurazione

## 1. Cosa ti serve

- **Java 11 o superiore** sul notebook (`java -version` per controllare)
- **odin-doc.jar**: lo scarichi dalla pagina Releases del repository
- **la cartella con gli script DDL** del cliente: tabelle, viste, sequence, trigger, package
- **la cartella con i sorgenti Java**: di solito `src/main/java`

Solo se vuoi leggere anche i dati:

- **il driver Oracle** (`ojdbc8.jar` o `ojdbc11.jar`)
- **un utente in sola lettura**, possibilmente su un ambiente di test o su una copia

## 2. Gli esempi pronti

Nella cartella `configurazioni/` trovi tre file da copiare e adattare:

- **01-solo-codice.properties**: solo DDL e sorgenti. È il punto di partenza consigliato.
- **02-con-database.properties**: aggiunge la lettura dei dati dal database.
- **03-piu-moduli.properties**: applicazione divisa in più progetti o cartelle.

Il consiglio è tenere un file per cliente, ad esempio `acme.properties`, fuori dal repository oppure nella cartella del cliente. Il `.gitignore` impedisce di caricare per sbaglio i file `.properties` dei clienti: entrano nel repository solo quelli della cartella `configurazioni/` e l'esempio.

## 3. Tutti i parametri

### Obbligatori

- **ddl**: cartella degli script DDL. Più cartelle si separano con `;`. Le sottocartelle vengono lette.
- **src**: cartella dei sorgenti Java. Anche qui più cartelle con `;`.

### Consigliati

- **titolo**: titolo del documento, per esempio il nome dell'applicazione.
- **cliente**: nome del cliente, compare in copertina e a piè di pagina.
- **descrizioni**: cartella delle descrizioni funzionali. Se non la indichi usa `descrizioni` nella cartella da cui lanci il comando.
- **output**: il PDF da creare. Se non lo indichi crea `documentazione.pdf`.
- **pacchetto**: il file per l'AI creato dal comando `pacchetto`. Se non lo indichi crea `da-descrivere.md`.

### Facoltativi

- **pacchetto.max**: quanti elementi al massimo per pacchetto. `0` significa tutti. Utile per far lavorare l'AI a rate.
- **includi.test**: `true` per leggere anche le cartelle `/test/`. Normalmente `false`.

### Database (tutti facoltativi)

- **db.url**: indirizzo JDBC, per esempio `jdbc:oracle:thin:@//server:1521/SERVIZIO`. Se è vuoto il database non si usa.
- **db.utente**: utente con cui collegarsi.
- **db.password**: meglio non scriverla nel file. Se manca la chiede all'avvio, oppure la legge dalla variabile d'ambiente `ODINDOC_DB_PASSWORD`.
- **db.driver.jar**: percorso del jar del driver Oracle.
- **db.soglia.righe**: oltre questo numero di righe conta solo le righe, senza analizzare le colonne. Predefinito `2000000`.
- **db.escludi**: tabelle da non leggere, separate da virgola. Mettici quelle con dati personali.
- **db.timeout**: secondi massimi per ogni query. Predefinito `60`.
- **db.salta**: `true` per ignorare il database senza cancellare la configurazione.

Ogni parametro si può anche passare da riga di comando e vince su quello del file:

```
java -jar odin-doc.jar genera acme.properties --db.salta=true --output=C:/temp/prova.pdf
```

### Percorsi su Windows

Nei file `.properties` la barra rovesciata `\` è un carattere speciale. Usa la barra normale `C:/clienti/acme`, oppure raddoppiala: `C:\\clienti\\acme`.

## 4. Il primo giro su un cliente

1. Copia `01-solo-codice.properties` come `acme.properties` e metti i percorsi giusti.
2. Lancia:
   ```
   java -jar odin-doc.jar genera acme.properties
   ```
   A video vedi quante tabelle e quante query ha trovato.
3. Apri il PDF e guarda soprattutto il capitolo 7, **Punti di attenzione**. La sezione *Avvisi di lettura dei sorgenti* dice se qualche file non è stato capito.
4. Se la base ti convince, aggiungi il database (vedi `02-con-database.properties`) e rilancia.

## 5. Le descrizioni con l'AI

1. Prepara il pacchetto:
   ```
   java -jar odin-doc.jar pacchetto acme.properties
   ```
   A video vedi anche quanti token occupa, più o meno.
2. Apri Claude Code (Claudia) nella cartella del cliente e scrivi: *"esegui le istruzioni del file da-descrivere.md"*. Scriverà i file nella cartella `descrizioni/`.
3. Rilancia `genera`: le descrizioni entrano nel PDF.

Se il pacchetto è grosso, metti `pacchetto.max=30` e ripeti i punti 1-3 finché il capitolo 8 non è vuoto.

## 6. Quando il codice del cliente cambia

1. `genera`: il capitolo 8 elenca le descrizioni **da rivedere**, cioè quelle il cui codice è cambiato.
2. Se la modifica non cambia il senso, per esempio è un bug fix:
   ```
   java -jar odin-doc.jar conferma acme.properties NomeClasse
   ```
   Senza nome le conferma tutte.
3. Altrimenti `pacchetto`: contiene solo gli elementi cambiati.

Cambiare solo commenti o formattazione non rende una descrizione da rivedere.

## 7. Se qualcosa non va

- **"Manca il parametro 'ddl'"**: il file `.properties` non ha quella chiave, oppure il percorso del file è sbagliato.
- **"Cartella inesistente"**: controlla il percorso e le barre (vedi *Percorsi su Windows*).
- **Lettere accentate strane nel PDF**: i file vengono letti in UTF-8 e, se non lo sono, in Windows-1252. Segnalami il caso.
- **Una query che sai esserci non compare**: probabilmente è costruita in un modo che ancora non riconosco. Mandami il metodo e lo aggiungo.
- **"Il driver non accetta l'URL"**: controlla `db.url` e che `db.driver.jar` punti a un ojdbc vero.
- **Per vedere l'errore completo**: imposta la variabile d'ambiente `ODINDOC_DEBUG=1` e rilancia.
