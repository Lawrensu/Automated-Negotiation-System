package ans.model;

public class BuyerRequirements {

    // Hard cutoffs
    private String buyerId;
    private String model;
    private int    yearMin;
    private int    yearMax;
    private int    maxMileage;
    private String condition;

    // Soft preferences (used by KA for ranking, not in matches())
    private String make;
    private String preferredColour;

    // Budget / opening bid (KA applies the 125% budget tolerance separately)
    private double maxBudget;
    private double firstOffer;

    /** No-arg constructor required by Gson. */
    public BuyerRequirements() {}

    /**
     * Hard-cutoff matching only.
     * The 125% budget tolerance is NOT applied here — that is KA's responsibility.
     */
    public boolean matches(CarListing listing) {
        if (!listing.getModel().equalsIgnoreCase(model))         return false;
        if (listing.getYear() < yearMin)                         return false;
        if (listing.getYear() > yearMax)                         return false;
        if (listing.getMileage() > maxMileage)                   return false;
        if (!listing.getCondition().equalsIgnoreCase(condition)) return false;
        return true;
    }

    public String getBuyerId()         { return buyerId; }
    public String getModel()           { return model; }
    public int    getYearMin()         { return yearMin; }
    public int    getYearMax()         { return yearMax; }
    public int    getMaxMileage()      { return maxMileage; }
    public String getCondition()       { return condition; }
    public String getMake()            { return make; }
    public String getPreferredColour() { return preferredColour; }
    public double getMaxBudget()       { return maxBudget; }
    public double getFirstOffer()      { return firstOffer; }
}
