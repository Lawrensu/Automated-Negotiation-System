package ans.negotiation;

// Boulware / tough — concedes very slowly at first, then rapidly in the final rounds. Good when the agent has time and wants to extract maximum value.
// Linear — equal concessions per round. Balanced strategy.</li>
// Conceder — concedes quickly early, then slows down. Good when the agent is risk-averse or has an urgent deadline.

// Formula (from Faratin et al. 1998):

// p(t) = reserveOrFloor + (firstOffer - reserveOrFloor)
// (1 - (t / T)^(1/alpha))

// For a Buyer   : firstOffer ≤ reservePrice   — offers INCREASE toward reserve.
// For a Dealer  : firstOffer ≥ floorPrice      — offers DECREASE toward floor.

public class TimeBasedStrategy {

    private final double firstOffer;       // BA: initial low bid; DA: retail price (ask)
    private final double reserveOrFloor;   // BA: reserve price (max willing to pay);
                                           // DA: floor price (min willing to accept)
    private final double alpha;            // concession shape (0 < alpha; default 1.0 = linear)
    private final int    maxRounds;        // T in the formula

    public TimeBasedStrategy(double firstOffer, double reserveOrFloor,
                             double alpha, int maxRounds) {
        this.firstOffer     = firstOffer;
        this.reserveOrFloor = reserveOrFloor;
        this.alpha          = (alpha <= 0) ? 1.0 : alpha;
        this.maxRounds      = Math.max(1, maxRounds);
    }

    public double calculateOffer(int currentRound) {
        if (currentRound <= 0) return firstOffer;
        if (currentRound >= maxRounds) return reserveOrFloor;

        // Normalised time t ∈ (0, 1)
        double t = (double) currentRound / (double) maxRounds;

        // Concession factor κ(t) = 1 − t^(1/α)  → ∈ (0, 1) for t ∈ (0, 1)
        double kappa = 1.0 - Math.pow(t, 1.0 / alpha);

        // Offer = reserve + (firstOffer − reserve) * κ(t)
        double offer = reserveOrFloor + (firstOffer - reserveOrFloor) * kappa;

        // Clamp to [min(first, reserve), max(first, reserve)] to avoid floating-point drift
        double lo = Math.min(firstOffer, reserveOrFloor);
        double hi = Math.max(firstOffer, reserveOrFloor);
        return Math.max(lo, Math.min(hi, offer));
    }

    public boolean shouldAccept(double opponentOffer, int currentRound) {
        double nextOwnOffer = calculateOffer(currentRound + 1);

        if (firstOffer < reserveOrFloor) {
            // Buyer: wants low price — accept if opponent is asking ≤ our next bid
            return opponentOffer <= nextOwnOffer;
        } else {
            // Dealer: wants high price — accept if opponent is offering ≥ our next ask
            return opponentOffer >= nextOwnOffer;
        }
    }

    public boolean isWithinLimit(double opponentOffer) {
        if (firstOffer < reserveOrFloor) {
            // Buyer: offer is acceptable if it does not exceed reserve price
            return opponentOffer <= reserveOrFloor;
        } else {
            // Dealer: offer is acceptable if it is at or above floor price
            return opponentOffer >= reserveOrFloor;
        }
    }

    public double getFirstOffer()     { return firstOffer; }
    public double getReserveOrFloor() { return reserveOrFloor; }
    public double getAlpha()          { return alpha; }
    public int    getMaxRounds()      { return maxRounds; }
}
