package ans.agent;

import ans.Config;
import ans.model.BuyerRequirements;
import ans.model.CarListing;
import ans.model.NegotiationState;
import ans.model.Offer;
import ans.util.MessageBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Buyer Agent (BA) — represents one car buyer.
 *
 * Responsibilities:
 *   - Send requirements to KA, receive matching listings
 *   - Let human shortlist and rank up to 3 dealers, send shortlist to KA
 *   - Receive dealer AID from KA, wait for DA's opening CFP
 *   - v1: human reads each offer and responds via BuyerWindow GUI
 *   - v2: autonomous concession via TimeBasedStrategy (Step 6+)
 *
 * Reserve price is stored privately here and never transmitted anywhere.
 *
 * GUI interaction pattern (mirrors DealerAgent):
 *   Protected hooks notify BuyerWindow when incoming messages arrive.
 *   Public response methods (submitOffer, acceptDeal, walkAway) are called
 *   by BuyerWindow button handlers on the Swing EDT — JADE's send() is
 *   thread-safe so no synchronisation is needed.
 *
 * Deal-outcome protocol:
 *   DA always notifies KA of outcomes (informDealClosed / informDealFailed).
 *   BA never sends those messages directly to avoid duplicate notifications:
 *     BA accepts → sends ACCEPT_PROPOSAL to DA → DA sends informDealClosed to KA.
 *     BA walks away → sends REJECT_PROPOSAL to DA → DA sends informDealFailed to KA.
 *     DA accepts → sends ACCEPT_PROPOSAL + informDealClosed to KA → BA cleans up.
 *     DA walks away → sends REJECT_PROPOSAL + informDealFailed to KA → BA cleans up.
 */
public class BuyerAgent extends Agent {

	private static final Gson GSON = new Gson();


	// ── State (technical_design.md — BuyerAgent section) ────────────────────

	private BuyerRequirements             requirements;
	private double                        reservePrice;           // private, never transmitted
	private Map<String, NegotiationState> activeNegotiations;    // carId → state
	private Map<String, AID>             dealerAIDs;            // carId → dealer AID
	private Map<String, Double>           submittedOfferAmounts; // carId → first offer BA sent
	private double                        alpha;                 // concession shape; default linear
	private AID                           brokerAID;


	// ── Lifecycle ────────────────────────────────────────────────────────────

	@Override
	protected void setup() {
		activeNegotiations    = new HashMap<>();
		dealerAIDs            = new HashMap<>();
		submittedOfferAmounts = new HashMap<>();
		alpha                 = 1.0; // linear concession; overridden via GUI in Step 5

		Object[] args = getArguments();
		String brokerName = (args != null && args.length > 0)
				? (String) args[0]
				: "BrokerAgent";
		brokerAID = new AID(brokerName, AID.ISLOCALNAME);

		addBehaviour(new ReceiveMatchesBehaviour());
		addBehaviour(new ReceiveAIDExchangeBehaviour());
		addBehaviour(new NegotiationBehaviour());

		System.out.println("[BA] " + getLocalName() + " started. Broker: " + brokerName);
	}

	@Override
	protected void takeDown() {
		System.out.println("[BA] " + getLocalName() + " shutting down.");
	}


	// ── Public API (called by BuyerWindow GUI — Step 5) ─────────────────────

	/**
	 * Called by BuyerWindow when the human submits the search form.
	 * reservePrice is kept private inside BA and never forwarded to KA or DA.
	 */
	public void setRequirementsAndReserve(BuyerRequirements req, double reservePrice) {
		this.requirements = req;
		this.reservePrice = reservePrice;
	}

	public BuyerRequirements getRequirements() {
		return requirements;
	}

	/**
	 * Send the search request to KA. Called by BuyerWindow's "Search" button
	 * after setRequirementsAndReserve() has populated the requirements.
	 */
	public void sendSearchRequest() {
		if (requirements == null) {
			System.err.println("[BA] sendSearchRequest() called before requirements set — ignored.");
			return;
		}
		send(MessageBuilder.requestSearch(brokerAID, requirements));
		System.out.println("[BA] Search request sent to KA.");
	}

	/**
	 * Send the ranked shortlist to KA. Called by BuyerWindow's "Send Shortlist" button.
	 *
	 * shortlist must be a LinkedHashMap so insertion order (= BA's ranking) is preserved
	 * when serialised to JSON — Gson serialises maps in iteration order, and KA's
	 * ReceiveShortlistBehaviour relies on that order for sequential dealer contact.
	 *
	 * Also caches each offer's amount by carId so NegotiationState can be seeded with
	 * the correct ownFirstOffer when DA's CFP arrives.
	 */
	public void submitShortlist(Map<String, Offer> shortlist) {
		for (Offer o : shortlist.values()) {
			submittedOfferAmounts.put(o.getCarId(), o.getAmount());
		}
		send(MessageBuilder.informShortlist(brokerAID, shortlist));
		System.out.println("[BA] Shortlist sent to KA — " + shortlist.size() + " dealer(s).");
	}

