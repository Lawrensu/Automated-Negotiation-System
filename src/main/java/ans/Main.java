package ans;

import ans.gui.LauncherWindow;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;

/**
 * Entry point. Boots the JADE main container then launches LauncherWindow.
 *
 * LauncherWindow is constructed on the Swing EDT (as required by Swing's
 * single-thread rule). It immediately creates and starts BrokerAgent (KA),
 * then waits for the human to open Dealer or Buyer windows.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=ans.Main
 */
public class Main {

	public static void main(String[] args) throws StaleProxyException {
		Runtime rt = Runtime.instance();

		Profile profile = new ProfileImpl();
		profile.setParameter(Profile.MAIN_HOST, "localhost");

		// JADE platform GUI stays in the background per system design.
		// Set to "false" to suppress it; set to "true" during development to inspect agents.
		profile.setParameter(Profile.GUI, "false");

		AgentContainer container = rt.createMainContainer(profile);

		// Boot the launcher on the Swing EDT — Swing is not thread-safe and
		// all window creation must happen on the Event Dispatch Thread.
		javax.swing.SwingUtilities.invokeLater(() ->
				new LauncherWindow(container).setVisible(true));
	}
}
