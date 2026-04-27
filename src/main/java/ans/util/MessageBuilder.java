package ans.util;

import ans.model.BuyerRequirements;
import ans.model.CarListing;
import ans.model.Offer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

import java.util.List;
import java.util.Map;

/**
 * Static factory for every ACL message type in the protocol.
 * Serialises content to JSON via Gson. Sets performative, receiver,
 * and conversationId on every message so behaviour MessageTemplates
 * can filter precisely.
 *
 * Convention: Phase 1 messages use fixed conversationIds.
 *             Phase 2/3 messages use carId as conversationId to prevent
 *             cross-talk between concurrent negotiations (technical_design.md).
 */
public class MessageBuilder {

	private static final Gson GSON = new Gson();

	private MessageBuilder() {}


	// ── Phase 1 — Discovery ──────────────────────────────────────────────────

	/** DA → KA: register full inventory on startup. */
	public static ACLMessage informListings(AID receiver, List<CarListing> listings) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId("car-listings");
		msg.setContent(GSON.toJson(listings));
		return msg;
	}

	/** BA → KA: send search requirements. */
	public static ACLMessage requestSearch(AID receiver, BuyerRequirements req) {
		ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
		msg.addReceiver(receiver);
		msg.setConversationId("buyer-search");
		msg.setContent(GSON.toJson(req));
		return msg;
	}

	/** KA → BA: return filtered and ranked matching listings. */
	public static ACLMessage informMatches(AID receiver, List<CarListing> matches) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId("buyer-search");
		msg.setContent(GSON.toJson(matches));
		return msg;
	}


	// ── Phase 2 — Matching ───────────────────────────────────────────────────

	/**
	 * BA → KA: send ranked shortlist.
	 * Content: Map<dealerAIDName, Offer> — one first offer per selected car.
	 * conversationId is "buyer-shortlist"; carId is embedded in each Offer.
	 */
	public static ACLMessage informShortlist(AID receiver, Map<String, Offer> shortlist) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId("buyer-shortlist");
		msg.setContent(GSON.toJson(shortlist));
		return msg;
	}

	/**
	 * KA → DA: forward BA's first offer for a specific car.
	 * conversationId = carId so DA can correlate with its inventory.
	 */
	public static ACLMessage informBuyerInterest(AID receiver, String buyerAIDName, Offer offer) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId(offer.getCarId());
		JsonObject payload = new JsonObject();
		payload.addProperty("buyerAIDName", buyerAIDName);
		payload.add("offer", GSON.toJsonTree(offer));
		msg.setContent(payload.toString());
		return msg;
	}

	/**
	 * DA → KA: accept or reject BA's interest.
	 * response is "accept" or "reject".
	 *
	 * Ontology "dealer-response" disambiguates this from deal-outcome INFORMs
	 * that also use carId as conversationId (technical_design.md matching logic).
	 */
	public static ACLMessage informDealerResponse(AID receiver, String response,
	                                               String buyerAIDName, String carId) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId(carId);
		msg.setOntology("dealer-response");
		JsonObject payload = new JsonObject();
		payload.addProperty("response", response);
		payload.addProperty("buyerAIDName", buyerAIDName);
		msg.setContent(payload.toString());
		return msg;
	}

	/**
	 * KA → BA: provide the matched dealer's AID name so BA can send directly.
	 *
	 * Ontology "aid-exchange" disambiguates this from informMatches (convId
	 * "buyer-search") and informNoDealerEngaged (ontology "no-dealer-engaged"),
	 * both of which BA also receives as INFORM from the broker.
	 */
	public static ACLMessage informDealerAID(AID receiver, String dealerAIDName, String carId) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId(carId);
		msg.setOntology("aid-exchange");
		JsonObject payload = new JsonObject();
		payload.addProperty("dealerAIDName", dealerAIDName);
		msg.setContent(payload.toString());
		return msg;
	}

	/** KA → DA: provide the buyer's AID name so DA can open the CFP directly. */
	public static ACLMessage informBuyerAID(AID receiver, String buyerAIDName, String carId) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId(carId);
		JsonObject payload = new JsonObject();
		payload.addProperty("buyerAIDName", buyerAIDName);
		msg.setContent(payload.toString());
		return msg;
	}


	// ── Phase 3 — Negotiation ────────────────────────────────────────────────

	/** DA → BA: open negotiation with asking price (round 0). */
	public static ACLMessage cfpOffer(AID receiver, Offer offer) {
		ACLMessage msg = new ACLMessage(ACLMessage.CFP);
		msg.addReceiver(receiver);
		msg.setConversationId(offer.getCarId());
		msg.setContent(GSON.toJson(offer));
		return msg;
	}

	/** Either side → other: counter-offer during negotiation rounds. */
	public static ACLMessage proposeOffer(AID receiver, Offer offer) {
		ACLMessage msg = new ACLMessage(ACLMessage.PROPOSE);
		msg.addReceiver(receiver);
		msg.setConversationId(offer.getCarId());
		msg.setContent(GSON.toJson(offer));
		return msg;
	}

	/** Either side → other: accept the opponent's last offer (isFinal = true). */
	public static ACLMessage acceptProposal(AID receiver, Offer offer) {
		ACLMessage msg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
		msg.addReceiver(receiver);
		msg.setConversationId(offer.getCarId());
		msg.setContent(GSON.toJson(offer));
		return msg;
	}

	/** Either side → other: walk away (isFinal = true). */
	public static ACLMessage rejectProposal(AID receiver, Offer offer) {
		ACLMessage msg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
		msg.addReceiver(receiver);
		msg.setConversationId(offer.getCarId());
		msg.setContent(GSON.toJson(offer));
		return msg;
	}

	/**
	 * DA → KA: notify broker that a deal was successfully closed.
	 *
	 * Ontology "deal-outcome" disambiguates this from dealer-response INFORMs
	 * that also use carId as conversationId.
	 */
	public static ACLMessage informDealClosed(AID receiver, Offer finalOffer) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId(finalOffer.getCarId());
		msg.setOntology("deal-outcome");
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "deal-closed");
		payload.add("offer", GSON.toJsonTree(finalOffer));
		msg.setContent(payload.toString());
		return msg;
	}

	/**
	 * DA or BA → KA: notify broker that the negotiation failed.
	 *
	 * Ontology "deal-outcome" disambiguates this from dealer-response INFORMs
	 * that also use carId as conversationId.
	 */
	public static ACLMessage informDealFailed(AID receiver, String carId) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId(carId);
		msg.setOntology("deal-outcome");
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "deal-failed");
		payload.addProperty("carId", carId);
		msg.setContent(payload.toString());
		return msg;
	}

	/**
	 * KA → BA: all dealers in the shortlist declined or the deal failed at every stage.
	 * BA should inform the user and offer a chance to revise requirements.
	 */
	public static ACLMessage informNoDealerEngaged(AID receiver) {
		ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
		msg.addReceiver(receiver);
		msg.setConversationId("buyer-shortlist");
		msg.setOntology("no-dealer-engaged");
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "no-dealer-engaged");
		msg.setContent(payload.toString());
		return msg;
	}
}
