package ans;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;

/**
 * Entry point. Boots the JADE main container programmatically.
 *
 * Step 5 will add:
 *   - LauncherWindow launch after container is ready
 *   - Agent creation via container.createNewAgent() triggered by the launcher buttons
 */
public class Main {

	public static void main(String[] args) throws StaleProxyException {
		Runtime rt = Runtime.instance();

		Profile profile = new ProfileImpl();
		profile.setParameter(Profile.MAIN_HOST, "localhost");

		// JADE platform GUI stays in the background per system design.
		// Set to "false" to suppress it; set to "true" during development to inspect agents.
		profile.setParameter(Profile.GUI, "true");

		AgentContainer container = rt.createMainContainer(profile);

		// Step 5: new LauncherWindow(container).setVisible(true);
	}
}
