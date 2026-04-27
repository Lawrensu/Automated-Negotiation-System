package ans.gui;

import ans.Config;
import ans.agent.DealerAgent;
import ans.model.CarListing;
import ans.model.Offer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

/**
 * Dealer Window — the human dealer's interface.
 *
 * Two tabs (system_design.md — DA Window):
 *   Listings tab  : JTable showing registered inventory + form to add cars
 *   Negotiation tab : hidden until buyer interest arrives from KA
 *
 * Negotiation tab — Phase A (buyer interest):
 *   Shows buyer AID + first offer.
 *   Accept / Decline buttons → calls agent.acceptBuyerInterest() /
 *   agent.declineBuyerInterest().
 *
 * Negotiation tab — Phase B (offer loop, after Accept in Phase A):
 *   Shows round number, full offer history, counter-offer input, and
 *   Accept Deal / Walk Away buttons.
 *   Phase A panel is hidden; Phase B panel is shown.
 *
 * Window ↔ Agent coupling (static-link pattern):
 *   DealerWindow.pendingWindow is set to 'this' immediately before
 *   container.createNewAgent() is called. WindowAgent.setup() reads and clears it.
 *   Safe for v1 (one DA at a time, no concurrent window creation).
 *
 * All Swing updates from JADE hook callbacks are dispatched via
 * SwingUtilities.invokeLater() — hooks run on JADE's agent thread, not the EDT.
 * Button handlers run on the EDT; calling agent.submitOffer() etc. is safe because
 * JADE's send() is thread-safe.
 */
public class DealerWindow extends JFrame {

	// ── Static link used during agent construction ────────────────────────────

	static DealerWindow pendingWindow;


	// ── Instance state ────────────────────────────────────────────────────────

	private WindowAgent           agent;
	private final Color           accent;

	// Listings tab model
	private DefaultTableModel     listingsModel;

	// Negotiation tab — shared across both phases
	private JTabbedPane           tabbedPane;
	private String                activeCarId;      // carId currently being negotiated
	private String                activeBuyerAIDName;

	// Phase A components
	private JPanel                phaseAPanel;
	private JLabel                interestLabel;

	// Phase B components
	private JPanel                phaseBPanel;
	private JLabel                roundLabel;
	private DefaultTableModel     offerHistoryModel;
	private JTextField            counterField;


	// ── Constructor ───────────────────────────────────────────────────────────

	public DealerWindow(AgentContainer container) {
		super("Dealer Window — DA1");
		this.accent = Color.decode(Config.get("gui.accentColour"));

		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		setSize(720, 560);
		setLocationRelativeTo(null);

		buildUI();
		startAgent(container);
	}


	// ── UI construction ───────────────────────────────────────────────────────

	private void buildUI() {
		tabbedPane = new JTabbedPane();
		tabbedPane.addTab("Listings",     buildListingsTab());
		tabbedPane.addTab("Negotiation",  buildNegotiationTab());
		tabbedPane.setEnabledAt(1, false); // hidden until buyer interest arrives

		setLayout(new BorderLayout());
		add(tabbedPane, BorderLayout.CENTER);
	}

	// ── Listings tab ─────────────────────────────────────────────────────────

	private JPanel buildListingsTab() {
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		panel.setBackground(Color.WHITE);

		// Inventory table
		listingsModel = new DefaultTableModel(
				new String[]{"Car ID", "Make", "Model", "Year", "Mileage",
						"Colour", "Condition", "Retail Price (RM)"}, 0) {
			@Override public boolean isCellEditable(int r, int c) { return false; }
		};
		JTable listingsTable = new JTable(listingsModel);
		listingsTable.setRowHeight(22);
		listingsTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
		JScrollPane listingsScroll = new JScrollPane(listingsTable);
		listingsScroll.setPreferredSize(new Dimension(700, 220));
		panel.add(listingsScroll, BorderLayout.CENTER);

		// Add-car form + register button
		panel.add(buildAddCarForm(), BorderLayout.SOUTH);
		return panel;
	}

