package ans.agent;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import ans.Config;
import ans.model.BuyerRequirements;
import ans.model.CarListing;
import ans.model.Offer;
import ans.util.MessageBuilder;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Broker Agent (KA) — the central facilitator.
 *
 * Responsibilities:
 *   - Receive and index DA inventory on startup
 *   - Match BA requirements (hard cutoffs + budget filter + soft ranking)
 *   - Contact dealers sequentially per BA's ranked shortlist
 *   - Exchange AIDs between matched DA–BA pairs, record fixed fee
 *   - Record commission on closed deals; advance shortlist on failures
 *
 * Message disambiguation:
 *   Phase 2 and Phase 3 both send INFORMs with carId as conversationId.
 *   informDealerResponse carries ontology "dealer-response".
 *   informDealClosed / informDealFailed carry ontology "deal-outcome".
 *   This prevents ReceiveDealerResponseBehaviour and ReceiveDealOutcomeBehaviour
 *   from consuming each other's messages (JADE receive() removes from queue
 *   permanently — see MessageBuilder for where ontology is set).
 */
public class BrokerAgent extends Agent {

	private static final Gson GSON = new Gson();


	// BrokerAgent section) 

	private Map<String, AID>              dealerAIDs;        
	private Map<String, List<CarListing>> dealerListings;    
	private Map<String, AID>              buyerAIDs;         
	private Map<String, List<String>>     buyerShortlists;   
	private Map<String, Integer>          shortlistProgress; 
	private double                        fixedFee;
	private double                        commissionRate;
	private double                        budgetTolerance;
	private List<String>                  eventLog;

	// Supplementary state not in the spec but required for sequential shortlist logic
	private Map<String, Map<String, Offer>> buyerOffers;           
	private Map<String, String>             activeNegotiationBuyer;
	private double                          totalFeesCollected;
	private double                          totalCommissionCollected;


	// Lifecycle 

	@Override
	protected void setup() {
		fixedFee        = Config.getDouble("broker.fixedFee");
		commissionRate  = Config.getDouble("broker.commissionRate");
		budgetTolerance = Config.getDouble("negotiation.budgetTolerance");

		dealerAIDs              = new HashMap<>();
		dealerListings          = new HashMap<>();
		buyerAIDs               = new HashMap<>();
		buyerShortlists         = new HashMap<>();
		shortlistProgress       = new HashMap<>();
		buyerOffers             = new HashMap<>();
		activeNegotiationBuyer  = new HashMap<>();
		eventLog                = new ArrayList<>();

		addBehaviour(new ReceiveListingsBehaviour());
		addBehaviour(new HandleBuyerSearchBehaviour());
		addBehaviour(new ReceiveShortlistBehaviour());
		addBehaviour(new ReceiveDealerResponseBehaviour());
		addBehaviour(new ReceiveDealOutcomeBehaviour());

		System.out.println("[KA] " + getLocalName() + " started.");
	}

	@Override
	protected void takeDown() {
		System.out.println("[KA] " + getLocalName() + " shutting down.");
	}


	// Public accessors (used by BrokerLogWindow in Step 5) 
	public List<String> getEventLog() {
		return eventLog;
	}

	public double getTotalFeesCollected() {
		return totalFeesCollected;
	}

	public double getTotalCommissionCollected() {
		return totalCommissionCollected;
	}


	// Behaviours 

	/**
	 * Handles DA INFORM carrying List<CarListing>.
	 * Indexes the dealer's AID and listings for use in matching.
	 */
	private class ReceiveListingsBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchConversationId("car-listings")
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			AID    sender        = msg.getSender();
			String dealerAIDName = sender.getLocalName();

			Type listType = new TypeToken<List<CarListing>>(){}.getType();
			List<CarListing> listings = GSON.fromJson(msg.getContent(), listType);

			dealerAIDs.put(dealerAIDName, sender);
			dealerListings.put(dealerAIDName, listings);

