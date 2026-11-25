package it.acme.vendite.gui;

import it.acme.vendite.dao.ClienteDao;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public class ClientiFrame extends JFrame {

    private final ClienteDao clienteDao = new ClienteDao(null);
    private final JTextField txtRagioneSociale = new JTextField(30);
    private final JComboBox<String> cbTipo = new JComboBox<>();
    private final JTable tblClienti = new JTable();

    public ClientiFrame() {
        setTitle("Gestione clienti");
        JPanel filtri = new JPanel();
        filtri.setBorder(BorderFactory.createTitledBorder("Ricerca"));
        filtri.add(new JLabel("Ragione sociale:"));
        filtri.add(txtRagioneSociale);
        filtri.add(new JLabel("Tipo cliente"));
        filtri.add(cbTipo);
        JCheckBox chkSoloAttivi = new JCheckBox("Solo clienti attivi");
        filtri.add(chkSoloAttivi);
        tblClienti.setModel(new DefaultTableModel(new Object[][]{}, new String[]{"Codice", "Ragione sociale", "Partita IVA", "Attivo"}));

        JButton btnCerca = new JButton("Cerca");
        btnCerca.addActionListener(e -> {
            try {
                clienteDao.cerca(txtRagioneSociale.getText(), (String) cbTipo.getSelectedItem());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Errore durante la ricerca: " + ex.getMessage());
            }
        });
        JButton btnDisattiva = new JButton("Disattiva");
        btnDisattiva.setToolTipText("Disattiva il cliente selezionato");
        btnDisattiva.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int ok = JOptionPane.showConfirmDialog(ClientiFrame.this, "Vuoi davvero disattivare il cliente selezionato?");
                if (ok == JOptionPane.YES_OPTION) {
                    try {
                        clienteDao.disattiva(1L);
                        JOptionPane.showMessageDialog(ClientiFrame.this, "Cliente disattivato correttamente");
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(ClientiFrame.this, "Impossibile disattivare il cliente");
                    }
                }
            }
        });
        JButton btnChiudi = new JButton("Chiudi");
        btnChiudi.addActionListener(e -> dispose());
    }
}