	/**
	 * Counter-offer. Called by BuyerWindow's "Counter" button.
	 * Increments round, records own offer, sends PROPOSE to DA.
	 */
	public void submitOffer(String carId, double amount) {
		NegotiationState state = activeNegotiations.get(carId);
		if (state == null) return;

		state.incrementRound();
		Offer offer = new Offer(carId, amount, state.getCurrentRound(), getLocalName(), false);
		state.recordOwnOffer(offer);

		send(MessageBuilder.proposeOffer(dealerAIDs.get(carId), offer));
		System.out.println("[BA] Counter-offer RM" + String.format("%.2f", amount)
				+ " for " + carId + " (round " + state.getCurrentRound() + ")");
	}

	/**
	 * Accept DA's last offer. Called by BuyerWindow's "Accept" button.
	 * Sends ACCEPT_PROPOSAL to DA. DA then sends informDealClosed to KA —
	 * BA does not send informDealClosed to avoid a duplicate notification to KA.
	 */
	public void acceptDeal(String carId) {
		NegotiationState state = activeNegotiations.get(carId);
		if (state == null) return;

		List<Offer> opponentHistory = state.getOpponentOfferHistory();
		if (opponentHistory.isEmpty()) return;

		Offer lastFromDA = opponentHistory.get(opponentHistory.size() - 1);
		Offer accept = new Offer(carId, lastFromDA.getAmount(),
				state.getCurrentRound(), getLocalName(), true);

		send(MessageBuilder.acceptProposal(dealerAIDs.get(carId), accept));
		state.setStatus(NegotiationState.Status.DEAL_REACHED);
		cleanUpNegotiation(carId);
		onNegotiationEnded(carId, true, lastFromDA.getAmount());
	}

	/**
	 * Walk away from the negotiation. Called by BuyerWindow's "Walk Away" button.
	 * Sends REJECT_PROPOSAL to DA. DA then sends informDealFailed to KA, which
	 * advances the shortlist — BA does not send informDealFailed directly.
	 */
	public void walkAway(String carId) {
		NegotiationState state = activeNegotiations.get(carId);
		if (state == null) return;

		Offer reject = new Offer(carId, 0, state.getCurrentRound(), getLocalName(), true);
		send(MessageBuilder.rejectProposal(dealerAIDs.get(carId), reject));
		state.setStatus(NegotiationState.Status.FAILED);
		cleanUpNegotiation(carId);
		onNegotiationEnded(carId, false, 0);
	}


	// ── Protected notification hooks (overridden by BuyerWindow in Step 5) ──

	/** Called when KA returns matched listings. Override to display results in GUI. */
	protected void onMatchesReceived(List<CarListing> matches) {
		System.out.println("[BA] " + matches.size() + " match(es) received from KA.");
		for (CarListing c : matches) {
			System.out.println("     " + c.getCarId()
					+ " | " + c.getMake() + " " + c.getModel() + " " + c.getYear()
					+ " | RM" + String.format("%.2f", c.getRetailPrice())
					+ " | dealer: " + c.getDealerAIDName());
		}
	}

	/** Called when KA completes AID exchange. Override to show negotiation tab in GUI. */
	protected void onAIDExchanged(String carId, String dealerAIDName) {
		System.out.println("[BA] AID exchanged — negotiating " + carId
				+ " with " + dealerAIDName + ". Waiting for DA's CFP…");
	}

	/** Called when KA exhausts the entire shortlist. Override to notify user in GUI. */
	protected void onNoDealerEngaged() {
		System.out.println("[BA] All shortlisted dealers declined — no negotiation started.");
	}

	/**
	 * Called on every incoming offer (CFP at round 0, PROPOSE at round 1+).
	 * Override in BuyerWindow to display the offer and enable the response buttons.
	 */
	protected void onNegotiationOfferReceived(String carId, Offer offer) {
		System.out.println("[BA] Offer received: RM" + String.format("%.2f", offer.getAmount())
				+ " for " + carId + " (round " + offer.getRound() + ")");
	}

	/** Called when a negotiation ends (deal or failure). Override to show outcome in GUI. */
	protected void onNegotiationEnded(String carId, boolean dealReached, double finalAmount) {
		if (dealReached) {
			System.out.println("[BA] Deal closed — " + carId
					+ " at RM" + String.format("%.2f", finalAmount));
		} else {
			System.out.println("[BA] No deal — " + carId);
		}
	}


	// ── Private helpers ───────────────────────────────────────────────────────

	private void cleanUpNegotiation(String carId) {
		activeNegotiations.remove(carId);
		dealerAIDs.remove(carId);
		submittedOfferAmounts.remove(carId);
	}


	// ── Behaviours ────────────────────────────────────────────────────────────

