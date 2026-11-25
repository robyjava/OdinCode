package it.acme.vendite.gui;

import javax.swing.*;

/** Finestra principale dell'applicazione vendite. */
public class MainFrame extends JFrame {

    public MainFrame() {
        super("ACME Vendite");
        JMenuBar barra = new JMenuBar();
        JMenu archivio = new JMenu("Archivio");
        JMenuItem miClienti = new JMenuItem("Clienti");
        miClienti.addActionListener(e -> new ClientiFrame().setVisible(true));
        JMenuItem miNuovoOrdine = new JMenuItem("Nuovo ordine");
        miNuovoOrdine.addActionListener(e -> new OrdineDialog(this).setVisible(true));
        archivio.add(miClienti);
        archivio.add(miNuovoOrdine);
        JMenu aiuto = new JMenu("?");
        JMenuItem miEsci = new JMenuItem("Esci");
        miEsci.addActionListener(e -> dispose());
        aiuto.add(miEsci);
        barra.add(archivio);
        barra.add(aiuto);
        setJMenuBar(barra);
    }
}
