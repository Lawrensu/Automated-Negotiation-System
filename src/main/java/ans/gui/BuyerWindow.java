package ans.gui;

import ans.Config;
import ans.agent.BuyerAgent;
import ans.model.BuyerRequirements;
import ans.model.CarListing;
import ans.model.Offer;
import com.google.gson.Gson;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

/**
 * Buyer Window — the human buyer's interface.
 *
 * Two tabs (system_design.md — BA Window):
 *   Search tab      : input form, results table, shortlist selection + ranking
 *   Negotiation tab : hidden until AID exchange; shows offer history + response buttons
 *
 * Search tab flow:
 *   1. Human fills in requirement fields (including reserve price — private to BA).
 *   2. Clicks "Search" → agent.setRequirementsAndReserve() + agent.sendSearchRequest().
 *   3. onMatchesReceived() populates the results table.
 *   4. Human checks rows (up to 3) and sets rank numbers.
 *   5. Clicks "Send Shortlist" → builds LinkedHashMap in rank order →
 *      agent.submitShortlist().
 *
 * Negotiation tab flow:
 *   1. onAIDExchanged() enables tab, shows dealer + carId.
 *   2. onNegotiationOfferReceived() adds row to history, updates round label.
 *   3. Human types counter or clicks Accept / Walk Away.
 *   4. onNegotiationEnded() resets tab.
 *
 * Window ↔ Agent coupling (static-link pattern — same as DealerWindow):
 *   BuyerWindow.pendingWindow is set to 'this' before createNewAgent() and cleared
 *   inside WindowAgent.setup().
 *
 * Swing EDT safety: all hook callbacks dispatch to EDT via SwingUtilities.invokeLater().
 */
public class BuyerWindow extends JFrame {

	// ── Static link used during agent construction ────────────────────────────

	static BuyerWindow pendingWindow;


	// ── Instance state ────────────────────────────────────────────────────────

	private WindowAgent           agent;
	private final Color           accent;
	private static final Gson     GSON = new Gson();

	// Search tab — results
	private DefaultTableModel     resultsModel;
	private List<CarListing>      lastMatches;   // parallel to resultsModel rows

	// Negotiation tab
	private JTabbedPane           tabbedPane;
	private String                activeCarId;
	private JLabel                negotiationLabel;
	private JLabel                roundLabel;
	private DefaultTableModel     offerHistoryModel;
	private JTextField            counterField;


	// ── Constructor ───────────────────────────────────────────────────────────

	public BuyerWindow(AgentContainer container) {
		super("Buyer Window — BA1");
		this.accent = Color.decode(Config.get("gui.accentColour"));

		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		setSize(800, 620);
		setLocationRelativeTo(null);

		buildUI();
		startAgent(container);
	}


	// ── UI construction ───────────────────────────────────────────────────────

	private void buildUI() {
		tabbedPane = new JTabbedPane();
		tabbedPane.addTab("Search",      buildSearchTab());
		tabbedPane.addTab("Negotiation", buildNegotiationTab());
		tabbedPane.setEnabledAt(1, false); // hidden until AID exchange

		setLayout(new BorderLayout());
		add(tabbedPane, BorderLayout.CENTER);
	}


	// ── Search tab ────────────────────────────────────────────────────────────

