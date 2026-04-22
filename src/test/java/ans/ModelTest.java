package ans;

import ans.model.BuyerRequirements;
import ans.model.CarListing;
import com.google.gson.Gson;

/**
 * Scratch smoke test — NOT part of the final build.
 * Run directly: javac + java, or via IDE run configuration.
 * Verifies BuyerRequirements.matches() hard-cutoff logic.
 */
public class ModelTest {

    public static void main(String[] args) {

        Gson gson = new Gson();

        // --- Build a CarListing via Gson (simulates what KA receives from DA) ---
        String listingJson = """
                {
                  "carId":       "DA1-001",
                  "make":        "Toyota",
                  "model":       "Camry",
                  "year":        2020,
                  "mileage":     65000,
                  "colour":      "White",
                  "condition":   "used",
                  "retailPrice": 55000.00
                }
                """;
        CarListing listing = gson.fromJson(listingJson, CarListing.class);
        System.out.println("Listing: " + listing.getCarId()
                + " | " + listing.getMake() + " " + listing.getModel()
                + " " + listing.getYear()
                + " | " + listing.getMileage() + " km"
                + " | " + listing.getCondition()
                + " | RM " + listing.getRetailPrice());

        // -----------------------------------------------------------------------
        // CASE 1 — PASS: requirements match all hard cutoffs
        // -----------------------------------------------------------------------
        String req1Json = """
                {
                  "buyerId":        "BA1",
                  "model":          "Camry",
                  "yearMin":        2018,
                  "yearMax":        2022,
                  "maxMileage":     80000,
                  "condition":      "USED",
                  "make":           "Toyota",
                  "preferredColour":"White",
                  "maxBudget":      60000.00,
                  "firstOffer":     45000.00
                }
                """;
        BuyerRequirements req1 = gson.fromJson(req1Json, BuyerRequirements.class);
        boolean result1 = req1.matches(listing);
        System.out.println("\n[CASE 1 — expect PASS] matches() = " + result1);
        assert result1 : "CASE 1 failed — should have matched";

        // -----------------------------------------------------------------------
        // CASE 2 — FAIL: model mismatch
        // -----------------------------------------------------------------------
        String req2Json = """
                {
                  "buyerId":    "BA2",
                  "model":      "Corolla",
                  "yearMin":    2018,
                  "yearMax":    2022,
                  "maxMileage": 80000,
                  "condition":  "used",
                  "maxBudget":  60000.00,
                  "firstOffer": 45000.00
                }
                """;
        BuyerRequirements req2 = gson.fromJson(req2Json, BuyerRequirements.class);
        boolean result2 = req2.matches(listing);
        System.out.println("[CASE 2 — expect FAIL (wrong model)] matches() = " + result2);
        assert !result2 : "CASE 2 failed — should not have matched (wrong model)";

        // -----------------------------------------------------------------------
        // CASE 3 — FAIL: year out of range
        // -----------------------------------------------------------------------
        String req3Json = """
                {
                  "buyerId":    "BA3",
                  "model":      "Camry",
                  "yearMin":    2015,
                  "yearMax":    2018,
                  "maxMileage": 80000,
                  "condition":  "used",
                  "maxBudget":  60000.00,
                  "firstOffer": 45000.00
                }
                """;
        BuyerRequirements req3 = gson.fromJson(req3Json, BuyerRequirements.class);
        boolean result3 = req3.matches(listing);
        System.out.println("[CASE 3 — expect FAIL (year 2020 > yearMax 2018)] matches() = " + result3);
        assert !result3 : "CASE 3 failed — should not have matched (year too new)";

        // -----------------------------------------------------------------------
        // CASE 4 — FAIL: mileage over cap
        // -----------------------------------------------------------------------
        String req4Json = """
                {
                  "buyerId":    "BA4",
                  "model":      "Camry",
                  "yearMin":    2018,
                  "yearMax":    2022,
                  "maxMileage": 50000,
                  "condition":  "used",
                  "maxBudget":  60000.00,
                  "firstOffer": 45000.00
                }
                """;
        BuyerRequirements req4 = gson.fromJson(req4Json, BuyerRequirements.class);
        boolean result4 = req4.matches(listing);
        System.out.println("[CASE 4 — expect FAIL (65k km > 50k cap)] matches() = " + result4);
        assert !result4 : "CASE 4 failed — should not have matched (too many km)";

        // -----------------------------------------------------------------------
        // CASE 5 — FAIL: condition mismatch
        // -----------------------------------------------------------------------
        String req5Json = """
                {
                  "buyerId":    "BA5",
                  "model":      "Camry",
                  "yearMin":    2018,
                  "yearMax":    2022,
                  "maxMileage": 80000,
                  "condition":  "new",
                  "maxBudget":  60000.00,
                  "firstOffer": 45000.00
                }
                """;
        BuyerRequirements req5 = gson.fromJson(req5Json, BuyerRequirements.class);
        boolean result5 = req5.matches(listing);
        System.out.println("[CASE 5 — expect FAIL (new vs used)] matches() = " + result5);
        assert !result5 : "CASE 5 failed — should not have matched (condition mismatch)";

        System.out.println("\nAll assertions passed.");

        // Verify Config loads
        System.out.println("\nConfig check:");
        System.out.println("  broker.fixedFee       = " + Config.getDouble("broker.fixedFee"));
        System.out.println("  broker.commissionRate = " + Config.getDouble("broker.commissionRate"));
        System.out.println("  negotiation.maxRounds = " + Config.getInt("negotiation.maxRounds"));
        System.out.println("  budgetTolerance       = " + Config.getDouble("negotiation.budgetTolerance"));
        System.out.println("  gui.accentColour      = " + Config.get("gui.accentColour"));
    }
}
