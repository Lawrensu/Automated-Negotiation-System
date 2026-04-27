package ans.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ans.Config;
import ans.model.CarListing;
import ans.model.NegotiationState;
import ans.model.Offer;
import ans.util.MessageBuilder;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Dealer Agent (DA) — represents one car dealership.
 *
 * Responsibilities:
 *   - Register inventory with KA on startup
 *   - Accept or decline buyer interest forwarded by KA (Phase 2)
 *   - Conduct manual price negotiation directly with BA (Phase 3, v1)
 *
 * Floor price is stored privately and never transmitted anywhere.
 *
 * GUI interaction pattern (Step 5 wiring):
 *   Protected hooks notify DealerWindow when messages arrive:
 *     onBuyerInterestReceived  → show Negotiation tab with Accept / Decline buttons
 *     onNegotiationOfferReceived → update offer history table, enable counter input
 *     onNegotiationEnded       → show outcome dialog, reset tab
 *
 *   Public response methods are called by DealerWindow button handlers:
 *     acceptBuyerInterest / declineBuyerInterest (Phase 2)
 *     submitOffer / acceptDeal / walkAway (Phase 3)
 *
 *   All of these send ACL messages directly — JADE's send() is thread-safe, so
 *   Swing EDT button handlers can call them without additional synchronisation.
 *
 * Phase 2 blocking design (Step 5 addition):
 *   ReceiveBuyerInterestBehaviour blocks after calling onBuyerInterestReceived()
 *   and waits for the human (or test agent) to call acceptBuyerInterest() or
 *   declineBuyerInterest(). Those public methods send the response to KA, update
 *   interestPhase, then call restart() on the behaviour to wake it.
 *
 *   interestPhase and pending* fields live on the outer DealerAgent so that the
 *   public methods and the inner behaviour share state without awkward accessors.
 *
 * Migration notes (from src/agents/dealer/DealerAgent.java):
 *   - registerWithDF() and DF deregister in takeDown() carried forward unchanged.
 *   - Broker AID from agent args pattern carried forward.
 *   - DealBroadcaster TickerBehaviour NOT migrated — replaced by
 *     RegisterListingsBehaviour (OneShotBehaviour), the correct v1 design.
 */
public class DealerAgent extends Agent {

	private static final Gson GSON = new Gson();


	// ── State (technical_design.md — DealerAgent section) ────────────────────

	private List<CarListing>              inventory;
	private Map<String, Double>           floorPrices;        // carId → floor price, private
	private Map<String, NegotiationState> activeNegotiations; // carId → negotiation state
	private double                        alpha;              // concession shape, set via GUI
	private AID                           brokerAID;

	// Tracks which buyer AID is engaged per car — needed to address direct messages
	private final Map<String, AID>        activeBuyerAIDs = new HashMap<>();


	// ── Phase 2 state (shared between ReceiveBuyerInterestBehaviour and public API) ──

	/**
	 * Three-state machine for the buyer-interest / AID-exchange phase.
	 *
	 *   WAITING_FOR_INTEREST    → idle, ready to receive KA forwarded interest
	 *   WAITING_FOR_HUMAN_DECISION → hook fired, waiting for acceptBuyerInterest / declineBuyerInterest
	 *   WAITING_FOR_AID         → human accepted, waiting for KA AID exchange confirmation
	 */
	private enum InterestPhase {
		WAITING_FOR_INTEREST,
		WAITING_FOR_HUMAN_DECISION,
		WAITING_FOR_AID
	}

	private InterestPhase interestPhase = InterestPhase.WAITING_FOR_INTEREST;

	// Pending carId — set in handleBuyerInterest(), used by handleAIDExchange()
	private String pendingInterestCarId;

	// Stored so public methods can call restart() to wake the behaviour
	private ReceiveBuyerInterestBehaviour receiveBuyerInterestBehaviour;


	// ── Lifecycle ────────────────────────────────────────────────────────────

	@Override
	protected void setup() {
		inventory          = new ArrayList<>();
		floorPrices        = new HashMap<>();
		activeNegotiations = new HashMap<>();
		alpha              = 1.0; // linear concession by default; overridden via GUI in Step 5

		Object[] args = getArguments();
		String brokerName = (args != null && args.length > 0)
				? (String) args[0]
				: "BrokerAgent";
		brokerAID = new AID(brokerName, AID.ISLOCALNAME);

		registerWithDF();

		addBehaviour(new RegisterListingsBehaviour());

		// Store reference so acceptBuyerInterest / declineBuyerInterest can restart it
		receiveBuyerInterestBehaviour = new ReceiveBuyerInterestBehaviour();
		addBehaviour(receiveBuyerInterestBehaviour);

		addBehaviour(new NegotiationBehaviour());

		System.out.println("[DA] " + getLocalName() + " started. Broker: " + brokerName);
	}

