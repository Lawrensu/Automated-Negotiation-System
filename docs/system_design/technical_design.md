# Automated Negotiation System — Technical Implementation Design

> This document maps the system design into concrete implementation decisions. It is intended for the development team to implement from. Every decision here traces back to the system design document.
>
> Sections marked **[v1]**, **[v2]**, or **[Ext1]** indicate which version introduced or modified that section. Unmarked sections apply to all versions.

---

## Technology Stack

| Layer           | Choice        | Reason                                                               |
| --------------- | ------------- | -------------------------------------------------------------------- |
| Agent framework | JADE 4.6.0    | FIPA-compliant, built-in AMS and DF, ContractNet behaviours included |
| Language        | Java 17+      | Required by JADE                                                     |
| Serialisation   | JSON via Gson | Human-readable, handles collections, no custom codec needed          |
| GUI             | Java Swing    | No extra dependency, runs natively with JADE, fully customisable     |
| Build tool      | Maven         | Dependency management for JADE and Gson                              |

---

## Configuration (config.properties)
All adjustable system values are stored in `src/main/resources/config.properties`. Loaded once on startup by a `Config` utility class. No magic numbers in agent code.

```properties
# Broker fees
broker.fixedFee=500.00
broker.commissionRate=0.02

# Negotiation defaults
negotiation.maxRounds=20
negotiation.budgetTolerance=1.25

# v2: Concession strategy defaults
negotiation.alphaDealer=0.7
negotiation.alphaBuyer=1.0

# v2: Regression predictor gates
negotiation.regressionMinPoints=3
negotiation.regressionMinR2=0.80
negotiation.boulwareSlopeThreshold=50.0

# v2: DA shortening threshold
negotiation.bestOfferThreshold=500.0

# GUI
gui.accentColour=#1E3A5F
```

Values and what they control:

| Key | Default | Version | What it controls |
|---|---|---|---|
| broker.fixedFee | 500.00 | all | Fixed fee charged to DA when KA connects DA–BA pair (in RM) |
| broker.commissionRate | 0.02 | all | Commission rate charged to DA on final deal price (0.02 = 2%) |
| negotiation.maxRounds | 20 | all | Default round limit T — configurable via GUI before each negotiation |
| negotiation.budgetTolerance | 1.25 | all | KA includes listings up to this multiple of BA's reserve price |
| negotiation.alphaDealer | 0.7 | v2+ | DA concession curve shape (0.7 = Conceder-leaning) |
| negotiation.alphaBuyer | 1.0 | v2+ | BA concession curve shape (1.0 = Linear) |
| negotiation.regressionMinPoints | 3 | v2+ | Minimum opponent offers before regression predictor fires |
| negotiation.regressionMinR2 | 0.80 | v2+ | R² confidence gate; below this, regression is suppressed |
| negotiation.boulwareSlopeThreshold | 50.0 | v2+ | Slope (RM/round) below which Boulware detection suppresses regression |
| negotiation.bestOfferThreshold | 500.0 | v2+ | RM gap at which DA makes final aggressive offer to close |
| gui.accentColour | #1E3A5F | all | Accent colour used across all Swing windows |

**Config.java utility class**

```java
public class Config {
    private static final Properties props = new Properties();

    static {
        try (InputStream in = Config.class
                .getClassLoader()
                .getResourceAsStream("config.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("config.properties not found", e);
        }
    }

    public static double getDouble(String key) {
        return Double.parseDouble(props.getProperty(key));
    }

    public static int getInt(String key) {
        return Integer.parseInt(props.getProperty(key));
    }

    public static String get(String key) {
        return props.getProperty(key);
    }
}
```

Usage anywhere in agent or GUI code:
```java
double fixedFee       = Config.getDouble("broker.fixedFee");
double commissionRate = Config.getDouble("broker.commissionRate");
int    maxRounds      = Config.getInt("negotiation.maxRounds");
double tolerance      = Config.getDouble("negotiation.budgetTolerance");
```


--- 

## Project Structure

