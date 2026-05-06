package ans.gui;

import ans.Config;
import ans.agent.AutoBuyerAgent;
import ans.model.BuyerRequirements;
import ans.model.CarListing;
import ans.model.Offer;
import com.google.gson.Gson;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

/**
 * Unlike BuyerWindow.java, this window adds strategy configuration panel where the user sets
 * reserve price, alpha (concession shape), and strategy type
 * (Boulware / Linear / Conceder) before negotiation starts.
 * 
 * Shows a live auto-negotiation log in the Negotiation tab
 * 
 * The window uses the same three-tab layout as BuyerWindow but adds a
 * Strategy panel above the search form.
 *
 */
public class AutoBuyerWindow extends JFrame {

    // ── Static link used during agent construction ───────────────────────────
    static AutoBuyerWindow pendingWindow;

    // ── Instance state ────────────────────────────────────────────────────────

    private volatile WindowAgent agent;
    private final Color accent;
    private static final Gson GSON = new Gson();

    // Strategy panel fields
    private JTextField reservePriceField;
    private JTextField alphaField;
    private JComboBox<String> strategyCombo;

    // Search tab
    private DefaultTableModel resultsModel;
    private List<CarListing> lastMatches;

    // Negotiation tab
    private JTabbedPane tabbedPane;
    private String activeCarId;
    private JLabel negotiationLabel;
    private JLabel negotiationStatusLabel;
    private DefaultTableModel offerLogModel;

    // Agent ID suffix — allows multiple auto-buyer windows
    private final String agentId;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AutoBuyerWindow(AgentContainer container, String agentId) {
        super("Auto Buyer — " + agentId + " (Automated Negotiation)");
        this.agentId = agentId;
        this.accent = Color.decode(Config.get("gui.accentColour"));

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(860, 700);
        setLocationRelativeTo(null);

        buildUI();
        startAgent(container);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Search & Strategy", buildSearchTab());
        tabbedPane.addTab("Auto-Negotiation", buildNegotiationTab());
        // Tab is always accessible — shows a waiting state until negotiation starts

        setLayout(new BorderLayout());
        add(tabbedPane, BorderLayout.CENTER);
    }

    // ── Search & Strategy tab ─────────────────────────────────────────────────

    private JPanel buildSearchTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setBackground(Color.WHITE);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBackground(Color.WHITE);
        top.add(buildRequirementsForm(), BorderLayout.CENTER);
        top.add(buildStrategyPanel(), BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);

        // Results table
        resultsModel = new DefaultTableModel(
                new String[] { "#", "Car ID", "Make", "Model", "Year", "Mileage",
                        "Colour", "Condition", "Price (RM)", "Dealer", "Select", "Rank" },
                0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return c == 10 || c == 11;
            }