	@Override
	protected void takeDown() {
		try {
			DFService.deregister(this);
		} catch (FIPAException fe) {
			System.err.println("[DA] DF deregister failed: " + fe.getMessage());
		}
		System.out.println("[DA] " + getLocalName() + " shutting down.");
	}


	// ── DF registration ───────────────────────────────────────────────────────

	private void registerWithDF() {
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("car-dealer");
		sd.setName(getLocalName() + "-car-dealer");
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
		} catch (FIPAException fe) {
			System.err.println("[DA] DF register failed: " + fe.getMessage());
		}
	}


	// ── Public API (called by DealerWindow GUI — Step 5) ────────────────────

	/**
	 * Add a car to inventory. Floor price is kept private inside DA only.
	 * Called by DealerWindow each time the human submits the "Add Car" form.
	 */
	public void addToInventory(CarListing listing, double floorPrice) {
		inventory.add(listing);
		floorPrices.put(listing.getCarId(), floorPrice);
	}

	public List<CarListing> getInventory() {
		return inventory;
	}

	/**
	 * Send the current inventory to KA.
	 * Called by RegisterListingsBehaviour on startup (if inventory is pre-populated)
	 * and directly by DealerWindow's "Register Listings" button.
	 */
	public void sendListingsToKA() {
		send(MessageBuilder.informListings(brokerAID, inventory));
		System.out.println("[DA] Sent " + inventory.size() + " listing(s) to KA.");
	}

	/**
	 * Accept the buyer's forwarded interest. Called by DealerWindow's "Accept" button
	 * in Phase A of the Negotiation tab.
	 *
	 * Sends "accept" informDealerResponse to KA, advances interestPhase to
	 * WAITING_FOR_AID, then restarts the behaviour so it can receive KA's AID
	 * exchange confirmation.
	 */
	public void acceptBuyerInterest(String carId, String buyerAIDName) {
		send(MessageBuilder.informDealerResponse(brokerAID, "accept", buyerAIDName, carId));
		System.out.println("[DA] Accepted interest for " + carId + " — waiting for AID exchange.");
		interestPhase = InterestPhase.WAITING_FOR_AID;
		receiveBuyerInterestBehaviour.restart();
	}

	/**
	 * Decline the buyer's forwarded interest. Called by DealerWindow's "Decline" button
	 * in Phase A of the Negotiation tab.
	 *
	 * Sends "reject" informDealerResponse to KA, resets interestPhase to
	 * WAITING_FOR_INTEREST, then restarts the behaviour so it is ready for the
	 * next buyer interest KA might forward.
	 */
	public void declineBuyerInterest(String carId, String buyerAIDName) {
		send(MessageBuilder.informDealerResponse(brokerAID, "reject", buyerAIDName, carId));
		System.out.println("[DA] Declined interest for " + carId + ".");
		interestPhase = InterestPhase.WAITING_FOR_INTEREST;
		receiveBuyerInterestBehaviour.restart();
	}

	/**
	 * Human counter-offer. Called by DealerWindow's "Send Offer" button (Phase B).
	 * Sends a PROPOSE to BA and records the offer in NegotiationState.
	 * The behaviour wakes naturally when BA's reply arrives.
	 */
	public void submitOffer(String carId, double amount) {
		NegotiationState state = activeNegotiations.get(carId);
		if (state == null) return;

		state.incrementRound();
		Offer offer = new Offer(carId, amount, state.getCurrentRound(), getLocalName(), false);
		state.recordOwnOffer(offer);

		send(MessageBuilder.proposeOffer(activeBuyerAIDs.get(carId), offer));
		System.out.println("[DA] Counter-offer RM " + amount
				+ " for " + carId + " (round " + state.getCurrentRound() + ")");
	}

	/**
	 * Human accepts BA's last offer. Called by DealerWindow's "Accept Deal" button.
	 * Sends ACCEPT_PROPOSAL to BA and notifies KA of a closed deal.
	 */
	public void acceptDeal(String carId) {
		NegotiationState state = activeNegotiations.get(carId);
		if (state == null) return;

		List<Offer> opponentHistory = state.getOpponentOfferHistory();
		if (opponentHistory.isEmpty()) return;

		Offer lastFromBA = opponentHistory.get(opponentHistory.size() - 1);
		Offer accept     = new Offer(carId, lastFromBA.getAmount(),
				state.getCurrentRound(), getLocalName(), true);

		send(MessageBuilder.acceptProposal(activeBuyerAIDs.get(carId), accept));
		send(MessageBuilder.informDealClosed(brokerAID, lastFromBA));

		state.setStatus(NegotiationState.Status.DEAL_REACHED);
		cleanUpNegotiation(carId);
		onNegotiationEnded(carId, true, lastFromBA.getAmount());
	}

	/**
	 * Human walks away. Called by DealerWindow's "Walk Away" button.
	 * Sends REJECT_PROPOSAL to BA and notifies KA of a failed deal.
	 */
	public void walkAway(String carId) {
		NegotiationState state = activeNegotiations.get(carId);
		if (state == null) return;

		Offer reject = new Offer(carId, 0, state.getCurrentRound(), getLocalName(), true);
		send(MessageBuilder.rejectProposal(activeBuyerAIDs.get(carId), reject));
		send(MessageBuilder.informDealFailed(brokerAID, carId));

		state.setStatus(NegotiationState.Status.FAILED);
		cleanUpNegotiation(carId);
		onNegotiationEnded(carId, false, 0);
	}


	// ── Protected notification hooks (overridden by DealerWindow in Step 5) ──

	/**
	 * Called when KA forwards buyer interest and the behaviour is about to block.
	 * DealerWindow overrides this to enable the Negotiation tab and show buyer details
	 * with Accept / Decline buttons. The subclass MUST call acceptBuyerInterest() or
	 * declineBuyerInterest() in response — otherwise the behaviour stays blocked.
	 */
	protected void onBuyerInterestReceived(String carId, String buyerAIDName, Offer offer) {
		System.out.println("[DA] Buyer interest — " + buyerAIDName
				+ " offers RM " + offer.getAmount() + " for " + carId);
	}

	/**
	 * Called when an offer arrives from BA during negotiation.
	 * DealerWindow overrides this to update the offer history table and prompt
	 * the human to respond.
	 */
	protected void onNegotiationOfferReceived(String carId, Offer offer) {
		System.out.println("[DA] Incoming offer: RM " + offer.getAmount()
				+ " for " + carId + " (round " + offer.getRound() + ")");
	}

	/**
	 * Called when a negotiation ends (deal or failure).
	 * DealerWindow overrides this to show outcome and reset the Negotiation tab.
	 */
	protected void onNegotiationEnded(String carId, boolean dealReached, double finalAmount) {
		if (dealReached) {
			System.out.println("[DA] Deal closed — " + carId + " at RM " + finalAmount);
		} else {
			System.out.println("[DA] No deal — " + carId);
		}
	}


	// ── Private helpers ───────────────────────────────────────────────────────

	private CarListing findListing(String carId) {
		return inventory.stream()
				.filter(l -> l.getCarId().equals(carId))
				.findFirst()
				.orElse(null);
	}

	private void cleanUpNegotiation(String carId) {
		activeNegotiations.remove(carId);
		activeBuyerAIDs.remove(carId);
	}


	// ── Behaviours ────────────────────────────────────────────────────────────

	/**
	 * Sends the current inventory to KA once on startup.
	 * If inventory is empty at launch (human hasn't added cars yet via GUI),
	 * logs a warning and exits — DealerWindow's "Register Listings" button
	 * calls sendListingsToKA() directly when the human is ready.
	 */
	private class RegisterListingsBehaviour extends OneShotBehaviour {
		@Override
		public void action() {
			if (inventory.isEmpty()) {
				System.out.println("[DA] Inventory empty at startup — use GUI to add cars"
						+ " then click Register Listings.");
				return;
			}
			sendListingsToKA();
		}
	}


	/**
	 * Handles two sequential INFORMs from KA per negotiation, with a human-decision
	 * pause between them.
	 *
	 *   WAITING_FOR_INTEREST (Phase A):
	 *     Receives KA INFORM with { buyerAIDName, offer }.
	 *     Fires onBuyerInterestReceived() hook, stores pending state, blocks.
	 *     Does NOT send any response yet — waits for human decision.
	 *
	 *   WAITING_FOR_HUMAN_DECISION:
	 *     Behaviour is blocked. acceptBuyerInterest() or declineBuyerInterest()
	 *     sends the response to KA, updates interestPhase, calls restart().
	 *
	 *   WAITING_FOR_AID (Phase B):
	 *     Receives KA INFORM with { buyerAIDName } (no "offer" key) after AID exchange.
	 *     Creates NegotiationState, sends CFP to BA at retailPrice (round 0).
	 *     Returns to WAITING_FOR_INTEREST for the next buyer.
	 *
	 * v1 is sequential — one active negotiation at a time.
	 * Extension 1 (concurrent) will require redesigning this behaviour.
	 */
	private class ReceiveBuyerInterestBehaviour extends CyclicBehaviour {

		// Only receive INFORMs from KA to avoid picking up Phase 3 messages
		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchSender(brokerAID)
		);

		@Override
		public void action() {
			// Human-decision state: hook has fired, waiting for acceptBuyerInterest /
			// declineBuyerInterest. Those methods call restart() which will re-enter
			// action() with interestPhase already updated to WAITING_FOR_AID or
			// WAITING_FOR_INTEREST — so we never receive a message in this state.
			if (interestPhase == InterestPhase.WAITING_FOR_HUMAN_DECISION) {
				block();
				return;
			}

			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			// Payload is always a JSON object — content differs by state
			JsonObject payload = JsonParser.parseString(msg.getContent()).getAsJsonObject();

			if (interestPhase == InterestPhase.WAITING_FOR_INTEREST && payload.has("offer")) {
				handleBuyerInterest(msg.getConversationId(), payload);
			} else if (interestPhase == InterestPhase.WAITING_FOR_AID && !payload.has("offer")) {
				handleAIDExchange(payload);
			}
			// Any other INFORM from KA during a mismatched phase is silently ignored
		}

		/**
		 * Receive buyer interest from KA. Store pending state, fire hook, then either
		 * block for human input (GUI path) or fall through immediately (test-agent path).
		 *
		 * The response is NOT sent here — it is sent by acceptBuyerInterest() or
		 * declineBuyerInterest(), which are called either by DealerWindow's buttons
		 * (asynchronously, after block()) or by a test-agent override of
		 * onBuyerInterestReceived() (synchronously, before this method returns).
		 *
		 * Synchronous-call guard:
		 *   If the hook (or a test agent subclass) calls acceptBuyerInterest() /
		 *   declineBuyerInterest() synchronously inside onBuyerInterestReceived(),
		 *   interestPhase is already changed to WAITING_FOR_AID or back to
		 *   WAITING_FOR_INTEREST by the time control returns here. In that case we
		 *   must NOT overwrite it with WAITING_FOR_HUMAN_DECISION — we just return
		 *   without blocking. The behaviour's action() will be called again naturally
		 *   (no block() was called, so the behaviour stays in the ready queue).
		 */
		private void handleBuyerInterest(String carId, JsonObject payload) {
			String buyerAIDName = payload.get("buyerAIDName").getAsString();
			Offer  offer        = GSON.fromJson(payload.get("offer"), Offer.class);

			// Store carId on outer DealerAgent so handleAIDExchange() can read it
			pendingInterestCarId = carId;

			// Notify GUI (or test agent override)
			onBuyerInterestReceived(carId, buyerAIDName, offer);

			// If onBuyerInterestReceived() already called accept/declineBuyerInterest()
			// synchronously, interestPhase is no longer WAITING_FOR_INTEREST — don't block.
			if (interestPhase == InterestPhase.WAITING_FOR_INTEREST) {
				// GUI path: human hasn't decided yet — block until button click
				interestPhase = InterestPhase.WAITING_FOR_HUMAN_DECISION;
				block();
			}
			// Test-agent / synchronous path: fall through; action() continues next cycle
		}

		/**
		 * KA has confirmed AID exchange. Create NegotiationState, send CFP to BA at
		 * retailPrice (round 0). Reset to WAITING_FOR_INTEREST for the next buyer.
		 */
		private void handleAIDExchange(JsonObject payload) {
			String buyerAIDName = payload.get("buyerAIDName").getAsString();
			AID    buyerAID     = new AID(buyerAIDName, AID.ISLOCALNAME);
			activeBuyerAIDs.put(pendingInterestCarId, buyerAID);

			CarListing listing = findListing(pendingInterestCarId);
			if (listing == null) {
				System.err.println("[DA] Error: listing not found for carId "
						+ pendingInterestCarId);
				interestPhase = InterestPhase.WAITING_FOR_INTEREST;
				return;
			}

			int    maxRounds  = Config.getInt("negotiation.maxRounds");
			double floorPrice = floorPrices.get(pendingInterestCarId);

			NegotiationState state = new NegotiationState(
					pendingInterestCarId, maxRounds,
					listing.getRetailPrice(), floorPrice, alpha);
			activeNegotiations.put(pendingInterestCarId, state);

			// DA opens negotiation at retailPrice, round 0 (technical_design.md Phase 3)
			Offer cfp = new Offer(pendingInterestCarId, listing.getRetailPrice(), 0,
					getLocalName(), false);
			send(MessageBuilder.cfpOffer(buyerAID, cfp));
			System.out.println("[DA] CFP sent to " + buyerAIDName
					+ " — RM " + listing.getRetailPrice() + " for " + pendingInterestCarId);

			interestPhase = InterestPhase.WAITING_FOR_INTEREST; // ready for next buyer interest
		}
	}


	/**
	 * Handles Phase 3: the direct offer loop between DA and BA.
	 *
	 * Receives from BA:
	 *   PROPOSE         → new counter-offer from BA; notify GUI and block until human responds
	 *   ACCEPT_PROPOSAL → BA accepted DA's last offer; close deal, notify KA
	 *   REJECT_PROPOSAL → BA walked away; notify KA
	 *
	 * DA's responses are sent by the human via DealerWindow (submitOffer, acceptDeal,
	 * walkAway). The behaviour wakes naturally when BA's next message arrives —
	 * no explicit restart is needed.
	 *
	 * Round limit: if currentRound >= maxRounds when a PROPOSE arrives, DA sends
	 * REJECT_PROPOSAL and notifies KA (technical_design.md edge case).
	 */
	private class NegotiationBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.or(
				MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
				MessageTemplate.or(
						MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
						MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
				)
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			Offer            offer = GSON.fromJson(msg.getContent(), Offer.class);
			String           carId = msg.getConversationId();
			NegotiationState state = activeNegotiations.get(carId);

			// Stale message for a negotiation that has already ended — discard
			if (state == null) return;

			switch (msg.getPerformative()) {

				case ACLMessage.PROPOSE -> {
					state.recordOpponentOffer(offer);
					state.incrementRound();

					// Edge case: round limit reached — terminate negotiation
					if (state.getCurrentRound() >= state.getMaxRounds()) {
						Offer reject = new Offer(carId, 0, state.getCurrentRound(),
								getLocalName(), true);
						send(MessageBuilder.rejectProposal(activeBuyerAIDs.get(carId), reject));
						send(MessageBuilder.informDealFailed(brokerAID, carId));
						state.setStatus(NegotiationState.Status.FAILED);
						cleanUpNegotiation(carId);
						onNegotiationEnded(carId, false, 0);
						System.out.println("[DA] Round limit reached for " + carId
								+ " — deal failed.");
						return;
					}

					// Display the incoming offer via GUI hook; human responds using
					// submitOffer(), acceptDeal(), or walkAway() from DealerWindow.
					// NegotiationBehaviour wakes naturally when BA's next message arrives.
					onNegotiationOfferReceived(carId, offer);
					block();
				}

				case ACLMessage.ACCEPT_PROPOSAL -> {
					// BA accepted DA's last counter-offer
					List<Offer> ownHistory = state.getOwnOfferHistory();
					double finalAmount = ownHistory.isEmpty()
							? offer.getAmount()
							: ownHistory.get(ownHistory.size() - 1).getAmount();

					Offer closed = new Offer(carId, finalAmount,
							state.getCurrentRound(), getLocalName(), true);
					send(MessageBuilder.informDealClosed(brokerAID, closed));
					state.setStatus(NegotiationState.Status.DEAL_REACHED);
					cleanUpNegotiation(carId);
					onNegotiationEnded(carId, true, finalAmount);
					System.out.println("[DA] BA accepted — deal closed at RM "
							+ finalAmount + " for " + carId);
				}

				case ACLMessage.REJECT_PROPOSAL -> {
					// BA walked away from the negotiation
					send(MessageBuilder.informDealFailed(brokerAID, carId));
					state.setStatus(NegotiationState.Status.FAILED);
					cleanUpNegotiation(carId);
					onNegotiationEnded(carId, false, 0);
					System.out.println("[DA] BA walked away from " + carId);
				}
			}
		}
	}
}
