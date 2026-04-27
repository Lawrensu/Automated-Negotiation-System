package ans;

import ans.agent.DealerAgent;
import ans.model.CarListing;
import ans.model.Offer;
import com.google.gson.Gson;

/**
 * Test-only DealerAgent subclass used by NegotiationFlowTest.
 * Not part of the production build — lives in src/test/java.
 *
 * Pre-loads one Toyota Camry 2020 into inventory so RegisterListingsBehaviour
 * sends it to KA on startup without any GUI interaction.
 *
 * Auto-negotiation logic:
 *
 *   onBuyerInterestReceived (Phase 2):
 *     If BA's first offer >= FLOOR_PRICE (RM 48 000) → acceptBuyerInterest().
 *     Otherwise → declineBuyerInterest().
 *     This replaces the old auto-respond that lived inside ReceiveBuyerInterestBehaviour.
 *     DealerAgent now blocks after calling this hook and requires an explicit call
 *     to acceptBuyerInterest() or declineBuyerInterest() to send the response to KA.
 *
 *   onNegotiationOfferReceived (Phase 3):
 *     If BA's offer >= DA_ACCEPT_THRESHOLD (RM 50 000) → acceptDeal().
 *     Otherwise → submitOffer(BA offer + RM 2 000).
 *
 * With the TestBuyerAgent scenario this produces:
 *   CFP  DA=55 000
 *   BA counters at 51 000   (55 000 − 4 000)
 *   DA accepts 51 000       (51 000 >= 50 000 threshold) ✓
 */
public class TestDealerAgent extends DealerAgent {

	private static final Gson   GSON                = new Gson();
	private static final double FLOOR_PRICE         = 48_000.0;
	private static final double DA_ACCEPT_THRESHOLD = 50_000.0;

	@Override
	protected void setup() {
		// super.setup() initialises collections and adds behaviours (scheduled, not yet run).
		// Adding to inventory here is safe because JADE runs behaviours only after setup()
		// returns — RegisterListingsBehaviour will see the car when it executes.
		super.setup();
		addToInventory(buildTestCar(), FLOOR_PRICE);
		System.out.println("[TEST-DA] Inventory loaded: DA1-001 Toyota Camry 2020, "
				+ "retailPrice=55000, floor=" + FLOOR_PRICE);
	}

	/**
	 * Auto-respond to KA's buyer interest forwarding.
	 * DealerAgent now blocks after calling this hook — we must call
	 * acceptBuyerInterest() or declineBuyerInterest() to unblock it.
	 */
	@Override
	protected void onBuyerInterestReceived(String carId, String buyerAIDName, Offer offer) {
		super.onBuyerInterestReceived(carId, buyerAIDName, offer); // console log
		if (offer.getAmount() >= FLOOR_PRICE) {
			System.out.println("[TEST-DA] Offer RM" + offer.getAmount()
					+ " >= floor RM" + FLOOR_PRICE + " — accepting interest.");
			acceptBuyerInterest(carId, buyerAIDName);
		} else {
			System.out.println("[TEST-DA] Offer RM" + offer.getAmount()
					+ " < floor RM" + FLOOR_PRICE + " — declining interest.");
			declineBuyerInterest(carId, buyerAIDName);
		}
	}

	/** Auto-respond to BA's PROPOSE: accept if offer meets threshold, else counter. */
	@Override
	protected void onNegotiationOfferReceived(String carId, Offer offer) {
		super.onNegotiationOfferReceived(carId, offer); // console log
		if (offer.getAmount() >= DA_ACCEPT_THRESHOLD) {
			System.out.println("[TEST-DA] Offer RM" + offer.getAmount()
					+ " >= threshold RM" + DA_ACCEPT_THRESHOLD + " — accepting.");
			acceptDeal(carId);
		} else {
			double counter = offer.getAmount() + 2_000.0;
			System.out.println("[TEST-DA] Offer RM" + offer.getAmount()
					+ " below threshold — countering at RM" + counter);
			submitOffer(carId, counter);
		}
	}


	// ── Helpers ──────────────────────────────────────────────────────────────

	private static CarListing buildTestCar() {
		String json = """
				{
				  "carId":       "DA1-001",
				  "make":        "Toyota",
				  "model":       "Camry",
				  "year":        2020,
				  "mileage":     65000,
				  "colour":      "White",
				  "condition":   "used",
				  "retailPrice": 55000.0
				}
				""";
		return GSON.fromJson(json, CarListing.class);
	}
}