```
automated-negotiation-system/
├── pom.xml
├── src/main/resources/ 
│ └── config.properties                  ← all adjustable values, loaded on startup
└── src/main/java/ans/
    ├── Main.java                        ← boots JADE, launches GUI launcher
    ├── agent/
    │   ├── BrokerAgent.java             ← KA
    │   ├── DealerAgent.java             ← DA
    │   └── BuyerAgent.java              ← BA
    ├── model/
    │   ├── CarListing.java              ← public DA listing fields
    │   ├── BuyerRequirements.java       ← BA search criteria + matching logic
    │   ├── Offer.java                   ← price offer passed between DA and BA
    │   └── NegotiationState.java        ← internal negotiation tracker (not transmitted)
    ├── strategy/
    │   ├── ConcessionStrategy.java      ← interface
    │   ├── TimeBasedStrategy.java       ← Faratin formula implementation [v2]
    │   └── RegressionPredictor.java     ← linear regression on opponent offer history [v2]
    ├── util/
    │   └── MessageBuilder.java          ← ACL message factory
    └── gui/
        ├── LauncherWindow.java          ← main launcher
        ├── DealerWindow.java            ← DA GUI
        ├── BuyerWindow.java             ← BA GUI
        └── BrokerLogWindow.java         ← KA log view
```

---

## Data Models

### CarListing.java

Represents one car in a dealer's inventory. Transmitted from DA to KA on startup.

```
Fields (all public, serialised to JSON):
  String  carId          ← unique identifier, e.g. "DA1-001"
  String  make           ← e.g. "Toyota"
  String  model          ← e.g. "Camry"
  int     year           ← e.g. 2020
  int     mileage        ← in km, e.g. 65000
  String  colour         ← e.g. "White"
  String  condition      ← "new" or "used"
  double  retailPrice    ← DA's public asking price

NOT included here (stored privately in DealerAgent only):
  double  floorPrice     ← DA's private minimum, per car
```

### BuyerRequirements.java

Represents what the buyer is looking for. Transmitted from BA to KA. Contains the matching logic via a matches(CarListing) method.

```
Fields (all public, serialised to JSON):
  String  buyerId
  String  model          ← hard cutoff
  int     yearMin        ← hard cutoff
  int     yearMax        ← hard cutoff
  int     maxMileage     ← hard cutoff
  String  condition      ← hard cutoff ("new" or "used")
  String  make           ← soft preference
  String  preferredColour ← soft preference
  double  maxBudget      ← used for KA budget filter only (120–130% tolerance)
  double  firstOffer     ← BA's opening bid, sent with shortlist

NOT included here (stored privately in BuyerAgent only):
  double  reservePrice   ← BA's true maximum, never transmitted

Method:
  boolean matches(CarListing listing)
    → returns true if listing passes all hard cutoffs
    → soft preferences are used separately for ranking
```

### Offer.java

A single price offer. Passed between DA and BA during negotiation.

```
Fields (all public, serialised to JSON):
  String  carId
  double  amount         ← the offered price
  int     round          ← which round this offer was made
  String  fromAgentId    ← AID name of the sender
  boolean isFinal        ← true if this is an accept/reject final message
```

### NegotiationState.java

Internal tracker. Never transmitted. Each agent holds one per active negotiation.

```
Fields:
  String          carId
  int             maxRounds          ← T, default 20
  int             currentRound       ← increments each exchange
  double          ownFirstOffer      ← starting point for concession formula
  double          ownReserveOrFloor  ← private limit (reserve for BA, floor for DA)
  double          alpha              ← α, set before negotiation, fixed during
  List<Offer>     ownOfferHistory
  List<Offer>     opponentOfferHistory
  Status          status             ← ACTIVE, DEAL_REACHED, FAILED, WAITING

Enum Status:
  ACTIVE, DEAL_REACHED, FAILED, WAITING
```

---

## Message Protocol (ACL Messages)

All message content is serialised as JSON using Gson. Every message has a conversationId set to the carId being negotiated. This prevents cross-talk between concurrent messages involving different cars.

### Phase 1 — Discovery

|Step|From|To|Performative|Content|
|---|---|---|---|---|
|DA registers|DA|KA|INFORM|List<CarListing>|
|BA searches|BA|KA|REQUEST|BuyerRequirements|
|KA returns matches|KA|BA|INFORM|List<CarListing>|

### Phase 2 — Matching

|Step|From|To|Performative|Content|
|---|---|---|---|---|
|BA sends shortlist|BA|KA|INFORM|Map<dealerAIDName, Offer> — one first offer per selected car|
|KA forwards to DA|KA|DA|INFORM|{ buyerAIDName, Offer }|
|DA responds|DA|KA|INFORM|"accept" or "reject" + buyerAIDName|
|KA sends DA's AID to BA|KA|BA|INFORM|{ dealerAIDName: "..." }|
|KA sends BA's AID to DA|KA|DA|INFORM|{ buyerAIDName: "..." }|
|KA records fixed fee|internal KA|||Triggered at AID exchange|

