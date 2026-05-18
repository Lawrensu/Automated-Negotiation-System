import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

public class LauncherGUI extends JFrame {

    private AgentContainer mainContainer;
    private JLabel statusLabel;
    private JLabel countLabel;
    private DefaultTableModel tableModel;
    private JTable agentTable;
    private int agentCount = 0;

    private final Map<String, AgentController> agentControllers = new HashMap<>();

    // ── Palette: white, black, slate ─────────────────────────────────────────
    private static final Color C_BG       = Color.WHITE;
    private static final Color C_TEXT     = new Color(15,  15,  15);
    private static final Color C_SLATE    = new Color(30,  58,  95);   // primary accent
    private static final Color C_SLATE_HV = new Color(20,  40,  70);   // hover
    private static final Color C_BORDER   = new Color(210, 215, 220);
    private static final Color C_ROW_ALT  = new Color(247, 249, 251);
    private static final Color C_MUTED    = new Color(110, 120, 130);
    private static final Color C_RED      = new Color(180,  35,  35);
    private static final Color C_RED_HV   = new Color(220,  50,  50);

    private static final Font FONT_BODY   = new Font("SansSerif", Font.PLAIN,  12);
    private static final Font FONT_BOLD   = new Font("SansSerif", Font.BOLD,   12);
    private static final Font FONT_SMALL  = new Font("SansSerif", Font.PLAIN,  11);
    private static final Font FONT_HEAD   = new Font("SansSerif", Font.BOLD,   18);
    private static final Font FONT_MONO   = new Font("Monospaced", Font.PLAIN, 11);

