package ans.gui;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/**
 * Broker Log Window — read-only event log for the Broker Agent (KA).
 *
 * Displays a JTable with four columns:
 *   Timestamp | Event | Agent | Amount
 *
 * Rows are added in real time by LauncherWindow's BrokerWindowAgent subclass
 * (which overrides the four BrokerAgent hooks and calls tableModel.addRow() via
 * SwingUtilities.invokeLater). This window does nothing but display the model —
 * it owns no agents and has no interactive components.
 *
 * Auto-scroll: a TableModelListener scrolls to the last row whenever a new row
 * is inserted.
 *
 * Usage:
 *   BrokerLogWindow win = new BrokerLogWindow(sharedTableModel);
 *   win.setVisible(true);
 */
public class BrokerLogWindow extends JFrame {

	// ── Constructor ───────────────────────────────────────────────────────────

	/**
	 * @param tableModel  Shared DefaultTableModel owned by LauncherWindow.
	 *                    LauncherWindow's BrokerWindowAgent pushes rows into it;
	 *                    this window just displays it.
	 */
	public BrokerLogWindow(DefaultTableModel tableModel) {
		super("Broker Event Log — KA");
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		setSize(780, 420);
		setLocationRelativeTo(null);

		buildUI(tableModel);
	}


	// ── UI construction ───────────────────────────────────────────────────────

	private void buildUI(DefaultTableModel tableModel) {
		JTable table = new JTable(tableModel) {
			// Make all cells non-editable
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};

		table.setFont(new Font("Monospaced", Font.PLAIN, 12));
		table.setRowHeight(22);
		table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));

		// Fixed column widths: Timestamp and Amount are narrower
		table.getColumnModel().getColumn(0).setPreferredWidth(160); // Timestamp
		table.getColumnModel().getColumn(1).setPreferredWidth(360); // Event
		table.getColumnModel().getColumn(2).setPreferredWidth(100); // Agent
		table.getColumnModel().getColumn(3).setPreferredWidth(120); // Amount

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		// Auto-scroll to the last row whenever a new row is added
		tableModel.addTableModelListener(e -> {
			if (e.getType() == TableModelEvent.INSERT) {
				SwingUtilities.invokeLater(() -> {
					int last = tableModel.getRowCount() - 1;
					if (last >= 0) {
						table.scrollRectToVisible(table.getCellRect(last, 0, true));
					}
				});
			}
		});

		setLayout(new BorderLayout());
		add(scrollPane, BorderLayout.CENTER);
	}
}