### Phase 3 — Negotiation

|Step|From|To|Performative|Content|
|---|---|---|---|---|
|DA opens|DA|BA|CFP|Offer { amount = retailPrice, round = 0 }|
|BA counter|BA|DA|PROPOSE|Offer { amount, round }|
|DA counter|DA|BA|PROPOSE|Offer { amount, round }|
|Either accepts|DA or BA|other|ACCEPT_PROPOSAL|Offer { isFinal = true }|
|Either walks away|DA or BA|other|REJECT_PROPOSAL|Offer { isFinal = true }|
|Deal closed|DA|KA|INFORM|"deal-closed" + Offer { finalAmount }|
|Deal failed|DA or BA|KA|INFORM|"deal-failed" + carId|

---

## Agent Behaviour Design (JADE)

### BrokerAgent (KA)

JADE agent with CyclicBehaviours, one per message type it handles.

```
Behaviours:
  ReceiveListingsBehaviour       ← handles DA INFORM with List<CarListing>
  HandleBuyerSearchBehaviour     ← handles BA REQUEST, runs matching, returns results
  ReceiveShortlistBehaviour      ← handles BA INFORM shortlist, contacts DA1
  ReceiveDealerResponseBehaviour ← handles DA INFORM accept/reject, does AID exchange or moves to next DA
  ReceiveDealOutcomeBehaviour    ← handles DA INFORM deal-closed or deal-failed, records fee/commission

State:
  Map<String, AID>               dealerAIDs         ← AID name → AID object
  Map<String, List<CarListing>>  dealerListings      ← AID name → listings
  Map<String, AID>               buyerAIDs
  Map<String, List<String>>      buyerShortlists     ← buyerAID → ordered list of dealerAID names
  Map<String, Integer>           shortlistProgress   ← buyerAID → current index in shortlist
  double                         fixedFee
  double                         commissionRate
  List<String>                   eventLog            ← shown in KA window
```

### DealerAgent (DA)

```
Behaviours:
  RegisterListingsBehaviour      ← OneShotBehaviour, runs on startup, sends listings to KA
  ReceiveBuyerInterestBehaviour  ← CyclicBehaviour, handles KA INFORM with buyer interest
  NegotiationBehaviour           ← CyclicBehaviour or FSMBehaviour, handles Phase 3 offer loop

State:
  List<CarListing>               inventory
  Map<String, Double>            floorPrices         ← carId → floor price (private)
  Map<String, NegotiationState>  activeNegotiations  ← carId → state
  double                         alpha               ← set via GUI before negotiation
```

### BuyerAgent (BA)

```
Behaviours:
  SearchBehaviour                ← OneShotBehaviour, sends requirements to KA
  ReceiveMatchesBehaviour        ← CyclicBehaviour, receives matched listings, waits for user to shortlist
  ReceiveAIDExchangeBehaviour    ← CyclicBehaviour, receives dealer AID from KA, waits for CFP
  NegotiationBehaviour           ← CyclicBehaviour or FSMBehaviour, handles Phase 3

  In v1: NegotiationBehaviour waits for human input via GUI
  In v2: NegotiationBehaviour calls TimeBasedStrategy to auto-calculate next offer

State:
  BuyerRequirements              requirements
  double                         reservePrice        ← private, never transmitted
  Map<String, NegotiationState>  activeNegotiations
  double                         alpha               ← set via GUI before negotiation
```

---

## Concession Strategy [v2]

### ConcessionStrategy.java (interface)

```java
interface ConcessionStrategy {
    double calculateNextOffer(NegotiationState state, boolean isDealer);
    boolean shouldAccept(double opponentOffer, NegotiationState state, boolean isDealer);
}
```

### TimeBasedStrategy.java (implements ConcessionStrategy)

Faratin et al. (1998) time-based concession formula.

```java
public double calculateNextOffer(NegotiationState state, boolean isDealer) {
    double t      = state.getCurrentRound();
    double T      = state.getMaxRounds();
    double alpha  = state.getAlpha();
    double factor = Math.pow(t / T, 1.0 / alpha);

    if (isDealer) {
        double floor  = state.getOwnReserveOrFloor();   // floor price
        double retail = state.getOwnFirstOffer();        // retail price (DA opening)
        return floor + (retail - floor) * (1.0 - factor);
    } else {
        double first   = state.getOwnFirstOffer();       // BA opening bid
        double reserve = state.getOwnReserveOrFloor();   // reserve price
        return first + (reserve - first) * factor;
    }
}
```

