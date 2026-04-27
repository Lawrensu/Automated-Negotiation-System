package ans;

import ans.agent.BuyerAgent;
import ans.model.BuyerRequirements;
import ans.model.CarListing;
import ans.model.Offer;
import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only BuyerAgent subclass used by NegotiationFlowTest.
 * Not part of the production build — lives in src/test/java.
 *
 * Auto-search: calls sendSearchRequest() at the end of setup() so no GUI is needed.
 *
 * Auto-shortlist (onMatchesReceived): takes the first result, submits it as a
 * one-entry shortlist with firstOffer = RM 49 000.
 *
 * Auto-negotiation (onNegotiationOfferReceived):
 *   If DA's offer <= RESERVE_PRICE (RM 53 000) → acceptDeal.
 *   Otherwise → counter at (DA offer − RM 4 000), floor of RM 49 000.
 *
 * With the TestDealerAgent scenario this produces:
 *   CFP  DA=55 000
 *   BA counters at 51 000   (55 000 − 4 000)
 *   DA accepts 51 000       (>= DA threshold RM 50 000) ✓
 *   KA records fixed fee RM 500 + commission RM 1 020 (51 000 × 2%)
 */
public class TestBuyerAgent extends BuyerAgent {

	private static final Gson   GSON          = new Gson();
	private static final double RESERVE_PRICE = 53_000.0;
	private static final double FIRST_OFFER   = 49_000.0;

	@Override
	protected void setup() {
		// Set requirements before super.setup() so the field is populated when
		// sendSearchRequest() is called below.
		setRequirementsAndReserve(buildRequirements(), RESERVE_PRICE);
		super.setup();

		// Auto-trigger the search immediately — no GUI button needed.
		// KA must already be up and DA's listings indexed before this fires,
		// which NegotiationFlowTest enforces with Thread.sleep() between agent starts.
		sendSearchRequest();
	}

	/** Auto-shortlist: take the first matching car, build a one-entry shortlist. */
	@Override
	protected void onMatchesReceived(List<CarListing> matches) {
		super.onMatchesReceived(matches); // console log
		if (matches.isEmpty()) {
			System.out.println("[TEST-BA] No matches from KA — check DA inventory / requirements.");
			return;
		}

		CarListing best = matches.get(0);
		Offer firstOffer = new Offer(
				best.getCarId(), FIRST_OFFER, 0, getLocalName(), false);

		// LinkedHashMap preserves insertion order — required by KA's shortlist logic.
		Map<String, Offer> shortlist = new LinkedHashMap<>();
		shortlist.put(best.getDealerAIDName(), firstOffer);

		System.out.println("[TEST-BA] Auto-shortlisting " + best.getCarId()
				+ " from " + best.getDealerAIDName()
				+ " with first offer RM" + FIRST_OFFER);
		submitShortlist(shortlist);
	}

	/** Auto-respond to DA's CFP / PROPOSE: accept if within reserve, else counter. */
	@Override
	protected void onNegotiationOfferReceived(String carId, Offer offer) {
		super.onNegotiationOfferReceived(carId, offer); // console log
		if (offer.getAmount() <= RESERVE_PRICE) {
			System.out.println("[TEST-BA] Offer RM" + offer.getAmount()
					+ " <= reserve RM" + RESERVE_PRICE + " — accepting.");
			acceptDeal(carId);
		} else {
			double counter = Math.max(FIRST_OFFER, offer.getAmount() - 4_000.0);
			System.out.println("[TEST-BA] Offer RM" + offer.getAmount()
					+ " above reserve — countering at RM" + counter);
			submitOffer(carId, counter);
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private static BuyerRequirements buildRequirements() {
		String json = """
				{
				  "buyerId":         "BA-TEST-1",
				  "model":           "Camry",
				  "yearMin":         2018,
				  "yearMax":         2022,
				  "maxMileage":      80000,
				  "condition":       "used",
				  "make":            "Toyota",
				  "preferredColour": "White",
				  "maxBudget":       60000.0,
				  "firstOffer":      49000.0
				}
				""";
		return GSON.fromJson(json, BuyerRequirements.class);
	}
}