	private JPanel buildAddCarForm() {
		JPanel form = new JPanel(new GridBagLayout());
		form.setBackground(Color.WHITE);
		form.setBorder(BorderFactory.createTitledBorder("Add Car to Inventory"));

		GridBagConstraints gc = new GridBagConstraints();
		gc.insets = new Insets(4, 6, 4, 6);
		gc.anchor = GridBagConstraints.WEST;

		// Row 1: carId, make, model
		JTextField carIdField    = addFormField(form, gc, 0, 0, "Car ID:");
		JTextField makeField     = addFormField(form, gc, 1, 0, "Make:");
		JTextField modelField    = addFormField(form, gc, 2, 0, "Model:");

		// Row 2: year, mileage, colour
		JTextField yearField     = addFormField(form, gc, 0, 1, "Year:");
		JTextField mileageField  = addFormField(form, gc, 1, 1, "Mileage (km):");
		JTextField colourField   = addFormField(form, gc, 2, 1, "Colour:");

		// Row 3: condition, retailPrice, floorPrice
		JTextField conditionField   = addFormField(form, gc, 0, 2, "Condition:");
		JTextField retailPriceField = addFormField(form, gc, 1, 2, "Retail Price (RM):");
		JTextField floorPriceField  = addFormField(form, gc, 2, 2, "Floor Price (RM, private):");

		// Buttons row
		JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		btnRow.setBackground(Color.WHITE);

		JButton addBtn      = makeButton("Add Car");
		JButton registerBtn = makeButton("Register Listings with KA");

		addBtn.addActionListener(e -> {
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

				double floor = Double.parseDouble(floorPriceField.getText().trim());
				agent.addToInventory(listing, floor);

				listingsModel.addRow(new Object[]{
						listing.getCarId(), listing.getMake(), listing.getModel(),
						listing.getYear(), listing.getMileage(), listing.getColour(),
						listing.getCondition(),
						String.format("%.2f", listing.getRetailPrice())
				});

				// Clear fields after successful add
				for (JTextField f : new JTextField[]{carIdField, makeField, modelField,
						yearField, mileageField, colourField, conditionField,
						retailPriceField, floorPriceField}) {
					f.setText("");
				}
			} catch (NumberFormatException ex) {
				JOptionPane.showMessageDialog(DealerWindow.this,
						"Year, Mileage, Retail Price, and Floor Price must be numbers.",
						"Input Error", JOptionPane.WARNING_MESSAGE);
			}
		});

		registerBtn.addActionListener(e -> {
			if (agent != null) agent.sendListingsToKA();
		});

		btnRow.add(addBtn);
		btnRow.add(registerBtn);

		gc.gridx = 0; gc.gridy = 3; gc.gridwidth = 3;
		form.add(btnRow, gc);