			String entry = "[KA] Received " + listings.size()
					+ " listing(s) from " + dealerAIDName;
			eventLog.add(entry);
			System.out.println(entry);
			onListingsReceived(dealerAIDName, listings);
		}
	}


	/**
	 * Handles BA REQUEST carrying BuyerRequirements.
	 *
	 * Matching pipeline (technical_design.md — Matching Logic):
	 *   Step 1 — Hard cutoffs delegated to BuyerRequirements.matches().
	 *   Step 2 — Budget filter: retailPrice ≤ maxBudget × budgetTolerance (1.25).
	 *   Step 3 — Soft ranking: make match (+2), colour match (+1), descending sort.
	 *
	 * Stores the buyer's AID for later shortlist processing.
	 */
	private class HandleBuyerSearchBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
				MessageTemplate.MatchConversationId("buyer-search")
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			AID sender = msg.getSender();
			buyerAIDs.put(sender.getLocalName(), sender);

			BuyerRequirements req = GSON.fromJson(msg.getContent(), BuyerRequirements.class);
			List<CarListing> matches = findAndRankMatches(req);

			send(MessageBuilder.informMatches(sender, matches));

			String entry = "[KA] Sent " + matches.size()
					+ " match(es) to " + sender.getLocalName();
			eventLog.add(entry);
			System.out.println(entry);
		}
	}


	/**
	 * Handles BA INFORM carrying the ranked shortlist (Map<dealerAIDName, Offer>).
	 *
	 * Gson uses LinkedHashMap by default, preserving the insertion order that
	 * represents BA's ranking — this order drives the sequential contact attempt.
	 *
	 * Stores the shortlist, sets shortlistProgress to 0, then contacts DA1.
	 */
	private class ReceiveShortlistBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchConversationId("buyer-shortlist")
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			String buyerAIDName = msg.getSender().getLocalName();

			// LinkedHashMap preserves insertion order = BA's priority ranking
			Type mapType = new TypeToken<Map<String, Offer>>(){}.getType();
			Map<String, Offer> shortlist = GSON.fromJson(msg.getContent(), mapType);

			List<String> orderedDealers = new ArrayList<>(shortlist.keySet());

			buyerShortlists.put(buyerAIDName, orderedDealers);
			buyerOffers.put(buyerAIDName, shortlist);
			shortlistProgress.put(buyerAIDName, 0);

			String entry = "[KA] Shortlist from " + buyerAIDName
					+ " — " + orderedDealers.size() + " dealer(s): " + orderedDealers;
			eventLog.add(entry);
			System.out.println(entry);

			contactNextDealer(buyerAIDName);
		}
	}


	/**
	 * Handles DA INFORM with "accept" or "reject" for a buyer's interest.
	 *
	 * On accept:
	 *   - Exchange AIDs (KA→BA sends dealerAIDName; KA→DA sends buyerAIDName)
	 *   - Record fixed fee to eventLog and totalFeesCollected
	 *   - Store activeNegotiationBuyer[carId] for later deal-failed lookup
	 *
	 * On reject:
	 *   - Advance shortlistProgress and contact the next dealer in the shortlist
	 *
	 * MessageTemplate uses MatchOntology("dealer-response") to distinguish from
	 * deal-outcome INFORMs that share the same performative and carId conversationId.
	 */
	private class ReceiveDealerResponseBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchOntology("dealer-response")
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			String dealerAIDName = msg.getSender().getLocalName();
			String carId         = msg.getConversationId();

			JsonObject payload  = JsonParser.parseString(msg.getContent()).getAsJsonObject();
			String response     = payload.get("response").getAsString();
			String buyerAIDName = payload.get("buyerAIDName").getAsString();

			if ("accept".equals(response)) {
				AID buyerAID  = buyerAIDs.get(buyerAIDName);
				AID dealerAID = dealerAIDs.get(dealerAIDName);

				// AID exchange — DA and BA can now speak directly
				send(MessageBuilder.informDealerAID(buyerAID, dealerAIDName, carId));
				send(MessageBuilder.informBuyerAID(dealerAID, buyerAIDName, carId));

				// Fixed fee is charged at the moment of AID exchange (technical_design.md)
				activeNegotiationBuyer.put(carId, buyerAIDName);
				totalFeesCollected += fixedFee;

				String entry = "FIXED FEE charged to " + dealerAIDName
						+ " — " + carId
						+ " — RM" + String.format("%.2f", fixedFee);
				eventLog.add(entry);
				System.out.println("[KA] " + entry);
				onFixedFeeCharged(dealerAIDName, carId);

			} else {
				// Dealer declined — try the next dealer without waiting
				String entry = "[KA] " + dealerAIDName + " declined interest for "
						+ carId + " from " + buyerAIDName;
				eventLog.add(entry);
				System.out.println(entry);

				int nextIndex = shortlistProgress.getOrDefault(buyerAIDName, 0) + 1;
				shortlistProgress.put(buyerAIDName, nextIndex);
				contactNextDealer(buyerAIDName);
			}
		}
	}


	/**
	 * Handles DA INFORM with "deal-closed" or "deal-failed" outcome.
	 *
	 * On deal-closed:
	 *   - Calculate commission = finalAmount × commissionRate
	 *   - Record to eventLog and totalCommissionCollected
	 *
	 * On deal-failed:
	 *   - Look up buyerAIDName from activeNegotiationBuyer[carId]
	 *   - Advance shortlistProgress and contact the next dealer
	 *   - If shortlist exhausted, send informNoDealerEngaged to BA
	 *
	 * MessageTemplate uses MatchOntology("deal-outcome") to distinguish from
	 * dealer-response INFORMs that share the same performative and carId conversationId.
	 */
	private class ReceiveDealOutcomeBehaviour extends CyclicBehaviour {

		private final MessageTemplate MT = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchOntology("deal-outcome")
		);

		@Override
		public void action() {
			ACLMessage msg = receive(MT);
			if (msg == null) { block(); return; }

			String     dealerAIDName = msg.getSender().getLocalName();
			JsonObject payload       = JsonParser.parseString(msg.getContent()).getAsJsonObject();
			String     type          = payload.get("type").getAsString();

			if ("deal-closed".equals(type)) {
				Offer  finalOffer  = GSON.fromJson(payload.get("offer"), Offer.class);
				double commission  = finalOffer.getAmount() * commissionRate;
				totalCommissionCollected += commission;

				activeNegotiationBuyer.remove(finalOffer.getCarId());

				String entry = "COMMISSION charged to " + dealerAIDName
						+ " — RM" + String.format("%.2f", commission)
						+ " (deal on " + finalOffer.getCarId()
						+ " at RM" + String.format("%.2f", finalOffer.getAmount()) + ")";
				eventLog.add(entry);
				System.out.println("[KA] " + entry);
				onDealClosed(finalOffer, commission);

			} else {
				// "deal-failed" — advance to the next dealer in the buyer's shortlist
				String carId        = payload.get("carId").getAsString();
				String buyerAIDName = activeNegotiationBuyer.remove(carId);

				String entry = "DEAL FAILED — " + carId + " — moving to next dealer";
				eventLog.add(entry);
				System.out.println("[KA] " + entry);

				if (buyerAIDName != null) {
					int nextIndex = shortlistProgress.getOrDefault(buyerAIDName, 0) + 1;
					shortlistProgress.put(buyerAIDName, nextIndex);
					contactNextDealer(buyerAIDName);
				} else {
					// buyerAIDName unknown — no shortlist to advance (edge case: stale message)
					System.out.println("[KA] Warning: no active buyer for failed carId " + carId);
				}
				onDealFailed(carId);
			}
		}
	}


	// Helpers 

	/**
	 * Contacts the dealer at the current shortlistProgress index for the given buyer.
	 *
	 * If the shortlist is exhausted (index ≥ list size), sends informNoDealerEngaged
	 * to BA so the user can be informed and revise their requirements if they wish.
	 *
	 * If a dealer in the list is not registered in dealerAIDs (e.g. offline), it is
	 * skipped and the next dealer is tried immediately via a recursive call.
	 */
	private void contactNextDealer(String buyerAIDName) {
		List<String> shortlist = buyerShortlists.get(buyerAIDName);
		int          index     = shortlistProgress.getOrDefault(buyerAIDName, 0);

		if (shortlist == null || index >= shortlist.size()) {
			AID buyerAID = buyerAIDs.get(buyerAIDName);
			if (buyerAID != null) {
				send(MessageBuilder.informNoDealerEngaged(buyerAID));
			}
			String entry = "[KA] All dealers exhausted for "
					+ buyerAIDName + " — no deal possible.";
			eventLog.add(entry);
			System.out.println(entry);
			return;
		}

		String dealerAIDName = shortlist.get(index);
		AID    dealerAID     = dealerAIDs.get(dealerAIDName);
		Map<String, Offer> offerMap = buyerOffers.get(buyerAIDName);
		Offer  offer         = (offerMap != null) ? offerMap.get(dealerAIDName) : null;

		if (dealerAID == null || offer == null) {
			// Dealer not registered or no offer in shortlist — skip silently
			System.out.println("[KA] Warning: skipping dealer " + dealerAIDName
					+ " (not registered or no offer)");
			shortlistProgress.put(buyerAIDName, index + 1);
			contactNextDealer(buyerAIDName);
			return;
		}

		send(MessageBuilder.informBuyerInterest(dealerAID, buyerAIDName, offer));

		String entry = "[KA] Forwarded interest from " + buyerAIDName
				+ " to " + dealerAIDName + " for car " + offer.getCarId()
				+ " (offer RM" + String.format("%.2f", offer.getAmount()) + ")";
		eventLog.add(entry);
		System.out.println(entry);
	}


	/**
	 * Three-step matching pipeline for a buyer's requirements.
	 *
	 * Step 1 — Hard cutoffs: delegated to BuyerRequirements.matches(CarListing).
	 * Step 2 — Budget filter: retailPrice ≤ maxBudget × budgetTolerance.
	 *           The 125% tolerance ensures overpriced listings are excluded while
	 *           allowing a negotiation window above the buyer's stated budget.
	 * Step 3 — Soft ranking: make match +2 points, colour match +1 point.
	 *           Listings are returned in descending score order (best match first).
	 *           Null soft-preference fields are treated as "no preference" and skipped.
	 */
	private List<CarListing> findAndRankMatches(BuyerRequirements req) {
		List<ScoredListing> scored = new ArrayList<>();

		for (Map.Entry<String, List<CarListing>> entry : dealerListings.entrySet()) {
			String           dealerName = entry.getKey();
			List<CarListing> listings   = entry.getValue();

			for (CarListing listing : listings) {

				// Step 1 — hard cutoffs
				if (!req.matches(listing)) continue;

				// Step 2 — budget filter (125% tolerance, value from config)
				if (listing.getRetailPrice() > req.getMaxBudget() * budgetTolerance) continue;

				// Step 3 — soft ranking score
				int score = 0;
				if (req.getMake() != null
						&& req.getMake().equalsIgnoreCase(listing.getMake())) {
					score += 2;
				}
				if (req.getPreferredColour() != null
						&& req.getPreferredColour().equalsIgnoreCase(listing.getColour())) {
					score += 1;
				}

				// Stamp the dealer name so BA can build its shortlist Map without
				// needing to infer the dealer from carId string conventions.
				listing.setDealerAIDName(dealerName);

				scored.add(new ScoredListing(listing, score));
			}
		}

		// Descending sort — highest score (best match) first
		scored.sort((a, b) -> Integer.compare(b.score, a.score));

		List<CarListing> result = new ArrayList<>(scored.size());
		for (ScoredListing s : scored) {
			result.add(s.listing);
		}
		return result;
	}


	/** Carries a listing and its soft-preference ranking score during sorting. */
	private static class ScoredListing {
		final CarListing listing;
		final int        score;

		ScoredListing(CarListing listing, int score) {
			this.listing = listing;
			this.score   = score;
		}
	}


	// Protected notification hooks (overridden by BrokerLogWindow in Step 5) 

	/** Called whenever a DA's listings are indexed. */
	protected void onListingsReceived(String dealerAIDName, List<CarListing> listings) {}

	/** Called immediately after the fixed fee is recorded for an AID exchange. */
	protected void onFixedFeeCharged(String dealerAIDName, String carId) {}

	/** Called when a deal closes and commission has been recorded. */
	protected void onDealClosed(Offer finalOffer, double commission) {}

	/** Called when a deal fails (before advancing to the next dealer). */
	protected void onDealFailed(String carId) {}
}
