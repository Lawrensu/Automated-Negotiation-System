package ans.gui;

import ans.Config;
import ans.agent.AutoDealerAgent;
import ans.model.CarListing;
import ans.model.Offer;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

/**
 * Unlike DealerWindow.java, this window adds a strategy configuration panel
 * where the dealer sets floor price, alpha (concession shape), and strategy
 * type (Boulware / Linear / Conceder) before negotiation starts.
 *
 * Shows a live auto-negotiation log in the Negotiation tab.
 *
 * The window uses the same two-tab layout as DealerWindow but adds a
 * Strategy panel above the inventory form. The dealer agent accepts or
 * declines buyer interest autonomously, and counter-offers are computed
 * automatically — no manual input is required.
 *
 * Window ↔ Agent coupling (static-link pattern):
 *   AutoDealerWindow.pendingWindow is set to 'this' immediately before
 *   container.createNewAgent() is called. WindowAgent.setup() reads and clears it.
 *
 * All Swing updates from JADE hook callbacks are dispatched via
 * SwingUtilities.invokeLater() — hooks run on JADE's agent thread, not the EDT.
 */
public class AutoDealerWindow extends JFrame {

    // ── Static link used during agent construction ────────────────────────────

    static AutoDealerWindow pendingWindow;


    // ── Instance state ────────────────────────────────────────────────────────

    private volatile WindowAgent agent;
    private final Color accent;

    // Strategy panel fields
    private JTextField floorPriceField;
    private JTextField alphaField;
    private JComboBox<String> strategyCombo;

    // Listings tab
    private DefaultTableModel listingsModel;

    // Negotiation tab
    private JTabbedPane tabbedPane;
    private String activeCarId;
    private String activeBuyerAIDName;
    private JLabel negotiationLabel;
    private JLabel negotiationStatusLabel;
    private DefaultTableModel offerLogModel;

    // Agent ID suffix — allows multiple auto-dealer windows
    private final String agentId;


    // ── Constructor ───────────────────────────────────────────────────────────

    public AutoDealerWindow(AgentContainer container, String agentId) {
        super("Auto Dealer — " + agentId + " (Automated Negotiation)");
        this.agentId = agentId;
        this.accent  = Color.decode(Config.get("gui.accentColour"));

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(860, 700);
        setLocationRelativeTo(null);

        buildUI();
        startAgent(container);
    }


    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Inventory & Strategy", buildListingsTab());
        tabbedPane.addTab("Auto-Negotiation",     buildNegotiationTab());
        // Negotiation tab is always accessible — shows a waiting state until negotiation starts