	private JPanel buildSearchTab() {
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		panel.setBackground(Color.WHITE);

		// Requirements form at top
		panel.add(buildRequirementsForm(), BorderLayout.NORTH);

		// Results table in the middle
		resultsModel = new DefaultTableModel(
				new String[]{"#", "Car ID", "Make", "Model", "Year", "Mileage",
						"Colour", "Condition", "Price (RM)", "Dealer", "Select", "Rank"}, 0) {
			@Override public boolean isCellEditable(int r, int c) {
				// Only the Select (checkbox) and Rank columns are editable
				return c == 10 || c == 11;
			}

			@Override public Class<?> getColumnClass(int c) {
				if (c == 10) return Boolean.class;
				if (c == 11) return Integer.class;
				return String.class;
			}
		};
		JTable resultsTable = new JTable(resultsModel);
		resultsTable.setRowHeight(22);
		resultsTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
		// Narrow index + select + rank columns
		resultsTable.getColumnModel().getColumn(0).setPreferredWidth(30);
		resultsTable.getColumnModel().getColumn(10).setPreferredWidth(50);
		resultsTable.getColumnModel().getColumn(11).setPreferredWidth(50);

		panel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

		// Send shortlist button at bottom
		JButton shortlistBtn = makeButton("Send Shortlist to KA");
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
		gc.insets  = new Insets(4, 6, 4, 6);
		gc.anchor  = GridBagConstraints.WEST;
		gc.fill    = GridBagConstraints.HORIZONTAL;

		// Row 0: model, make, condition
		JTextField modelField     = addFormField(form, gc, 0, 0, "Model:");
		JTextField makeField      = addFormField(form, gc, 1, 0, "Make (preferred):");
		JTextField conditionField = addFormField(form, gc, 2, 0, "Condition:");

		// Row 1: yearMin, yearMax, colour
		JTextField yearMinField   = addFormField(form, gc, 0, 1, "Year (min):");
		JTextField yearMaxField   = addFormField(form, gc, 1, 1, "Year (max):");
		JTextField colourField    = addFormField(form, gc, 2, 1, "Colour (preferred):");

		// Row 2: maxMileage, maxBudget, firstOffer, reservePrice
		JTextField mileageField   = addFormField(form, gc, 0, 2, "Max Mileage (km):");
		JTextField budgetField    = addFormField(form, gc, 1, 2, "Max Budget (RM):");
		JTextField firstOfferField = addFormField(form, gc, 2, 2, "First Offer (RM):");

		// Reserve price on its own row — labelled as private to BA
		gc.gridx = 0; gc.gridy = 3; gc.gridwidth = 1;
		form.add(new JLabel("Reserve Price (RM, private):"), gc);
		JTextField reservePriceField = new JTextField(10);
		gc.gridx = 1; gc.gridy = 3;
		form.add(reservePriceField, gc);

		// Search button
		JButton searchBtn = makeButton("Search");
		gc.gridx = 2; gc.gridy = 3; gc.gridwidth = 1;
		form.add(searchBtn, gc);

		searchBtn.addActionListener(e -> {
			if (agent == null) return;
			try {
				// Build BuyerRequirements from JSON — avoids needing a parameterised constructor
				String json = String.format(
						"{\"model\":\"%s\",\"make\":\"%s\",\"condition\":\"%s\","
								+ "\"yearMin\":%s,\"yearMax\":%s,"
								+ "\"maxMileage\":%s,\"preferredColour\":\"%s\","
								+ "\"maxBudget\":%s,\"firstOffer\":%s}",
						modelField.getText().trim(),
						makeField.getText().trim(),
						conditionField.getText().trim(),
						yearMinField.getText().trim(),
						yearMaxField.getText().trim(),
						mileageField.getText().trim(),
						colourField.getText().trim(),
						budgetField.getText().trim(),
						firstOfferField.getText().trim()
				);
				BuyerRequirements req = GSON.fromJson(json, BuyerRequirements.class);
				double reserve = Double.parseDouble(reservePriceField.getText().trim());
				agent.setRequirementsAndReserve(req, reserve);
				agent.sendSearchRequest();
			} catch (NumberFormatException ex) {
				JOptionPane.showMessageDialog(BuyerWindow.this,
						"Year, Mileage, Budget, First Offer, and Reserve Price must be numbers.",
						"Input Error", JOptionPane.WARNING_MESSAGE);
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

	/**
	 * Reads checked rows from the results table, sorts by rank value, and calls
	 * agent.submitShortlist() with a LinkedHashMap (preserves rank order).
	 *
	 * Up to 3 dealers can be selected. If a selected row has no rank, it is placed
	 * at the end in table order.
	 */
	private void sendShortlist() {
		if (agent == null || lastMatches == null) return;

		// Collect selected rows in rank order
		java.util.TreeMap<Integer, CarListing> ranked   = new java.util.TreeMap<>();
		java.util.List<CarListing>             unranked = new java.util.ArrayList<>();

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

		// Merge ranked then unranked into insertion-order LinkedHashMap
		Map<String, Offer> shortlist = new LinkedHashMap<>();
		int row = 0;
		for (CarListing listing : ranked.values()) {
			if (++row > 3) break;
			BuyerRequirements req = agent.getRequirements();
			double firstOffer = (req != null) ? req.getFirstOffer() : 0;
			shortlist.put(listing.getDealerAIDName(),
					new Offer(listing.getCarId(), firstOffer, 0, "BA1", false));
		}
		for (CarListing listing : unranked) {
			if (shortlist.size() >= 3) break;
			BuyerRequirements req = agent.getRequirements();
			double firstOffer = (req != null) ? req.getFirstOffer() : 0;
			shortlist.put(listing.getDealerAIDName(),
					new Offer(listing.getCarId(), firstOffer, 0, "BA1", false));
		}

		if (shortlist.isEmpty()) {
			JOptionPane.showMessageDialog(this,
					"Select at least one car before sending the shortlist.",
					"No Selection", JOptionPane.WARNING_MESSAGE);
			return;
		}

		agent.submitShortlist(shortlist);
	}


	// ── Negotiation tab ───────────────────────────────────────────────────────

	private JPanel buildNegotiationTab() {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
		panel.setBackground(Color.WHITE);

		negotiationLabel = new JLabel("—", SwingConstants.CENTER);
		negotiationLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
		panel.add(negotiationLabel, BorderLayout.NORTH);

		roundLabel = new JLabel("Round: —", SwingConstants.LEFT);
		roundLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));

		// Offer history table
		offerHistoryModel = new DefaultTableModel(
				new String[]{"Round", "From", "Amount (RM)"}, 0) {
			@Override public boolean isCellEditable(int r, int c) { return false; }
		};
		JTable historyTable = new JTable(offerHistoryModel);
		historyTable.setRowHeight(22);
		historyTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));

		JPanel centrePanel = new JPanel(new BorderLayout(0, 6));
		centrePanel.setBackground(Color.WHITE);
		centrePanel.add(roundLabel, BorderLayout.NORTH);
		centrePanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
		panel.add(centrePanel, BorderLayout.CENTER);

		// Counter-offer input + buttons
		JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
		inputRow.setBackground(Color.WHITE);

		counterField = new JTextField(10);
		counterField.setFont(new Font("Monospaced", Font.PLAIN, 13));

		JButton counterBtn  = makeButton("Counter");
		JButton acceptBtn   = makeButton("Accept");
		JButton walkAwayBtn = makeButton("Walk Away");

		counterBtn.addActionListener(e -> {
			if (agent == null || activeCarId == null) return;
			try {
				double amount = Double.parseDouble(counterField.getText().trim());
				agent.submitOffer(activeCarId, amount);
				counterField.setText("");
			} catch (NumberFormatException ex) {
				JOptionPane.showMessageDialog(BuyerWindow.this,
						"Enter a valid number for the counter-offer.",
						"Input Error", JOptionPane.WARNING_MESSAGE);
			}
		});
		acceptBtn.addActionListener(e -> {
			if (agent != null && activeCarId != null) agent.acceptDeal(activeCarId);
		});
		walkAwayBtn.addActionListener(e -> {
			if (agent != null && activeCarId != null) agent.walkAway(activeCarId);
		});

		inputRow.add(new JLabel("Counter offer (RM):"));
		inputRow.add(counterField);
		inputRow.add(counterBtn);
		inputRow.add(acceptBtn);
		inputRow.add(walkAwayBtn);
		panel.add(inputRow, BorderLayout.SOUTH);

		return panel;
	}