**Base accept condition (either agent):**
- If opponent's offer has crossed the agent's own current calculated offer → accept
- If |myNextOffer − opponentOffer| < 1.00 (convergence threshold) → accept

The RM1.00 convergence threshold handles floating-point arithmetic differences across different α values.

---

## Regression Predictor [v2]

### RegressionPredictor.java

Fits a least-squares linear regression on the opponent's offer history and predicts
where offers are heading at the deadline. Used to enhance the accept condition and
enable early walk-away when no deal is possible.

```java
public class RegressionPredictor {
    private final List<double[]> points = new ArrayList<>(); // {round, offer}

    public void addPoint(int round, double offer) {
        points.add(new double[]{round, offer});
    }

    public boolean isReliable(double minR2, double slopeThreshold) {
        if (points.size() < Config.getInt("negotiation.regressionMinPoints")) return false;
        return rSquared() >= minR2 && Math.abs(slope()) >= slopeThreshold;
    }

    public double predictAt(int futureRound) {
        double xMean = points.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double yMean = points.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double num = 0, den = 0;
        for (double[] p : points) {
            num += (p[0] - xMean) * (p[1] - yMean);
            den += (p[0] - xMean) * (p[0] - xMean);
        }
        double slope     = (den == 0) ? 0 : num / den;
        double intercept = yMean - slope * xMean;
        return intercept + slope * futureRound;
    }

    private double slope() { /* same slope calculation as predictAt */ }
    private double rSquared() { /* 1 - SS_res / SS_tot */ }
}
```

**Enhanced accept condition (wraps the base Faratin condition):**

```java
// Called each round after receiving opponent offer
private void processIncomingOffer(Offer opponentOffer, NegotiationState state,
                                   RegressionPredictor regressor, boolean isDealer) {
    regressor.addPoint(state.getCurrentRound(), opponentOffer.getAmount());

    double myFaratinOffer = strategy.calculateNextOffer(state, isDealer);
    double minR2          = Config.getDouble("negotiation.regressionMinR2");
    double slopeThreshold = Config.getDouble("negotiation.boulwareSlopeThreshold");

    boolean accept = strategy.shouldAccept(opponentOffer.getAmount(), state, isDealer);

    if (!accept && regressor.isReliable(minR2, slopeThreshold)) {
        double opponentFirst = state.getOpponentOfferHistory().get(0).getAmount();
        double lowerBound    = isDealer ? opponentOffer.getAmount() : opponentFirst;
        double upperBound    = isDealer ? opponentFirst : opponentOffer.getAmount();
        double predicted     = Math.max(lowerBound, Math.min(upperBound,
                                   regressor.predictAt(state.getMaxRounds())));

        // Accept early if opponent is predicted to reach our price
        if (isDealer && predicted >= myFaratinOffer) accept = true;
        if (!isDealer && predicted <= myFaratinOffer) accept = true;

        // Early walk-away if no overlap
        // (DA: predicted BA ceiling still below DA floor)
        // (BA: predicted DA floor still above BA reserve)
        double ownLimit = state.getOwnReserveOrFloor();
        if (isDealer && predicted < ownLimit) { walkAway(state); return; }
        if (!isDealer && predicted > ownLimit) { walkAway(state); return; }
    }

    if (accept) acceptDeal(opponentOffer, state);
    else        sendOffer(myFaratinOffer, state);
}
```

---

## Matching Logic (KA)

Implemented inside KA's HandleBuyerSearchBehaviour. Matching logic runs via BuyerRequirements.matches(CarListing).

```
Step 1 — Hard cutoffs (BuyerRequirements.matches()):
  listing.model.equalsIgnoreCase(requirements.model)         → must match
  listing.year >= requirements.yearMin                        → must be in range
  listing.year <= requirements.yearMax
  listing.mileage <= requirements.maxMileage                  → must be under cap
  listing.condition.equalsIgnoreCase(requirements.condition) → must match

Step 2 — Budget filter (KA applies after hard cutoffs):
  listing.retailPrice <= requirements.maxBudget * 1.25       → include up to 125% of budget
  (125% is the midpoint of the 120–130% range — confirm exact value with team)

Step 3 — Soft preference ranking (KA sorts results):
  If listing.make matches requirements.make → rank higher
  If listing.colour matches requirements.preferredColour → rank higher
  Listings are returned sorted by rank (best match first)
```