        setLayout(new BorderLayout());
        add(tabbedPane, BorderLayout.CENTER);
    }


    // ── Inventory & Strategy tab ──────────────────────────────────────────────

    private JPanel buildListingsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setBackground(Color.WHITE);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBackground(Color.WHITE);
        top.add(buildStrategyPanel(), BorderLayout.CENTER);
        top.add(buildAddCarForm(),    BorderLayout.NORTH);
        panel.add(top, BorderLayout.NORTH);

        // Inventory table
        listingsModel = new DefaultTableModel(
                new String[]{"Car ID", "Make", "Model", "Year", "Mileage",
                             "Colour", "Condition", "Retail Price (RM)", "Floor Price (RM)"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable listingsTable = new JTable(listingsModel);
        listingsTable.setRowHeight(22);
        listingsTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        panel.add(new JScrollPane(listingsTable), BorderLayout.CENTER);

        JButton registerBtn = makeButton("Register Listings with KA");
        registerBtn.addActionListener(e -> {
            if (agent != null) agent.sendListingsToKA();
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnRow.setBackground(Color.WHITE);
        btnRow.add(registerBtn);
        panel.add(btnRow, BorderLayout.SOUTH);

        return panel;
    }

    /** Strategy configuration panel — unique to AutoDealerWindow. */
    private JPanel buildStrategyPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Negotiation Strategy (Automated)",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                accent));

        // Floor price — dealer's private lower limit (used as fallback; per-car floor is set in inventory)
        panel.add(new JLabel("Default Floor Price (RM, private):"));
        floorPriceField = new JTextField("40000", 8);
        floorPriceField.setToolTipText(
                "Fallback floor price used when no per-car floor is registered. "
              + "The agent will never accept below this amount.");
        panel.add(floorPriceField);

        // Strategy type
        panel.add(new JLabel("Strategy:"));
        strategyCombo = new JComboBox<>(new String[]{
                "Linear (α=1)", "Boulware (α=0.2)", "Conceder (α=3)"
        });
        strategyCombo.setToolTipText(
                "Controls concession speed from retail to floor price. "
              + "Boulware: slow/tough. Linear: steady. Conceder: fast/accommodating.");
        strategyCombo.addActionListener(e -> syncAlphaToCombo());
        panel.add(strategyCombo);

        // Custom alpha override
        panel.add(new JLabel("Custom α:"));
        alphaField = new JTextField("1.0", 5);
        alphaField.setToolTipText("Override alpha directly (0 < α). Overrides the Strategy dropdown.");
        panel.add(alphaField);

        // Hint label
        JLabel hint = new JLabel(
                "<html><i>Boulware = tough negotiator &nbsp;|&nbsp; "
              + "Linear = balanced &nbsp;|&nbsp; "
              + "Conceder = quick deal</i></html>");
        hint.setFont(new Font("SansSerif", Font.PLAIN, 11));
        hint.setForeground(Color.GRAY);
        panel.add(hint);

        return panel;
    }

    /** Sync alpha field when combo selection changes. */
    private void syncAlphaToCombo() {
        int idx = strategyCombo.getSelectedIndex();
        switch (idx) {
            case 0 -> alphaField.setText("1.0"); // Linear
            case 1 -> alphaField.setText("0.2"); // Boulware
            case 2 -> alphaField.setText("3.0"); // Conceder
        }
    }

    private double parseAlphaFromFields() {
        try {
            double a = Double.parseDouble(alphaField.getText().trim());
            return (a > 0) ? a : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private double parseFloorPrice() {
        try {
            return Double.parseDouble(floorPriceField.getText().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Add-car form — mirrors DealerWindow but applies strategy settings on add. */
    private JPanel buildAddCarForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Color.WHITE);
        form.setBorder(BorderFactory.createTitledBorder("Add Car to Inventory"));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new Insets(4, 6, 4, 6);
        gc.anchor  = GridBagConstraints.WEST;
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;

        // Row 0: carId, make, model
        JTextField carIdField     = addFormField(form, gc, 0, 0, "Car ID:");
        JTextField makeField      = addFormField(form, gc, 1, 0, "Make:");
        JTextField modelField     = addFormField(form, gc, 2, 0, "Model:");

        // Row 1: year, mileage, colour
        JTextField yearField      = addFormField(form, gc, 0, 1, "Year:");
        JTextField mileageField   = addFormField(form, gc, 1, 1, "Mileage (km):");
        JTextField colourField    = addFormField(form, gc, 2, 1, "Colour:");

        // Row 2: condition, retailPrice, floorPrice override per-car
        JTextField conditionField    = addFormField(form, gc, 0, 2, "Condition:");
        JTextField retailPriceField  = addFormField(form, gc, 1, 2, "Retail Price (RM):");
        JTextField carFloorField     = addFormField(form, gc, 2, 2, "Floor Price (RM, private):");

        JButton addBtn = makeButton("Add Car");
        gc.gridx = 0; gc.gridy = 3; gc.gridwidth = 3;
        form.add(addBtn, gc);

        addBtn.addActionListener(e -> {
            if (agent == null) return;
            try {
                CarListing listing = new CarListing();
                listing.setCarId(carIdField.getText().trim());
                listing.setMake(makeField.getText().trim());
                listing.setModel(modelField.getText().trim());
                listing.setYear(Integer.parseInt(yearField.getText().trim()));
                listing.setMileage(Integer.parseInt(mileageField.getText().trim()));
                listing.setColour(colourField.getText().trim());
                listing.setCondition(conditionField.getText().trim());
                listing.setRetailPrice(Double.parseDouble(retailPriceField.getText().trim()));

                String carFloorText = carFloorField.getText().trim();
                double floorForCar  = carFloorText.isEmpty()
                        ? parseFloorPrice()
                        : Double.parseDouble(carFloorText);

                double alpha = parseAlphaFromFields();
                agent.setAlpha(alpha);
                agent.addToInventory(listing, floorForCar);

                listingsModel.addRow(new Object[]{
                        listing.getCarId(), listing.getMake(), listing.getModel(),
                        listing.getYear(), listing.getMileage(), listing.getColour(),
                        listing.getCondition(),
                        String.format("%.2f", listing.getRetailPrice()),
                        String.format("%.2f", floorForCar)
                });

                // Clear fields
                for (JTextField f : new JTextField[]{
                        carIdField, makeField, modelField, yearField, mileageField,
                        colourField, conditionField, retailPriceField, carFloorField}) {
                    f.setText("");
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(AutoDealerWindow.this,
                        "Year, Mileage, Retail Price, and Floor Price must be valid numbers.\n"
                                + ex.getMessage(),
                        "Input Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        return form;
    }

    private JTextField addFormField(JPanel form, GridBagConstraints gc,
                                    int col, int row, String label) {
        gc.gridwidth = 1;
        gc.gridx = col * 2;
        gc.gridy = row;
        form.add(new JLabel(label), gc);
        JTextField field = new JTextField(9);
        gc.gridx = col * 2 + 1;
        form.add(field, gc);
        return field;
    }


    // ── Auto-Negotiation tab ──────────────────────────────────────────────────

    private JPanel buildNegotiationTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
        panel.setBackground(Color.WHITE);

        negotiationLabel = new JLabel("—", SwingConstants.CENTER);
        negotiationLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        negotiationLabel.setForeground(accent);

        negotiationStatusLabel = new JLabel("Waiting for buyer interest…", SwingConstants.CENTER);
        negotiationStatusLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        negotiationStatusLabel.setForeground(Color.GRAY);

        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        headerPanel.setBackground(Color.WHITE);
        headerPanel.add(negotiationLabel);
        headerPanel.add(negotiationStatusLabel);
        panel.add(headerPanel, BorderLayout.NORTH);

        // Offer log table (read-only)
        offerLogModel = new DefaultTableModel(
                new String[]{"Round", "Party", "Action", "Amount (RM)", "Notes"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable logTable = new JTable(offerLogModel);
        logTable.setRowHeight(22);
        logTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        logTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(300);
        panel.add(new JScrollPane(logTable), BorderLayout.CENTER);

        // Info label at bottom
        JLabel infoLabel = new JLabel(
                "<html><b>Auto-Negotiation Mode:</b> "
              + "The agent is negotiating automatically using your strategy settings. "
              + "No manual input is required.</html>",
                SwingConstants.CENTER);
        infoLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        infoLabel.setForeground(Color.DARK_GRAY);
        infoLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        panel.add(infoLabel, BorderLayout.SOUTH);

        return panel;
    }


    // ── Hook callbacks (called from WindowAgent on JADE thread → EDT) ─────────

    /**
     * Called when KA forwards buyer interest.
     * The auto-dealer will decide to accept or decline autonomously.
     */
    void showBuyerInterest(String carId, String buyerAIDName, Offer offer) {
        activeCarId        = carId;
        activeBuyerAIDName = buyerAIDName;
        negotiationLabel.setText("Buyer Interest: " + carId + " from " + buyerAIDName);
        negotiationStatusLabel.setText(
                "First offer: RM " + String.format("%.2f", offer.getAmount())
              + " — agent evaluating…");
        tabbedPane.setSelectedIndex(1);
        offerLogModel.setRowCount(0);
        appendLog("—", buyerAIDName, "Interest", offer.getAmount(),
                "Buyer first offer RM " + String.format("%.2f", offer.getAmount()));
    }

    /** Called by WindowAgent when the auto-dealer accepts buyer interest. */
    void showAutoAcceptInterest(String carId, String buyerAIDName) {
        appendLog("—", agentId + " (auto)", "ACCEPT INTEREST", 0,
                "Agent accepted buyer interest — negotiation begins");
        negotiationStatusLabel.setText("Accepted buyer — awaiting first PROPOSE…");
    }

    /** Called by WindowAgent when the auto-dealer declines buyer interest. */
    void showAutoDeclineInterest(String carId, String buyerAIDName) {
        appendLog("—", agentId + " (auto)", "DECLINE INTEREST", 0,
                "Agent declined buyer — below reserve or no match");
        negotiationStatusLabel.setText("Agent declined buyer interest.");
        negotiationLabel.setText("✘ Declined — " + carId);
    }

    /** Called when a PROPOSE (Phase B offer) arrives from the buyer. */
    void showOffer(String carId, Offer offer) {
        appendLog(
                String.valueOf(offer.getRound()),
                offer.getFromAgentId(),
                "Offer",
                offer.getAmount(),
                "Buyer offers RM " + String.format("%.2f", offer.getAmount()));
        negotiationStatusLabel.setText(
                "Round " + offer.getRound()
              + " — evaluating buyer offer RM" + String.format("%.2f", offer.getAmount()) + "…");
    }

    /** Called by WindowAgent when the auto-dealer decides to counter. */
    void showAutoCounter(String carId, int round, double amount) {
        appendLog(
                String.valueOf(round),
                agentId + " (auto)",
                "Counter",
                amount,
                "Strategy computed RM " + String.format("%.2f", amount));
        negotiationStatusLabel.setText(
                "Round " + round
              + " — agent counter-offer RM" + String.format("%.2f", amount) + " sent.");
    }

    /** Called by WindowAgent when the auto-dealer decides to accept a deal. */
    void showAutoAcceptDeal(String carId, int round, double buyerAmount) {
        appendLog(
                String.valueOf(round),
                agentId + " (auto)",
                "ACCEPT DEAL",
                buyerAmount,
                "Agent accepted buyer offer — deal pending");
        negotiationStatusLabel.setText(
                "Accepting deal at RM" + String.format("%.2f", buyerAmount) + "…");
    }

    /** Called by WindowAgent when the auto-dealer decides to walk away. */
    void showAutoWalkAway(String carId, int round) {
        appendLog(
                String.valueOf(round),
                agentId + " (auto)",
                "WALK AWAY",
                0,
                "Buyer below floor price — agent walked away");
        negotiationStatusLabel.setText("Agent walked away.");
    }

    /** Called when the negotiation concludes (deal reached or failed). */
    void showNegotiationOutcome(String carId, boolean dealReached, double finalAmount) {
        String outcome = dealReached
                ? "✔ Deal closed — " + carId + " at RM " + String.format("%.2f", finalAmount)
                : "✘ No deal — " + carId;
        negotiationLabel.setText(outcome);
        negotiationStatusLabel.setText(dealReached ? "Negotiation complete." : "Negotiation failed.");

        JOptionPane.showMessageDialog(this,
                (dealReached
                        ? "Auto-negotiation succeeded!\n" + carId + " sold at RM "
                                + String.format("%.2f", finalAmount)
                        : "Auto-negotiation failed to reach a deal for " + carId + "."),
                dealReached ? "Deal Closed" : "No Deal",
                dealReached ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);

        activeCarId        = null;
        activeBuyerAIDName = null;
        tabbedPane.setSelectedIndex(0);
    }


    // ── Utilities ─────────────────────────────────────────────────────────────

    private void appendLog(String round, String party, String action,
                            double amount, String notes) {
        offerLogModel.addRow(new Object[]{
                round,
                party,
                action,
                amount > 0 ? String.format("%.2f", amount) : "—",
                notes
        });
    }

    private JButton makeButton(String label) {
        JButton btn = new JButton(label);
        btn.setBackground(accent);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        return btn;
    }


    // ── Agent creation ─────────────────────────────────────────────────────────

    private void startAgent(AgentContainer container) {
        pendingWindow = this;
        try {
            AgentController da = container.createNewAgent(
                    agentId,
                    WindowAgent.class.getName(),
                    new Object[]{"BrokerAgent"}
            );
            da.start();
        } catch (StaleProxyException ex) {
            pendingWindow = null;
            String msg = ex.getMessage();
            if (msg != null && msg.contains("Name-clash")) {
                JOptionPane.showMessageDialog(this,
                        "An Auto Dealer Agent '" + agentId + "' is already running.\n"
                                + "Close the existing window before opening a new one.",
                        "Agent Already Running", JOptionPane.WARNING_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Failed to start Auto Dealer Agent:\n" + msg,
                        "Startup Error", JOptionPane.ERROR_MESSAGE);
            }
            dispose();
        }
    }


    // ── WindowAgent (inner class) ──────────────────────────────────────────────

    /**
     * AutoDealerAgent subclass that:
     * <ol>
     * <li>Overrides the protected hooks to push updates to the Swing EDT via
     *     {@code SwingUtilities.invokeLater}.</li>
     * <li>Overrides {@code acceptBuyerInterest}, {@code declineBuyerInterest},
     *     {@code submitOffer}, {@code acceptDeal}, and {@code walkAway} to log
     *     the agent's autonomous decision to the GUI <em>before</em> the action
     *     is actually sent.</li>
     * </ol>
     *
     * <p>The intercept works by wrapping the parent's logic:
     * AutoDealerAgent makes the autonomous decision and calls these public
     * methods. We override them here to log to the window before delegating
     * to the super implementation.
     */
    public static class WindowAgent extends AutoDealerAgent {

        private AutoDealerWindow window;

        @Override
        protected void setup() {
            window        = pendingWindow;
            pendingWindow = null;
            if (window != null) window.agent = this;
            super.setup();
        }

        // Intercept acceptBuyerInterest — log before sending
        @Override
        public void acceptBuyerInterest(String carId, String buyerAIDName) {
            if (window != null) {
                SwingUtilities.invokeLater(
                        () -> window.showAutoAcceptInterest(carId, buyerAIDName));
            }
            super.acceptBuyerInterest(carId, buyerAIDName);
        }

        // Intercept declineBuyerInterest — log before sending
        @Override
        public void declineBuyerInterest(String carId, String buyerAIDName) {
            if (window != null) {
                SwingUtilities.invokeLater(
                        () -> window.showAutoDeclineInterest(carId, buyerAIDName));
            }
            super.declineBuyerInterest(carId, buyerAIDName);
        }

        // Intercept submitOffer — log the auto-counter to the GUI
        @Override
        public void submitOffer(String carId, double amount) {
            int round = activeNegotiations.containsKey(carId)
                    ? activeNegotiations.get(carId).getCurrentRound() + 1
                    : -1;
            if (window != null) {
                final int    r = round;
                final double a = amount;
                SwingUtilities.invokeLater(() -> window.showAutoCounter(carId, r, a));
            }
            super.submitOffer(carId, amount);
        }

        // Intercept acceptDeal — log to GUI before message goes out
        @Override
        public void acceptDeal(String carId) {
            if (window != null && activeNegotiations.containsKey(carId)) {
                var history = activeNegotiations.get(carId).getOpponentOfferHistory();
                int round   = activeNegotiations.get(carId).getCurrentRound();
                double amt  = history.isEmpty() ? 0
                        : history.get(history.size() - 1).getAmount();
                SwingUtilities.invokeLater(() -> window.showAutoAcceptDeal(carId, round, amt));
            }
            super.acceptDeal(carId);
        }

        // Intercept walkAway — log to GUI
        @Override
        public void walkAway(String carId) {
            if (window != null && activeNegotiations.containsKey(carId)) {
                int round = activeNegotiations.get(carId).getCurrentRound();
                SwingUtilities.invokeLater(() -> window.showAutoWalkAway(carId, round));
            }
            super.walkAway(carId);
        }


        // ── Notification hooks ─────────────────────────────────────────────────

        @Override
        protected void onBuyerInterestReceived(String carId, String buyerAIDName, Offer offer) {
            super.onBuyerInterestReceived(carId, buyerAIDName, offer);
            if (window == null) return;
            SwingUtilities.invokeLater(
                    () -> window.showBuyerInterest(carId, buyerAIDName, offer));
        }

        @Override
        protected boolean onNegotiationOfferReceived(String carId, Offer offer) {
            // Push incoming offer to GUI log first
            if (window != null) {
                SwingUtilities.invokeLater(() -> window.showOffer(carId, offer));
            }
            // Delegate to AutoDealerAgent which makes the autonomous decision
            return super.onNegotiationOfferReceived(carId, offer);
        }

        @Override
        protected void onNegotiationEnded(String carId, boolean dealReached, double finalAmount) {
            super.onNegotiationEnded(carId, dealReached, finalAmount);
            if (window == null) return;
            SwingUtilities.invokeLater(
                    () -> window.showNegotiationOutcome(carId, dealReached, finalAmount));
        }
    }
}