	/**
	 * Waits for KA to return the list of matching listings.
	 * Passes the list to onMatchesReceived so the GUI can display it.
	 * The human then shortlists cars and clicks "Send Shortlist" —
	 * BuyerWindow calls submitShortlist() directly at that point.
	 */
	private class ReceiveMatchesBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchConversationId("buyer-search")
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			Type listType = new TypeToken<List<CarListing>>(){}.getType();
			List<CarListing> matches = GSON.fromJson(msg.getContent(), listType);
			onMatchesReceived(matches);
		}
	}


	/**
	 * Waits for two possible outcomes from KA after the shortlist is submitted:
	 *
	 *   "aid-exchange" (ontology) — a dealer accepted; KA sends the dealer's AID.
	 *     Stores the AID keyed by carId. DA will send its CFP soon after;
	 *     NegotiationBehaviour handles that incoming message.
	 *
	 *   "no-dealer-engaged" (ontology) — shortlist exhausted; all dealers declined.
	 *     Notifies the user via hook so they can revise requirements if they wish.
	 *
	 * Both arrive as INFORM from KA. Combining them in one behaviour avoids
	 * a second behaviour that would share a broad INFORM filter and risk
	 * consuming the wrong message from the queue.
	 */
	private class ReceiveAIDExchangeBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.or(
						MessageTemplate.MatchOntology("aid-exchange"),
						MessageTemplate.MatchOntology("no-dealer-engaged")
				)
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			if ("no-dealer-engaged".equals(msg.getOntology())) {
				onNoDealerEngaged();
				return;
			}

			// "aid-exchange" — KA confirmed which dealer was matched
			String     carId         = msg.getConversationId();
			JsonObject payload       = JsonParser.parseString(msg.getContent()).getAsJsonObject();
			String     dealerAIDName = payload.get("dealerAIDName").getAsString();

			dealerAIDs.put(carId, new AID(dealerAIDName, AID.ISLOCALNAME));
			onAIDExchanged(carId, dealerAIDName);
		}
	}


	/**
	 * Handles Phase 3: the direct offer loop between BA and DA.
	 *
	 * CFP (round 0 — DA's opening ask):
	 *   Creates NegotiationState seeded with BA's submitted first offer and
	 *   reservePrice. Calls hook so GUI can display offer and enable buttons.
	 *   Blocks until the human responds via submitOffer, acceptDeal, or walkAway.
	 *
	 * PROPOSE (DA counter-offer, round ≥ 1):
	 *   Records offer, increments round. If round limit is reached, calls walkAway()
	 *   which sends REJECT_PROPOSAL to DA; DA then notifies KA — no duplicate needed.
	 *   Calls hook and blocks.
	 *
	 * ACCEPT_PROPOSAL (DA accepted BA's last offer):
	 *   DA has already sent informDealClosed to KA. BA just cleans up and fires hook.
	 *
	 * REJECT_PROPOSAL (DA walked away):
	 *   DA has already sent informDealFailed to KA. BA just cleans up and fires hook.
	 */
	private class NegotiationBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.or(
				MessageTemplate.MatchPerformative(ACLMessage.CFP),
				MessageTemplate.or(
						MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
						MessageTemplate.or(
								MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
								MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
						)
				)
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			Offer  offer = GSON.fromJson(msg.getContent(), Offer.class);
			String carId = msg.getConversationId();

			switch (msg.getPerformative()) {

				case ACLMessage.CFP -> {
					// DA's opening offer — create negotiation state for this car
					int    maxRounds  = Config.getInt("negotiation.maxRounds");
					double firstOffer = submittedOfferAmounts.getOrDefault(
							carId,
							requirements != null ? requirements.getFirstOffer() : 0.0);

					NegotiationState state = new NegotiationState(
							carId, maxRounds, firstOffer, reservePrice, alpha);
					activeNegotiations.put(carId, state);

					onNegotiationOfferReceived(carId, offer);
					block();
				}

				case ACLMessage.PROPOSE -> {
					NegotiationState state = activeNegotiations.get(carId);
					if (state == null) return; // stale message — negotiation already ended

					state.recordOpponentOffer(offer);
					state.incrementRound();

					if (state.getCurrentRound() >= state.getMaxRounds()) {
						System.out.println("[BA] Round limit reached for " + carId
								+ " — walking away.");
						walkAway(carId);
						return;
					}

					onNegotiationOfferReceived(carId, offer);
					block();
				}

				case ACLMessage.ACCEPT_PROPOSAL -> {
					// DA accepted BA's last counter-offer
					// DA already sent informDealClosed to KA — BA does not send it again
					NegotiationState state = activeNegotiations.get(carId);
					if (state != null) state.setStatus(NegotiationState.Status.DEAL_REACHED);
					cleanUpNegotiation(carId);
					onNegotiationEnded(carId, true, offer.getAmount());
				}

				case ACLMessage.REJECT_PROPOSAL -> {
					// DA walked away — DA already sent informDealFailed to KA
					NegotiationState state = activeNegotiations.get(carId);
					if (state != null) state.setStatus(NegotiationState.Status.FAILED);
					cleanUpNegotiation(carId);
					onNegotiationEnded(carId, false, 0);
				}
			}
		}
	}
}
