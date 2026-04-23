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
 *   - Accept or reject BA's first offer (floor price check)
 *   - Conduct manual price negotiation directly with BA (v1)
 *
 * Floor price is stored privately and never transmitted anywhere.
 *
 * GUI interaction pattern (Step 5 wiring):
 *   - Protected notify hooks (onBuyerInterestReceived, onNegotiationOfferReceived,
 *     onNegotiationEnded) are overridden by DealerWindow to update UI.
 *   - Public response methods (submitOffer, acceptDeal, walkAway) are called
 *     by DealerWindow button handlers. They send ACL messages directly —
 *     no behaviour restart needed because behaviours wake naturally on BA's reply.
 *
 * Migration notes (from src/agents/dealer/DealerAgent.java):
 *   - registerWithDF() and DF deregister in takeDown() carried forward unchanged.
 *   - Broker AID from agent args pattern carried forward.
 *   - DealBroadcaster TickerBehaviour NOT migrated — replaced by
 *     RegisterListingsBehaviour (OneShotBehaviour), the correct v1 design.
 */
public class DealerAgent extends Agent {

	private static final Gson GSON = new Gson();


	// State (technical_design.md — DealerAgent section) 

	private List<CarListing>              inventory;
	private Map<String, Double>           floorPrices;        // carId → floor price, private
	private Map<String, NegotiationState> activeNegotiations; // carId → negotiation state
	private double                        alpha;              // concession shape, set via GUI
	private AID                           brokerAID;

	// Tracks which buyer AID is engaged per car — needed to address direct messages
	private final Map<String, AID>        activeBuyerAIDs = new HashMap<>();


	// Lifecycle 
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
		addBehaviour(new ReceiveBuyerInterestBehaviour());
		addBehaviour(new NegotiationBehaviour());

		System.out.println("[DA] " + getLocalName() + " started. Broker: " + brokerName);
	}

	@Override
	protected void takeDown() {
		try {
			DFService.deregister(this);
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
		System.out.println("[DA] " + getLocalName() + " shutting down.");
	}


	// DF registration (

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
			fe.printStackTrace();
		}
	}


	// Public accessors (called by DealerWindow GUI — Step 5) 

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
	 * Human counter-offer. Called by DealerWindow's "Send Offer" button.
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
	 * Human accepts BA's last offer. Called by DealerWindow's "Accept" button.
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


	// GUI notification hooks 

	/**
	 * Called when KA forwards buyer interest.
	 * DealerWindow overrides this to show the Negotiation tab with buyer details.
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


	// Behaviours 

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
	 * Handles two sequential INFORMs from KA per negotiation:
	 *
	 *   Phase A — WAITING_FOR_INTEREST:
	 *     Receives KA INFORM with { buyerAIDName, offer }.
	 *     If offer < floorPrice → reject immediately (edge case: first offer below floor).
	 *     If offer >= floorPrice → accept, transition to Phase B.
	 *
	 *   Phase B — WAITING_FOR_AID:
	 *     Receives KA INFORM with { buyerAIDName } after AID exchange.
	 *     Creates NegotiationState, sends CFP to BA at retailPrice (round 0).
	 *     Returns to Phase A for the next buyer.
	 *
	 * v1 is sequential — one active negotiation at a time.
	 * Extension 1 (concurrent) will require redesigning this behaviour.
	 */
	private class ReceiveBuyerInterestBehaviour extends CyclicBehaviour {

		private enum Phase { WAITING_FOR_INTEREST, WAITING_FOR_AID }

		private Phase  phase               = Phase.WAITING_FOR_INTEREST;
		private String pendingCarId;
		private String pendingBuyerAIDName;

		// Only receive INFORMs from KA to avoid picking up Phase 3 messages
		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchSender(brokerAID)
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			// Payload is always a JSON object — content differs by phase
			JsonObject payload = JsonParser.parseString(msg.getContent()).getAsJsonObject();

			if (phase == Phase.WAITING_FOR_INTEREST && payload.has("offer")) {
				handleBuyerInterest(msg.getConversationId(), payload);
			} else if (phase == Phase.WAITING_FOR_AID && !payload.has("offer")) {
				handleAIDExchange(payload);
			}
			// Any other INFORM from KA (e.g. during a different phase) is silently ignored
		}

		private void handleBuyerInterest(String carId, JsonObject payload) {
			String buyerAIDName = payload.get("buyerAIDName").getAsString();
			Offer  offer        = GSON.fromJson(payload.get("offer"), Offer.class);

			onBuyerInterestReceived(carId, buyerAIDName, offer);

			double floor = floorPrices.getOrDefault(carId, Double.MAX_VALUE);

			if (offer.getAmount() < floor) {
				// Edge case: first offer is already below floor price — reject immediately
				send(MessageBuilder.informDealerResponse(
						brokerAID, "reject", buyerAIDName, carId));
				System.out.println("[DA] Rejected interest for " + carId
						+ " (RM " + offer.getAmount() + " < floor RM " + floor + ")");
			} else {
				send(MessageBuilder.informDealerResponse(
						brokerAID, "accept", buyerAIDName, carId));
				System.out.println("[DA] Accepted interest for " + carId
						+ " — waiting for AID exchange.");
				pendingCarId        = carId;
				pendingBuyerAIDName = buyerAIDName;
				phase               = Phase.WAITING_FOR_AID;
			}
		}

		private void handleAIDExchange(JsonObject payload) {
			// KA has confirmed AID exchange — payload contains buyerAIDName
			String buyerAIDName = payload.get("buyerAIDName").getAsString();
			AID    buyerAID     = new AID(buyerAIDName, AID.ISLOCALNAME);
			activeBuyerAIDs.put(pendingCarId, buyerAID);

			CarListing listing = findListing(pendingCarId);
			if (listing == null) {
				System.err.println("[DA] Error: listing not found for carId " + pendingCarId);
				phase = Phase.WAITING_FOR_INTEREST;
				return;
			}

			int    maxRounds  = Config.getInt("negotiation.maxRounds");
			double floorPrice = floorPrices.get(pendingCarId);

			NegotiationState state = new NegotiationState(
					pendingCarId, maxRounds,
					listing.getRetailPrice(), floorPrice, alpha);
			activeNegotiations.put(pendingCarId, state);

			// DA opens negotiation at retailPrice, round 0 (technical_design.md Phase 3)
			Offer cfp = new Offer(pendingCarId, listing.getRetailPrice(), 0,
					getLocalName(), false);
			send(MessageBuilder.cfpOffer(buyerAID, cfp));
			System.out.println("[DA] CFP sent to " + buyerAIDName
					+ " — RM " + listing.getRetailPrice() + " for " + pendingCarId);

			phase = Phase.WAITING_FOR_INTEREST; // ready for next buyer interest
		}
	}


	/**
	 * Handles Phase 3: the direct offer loop between DA and BA.
	 *
	 * Receives from BA:
	 *   PROPOSE       → new counter-offer from BA; notify GUI and block until human responds
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

			Offer            offer  = GSON.fromJson(msg.getContent(), Offer.class);
			String           carId  = msg.getConversationId();
			NegotiationState state  = activeNegotiations.get(carId);

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
					// submitOffer(), acceptDeal(), or walkAway() from DealerWindow
					onNegotiationOfferReceived(carId, offer);
					// Block until BA's next message arrives (natural JADE pattern —
					// no manual restart needed since BA replies after human action)
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
