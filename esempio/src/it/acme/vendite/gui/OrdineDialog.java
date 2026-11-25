package it.acme.vendite.gui;

import it.acme.vendite.servizi.OrdineService;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ResourceBundle;
import javax.swing.*;

/** Dialogo per inserire un nuovo ordine (stile NetBeans). */
public class OrdineDialog extends JDialog implements ActionListener {

    private OrdineService service;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JTextField jTextField1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JFormattedTextField jFormattedTextField1;
    private javax.swing.JButton jButton1;
    private javax.swing.JButton jButton2;

    public OrdineDialog(JFrame owner) {
        super(owner, "Nuovo ordine", true);
        initComponents();
    }

    private void initComponents() {
        ResourceBundle bundle = ResourceBundle.getBundle("messaggi");
        jLabel1 = new javax.swing.JLabel();
        jTextField1 = new javax.swing.JTextField();
        jLabel2 = new javax.swing.JLabel();
        jFormattedTextField1 = new javax.swing.JFormattedTextField();
        jButton1 = new javax.swing.JButton();
        jButton2 = new javax.swing.JButton();
        jLabel1.setText(bundle.getString("ordine.cliente"));
        jLabel2.setText(bundle.getString("ordine.totale"));
        jButton1.setText(bundle.getString("ordine.salva"));
        jButton2.setText("Annulla");
        jButton1.addActionListener(this);
        jButton2.addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == jButton1) {
            try {
                service.nuovoOrdine(Long.parseLong(jTextField1.getText()), 0);
                JOptionPane.showMessageDialog(this, "Ordine registrato");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Controlla il codice cliente e il totale");
            }
        } else if (e.getSource() == jButton2) {
            dispose();
        }
    }
}
