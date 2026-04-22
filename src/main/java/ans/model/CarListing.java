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

    /** No-arg constructor required by Gson. */
    public CarListing() {}

    public String getCarId()      { return carId; }
    public String getMake()       { return make; }
    public String getModel()      { return model; }
    public int    getYear()       { return year; }
    public int    getMileage()    { return mileage; }
    public String getColour()     { return colour; }
    public String getCondition()  { return condition; }
    public double getRetailPrice(){ return retailPrice; }
}