		return form;
	}

	/**
	 * Adds a label + text field pair at grid position (col*2, row*something).
	 * Returns the JTextField for later use in the add-car button handler.
	 */
	private JTextField addFormField(JPanel form, GridBagConstraints gc,
	                                int col, int row, String label) {
		gc.gridwidth = 1;
		gc.gridx = col * 2;
		gc.gridy = row;
		form.add(new JLabel(label), gc);

		JTextField field = new JTextField(10);
		gc.gridx = col * 2 + 1;
		form.add(field, gc);
		return field;
	}


	// ── Negotiation tab ───────────────────────────────────────────────────────

	private JPanel buildNegotiationTab() {
		JPanel container = new JPanel(new CardLayout());
		container.setBackground(Color.WHITE);

		phaseAPanel = buildPhaseAPanel();
		phaseBPanel = buildPhaseBPanel();

		container.add(phaseAPanel, "PHASE_A");
		container.add(phaseBPanel, "PHASE_B");
		// Start showing Phase A (when tab becomes enabled)
		((CardLayout) container.getLayout()).show(container, "PHASE_A");

		return container;
	}

	private JPanel buildPhaseAPanel() {
		JPanel panel = new JPanel(new BorderLayout(0, 16));
		panel.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
		panel.setBackground(Color.WHITE);

		interestLabel = new JLabel("Waiting for buyer interest…", SwingConstants.CENTER);
		interestLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
		panel.add(interestLabel, BorderLayout.CENTER);

		JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 0));
		btnRow.setBackground(Color.WHITE);

		JButton acceptBtn  = makeButton("Accept");
		JButton declineBtn = makeButton("Decline");

		acceptBtn.addActionListener(e -> {
			if (agent != null && activeCarId != null) {
				agent.acceptBuyerInterest(activeCarId, activeBuyerAIDName);
			}
		});
		declineBtn.addActionListener(e -> {
			if (agent != null && activeCarId != null) {
				agent.declineBuyerInterest(activeCarId, activeBuyerAIDName);
				resetNegotiationTab();
			}
		});

		btnRow.add(acceptBtn);
		btnRow.add(declineBtn);
		panel.add(btnRow, BorderLayout.SOUTH);

		return panel;
	}

	private JPanel buildPhaseBPanel() {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
		panel.setBackground(Color.WHITE);

		roundLabel = new JLabel("Round: —", SwingConstants.CENTER);
		roundLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
		panel.add(roundLabel, BorderLayout.NORTH);

		// Offer history table
		offerHistoryModel = new DefaultTableModel(
				new String[]{"Round", "From", "Amount (RM)"}, 0) {
			@Override public boolean isCellEditable(int r, int c) { return false; }
		};
		JTable historyTable = new JTable(offerHistoryModel);
		historyTable.setRowHeight(22);
		historyTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
		panel.add(new JScrollPane(historyTable), BorderLayout.CENTER);

		// Counter-offer input row
		JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
		inputRow.setBackground(Color.WHITE);

		counterField = new JTextField(10);
		counterField.setFont(new Font("Monospaced", Font.PLAIN, 13));

		JButton counterBtn  = makeButton("Send Counter");
		JButton acceptBtn   = makeButton("Accept Deal");
		JButton walkAwayBtn = makeButton("Walk Away");

		counterBtn.addActionListener(e -> {
			if (agent == null || activeCarId == null) return;
			try {
				double amount = Double.parseDouble(counterField.getText().trim());
				agent.submitOffer(activeCarId, amount);
				counterField.setText("");
			} catch (NumberFormatException ex) {
				JOptionPane.showMessageDialog(DealerWindow.this,
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

	/** Called when KA forwards buyer interest. Enables tab, shows Phase A. */
	void showBuyerInterest(String carId, String buyerAIDName, Offer offer) {
		activeCarId        = carId;
		activeBuyerAIDName = buyerAIDName;
		interestLabel.setText("<html><center>"
				+ "Buyer: <b>" + buyerAIDName + "</b><br>"
				+ "Car: <b>" + carId + "</b><br>"
				+ "First offer: <b>RM " + String.format("%.2f", offer.getAmount()) + "</b>"
				+ "</center></html>");
		tabbedPane.setEnabledAt(1, true);
		tabbedPane.setSelectedIndex(1);
		showPhase("PHASE_A");
	}

	/** Called when BA sends a PROPOSE (Phase B offer). Switches to Phase B panel. */
	void showNegotiationOffer(String carId, Offer offer) {
		showPhase("PHASE_B");
		roundLabel.setText("Round: " + offer.getRound());
		offerHistoryModel.addRow(new Object[]{
				offer.getRound(), offer.getFromAgentId(),
				String.format("%.2f", offer.getAmount())
		});
	}

	/** Called when a negotiation ends (deal or failure). Resets tab. */
	void showNegotiationOutcome(String carId, boolean dealReached, double finalAmount) {
		String msg = dealReached
				? "Deal closed — " + carId + " at RM " + String.format("%.2f", finalAmount)
				: "No deal — " + carId;
		JOptionPane.showMessageDialog(this, msg,
				dealReached ? "Deal Closed" : "No Deal",
				dealReached ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
		resetNegotiationTab();
	}

	private void showPhase(String key) {
		// Find the CardLayout panel that is the negotiation tab content
		Component negTab = tabbedPane.getComponentAt(1);
		if (negTab instanceof JPanel) {
			((CardLayout) ((JPanel) negTab).getLayout()).show((JPanel) negTab, key);
		}
	}

	private void resetNegotiationTab() {
		activeCarId        = null;
		activeBuyerAIDName = null;
		if (offerHistoryModel != null) offerHistoryModel.setRowCount(0);
		if (roundLabel != null)        roundLabel.setText("Round: —");
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
			AgentController da = container.createNewAgent(
					"DA1",
					WindowAgent.class.getName(),
					new Object[]{"BrokerAgent"}
			);
			da.start();
		} catch (StaleProxyException ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this,
					"Failed to start Dealer Agent:\n" + ex.getMessage(),
					"Startup Error", JOptionPane.ERROR_MESSAGE);
		} finally {
			pendingWindow = null;
		}
	}


	// ── WindowAgent (inner class) ─────────────────────────────────────────────

	/**
	 * DealerAgent subclass that overrides the three protected hooks to update
	 * DealerWindow's Swing components via SwingUtilities.invokeLater().
	 *
	 * Grabs the DealerWindow reference from the static pendingWindow field
	 * during setup() and clears it.
	 */
	public static class WindowAgent extends DealerAgent {

		private DealerWindow window;

		@Override
		protected void setup() {
			window        = pendingWindow;
			pendingWindow = null;
			super.setup();
		}

		@Override
		protected void onBuyerInterestReceived(String carId, String buyerAIDName, Offer offer) {
			super.onBuyerInterestReceived(carId, buyerAIDName, offer); // console log
			if (window == null) return;
			SwingUtilities.invokeLater(() ->
					window.showBuyerInterest(carId, buyerAIDName, offer));
		}

		@Override
		protected void onNegotiationOfferReceived(String carId, Offer offer) {
			super.onNegotiationOfferReceived(carId, offer); // console log
			if (window == null) return;
			SwingUtilities.invokeLater(() ->
					window.showNegotiationOffer(carId, offer));
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