            @Override
            public Class<?> getColumnClass(int c) {
                if (c == 10)
                    return Boolean.class;
                if (c == 11)
                    return Integer.class;
                return String.class;
            }
        };
        JTable resultsTable = new JTable(resultsModel);
        resultsTable.setRowHeight(22);
        resultsTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        resultsTable.getColumnModel().getColumn(10).setPreferredWidth(50);
        resultsTable.getColumnModel().getColumn(11).setPreferredWidth(50);
        panel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        JButton shortlistBtn = makeButton("Send Shortlist to KA (Auto-Negotiate)");
        shortlistBtn.addActionListener(e -> sendShortlist());
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnRow.setBackground(Color.WHITE);
        btnRow.add(shortlistBtn);
        panel.add(btnRow, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildRequirementsForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Color.WHITE);
        form.setBorder(BorderFactory.createTitledBorder("Search Requirements"));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        JTextField modelField = addFormField(form, gc, 0, 0, "Model:");
        JTextField makeField = addFormField(form, gc, 1, 0, "Make (preferred):");
        JTextField conditionField = addFormField(form, gc, 2, 0, "Condition:");
        JTextField yearMinField = addFormField(form, gc, 0, 1, "Year (min):");
        JTextField yearMaxField = addFormField(form, gc, 1, 1, "Year (max):");
        JTextField colourField = addFormField(form, gc, 2, 1, "Colour (preferred):");
        JTextField mileageField = addFormField(form, gc, 0, 2, "Max Mileage (km):");
        JTextField budgetField = addFormField(form, gc, 1, 2, "Max Budget (RM):");
        JTextField firstOfferField = addFormField(form, gc, 2, 2, "First Offer (RM):");

        JButton searchBtn = makeButton("Search");
        gc.gridx = 0;
        gc.gridy = 3;
        gc.gridwidth = 3;
        form.add(searchBtn, gc);

        searchBtn.addActionListener(e -> {
            if (agent == null)
                return;
            try {
                double reservePrice = Double.parseDouble(reservePriceField.getText().trim());
                double alphaVal = parseAlphaFromFields();

                String json = String.format(
                        "{\"model\":\"%s\",\"make\":\"%s\",\"condition\":\"%s\","
                                + "\"yearMin\":%s,\"yearMax\":%s,"
                                + "\"maxMileage\":%s,\"preferredColour\":\"%s\","
                                + "\"maxBudget\":%s,\"firstOffer\":%s}",
                        modelField.getText().trim(),
                        makeField.getText().trim(),
                        conditionField.getText().trim(),
                        blankOr(yearMinField.getText(), "0"),
                        blankOr(yearMaxField.getText(), "9999"),
                        blankOr(mileageField.getText(), "9999999"),
                        colourField.getText().trim(),
                        blankOr(budgetField.getText(), "9999999"),
                        blankOr(firstOfferField.getText(), "0"));

                BuyerRequirements req = GSON.fromJson(json, BuyerRequirements.class);
                agent.setRequirementsAndReserve(req, reservePrice);
                agent.setAlpha(alphaVal);
                agent.sendSearchRequest();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                        "Reserve Price must be a valid number.\n" + ex.getMessage(),
                        "Input Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        return form;
    }

    /** Strategy configuration panel — unique to AutoBuyerWindow. */
    private JPanel buildStrategyPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Negotiation Strategy (Automated)",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                accent));

        // Reserve price — auto buyer's private upper limit
        panel.add(new JLabel("Reserve Price (RM, private):"));
        reservePriceField = new JTextField("55000", 8);
        panel.add(reservePriceField);

        // Strategy type
        panel.add(new JLabel("Strategy:"));
        strategyCombo = new JComboBox<>(new String[] { "Linear (α=1)", "Boulware (α=0.2)", "Conceder (α=3)" });
        strategyCombo.setToolTipText(
                "Controls concession speed. Boulware: slow/tough. "
                        + "Linear: steady. Conceder: fast/accommodating.");
        strategyCombo.addActionListener(e -> syncAlphaToCombo());
        panel.add(strategyCombo);

        // Custom alpha override
        panel.add(new JLabel("Custom α:"));
        alphaField = new JTextField("1.0", 5);
        alphaField.setToolTipText("Override alpha directly (0 < α). Overrides the Strategy dropdown.");
        panel.add(alphaField);

        // Strategy explanation label
        JLabel hint = new JLabel(
                "<html><i>Boulware = tough negotiator &nbsp;|&nbsp; "
                        + "Linear = balanced &nbsp;|&nbsp; "
                        + "Conceder = quick deal</i></html>");
        hint.setFont(new Font("SansSerif", Font.PLAIN, 11));
        hint.setForeground(Color.GRAY);
        panel.add(hint);

        return panel;
    }

    /** Sync alpha field when combo changes. */
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

    private String blankOr(String text, String fallback) {
        String t = text.trim();
        return t.isEmpty() ? fallback : t;
    }

    // ── Negotiation tab ────────────────────────────────────────────────────────

    private JPanel buildNegotiationTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
        panel.setBackground(Color.WHITE);

        negotiationLabel = new JLabel("—", SwingConstants.CENTER);
        negotiationLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        negotiationLabel.setForeground(accent);

        negotiationStatusLabel = new JLabel("Waiting for negotiation to start…", SwingConstants.CENTER);
        negotiationStatusLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        negotiationStatusLabel.setForeground(Color.GRAY);

        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        headerPanel.setBackground(Color.WHITE);
        headerPanel.add(negotiationLabel);
        headerPanel.add(negotiationStatusLabel);
        panel.add(headerPanel, BorderLayout.NORTH);

        // Offer log table (read-only)
        offerLogModel = new DefaultTableModel(
                new String[] { "Round", "Party", "Action", "Amount (RM)", "Notes" }, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable logTable = new JTable(offerLogModel);
        logTable.setRowHeight(22);
        logTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        logTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(300);
        panel.add(new JScrollPane(logTable), BorderLayout.CENTER);

        // Info label at bottom — explains that this is autonomous
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

    // ── Shortlist sending ──────────────────────────────────────────────────────

    private void sendShortlist() {
        if (agent == null || lastMatches == null)
            return;

        java.util.TreeMap<Integer, CarListing> ranked = new java.util.TreeMap<>();
        java.util.List<CarListing> unranked = new java.util.ArrayList<>();

        for (int i = 0; i < resultsModel.getRowCount(); i++) {
            Boolean selected = (Boolean) resultsModel.getValueAt(i, 10);
            if (Boolean.TRUE.equals(selected) && i < lastMatches.size()) {
                CarListing listing = lastMatches.get(i);
                Object rankObj = resultsModel.getValueAt(i, 11);
                if (rankObj instanceof Integer rank && rank > 0) {
                    ranked.put(rank, listing);
                } else {
                    unranked.add(listing);
                }
            }
        }

        Map<String, Offer> shortlist = new LinkedHashMap<>();
        int row = 0;
        BuyerRequirements req = agent.getRequirements();
        double firstOffer = (req != null) ? req.getFirstOffer() : 0;

        for (CarListing listing : ranked.values()) {
            if (++row > 1)
                break;
            shortlist.put(listing.getDealerAIDName(),
                    new Offer(listing.getCarId(), firstOffer, 0, agentId, false));
        }
        for (CarListing listing : unranked) {
            if (shortlist.size() >= 1)
                break;
            shortlist.put(listing.getDealerAIDName(),
                    new Offer(listing.getCarId(), firstOffer, 0, agentId, false));
        }

        if (shortlist.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Select one car before sending the shortlist.",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        agent.submitShortlist(shortlist);
        appendLog("—", "System", "Shortlist sent", 0,
                "1 dealer queued for auto-negotiation");
    }

    // ── Hook callbacks (called from WindowAgent on JADE thread → EDT) ─────────

    void showMatches(List<CarListing> matches) {
        lastMatches = matches;
        resultsModel.setRowCount(0);
        int idx = 1;
        for (CarListing c : matches) {
            resultsModel.addRow(new Object[] {
                    idx++,
                    c.getCarId(), c.getMake(), c.getModel(), c.getYear(),
                    c.getMileage(), c.getColour(), c.getCondition(),
                    String.format("%.2f", c.getRetailPrice()),
                    c.getDealerAIDName(),
                    Boolean.FALSE, 1
            });
        }
    }

    void showAIDExchanged(String carId, String dealerAIDName) {
        activeCarId = carId;
        negotiationLabel.setText("Auto-Negotiating: " + carId + " with " + dealerAIDName);
        negotiationStatusLabel.setText("Agent is negotiating autonomously…");
        tabbedPane.setSelectedIndex(1);
        offerLogModel.setRowCount(0);
    }

    void showNoDealerEngaged() {
        JOptionPane.showMessageDialog(this,
                "All shortlisted dealers declined — no negotiation started.\n"
                        + "Revise your requirements and try again.",
                "No Dealer Available", JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Logs every received offer — shows the dealer's position in the log table.
     * The agent's autonomous response will be shown by {@link #showAutoCounter}.
     */
    void showOffer(String carId, Offer offer) {
        appendLog(
                String.valueOf(offer.getRound()),
                offer.getFromAgentId(),
                "Offer",
                offer.getAmount(),
                "Dealer asks RM " + String.format("%.2f", offer.getAmount()));
        negotiationStatusLabel.setText("Round " + offer.getRound()
                + " — evaluating dealer offer RM" + String.format("%.2f", offer.getAmount()) + "…");
    }

    /** Called by WindowAgent when the auto-buyer decides to counter. */
    void showAutoCounter(String carId, int round, double amount) {
        appendLog(
                String.valueOf(round),
                agentId + " (auto)",
                "Counter",
                amount,
                "Strategy computed RM " + String.format("%.2f", amount));
        negotiationStatusLabel.setText("Round " + round
                + " — agent counter-offer RM" + String.format("%.2f", amount) + " sent.");
    }

    /** Called by WindowAgent when the auto-buyer decides to accept. */
    void showAutoAccept(String carId, int round, double dealerAmount) {
        appendLog(
                String.valueOf(round),
                agentId + " (auto)",
                "ACCEPT",
                dealerAmount,
                "Agent accepted dealer offer — deal pending");
        negotiationStatusLabel.setText("Accepting deal at RM"
                + String.format("%.2f", dealerAmount) + "…");
    }

    /** Called by WindowAgent when the auto-buyer decides to walk away. */
    void showAutoWalkAway(String carId, int round) {
        appendLog(
                String.valueOf(round),
                agentId + " (auto)",
                "WALK AWAY",
                0,
                "Dealer exceeded reserve price — agent walked away");
        negotiationStatusLabel.setText("Agent walked away.");
    }

    void showNegotiationOutcome(String carId, boolean dealReached, double finalAmount) {
        String outcome = dealReached
                ? "✔ Deal closed — " + carId + " at RM " + String.format("%.2f", finalAmount)
                : "✘ No deal — " + carId;
        negotiationLabel.setText(outcome);
        negotiationStatusLabel.setText(dealReached ? "Negotiation complete." : "Negotiation failed.");

        String msgTitle = dealReached ? "Deal Closed" : "No Deal";
        int msgType = dealReached
                ? JOptionPane.INFORMATION_MESSAGE
                : JOptionPane.WARNING_MESSAGE;
        JOptionPane.showMessageDialog(this,
                (dealReached
                        ? "Auto-negotiation succeeded!\n" + carId + " purchased at RM "
                                + String.format("%.2f", finalAmount)
                        : "Auto-negotiation failed to reach a deal for " + carId + "."),
                msgTitle, msgType);

        activeCarId = null;
        tabbedPane.setSelectedIndex(0);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void appendLog(String round, String party, String action,
            double amount, String notes) {
        offerLogModel.addRow(new Object[] {
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
            AgentController ba = container.createNewAgent(
                    agentId,
                    WindowAgent.class.getName(),
                    new Object[] { "BrokerAgent" });
            ba.start();
        } catch (StaleProxyException ex) {
            pendingWindow = null;
            String msg = ex.getMessage();
            if (msg != null && msg.contains("Name-clash")) {
                JOptionPane.showMessageDialog(this,
                        "An Auto Buyer Agent '" + agentId + "' is already running.\n"
                                + "Close the existing window before opening a new one.",
                        "Agent Already Running", JOptionPane.WARNING_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Failed to start Auto Buyer Agent:\n" + msg,
                        "Startup Error", JOptionPane.ERROR_MESSAGE);
            }
            dispose();
        }
    }

    // ── WindowAgent (inner class) ──────────────────────────────────────────────

    /**
     * AutoBuyerAgent subclass that:
     * <ol>
     * <li>Overrides the four BuyerAgent notification hooks to push updates to
     * the Swing EDT via {@code SwingUtilities.invokeLater}.</li>
     * <li>Overrides {@code onNegotiationOfferReceived} to also capture the
     * agent's own decision (counter / accept / walk away) and push that
     * to the GUI log <em>before</em> the action is actually sent.</li>
     * </ol>
     *
     * <p>
     * The intercept works by wrapping the parent's logic:
     * AutoBuyerAgent.onNegotiationOfferReceived calls submitOffer, acceptDeal,
     * or walkAway. We override those public methods here to log the decision
     * to the window before delegating to the super implementation.
     */
    public static class WindowAgent extends AutoBuyerAgent {

        private AutoBuyerWindow window;

        @Override
        protected void setup() {
            window = pendingWindow;
            pendingWindow = null;
            if (window != null)
                window.agent = this;
            super.setup();
        }

        // Intercept submitOffer so we can log the auto-counter to the GUI
        @Override
        public void submitOffer(String carId, double amount) {
            int round = activeNegotiations.containsKey(carId)
                    ? activeNegotiations.get(carId).getCurrentRound() + 1
                    : -1;
            if (window != null) {
                final int r = round;
                final double a = amount;
                SwingUtilities.invokeLater(() -> window.showAutoCounter(carId, r, a));
            }
            super.submitOffer(carId, amount);
        }

        // Intercept acceptDeal so we can log to the GUI before the message goes out
        @Override
        public void acceptDeal(String carId) {
            if (window != null && activeNegotiations.containsKey(carId)) {
                var history = activeNegotiations.get(carId).getOpponentOfferHistory();
                int round = activeNegotiations.get(carId).getCurrentRound();
                double amt = history.isEmpty() ? 0
                        : history.get(history.size() - 1).getAmount();
                SwingUtilities.invokeLater(() -> window.showAutoAccept(carId, round, amt));
            }
            super.acceptDeal(carId);
        }

        // Intercept walkAway so we can log to the GUI
        @Override
        public void walkAway(String carId) {
            if (window != null && activeNegotiations.containsKey(carId)) {
                int round = activeNegotiations.get(carId).getCurrentRound();
                SwingUtilities.invokeLater(() -> window.showAutoWalkAway(carId, round));
            }
            super.walkAway(carId);
        }

        // ── Notification hooks ────────────────────────────────────────────────

        @Override
        protected void onMatchesReceived(List<CarListing> matches) {
            super.onMatchesReceived(matches);
            if (window == null)
                return;
            SwingUtilities.invokeLater(() -> window.showMatches(matches));
        }

        @Override
        protected void onAIDExchanged(String carId, String dealerAIDName) {
            super.onAIDExchanged(carId, dealerAIDName);
            if (window == null)
                return;
            SwingUtilities.invokeLater(() -> window.showAIDExchanged(carId, dealerAIDName));
        }

        @Override
        protected void onNoDealerEngaged() {
            super.onNoDealerEngaged();
            if (window == null)
                return;
            SwingUtilities.invokeLater(() -> window.showNoDealerEngaged());
        }

        @Override
        protected boolean onNegotiationOfferReceived(String carId, Offer offer) {
            // Push incoming offer to GUI log first
            if (window != null) {
                SwingUtilities.invokeLater(() -> window.showOffer(carId, offer));
            }
            // Delegate to AutoBuyerAgent which makes the autonomous decision
            // and returns true (handled — do not block behaviour)
            return super.onNegotiationOfferReceived(carId, offer);
        }

        @Override
        protected void onNegotiationEnded(String carId, boolean dealReached, double finalAmount) {
            super.onNegotiationEnded(carId, dealReached, finalAmount);
            if (window == null)
                return;
            SwingUtilities.invokeLater(() -> window.showNegotiationOutcome(carId, dealReached, finalAmount));
        }
    }
}
