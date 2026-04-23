package ans.model;

public class Offer {

    private String  carId;
    private double  amount;
    private int     round;
    private String  fromAgentId;
    private boolean isFinal;

    /** No-arg constructor required by Gson deserialisation. */
    public Offer() {}

    /** Convenience constructor for creating offers inside agent code. */
    public Offer(String carId, double amount, int round, String fromAgentId, boolean isFinal) {
        this.carId       = carId;
        this.amount      = amount;
        this.round       = round;
        this.fromAgentId = fromAgentId;
        this.isFinal     = isFinal;
    }

    public String  getCarId()       { return carId; }
    public double  getAmount()      { return amount; }
    public int     getRound()       { return round; }
    public String  getFromAgentId() { return fromAgentId; }
    public boolean isFinal()        { return isFinal; }
}
