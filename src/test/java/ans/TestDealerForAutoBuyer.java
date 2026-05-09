package ans;

import ans.agent.DealerAgent;
import ans.model.CarListing;
import ans.model.Offer;

/**
 * Test dealer for autonomous buyer integration test.
 *
 * Phase 2: always accepts buyer interest if first offer is above floor.
 * Phase 3: accepts once buyer reaches threshold, otherwise counters.
 */
public class TestDealerForAutoBuyer extends DealerAgent {

    private static final double FLOOR_PRICE = 48_000.0;
    private static final double DA_ACCEPT_THRESHOLD = 49_500.0;

    @Override
    protected void setup() {
        super.setup();
        addToInventory(buildTestCar(), FLOOR_PRICE);
    }

    @Override
    protected void onBuyerInterestReceived(String carId, String buyerAIDName, Offer offer) {
        super.onBuyerInterestReceived(carId, buyerAIDName, offer);
        if (offer.getAmount() >= FLOOR_PRICE) {
            acceptBuyerInterest(carId, buyerAIDName);
        } else {
            declineBuyerInterest(carId, buyerAIDName);
        }
    }

	@Override
	protected boolean onNegotiationOfferReceived(String carId, Offer offer) {
		super.onNegotiationOfferReceived(carId, offer);
		if (offer.getAmount() >= DA_ACCEPT_THRESHOLD) {
			acceptDeal(carId);
		} else {
			submitOffer(carId, offer.getAmount() + 1_000.0);
		}
		return true; // handled autonomously — don't block
	}

    private static CarListing buildTestCar() {
        CarListing c = new CarListing();
        c.setCarId("DA1-001");
        c.setMake("Toyota");
        c.setModel("Camry");
        c.setYear(2020);
        c.setMileage(65_000);
        c.setColour("White");
        c.setCondition("used");
        c.setRetailPrice(55_000.0);
        return c;
    }
}
