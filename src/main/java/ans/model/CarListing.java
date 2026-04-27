package ans.model;

public class CarListing {

    private String carId;
    private String make;
    private String model;
    private int    year;
    private int    mileage;
    private String colour;
    private String condition;
    private double retailPrice;

    /**
     * Populated by KA when sending matches back to BA — never set by DA.
     * Lets BA build the shortlist Map<dealerAIDName, Offer> without inferring
     * the dealer's identity from carId string conventions.
     */
    private String dealerAIDName;

    /** No-arg constructor required by Gson. */
    public CarListing() {}

    public String getCarId()         { return carId; }
    public String getMake()          { return make; }
    public String getModel()         { return model; }
    public int    getYear()          { return year; }
    public int    getMileage()       { return mileage; }
    public String getColour()        { return colour; }
    public String getCondition()     { return condition; }
    public double getRetailPrice()   { return retailPrice; }
    public String getDealerAIDName() { return dealerAIDName; }

    // ── Setters for GUI form use (DealerWindow "Add Car" form) ────────────────

    /** Called by DealerWindow's "Add Car" form when the human enters a new car. */
    public void setCarId(String carId)           { this.carId      = carId; }
    public void setMake(String make)             { this.make       = make; }
    public void setModel(String model)           { this.model      = model; }
    public void setYear(int year)                { this.year       = year; }
    public void setMileage(int mileage)          { this.mileage    = mileage; }
    public void setColour(String colour)         { this.colour     = colour; }
    public void setCondition(String condition)   { this.condition  = condition; }
    public void setRetailPrice(double price)     { this.retailPrice = price; }

    /** Called by KA only, immediately before forwarding matches to BA. */
    public void setDealerAIDName(String name) { this.dealerAIDName = name; }
}