	// ── Hook callbacks (called by WindowAgent on JADE thread, dispatched to EDT) ──

	/** Called when KA returns matched listings. Populates results table. */
	void showMatches(List<CarListing> matches) {
		lastMatches = matches;
		resultsModel.setRowCount(0);
		int idx = 1;
		for (CarListing c : matches) {
			resultsModel.addRow(new Object[]{
					idx++,
					c.getCarId(), c.getMake(), c.getModel(), c.getYear(),
					c.getMileage(), c.getColour(), c.getCondition(),
					String.format("%.2f", c.getRetailPrice()),
					c.getDealerAIDName(),
					Boolean.FALSE, // Select checkbox — unchecked by default
					1              // Rank — default 1
			});
		}
	}

	/** Called when KA confirms AID exchange. Enables Negotiation tab. */
	void showAIDExchanged(String carId, String dealerAIDName) {
		activeCarId = carId;
		negotiationLabel.setText("Negotiating " + carId + " with " + dealerAIDName);
		tabbedPane.setEnabledAt(1, true);
		tabbedPane.setSelectedIndex(1);
	}

	/** Called when shortlist is exhausted — no deal possible. */
	void showNoDealerEngaged() {
		JOptionPane.showMessageDialog(this,
				"All shortlisted dealers declined — no negotiation started.\n"
						+ "Revise your requirements and try again.",
				"No Dealer Available", JOptionPane.WARNING_MESSAGE);
	}

