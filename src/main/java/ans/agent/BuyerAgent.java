package ans.agent;

import java.util.HashMap;
import java.util.Map;

import ans.model.BuyerRequirements;
import ans.model.NegotiationState;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;

/**
 * Buyer Agent (BA) — represents one car buyer.
 *
 * Responsibilities (all implemented in Step 4):
 *   - Send requirements to KA and receive matching listings
 *   - Display matches, let the human shortlist and rank up to 3 dealers
 *   - Send shortlist + first offer per car to KA
 *   - Receive dealer AID from KA, wait for DA's CFP
 *   - v1: human reads offers and types counters via BuyerWindow GUI
 *   - v2: autonomous negotiation via TimeBasedStrategy (future)
 *
 * reservePrice is stored privately here and never transmitted anywhere.
 *
 * This file is the Step 1 skeleton: state, setup only.
 * Behaviour logic is implemented in Step 4.
 */
public class BuyerAgent extends Agent {

	// ── State (technical_design.md — BuyerAgent section) ────────────────────

	private BuyerRequirements             requirements;
	private double                        reservePrice;      
	private Map<String, NegotiationState> activeNegotiations; 
	private double                        alpha;
	private AID                           brokerAID;


	// Lifecycle  

	@Override
	protected void setup() {
		activeNegotiations = new HashMap<>();

		Object[] args = getArguments();
		String brokerName = (args != null && args.length > 0)
				? (String) args[0]
				: "BrokerAgent";
		brokerAID = new AID(brokerName, AID.ISLOCALNAME);

		addBehaviour(new SearchBehaviour());
		addBehaviour(new ReceiveMatchesBehaviour());
		addBehaviour(new ReceiveAIDExchangeBehaviour());
		addBehaviour(new NegotiationBehaviour());

		System.out.println("[BA] " + getLocalName() + " started. Broker: " + brokerName);
	}

	@Override
	protected void takeDown() {
		System.out.println("[BA] " + getLocalName() + " shutting down.");
	}


	// Public accessors (used by BuyerWindow GUI in Step 5)

	/**
	 * Called by BuyerWindow when the user submits the search form.
	 * reservePrice is kept private inside BA and never sent to KA or DA.
	 */
	public void setRequirementsAndReserve(BuyerRequirements req, double reservePrice) {
		this.requirements  = req;
		this.reservePrice  = reservePrice;
	}

	public BuyerRequirements getRequirements() {
		return requirements;
	}


	// Behaviour stubs (logic implemented in Step 4)

	/**
	 * Runs once. Sends BuyerRequirements to KA via requestSearch().
	 * Waits for requirements to be set by the GUI before firing
	 * (in practice, triggered by BuyerWindow's "Search" button).
	 */
	private class SearchBehaviour extends OneShotBehaviour {
		@Override
		public void action() {
			// TODO Step 4: send MessageBuilder.requestSearch(brokerAID, requirements).
		}
	}


	/**
	 * Waits for KA to return the list of matching listings (informMatches).
	 * Passes the list to BuyerWindow for the human to review and shortlist.
	 */
	private class ReceiveMatchesBehaviour extends CyclicBehaviour {
		@Override
		public void action() {
			// TODO Step 4: filter on conversationId "buyer-search", deserialise
			//              List<CarListing> via Gson, pass to BuyerWindow for display.
			block();
		}
	}


	/**
	 * Waits for KA to send the matched dealer's AID (informDealerAID).
	 * Stores it so NegotiationBehaviour can receive the incoming CFP.
	 */
	private class ReceiveAIDExchangeBehaviour extends CyclicBehaviour {
		@Override
		public void action() {
			// TODO Step 4: filter on conversationId = carId, deserialise dealerAIDName,
			//              store AID, notify BuyerWindow to show the Negotiation tab.
			block();
		}
	}


	/**
	 * v1: waits for DA's CFP and each subsequent PROPOSE.
	 * Passes each offer to BuyerWindow for the human to read and respond.
	 * Sends BA's counter-offer (PROPOSE), accept (ACCEPT_PROPOSAL), or
	 * walk away (REJECT_PROPOSAL) based on the human's GUI input.
	 * Enforces reservePrice privately — BA never sends above it.
	 */
	private class NegotiationBehaviour extends CyclicBehaviour {
		@Override
		public void action() {
			// TODO Step 4: handle CFP, PROPOSE messages. Forward to BuyerWindow.
			//              On GUI response: send proposeOffer(), acceptProposal(),
			//              or rejectProposal(). Track rounds via NegotiationState.
			block();
		}
	}
}
