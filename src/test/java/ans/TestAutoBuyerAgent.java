package ans;

import ans.agent.AutoBuyerAgent;
import ans.model.BuyerRequirements;
import ans.model.CarListing;
import ans.model.Offer;
import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only AutoBuyerAgent used to validate autonomous single-dealer flow.
 *
 * It only sets initial negotiation inputs (first offer + reserve price),
 * then performs search and submits exactly one shortlisted dealer.
 */
public class TestAutoBuyerAgent extends AutoBuyerAgent {

    private static final double RESERVE_PRICE = 53_000.0;
    private static final double FIRST_OFFER = 49_000.0;
    private static final Gson GSON = new Gson();

    @Override
    protected void setup() {
        setRequirementsAndReserve(buildRequirements(), RESERVE_PRICE);
        setAlpha(1.0); // linear concession
        super.setup();
        sendSearchRequest();
    }

    @Override
    protected void onMatchesReceived(List<CarListing> matches) {
        super.onMatchesReceived(matches);
        if (matches.isEmpty()) {
            System.out.println("[TEST-AUTO-BA] No matches from KA.");
            return;
        }

        CarListing selected = matches.get(0);
        Map<String, Offer> shortlist = new LinkedHashMap<>();
        shortlist.put(
                selected.getDealerAIDName(),
                new Offer(selected.getCarId(), FIRST_OFFER, 0, getLocalName(), false));

        System.out.println("[TEST-AUTO-BA] Auto-shortlisting single dealer "
                + selected.getDealerAIDName() + " for " + selected.getCarId());
        submitShortlist(shortlist);
    }

    private static BuyerRequirements buildRequirements() {
        String json = """
                {
                  \"buyerId\":         \"BA-AUTO-TEST-1\",
                  \"model\":           \"Camry\",
                  \"yearMin\":         2018,
                  \"yearMax\":         2022,
                  \"maxMileage\":      80000,
                  \"condition\":       \"used\",
                  \"make\":            \"Toyota\",
                  \"preferredColour\": \"White\",
                  \"maxBudget\":       60000.0,
                  \"firstOffer\":      49000.0
                }
                """;
        return GSON.fromJson(json, BuyerRequirements.class);
    }
}
