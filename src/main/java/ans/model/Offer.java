package ans.model;

public class Offer {

    private String  carId;
    private double  amount;
    private int     round;
    private String  fromAgentId;
    private boolean isFinal;

    /** No-arg constructor required by Gson. */
    public Offer() {}

    public String  getCarId()       { return carId; }
    public double  getAmount()      { return amount; }
    public int     getRound()       { return round; }
    public String  getFromAgentId() { return fromAgentId; }
    public boolean isFinal()        { return isFinal; }
}
