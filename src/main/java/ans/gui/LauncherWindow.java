package ans.gui;

import ans.Config;
import ans.agent.BrokerAgent;
import ans.model.CarListing;
import ans.model.Offer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

/**
 * Launcher Window — entry point for the GUI.
 *
 * Responsibilities:
 *   - Boot BrokerAgent (KA) immediately on construction, before any buttons are
 *     clicked. KA must be running before DA or BA start.
 *   - Provide three JButtons: "Start as Dealer", "Start as Buyer", "View Broker Log".
 *   - Pass the JADE AgentContainer to DealerWindow and BuyerWindow so they can
 *     create their own agents.
 *   - Share a DefaultTableModel with BrokerLogWindow so KA events are visible.
 *
 * BrokerWindowAgent (private inner class):
 *   Extends BrokerAgent and overrides the four protected hooks to push rows into
 *   brokerLogModel via SwingUtilities.invokeLater(). This keeps all Swing updates
 *   on the EDT even though hooks fire on JADE's agent thread.
 *
 * Static-link pattern:
 *   LauncherWindow.pendingLauncher is set to 'this' immediately before
 *   container.createNewAgent() is called. BrokerWindowAgent.setup() reads it and
 *   clears it. This is safe for v1 (one KA, one DA, one BA — never concurrent).
 */
public class LauncherWindow extends JFrame {

	// ── Static link used during agent construction ────────────────────────────

	/**
	 * Set just before createNewAgent() and cleared inside BrokerWindowAgent.setup().
	 * Lets the inner agent class grab a reference to this window without needing
	 * O2A or a registry map.
	 */
	static LauncherWindow pendingLauncher;


	// ── Instance state ────────────────────────────────────────────────────────

	private final AgentContainer    container;
	private final DefaultTableModel brokerLogModel;
	private final Color             accent;


	// ── Constructor ───────────────────────────────────────────────────────────

	public LauncherWindow(AgentContainer container) {
		super("Automated Negotiation System — Launcher");
		this.container     = container;
		this.brokerLogModel = buildBrokerLogModel();
		this.accent         = Color.decode(Config.get("gui.accentColour"));

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(400, 280);
		setLocationRelativeTo(null);
		setResizable(false);

		buildUI();
		startBrokerAgent();
	}


	// ── UI construction ───────────────────────────────────────────────────────

	private void buildUI() {
		JPanel root = new JPanel(new BorderLayout(0, 12));
		root.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
		root.setBackground(Color.WHITE);

		// Title label
		JLabel title = new JLabel("Automated Negotiation System", SwingConstants.CENTER);
		title.setFont(new Font("SansSerif", Font.BOLD, 16));
		title.setForeground(accent);
		root.add(title, BorderLayout.NORTH);

		// Button panel
		JPanel btnPanel = new JPanel(new GridLayout(3, 1, 0, 10));
		btnPanel.setBackground(Color.WHITE);

		JButton dealerBtn = makeButton("Start as Dealer");
		JButton buyerBtn  = makeButton("Start as Buyer");
		JButton logBtn    = makeButton("View Broker Log");

		dealerBtn.addActionListener(e -> openDealerWindow());
		buyerBtn.addActionListener(e  -> openBuyerWindow());
		logBtn.addActionListener(e    -> openBrokerLog());

		btnPanel.add(dealerBtn);
		btnPanel.add(buyerBtn);
		btnPanel.add(logBtn);
		root.add(btnPanel, BorderLayout.CENTER);

		// Status label at bottom
		JLabel status = new JLabel("KA starting…", SwingConstants.CENTER);
		status.setFont(new Font("SansSerif", Font.ITALIC, 11));
		status.setForeground(Color.GRAY);
		root.add(status, BorderLayout.SOUTH);

		setContentPane(root);
	}

	private JButton makeButton(String label) {
		JButton btn = new JButton(label);
		btn.setBackground(accent);
		btn.setForeground(Color.WHITE);
		btn.setFocusPainted(false);
		btn.setFont(new Font("SansSerif", Font.BOLD, 13));
		btn.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
		return btn;
	}

	private static DefaultTableModel buildBrokerLogModel() {
		DefaultTableModel model = new DefaultTableModel(
				new String[]{"Timestamp", "Event", "Agent", "Amount"}, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		return model;
	}


	// ── Agent creation ────────────────────────────────────────────────────────

	/**
	 * Creates and starts BrokerWindowAgent ("BrokerAgent").
	 * Called once during construction — must complete before the user can click
	 * "Start as Dealer" or "Start as Buyer" (KA must be running first).
	 *
	 * A 500 ms sleep gives KA time to reach ACTIVE state after start().
	 */
	private void startBrokerAgent() {
		try {
			pendingLauncher = this;
			AgentController ka = container.createNewAgent(
					"BrokerAgent",
					BrokerWindowAgent.class.getName(),
					null
			);
			ka.start();
			Thread.sleep(500); // let KA reach ACTIVE before DA/BA try to talk to it
		} catch (StaleProxyException | InterruptedException ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this,
					"Failed to start Broker Agent:\n" + ex.getMessage(),
					"Startup Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void openDealerWindow() {
		new DealerWindow(container).setVisible(true);
	}

	private void openBuyerWindow() {
		new BuyerWindow(container).setVisible(true);
	}

	private void openBrokerLog() {
		new BrokerLogWindow(brokerLogModel).setVisible(true);
	}


	// ── BrokerWindowAgent (inner class) ──────────────────────────────────────

	/**
	 * BrokerAgent subclass that pushes event-log rows into LauncherWindow's
	 * brokerLogModel via SwingUtilities.invokeLater().
	 *
	 * Grabs the LauncherWindow reference from the static pendingLauncher field
	 * during setup() and clears it immediately so the next agent creation doesn't
	 * accidentally reuse it.
	 */
	public static class BrokerWindowAgent extends BrokerAgent {

		private LauncherWindow launcher;
		private static final DateTimeFormatter TIME_FMT =
				DateTimeFormatter.ofPattern("HH:mm:ss");

		@Override
		protected void setup() {
			// Grab and clear the static link — must happen before super.setup()
			// fires any hooks (which it doesn't, but defensive is safer).
			launcher        = pendingLauncher;
			pendingLauncher = null;
			super.setup();
		}

		@Override
		protected void onListingsReceived(String dealerAIDName, List<CarListing> listings) {
			addRow("Listings received (" + listings.size() + ")", dealerAIDName, "");
		}

		@Override
		protected void onFixedFeeCharged(String dealerAIDName, String carId) {
			double fee = Double.parseDouble(Config.get("broker.fixedFee"));
			addRow("Fixed fee charged — " + carId, dealerAIDName,
					String.format("RM %.2f", fee));
		}

		@Override
		protected void onDealClosed(Offer finalOffer, double commission) {
			addRow("Deal closed — " + finalOffer.getCarId()
					+ " at RM" + String.format("%.2f", finalOffer.getAmount()),
					finalOffer.getFromAgentId(),
					String.format("Commission RM %.2f", commission));
		}

		@Override
		protected void onDealFailed(String carId) {
			addRow("Deal failed — " + carId, "", "");
		}

		/** Push one row into the shared table model on the Swing EDT. */
		private void addRow(String event, String agent, String amount) {
			if (launcher == null) return;
			String timestamp = LocalTime.now().format(TIME_FMT);
			SwingUtilities.invokeLater(() ->
					launcher.brokerLogModel.addRow(
							new Object[]{timestamp, event, agent, amount}));
		}
	}
}
