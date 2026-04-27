package ans;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

/**
 * End-to-end integration test — runs the complete negotiation protocol
 * without any GUI. All agents behave autonomously via their auto-respond hooks.
 *
 * Expected console output (abridged):
 *   [KA]  BrokerAgent started.
 *   [DA]  DA1 started.
 *   [KA]  Received 1 listing(s) from DA1
 *   [BA]  BA1 started.
 *   [BA]  Search request sent to KA.
 *   [KA]  Sent 1 match(es) to BA1
 *   [BA]  1 match(es) received from KA.
 *         DA1-001 | Toyota Camry 2020 | RM55000.00 | dealer: DA1
 *   [BA]  Shortlist sent to KA — 1 dealer(s).
 *   [KA]  Forwarded interest from BA1 to DA1 for car DA1-001 (offer RM49000.00)
 *   [DA]  Accepted interest for DA1-001 — waiting for AID exchange.
 *   [KA]  FIXED FEE charged to DA1 — DA1-001 — RM500.00
 *   [BA]  AID exchanged — negotiating DA1-001 with DA1.
 *   [DA]  CFP sent to BA1 — RM55000.0 for DA1-001
 *   [BA]  Offer received: RM55000.00 for DA1-001 (round 0)
 *   [BA]  Counter-offer RM51000.00 for DA1-001 (round 1)
 *   [DA]  Incoming offer: RM51000.00 for DA1-001 (round 1)
 *   [DA]  Offer RM51000.0 >= threshold RM50000.0 — accepting.
 *   [DA]  Deal closed — DA1-001 at RM51000.0
 *   [KA]  COMMISSION charged to DA1 — RM1020.00 (deal on DA1-001 at RM51000.00)
 *   [BA]  Deal closed — DA1-001 at RM51000.00
 *
 * How to run:
 *   mvn test-compile exec:java \
 *       -Dexec.mainClass=ans.NegotiationFlowTest \
 *       -Dexec.classpathScope=test
 *
 * Or from IntelliJ / VS Code: right-click this file → Run 'NegotiationFlowTest.main()'.
 * (Ensure the run configuration includes test-classes on the classpath.)
 */
public class NegotiationFlowTest {

	public static void main(String[] args) throws StaleProxyException, InterruptedException {

		// ── Boot JADE ────────────────────────────────────────────────────────
		Runtime rt = Runtime.instance();
		Profile profile = new ProfileImpl();
		profile.setParameter(Profile.MAIN_HOST, "localhost");
		// JADE GUI disabled for test — set to "true" to watch agent lifecycles
		profile.setParameter(Profile.GUI, "false");
		AgentContainer container = rt.createMainContainer(profile);

		// ── 1. Broker ─────────────────────────────────────────────────────────
		AgentController ka = container.createNewAgent(
				"BrokerAgent",
				"ans.agent.BrokerAgent",
				null
		);
		ka.start();

		// Give KA time to reach ACTIVE state before DA tries to register listings.
		Thread.sleep(500);

		// ── 2. Dealer ─────────────────────────────────────────────────────────
		// Agent name "DA1" matches the carId prefix "DA1-001" used in TestDealerAgent,
		// and is the name KA will stamp on the CarListing sent back to BA.
		AgentController da = container.createNewAgent(
				"DA1",
				"ans.TestDealerAgent",
				new Object[]{"BrokerAgent"}
		);
		da.start();

		// Give DA time to send its listings and KA time to index them before BA
		// fires its search request. Without this delay BA's REQUEST would arrive
		// at KA before the listings are stored and return an empty match list.
		Thread.sleep(1_000);

		// ── 3. Buyer ──────────────────────────────────────────────────────────
		AgentController ba = container.createNewAgent(
				"BA1",
				"ans.TestBuyerAgent",
				new Object[]{"BrokerAgent"}
		);
		ba.start();

		// Keep the JVM alive long enough for the full protocol to complete.
		// Typical run: < 2 s. 10 s gives plenty of headroom.
		Thread.sleep(10_000);

		System.out.println("\n[TEST] Run complete — shutting down JADE platform.");
		rt.shutDown();
	}
}
