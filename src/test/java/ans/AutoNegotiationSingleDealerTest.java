package ans;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

/**
 * End-to-end smoke test for autonomous buyer negotiation with one dealer.
 *
 * Run:
 * mvn test-compile exec:java
 * -Dexec.mainClass=ans.AutoNegotiationSingleDealerTest
 * -Dexec.classpathScope=test
 */
public class AutoNegotiationSingleDealerTest {

    public static void main(String[] args) throws StaleProxyException, InterruptedException {
        Runtime rt = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.MAIN_PORT, "1200");
        profile.setParameter(Profile.GUI, "false");
        AgentContainer container = rt.createMainContainer(profile);

        AgentController ka = container.createNewAgent("BrokerAgent", "ans.agent.BrokerAgent", null);
        ka.start();
        Thread.sleep(500);

        AgentController da = container.createNewAgent("DA1", "ans.TestDealerForAutoBuyer",
                new Object[] { "BrokerAgent" });
        da.start();
        Thread.sleep(1_000);

        AgentController ba = container.createNewAgent("AutoBA1", "ans.TestAutoBuyerAgent",
                new Object[] { "BrokerAgent" });
        ba.start();

        Thread.sleep(10_000);
        System.out.println("\n[TEST] Auto negotiation run complete.");
        rt.shutDown();
    }
}
