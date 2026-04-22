package ans.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal negotiation tracker held by DA or BA.
 * Never serialised or transmitted — no Gson needed.
 */
public class NegotiationState {

    public enum Status {
        ACTIVE, DEAL_REACHED, FAILED, WAITING
    }

    private final String      carId;
    private final int         maxRounds;
    private int               currentRound;
    private final double      ownFirstOffer;
    private final double      ownReserveOrFloor;
    private final double      alpha;
    private final List<Offer> ownOfferHistory;
    private final List<Offer> opponentOfferHistory;
    private Status            status;

    public NegotiationState(String carId, int maxRounds,
                            double ownFirstOffer, double ownReserveOrFloor,
                            double alpha) {
        this.carId               = carId;
        this.maxRounds           = maxRounds;
        this.ownFirstOffer       = ownFirstOffer;
        this.ownReserveOrFloor   = ownReserveOrFloor;
        this.alpha               = alpha;
        this.currentRound        = 0;
        this.status              = Status.ACTIVE;
        this.ownOfferHistory     = new ArrayList<>();
        this.opponentOfferHistory= new ArrayList<>();
    }

    public String      getCarId()               { return carId; }
    public int         getMaxRounds()           { return maxRounds; }
    public int         getCurrentRound()        { return currentRound; }
    public double      getOwnFirstOffer()       { return ownFirstOffer; }
    public double      getOwnReserveOrFloor()   { return ownReserveOrFloor; }
    public double      getAlpha()               { return alpha; }
    public List<Offer> getOwnOfferHistory()     { return ownOfferHistory; }
    public List<Offer> getOpponentOfferHistory(){ return opponentOfferHistory; }
    public Status      getStatus()              { return status; }

    public void incrementRound()              { currentRound++; }
    public void setStatus(Status status)      { this.status = status; }
    public void recordOwnOffer(Offer o)       { ownOfferHistory.add(o); }
    public void recordOpponentOffer(Offer o)  { opponentOfferHistory.add(o); }
}
