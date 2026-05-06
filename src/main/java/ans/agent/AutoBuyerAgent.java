package ans.agent;


import ans.Config;
import ans.model.NegotiationState;
import ans.model.Offer;
import ans.negotiation.TimeBasedStrategy;

public class AutoBuyerAgent extends BuyerAgent {
    @Override
    protected boolean onNegotiationOfferReceived(String carId, Offer offer){
        // Let the parent log the offer to the console.
        super.onNegotiationOfferReceived(carId, offer);

        // Input validation: Check if the offer is null or has an invalid price.
        NegotiationState state = activeNegotiations.get(carId);
        if (state == null) {
            System.err.println("[AutoBA] ERROR: No negotiation state for carId=" + carId
                                        + " — ignoring offer.");
            return false; // fall back to blocking 
        }

        // Validate the offer's price.
        if (offer == null) {
            System.err.println("[AutoBA] ERROR: Invalid offer received for carId=" + carId
                                        + " — ignoring offer.");
            return false;  
        }

        double dealerAsk = offer.getAmount();
        if (dealerAsk < 0) {
            System.err.println("[AutoBA] ERROR: Negative offer RM" + String.format("%.2f", dealerAsk)
                                        + " for carId=" + carId);
            return false;
        }

        // Strategy initialization: Create a time-based strategy instance for this negotiation.
        TimeBasedStrategy strategy = new TimeBasedStrategy(
                            state.getOwnFirstOffer(),
                            state.getOwnReserveOrFloor(),
                            state.getAlpha(),
                            state.getMaxRounds()
                        );

        int round = state.getCurrentRound();
        logStrategy(carId, dealerAsk, round, strategy);

        // DECISION TREE: 4-step autonomous decision logic
        // Accept if within reserve AND strategy says acceptable
        if (strategy.isWithinLimit(dealerAsk) && strategy.shouldAccept(dealerAsk, round)) {
            System.out.println("[AutoBA] ✓ ACCEPT at RM" + String.format("%.2f", dealerAsk) + " (round " + round + ") — meets acceptance criteria");
            acceptDeal(carId);
            return true;
        }

        // Accept within reserve or walk away
        if (round >= state.getMaxRounds() - 1) {
            if (strategy.isWithinLimit(dealerAsk)) {
                System.out.println("[AutoBA] ✓ ACCEPT at RM" + String.format("%.2f", dealerAsk) + " (final round, within reserve)");
                acceptDeal(carId);
            } else {
                System.out.println("[AutoBA] ✗ WALK AWAY from " + carId + " — final round, dealer ask RM" + String.format("%.2f", dealerAsk) + " exceeds reserve RM" + String.format("%.2f", strategy.getReserveOrFloor()));
                walkAway(carId);
            }
                return true;
        }

        // Mid-negotiation — counter with formula-based offer
        double myOffer = strategy.calculateOffer(round + 1);
        System.out.println("[AutoBA] ↔ COUNTER with RM" + String.format("%.2f", myOffer) + " (round " + (round + 1) + "/" + state.getMaxRounds() + ")");
        submitOffer(carId, myOffer);
        return true;
    }

        // Logs negotiation context for each offer.
        private void logStrategy(String carId, double dealerAsk, int round, TimeBasedStrategy strategy) {
            double nextBid = strategy.calculateOffer(round + 1);
            System.out.printf(
                                "[AutoBA] carId=%-12s | round=%2d/%2d | dealerAsk=RM%-10.2f" + " | nextBid=RM%-10.2f | reserve=RM%-10.2f | alpha=%.2f%n",
                                carId, round, strategy.getMaxRounds(), dealerAsk, nextBid,
                                strategy.getReserveOrFloor(), strategy.getAlpha());
        }
    
}
