# Automated Buyer 

## Running the program 

### Step 1: Build and Launch

```bash
mvn install:install-file "-Dfile=lib/jade.jar" "-DgroupId=com.tilab.jade" "-DartifactId=jade" "-Dversion=4.6.0" "-Dpackaging=jar"
mvn clean compile
mvn exec:java "-Dexec.mainClass=ans.Main"
```
 
### Step 2: Start Dealer (Manual Setup)

1. Click **Start as Dealer**
2. In **Listings** tab, add a car
3. Click **Add Car**, then **Register Listings with KA**
4. The dealer tab must **NOT** be closed

### Step 3: Start AutoBuyer (Fully Autonomous)

1. From Launcher, click **Start as Automated Buyer**
2. Fill in the search tab
3. Click **Search** — results appear
4. Select the car that was added by the dealer
5. Click **Send Shortlist to KA (Auto-Negotiate)**
6. Choose your strategy type (Boulware, Linear and Conceder)

### Step 4: Watch Automated Negotiation

- **Auto-Negotiation** tab shows live offer log
- Console shows detailed decision logs (ACCEPT/COUNTER/WALK AWAY)
- Dealer must manually respond in DealerWindow:
  - When offer arrives, check offer amount
  - Click **Accept Deal** if acceptable
  - Or click counter field, enter new price, click **Counter**
- AutoBuyer responds automatically

### Example for the Automated Buyer

| Field       | Example | What it means                          |
| ----------- | ------- | -------------------------------------- |
| First Offer | 49,000  | Your opening bid                       |
| Reserve     | 53,000  | Your max price (never exceed)          |
| Strategy    | Linear  | How fast you concede (Linear=balanced) |
| Custom α    | 1.0     | Fine-tune (usually keep default)       |