    public LauncherGUI() {
        super("JADE Agent Launcher");
        setupJADE();
        buildUI();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────────────────

    private void buildUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(620, 560);
        setLocationRelativeTo(null);
        setResizable(true);
        getContentPane().setBackground(C_BG);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),    BorderLayout.NORTH);
        add(buildMain(),      BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        setVisible(true);
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(C_SLATE);
        header.setBorder(new EmptyBorder(16, 24, 16, 24));

        JLabel title = new JLabel("JADE Agent Launcher");
        title.setFont(FONT_HEAD);
        title.setForeground(Color.WHITE);

        JLabel sub = new JLabel("Manage dealer and buyer agents on the local JADE platform");
        sub.setFont(FONT_SMALL);
        sub.setForeground(new Color(180, 200, 220));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(title);
        text.add(Box.createVerticalStrut(3));
        text.add(sub);

        header.add(text, BorderLayout.WEST);
        return header;
    }

    // ── Main content ──────────────────────────────────────────────────────────

    private JPanel buildMain() {
        JPanel main = new JPanel();
        main.setBackground(C_BG);
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(new EmptyBorder(20, 24, 16, 24));

        JPanel spawnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        spawnRow.setOpaque(false);
        spawnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        JLabel spawnLabel = new JLabel("Spawn:");
        spawnLabel.setFont(FONT_BOLD);
        spawnLabel.setForeground(C_TEXT);

        JButton dealerBtn = buildPrimaryButton("+ Dealer Agent");
        dealerBtn.addActionListener(e ->
            spawnAgent("DealerAgent", "agents.dealer.DealerAgent", "Dealer"));

        JButton buyerBtn = buildPrimaryButton("+ Buyer Agent");
        buyerBtn.addActionListener(e ->
            spawnAgent("BuyerAgent", "agents.buyer.BuyerAgent", "Buyer"));

        spawnRow.add(spawnLabel);
        spawnRow.add(dealerBtn);
        spawnRow.add(buyerBtn);

        main.add(spawnRow);
        main.add(Box.createVerticalStrut(16));

        // ── Divider ──────────────────────────────────────────────
        main.add(buildDivider("Active Agents"));
        main.add(Box.createVerticalStrut(10));

        // ── Table ────────────────────────────────────────────────
        String[] cols = {"Agent Name", "Type", "Started At", "Action"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c == 3; }
            @Override public String getColumnName(int c) { return cols[c]; }
        };

        agentTable = new JTable(tableModel);
        agentTable.setFont(FONT_BODY);
        agentTable.setRowHeight(32);
        agentTable.setShowGrid(false);
        agentTable.setIntercellSpacing(new Dimension(0, 0));
        agentTable.setBackground(C_BG);
        agentTable.setForeground(C_TEXT);
        agentTable.setSelectionBackground(new Color(220, 230, 245));
        agentTable.setSelectionForeground(C_TEXT);
        agentTable.setFocusable(false);
        agentTable.setRowSelectionAllowed(true);

        // Column widths
        agentTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        agentTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        agentTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        agentTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        agentTable.getColumnModel().getColumn(3).setMaxWidth(90);

        // Header style
        JTableHeader th = agentTable.getTableHeader();
        th.setFont(FONT_BOLD);
        th.setBackground(new Color(235, 239, 244));
        th.setForeground(C_TEXT);
        th.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        th.setReorderingAllowed(false);
        ((DefaultTableCellRenderer) th.getDefaultRenderer())
            .setHorizontalAlignment(SwingConstants.LEFT);

        // Alternating row renderer
        agentTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object val, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, val, sel, foc, row, col);
                setFont(col == 0 ? FONT_MONO : FONT_BODY);
                setForeground(col == 1 ? C_MUTED : C_TEXT);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                if (!sel) setBackground(row % 2 == 0 ? C_BG : C_ROW_ALT);
                return this;
            }
        });

        // Remove button renderer + editor
        agentTable.getColumnModel().getColumn(3)
            .setCellRenderer(new RemoveButtonRenderer());
        agentTable.getColumnModel().getColumn(3)
            .setCellEditor(new RemoveButtonEditor());

        JScrollPane scroll = new JScrollPane(agentTable);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
        scroll.setBackground(C_BG);
        scroll.getViewport().setBackground(C_BG);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);

        main.add(scroll);
        return main;
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(245, 247, 249));
        bar.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, C_BORDER),
            new EmptyBorder(6, 24, 6, 24)
        ));

        statusLabel = new JLabel("Platform running · localhost");
        statusLabel.setFont(FONT_SMALL);
        statusLabel.setForeground(C_MUTED);

        countLabel = new JLabel("0 agents");
        countLabel.setFont(FONT_SMALL);
        countLabel.setForeground(C_MUTED);

        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(countLabel,  BorderLayout.EAST);
        return bar;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Agent lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private void spawnAgent(String prefix, String className, String type) {
        String name = prefix + "_" + System.currentTimeMillis();
        try {
            AgentController ac = mainContainer.createNewAgent(name, className, new Object[0]);
            ac.start();
            agentControllers.put(name, ac);
            agentCount++;

            String time = new java.text.SimpleDateFormat("HH:mm:ss")
                    .format(new java.util.Date());
            tableModel.addRow(new Object[]{name, type, time, "Remove"});

            updateFooter("Spawned: " + name);
        } catch (Exception ex) {
            statusLabel.setText("Error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void removeAgentByRow(int row) {
        String name = (String) tableModel.getValueAt(row, 0);
        AgentController ac = agentControllers.get(name);
        if (ac != null) {
            try { ac.kill(); } catch (StaleProxyException ignored) {}
            agentControllers.remove(name);
        }
        tableModel.removeRow(row);
        agentCount--;
        updateFooter("Removed: " + name);
    }

    private void updateFooter(String status) {
        statusLabel.setText(status);
        countLabel.setText(agentCount + " agent" + (agentCount == 1 ? "" : "s"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Table button renderer / editor
    // ─────────────────────────────────────────────────────────────────────────

    private class RemoveButtonRenderer implements javax.swing.table.TableCellRenderer {
        private final JButton btn = buildRemoveButton();
        @Override
        public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            btn.setBackground(sel ? new Color(220, 230, 245) : (row % 2 == 0 ? C_BG : C_ROW_ALT));
            return btn;
        }
    }

    private class RemoveButtonEditor extends DefaultCellEditor {
        private final JButton btn;
        private int editingRow;

        RemoveButtonEditor() {
            super(new JCheckBox());
            btn = buildRemoveButton();
            btn.addActionListener(e -> {
                fireEditingStopped();
                int row = agentTable.convertRowIndexToModel(editingRow);
                String name = (String) tableModel.getValueAt(row, 0);
                int confirm = JOptionPane.showConfirmDialog(
                    LauncherGUI.this,
                    "Remove agent \"" + name + "\" and deregister from DF?",
                    "Confirm",
                    JOptionPane.YES_NO_OPTION
                );
                if (confirm == JOptionPane.YES_OPTION) removeAgentByRow(row);
            });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable t, Object v, boolean sel, int row, int col) {
            editingRow = row;
            return btn;
        }

        @Override public Object getCellEditorValue() { return "Remove"; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Component helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Slate filled button — primary action. */
    private JButton buildPrimaryButton(String text) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? C_SLATE_HV : C_SLATE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.setColor(Color.WHITE);
                g2.setFont(FONT_BOLD);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()  - fm.stringWidth(getText())) / 2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        btn.setPreferredSize(new Dimension(140, 32));
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /** Small outlined red "Remove" button used in the table. */
    private JButton buildRemoveButton() {
        JButton btn = new JButton("Remove") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isRollover() || getModel().isPressed()) {
                    g2.setColor(C_RED_HV);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    g2.setColor(Color.WHITE);
                } else {
                    g2.setColor(Color.WHITE);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    g2.setColor(C_RED);
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 5, 5);
                }
                g2.setFont(FONT_SMALL);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()  - fm.stringWidth(getText())) / 2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        btn.setPreferredSize(new Dimension(76, 26));
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /** Horizontal rule with a centred label. */
    private JPanel buildDivider(String label) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JSeparator left  = new JSeparator();
        JSeparator right = new JSeparator();
        left.setForeground(C_BORDER);
        right.setForeground(C_BORDER);

        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_BOLD);
        lbl.setForeground(C_TEXT);

        p.add(left,  BorderLayout.WEST);
        p.add(lbl,   BorderLayout.CENTER);
        p.add(right, BorderLayout.EAST);

        // Give the separators equal weight
        left.setPreferredSize(new Dimension(0, 1));
        right.setPreferredSize(new Dimension(0, 1));

        return p;
    }



    private void setupJADE() {
        Runtime rt = Runtime.instance();
        Profile p  = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.GUI, "true");
        mainContainer = rt.createMainContainer(p);
    }

    private void disposeJADE() {
        try { if (mainContainer != null) mainContainer.kill(); }
        catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new LauncherGUI();
        });
    }
}