---

## Fee Recording (KA)

```
On AID exchange (negotiation begins):
  eventLog.add("FIXED FEE charged to " + dealerAIDName + " — " + carId)
  totalFeesCollected += fixedFee

On deal-closed INFORM received:
  eventLog.add("COMMISSION charged to " + dealerAIDName + " — " + finalAmount * commissionRate)
  totalCommissionCollected += finalAmount * commissionRate

On deal-failed INFORM received:
  eventLog.add("DEAL FAILED — " + carId + " — moving to next dealer")
  (no commission recorded)
```

---

## GUI Implementation Notes

Built with Java Swing. All windows extend JFrame. Main.java boots JADE then launches LauncherWindow.

### LauncherWindow.java

- Three JButtons: "Start as Dealer", "Start as Buyer", "View Broker Log"
- Each button creates the JADE agent via container.createNewAgent() and opens the corresponding JFrame

### DealerWindow.java

- JTabbedPane with two tabs: Listings and Negotiation
- Listings tab: JTable (read-only, shows inventory) + JPanel form (add car inputs + floor price field)
- Negotiation tab: hidden (setVisible(false)) until KA sends buyer interest
    - Shows buyer AID and first offer
    - JButton: Accept, Decline
    - On accept: shows offer history JTable, round counter JLabel, counter-offer JTextField, Accept JButton, Walk Away JButton

### BuyerWindow.java

- JTabbedPane with two tabs: Search and Negotiation
- Search tab: JPanel with input fields for all requirements including reserve price
    - JTable showing matched results from KA
    - JCheckBox per result for shortlist selection
    - JSpinner or drag-to-rank for ordering
    - JButton: Send Shortlist
- Negotiation tab: hidden until AID exchange
    - Shows dealer AID, current offer, round number, offer history JTable
    - In v1: counter-offer JTextField + Accept, Counter, Walk Away buttons
    - In v2: auto-mode indicator, shows calculated offers being sent, no manual input

### BrokerLogWindow.java

- Single JTable with columns: Timestamp, Event, Agent, Amount
- Auto-scrolls as new events are added
- Read only — no input components

---

## Edge Case Handling in Code

| Edge case | Version | Where handled | How |
|---|---|---|---|
| No matching listings | all | KA HandleBuyerSearchBehaviour | Send INFORM with empty list; BA shows "no matches" message |
| All dealers decline | all | KA ReceiveDealerResponseBehaviour | shortlistProgress index exceeds list size; send INFORM to BA "no dealer engaged" |
| First offer below floor price | all | DA ReceiveBuyerInterestBehaviour | if offer.amount < floorPrices.get(carId) → send "reject" to KA immediately |
| Round limit reached | all | DA or BA NegotiationBehaviour | if state.currentRound >= state.maxRounds → REJECT_PROPOSAL to opponent, INFORM "deal-failed" to KA |
| BA stops responding | all | DA NegotiationBehaviour | if no message after timeout → treat as failed, INFORM "deal-failed" to KA |
| DA stops responding | all | BA NegotiationBehaviour | if no message after timeout → treat as failed, INFORM "deal-failed" to KA |
| Boulware opponent (slope near zero) | v2+ | RegressionPredictor.isReliable() | slope gate fails → regression suppressed → fall back to Faratin base only |
| Low R² (noisy offers) | v2+ | RegressionPredictor.isReliable() | R² gate fails → regression suppressed → fall back to Faratin base only |
| Insufficient history | v2+ | RegressionPredictor.isReliable() | size < minPoints → regression does not fire |
| No-overlap detected | v2+ | processIncomingOffer() | predicted opponent limit beyond own limit → walkAway() called immediately |
| Concurrent deal race | Ext1 | BA NegotiationBehaviour | JADE serialises behaviour execution per agent; first accept processed wins; others trigger walk-away |

---

## Build and Run Instructions (to be completed during implementation)

```
# Install JADE manually (not on Maven Central)
mvn install:install-file \
  -Dfile=jade.jar \
  -DgroupId=com.tilab.jade \
  -DartifactId=jade \
  -Dversion=4.6.0 \
  -Dpackaging=jar

# Build
mvn clean package

# Run
java -jar target/ans-1.0-jar-with-dependencies.jar
```