	/** Called on every incoming offer (CFP at round 0, PROPOSE at round 1+). */
	void showOffer(String carId, Offer offer) {
		roundLabel.setText("Round: " + offer.getRound());
		offerHistoryModel.addRow(new Object[]{
				offer.getRound(), offer.getFromAgentId(),
				String.format("%.2f", offer.getAmount())
		});
	}

	/** Called when negotiation ends. Resets tab. */
	void showNegotiationOutcome(String carId, boolean dealReached, double finalAmount) {
		String msg = dealReached
				? "Deal closed — " + carId + " at RM " + String.format("%.2f", finalAmount)
				: "No deal — " + carId;
		JOptionPane.showMessageDialog(this, msg,
				dealReached ? "Deal Closed" : "No Deal",
				dealReached ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
		// Reset
		activeCarId = null;
		if (offerHistoryModel != null) offerHistoryModel.setRowCount(0);
		if (roundLabel != null)        roundLabel.setText("Round: —");
		if (negotiationLabel != null)  negotiationLabel.setText("—");
		tabbedPane.setEnabledAt(1, false);
		tabbedPane.setSelectedIndex(0);
	}


	// ── Button factory ────────────────────────────────────────────────────────

	private JButton makeButton(String label) {
		JButton btn = new JButton(label);
		btn.setBackground(accent);
		btn.setForeground(Color.WHITE);
		btn.setFocusPainted(false);
		btn.setFont(new Font("SansSerif", Font.BOLD, 12));
		return btn;
	}


	// ── Agent creation ────────────────────────────────────────────────────────

	private void startAgent(AgentContainer container) {
		try {
			pendingWindow = this;
			AgentController ba = container.createNewAgent(
					"BA1",
					WindowAgent.class.getName(),
					new Object[]{"BrokerAgent"}
			);
			ba.start();
		} catch (StaleProxyException ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this,
					"Failed to start Buyer Agent:\n" + ex.getMessage(),
					"Startup Error", JOptionPane.ERROR_MESSAGE);
		} finally {
			pendingWindow = null;
		}
	}


	// ── WindowAgent (inner class) ─────────────────────────────────────────────

	/**
	 * BuyerAgent subclass that overrides the four protected hooks to update
	 * BuyerWindow's Swing components via SwingUtilities.invokeLater().
	 *
	 * Grabs the BuyerWindow reference from the static pendingWindow field
	 * during setup() and clears it.
	 */
	public static class WindowAgent extends BuyerAgent {

		private BuyerWindow window;

		@Override
		protected void setup() {
			window        = pendingWindow;
			pendingWindow = null;
			super.setup();
		}

		@Override
		protected void onMatchesReceived(List<CarListing> matches) {
			super.onMatchesReceived(matches); // console log
			if (window == null) return;
			SwingUtilities.invokeLater(() -> window.showMatches(matches));
		}

		@Override
		protected void onAIDExchanged(String carId, String dealerAIDName) {
			super.onAIDExchanged(carId, dealerAIDName); // console log
			if (window == null) return;
			SwingUtilities.invokeLater(() -> window.showAIDExchanged(carId, dealerAIDName));
		}

		@Override
		protected void onNoDealerEngaged() {
			super.onNoDealerEngaged(); // console log
			if (window == null) return;
			SwingUtilities.invokeLater(() -> window.showNoDealerEngaged());
		}

		@Override
		protected void onNegotiationOfferReceived(String carId, Offer offer) {
			super.onNegotiationOfferReceived(carId, offer); // console log
			if (window == null) return;
			SwingUtilities.invokeLater(() -> window.showOffer(carId, offer));
		}

		@Override
		protected void onNegotiationEnded(String carId, boolean dealReached, double finalAmount) {
			super.onNegotiationEnded(carId, dealReached, finalAmount); // console log
			if (window == null) return;
			SwingUtilities.invokeLater(() ->
					window.showNegotiationOutcome(carId, dealReached, finalAmount));
		}
	}
}
