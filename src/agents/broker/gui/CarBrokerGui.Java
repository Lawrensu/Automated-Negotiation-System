package agents.broker.gui;


import agents.broker.BrokerAgent;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.util.Map;


public class CarBrokerGui extends JFrame {
    private BrokerAgent myAgent;

    private JTable offersTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    private JButton clearButton, refreshButton;

    public CarBrokerGui(BrokerAgent a) {
        super("Broker Agent - " + a.getLocalName());

        myAgent = a;

        // ── Main layout ──────────────────────────────────────────────────────
        setLayout(new BorderLayout(10, 10));

        // ── TOP: status bar ───────────────────────────────────────────────────
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 0, 10));
        statusLabel = new JLabel("Waiting for offers...");
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        statusLabel.setForeground(Color.GRAY);
        topPanel.add(new JLabel("Status: "));
        topPanel.add(statusLabel);
        add(topPanel, BorderLayout.NORTH);

        // ── CENTER: offers table ──────────────────────────────────────────────
        String[] columnNames = {"#", "Seller Agent", "Car Model", "Price ($)", "Received At"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // read-only table
            }
        };

        offersTable = new JTable(tableModel);
        offersTable.setFillsViewportHeight(true);
        offersTable.setRowHeight(24);
        offersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Column widths
        offersTable.getColumnModel().getColumn(0).setPreferredWidth(30);   // #
        offersTable.getColumnModel().getColumn(1).setPreferredWidth(150);  // Seller
        offersTable.getColumnModel().getColumn(2).setPreferredWidth(150);  // Car Model
        offersTable.getColumnModel().getColumn(3).setPreferredWidth(100);  // Price
        offersTable.getColumnModel().getColumn(4).setPreferredWidth(150);  // Time

        // Alternate row colors
        offersTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(240, 248, 255));
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(offersTable);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Received Offers"));
        add(scrollPane, BorderLayout.CENTER);

        // ── BOTTOM: summary + buttons ─────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        // Summary info
        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        JLabel totalOffersLabel  = new JLabel("Total Offers: 0");
        JLabel bestPriceLabel    = new JLabel("Best Price: N/A");
        JLabel bestSellerLabel   = new JLabel("Best Seller: N/A");

        totalOffersLabel.setFont(new Font("Arial", Font.BOLD, 11));
        bestPriceLabel.setFont(new Font("Arial", Font.BOLD, 11));
        bestSellerLabel.setFont(new Font("Arial", Font.BOLD, 11));
        bestPriceLabel.setForeground(new Color(0, 128, 0));

        summaryPanel.add(totalOffersLabel);
        summaryPanel.add(new JSeparator(SwingConstants.VERTICAL));
        summaryPanel.add(bestPriceLabel);
        summaryPanel.add(new JSeparator(SwingConstants.VERTICAL));
        summaryPanel.add(bestSellerLabel);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        refreshButton = new JButton("Refresh");
        clearButton   = new JButton("Clear All");

        refreshButton.addActionListener(ev -> {
            refreshOffers(totalOffersLabel, bestPriceLabel, bestSellerLabel);
        });

        clearButton.addActionListener(ev -> {
            int confirm = JOptionPane.showConfirmDialog(
                CarBrokerGui.this,
                "Clear all received offers?",
                "Confirm Clear",
                JOptionPane.YES_NO_OPTION
            );
            if (confirm == JOptionPane.YES_OPTION) {
                tableModel.setRowCount(0);
                totalOffersLabel.setText("Total Offers: 0");
                bestPriceLabel.setText("Best Price: N/A");
                bestSellerLabel.setText("Best Seller: N/A");
                statusLabel.setText("Offers cleared.");
                statusLabel.setForeground(Color.GRAY);
            }
        });

        buttonPanel.add(refreshButton);
        buttonPanel.add(clearButton);

        bottomPanel.add(summaryPanel, BorderLayout.WEST);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);

        // ── Window close → terminate agent ───────────────────────────────────
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                myAgent.doDelete();
            }
        });

        setResizable(true);
        setMinimumSize(new Dimension(620, 380));
    }

    /**
     * Called by BrokerAgent when a new offer arrives.
     * Thread-safe: uses SwingUtilities.invokeLater.
     *
     * @param sellerName  AID local name of the seller agent
     * @param carModel    car model string
     * @param price       offered price
     */
    public void addOffer(String sellerName, String carModel, int price) {
        SwingUtilities.invokeLater(() -> {
            int rowNum = tableModel.getRowCount() + 1;
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss")
                    .format(new java.util.Date());

            tableModel.addRow(new Object[]{rowNum, sellerName, carModel, price, timestamp});

            statusLabel.setText("New offer received from " + sellerName);
            statusLabel.setForeground(new Color(0, 128, 0));

            // Auto-scroll to the latest row
            int lastRow = tableModel.getRowCount() - 1;
            offersTable.scrollRectToVisible(offersTable.getCellRect(lastRow, 0, true));
        });
    }

    /**
     * Reads offers from the agent's catalogue and repopulates the table.
     * Useful if the agent stores offers internally.
     */
    private void refreshOffers(JLabel totalLabel, JLabel bestPriceLabel, JLabel bestSellerLabel) {
        Map<String, Integer> catalogue = myAgent.getCatalogue();
        if (catalogue == null || catalogue.isEmpty()) {
            statusLabel.setText("No offers available.");
            statusLabel.setForeground(Color.GRAY);
            return;
        }

        tableModel.setRowCount(0);
        int rowNum = 1;
        int bestPrice = Integer.MAX_VALUE;
        String bestSeller = "N/A";

        for (Map.Entry<String, Integer> entry : catalogue.entrySet()) {
            String key      = entry.getKey();          // e.g. "SellerA::Toyota Camry"
            int    price    = entry.getValue();
            String[] parts  = key.split("::", 2);
            String seller   = parts.length == 2 ? parts[0] : "Unknown";
            String carModel = parts.length == 2 ? parts[1] : key;

            tableModel.addRow(new Object[]{rowNum++, seller, carModel, price,
                    new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())});

            if (price < bestPrice) {
                bestPrice  = price;
                bestSeller = seller;
            }
        }

        totalLabel.setText("Total Offers: " + tableModel.getRowCount());
        bestPriceLabel.setText("Best Price: $" + (bestPrice == Integer.MAX_VALUE ? "N/A" : bestPrice));
        bestSellerLabel.setText("Best Seller: " + bestSeller);
        statusLabel.setText("Refreshed — " + tableModel.getRowCount() + " offer(s) loaded.");
        statusLabel.setForeground(new Color(0, 100, 180));
    }

    public void removeOffer(String sellerName, String carModel) {
    SwingUtilities.invokeLater(() -> {
        for (int row = tableModel.getRowCount() - 1; row >= 0; row--) {
            if (sellerName.equals(tableModel.getValueAt(row, 1)) &&
                carModel.equals(tableModel.getValueAt(row, 2))) {
                tableModel.removeRow(row);
                // Re-number the # column
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    tableModel.setValueAt(i + 1, i, 0);
                }
                statusLabel.setText("Listing removed: " + carModel + " from " + sellerName);
                statusLabel.setForeground(Color.RED);
                break;
             }
            }
        });
    }

    public void display() {
        pack();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation(
            (int) screenSize.getWidth()  / 2 - getWidth()  / 2,
            (int) screenSize.getHeight() / 2 - getHeight() / 2
        );
        super.setVisible(true);
    }
}