# Getting Started


## Prerequisites

- Java 17 LTS or higher — verify with `java -version`
- Maven 3.8+ — verify with `mvn -version` ([download Maven](https://maven.apache.org/download.cgi))
- Git
- JADE 4.6.0 — already included at `lib/jade.jar`, no download needed


---


## First-time Setup (run once)

JADE is not published on Maven Central. Register it with your local Maven repository
before building:

```bash
mvn install:install-file "-Dfile=lib/jade.jar" "-DgroupId=com.tilab.jade" "-DartifactId=jade" "-Dversion=4.6.0" "-Dpackaging=jar"
```

Then compile the project:

```bash
mvn clean compile
```

A clean compile produces no errors and no output. If JADE is missing, this step will
fail with `Missing artifact com.tilab.jade:jade:jar:4.6.0` — re-run the
`install:install-file` command above.

> **VSCode users:** If the Problems panel still shows a JADE artifact error after a
> successful compile, the Language Server cache is stale. Press `Ctrl+Shift+P` →
> **Java: Clean Language Server Workspace** → **Restart and delete**.


---


## IDE Setup

### VS Code

1. Install **Extension Pack for Java** (by Microsoft) from the Extensions sidebar
2. Open the project root — VS Code detects `pom.xml` and imports it automatically
3. Wait for indexing to complete (progress indicator, bottom-right)
4. If source files are not picked up: `Ctrl+Shift+P` → **Java: Force Java Compilation**

**To run the GUI:** open the integrated terminal and use the terminal commands below.

**To run the integration test:** same — use the terminal commands below.

> VS Code's Run button does not pass `-D` JVM arguments reliably. Use the terminal.


### IntelliJ IDEA

1. **File → Open** the project root folder
2. Click **Trust Project** when prompted to import as a Maven project
   - If not prompted: right-click `pom.xml` in the Project panel → **Add as Maven Project**
3. IntelliJ marks `src/main/java` as Sources Root and `src/main/resources` as
   Resources Root automatically
   - If not: right-click each folder → **Mark Directory as → Sources Root / Resources Root**

**To run the GUI:** create a Run Configuration with main class `ans.Main` and
no additional VM options required.

**To run the integration test:** create a Run Configuration with main class
`ans.NegotiationFlowTest`, classpath set to `test`.


---


## Running the System

> **PowerShell users:** `-Dkey=value` arguments must always be quoted.
> Example: `"-Dexec.mainClass=ans.Main"` — without quotes, PowerShell splits the argument
> and Maven fails with `Unknown lifecycle phase`.

### Integration Test (no GUI)

Verifies the complete three-phase protocol end-to-end. Run this before launching the GUI
to confirm the core protocol is working.

```bash
mvn test-compile "-Dexec.mainClass=ans.NegotiationFlowTest" exec:java "-Dexec.classpathScope=test"
```

Expected result: deal closed at RM51,000, commission RM1,020 charged. Full output in ~15 seconds.


### GUI Application

```bash
mvn exec:java "-Dexec.mainClass=ans.Main"
```

The Launcher Window appears with three buttons:

| Button | Purpose |
|---|---|
| Start as Dealer | Add cars to inventory, accept or decline buyer interest, negotiate price |
| Start as Buyer | Search for cars, select a shortlist, negotiate price |
| View Broker Log | Monitor fixed fees and commissions in real time |


---


## Demo Walkthrough

This walkthrough demonstrates a complete manual negotiation between one dealer and one
buyer. Use the values below for a reproducible result.

### Step 1 — Register a Car (Dealer)

1. Click **Start as Dealer**
2. In the **Listings** tab, fill in the form:

   | Field | Value |
   |---|---|
   | Car ID | `DA1-001` |
   | Make | `Toyota` |
   | Model | `Camry` |
   | Year | `2020` |
   | Mileage (km) | `45000` |
   | Colour | `Silver` |
   | Condition | `Good` |
   | Retail Price (RM) | `55000` |
   | Floor Price (RM, private) | `48000` |

3. Click **Add Car** — the row appears in the table above
4. Click **Register Listings with KA**

> Floor price is private to the dealer and is never transmitted to the buyer or broker.
> See `docs/methodology.md` — Information Asymmetry.


### Step 2 — Search and Shortlist (Buyer)

1. Click **Start as Buyer**
2. In the **Search** tab, fill in the form:

   | Field | Value |
   |---|---|
   | Model | `Camry` |
   | Make (preferred) | `Toyota` |
   | Condition | `Good` |
   | Year (min) | `2015` |
   | Year (max) | `2025` |
   | Max Mileage (km) | `100000` |
   | Max Budget (RM) | `53000` |
   | First Offer (RM) | `49000` |
   | Reserve Price (RM, private) | `51000` |

3. Click **Search** — the Toyota Camry from DA1 appears in the results table
4. Check the **Select** checkbox on that row and set **Rank** to `1`
5. Click **Send Shortlist to KA**

> Reserve price is private to the buyer. `Max Budget` is sent to the broker for matching
> only — it is not the buyer's negotiation limit.


### Step 3 — Accept Buyer Interest (Dealer)

1. The Dealer's **Negotiation** tab enables automatically
2. Phase A shows the buyer's identity and first offer (RM49,000)
3. Since RM49,000 ≥ floor price RM48,000, click **Accept**
4. AID exchange occurs; the broker charges the fixed fee (RM500) to the dealer


### Step 4 — Negotiate (Dealer and Buyer)

The negotiation proceeds directly between dealer and buyer. The broker has stepped back.

1. Dealer: Phase B shows. DA opens at retail price RM55,000 (sent automatically as CFP)
2. Buyer: **Negotiation** tab enables. Offer history shows RM55,000 (round 0)
3. Buyer: type `51000` in the counter-offer field → click **Counter**
4. Dealer: offer history updates — RM51,000 received (round 1)
5. Dealer: since RM51,000 ≥ floor RM48,000, click **Accept Deal**

Deal closes at RM51,000. Commission (2% = RM1,020) is charged to the dealer.


### Step 5 — View Broker Log

1. From the Launcher, click **View Broker Log**
2. The table shows two entries:
   - Fixed fee: RM500 (charged when DA accepted buyer interest)
   - Commission: RM1,020 (charged on deal closure)


---


## Port Conflict

If you see `Cannot bind server socket to localhost port 1099`, a previous JADE instance
is still holding the port. Kill it, then retry.

**Windows (PowerShell):**
```powershell
$proc = Get-NetTCPConnection -LocalPort 1099 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($proc) { Stop-Process -Id $proc.OwningProcess -Force }
```

**Linux / macOS:**
```bash
lsof -ti:1099 | xargs kill -9
```


---


## Troubleshooting

| Problem | Solution |
|---|---|
| `Missing artifact com.tilab.jade:jade:jar:4.6.0` | Run the `install:install-file` command in First-time Setup |
| `Cannot bind server socket to localhost port 1099` | Kill the process holding port 1099 — see Port Conflict above |
| `Unknown lifecycle phase ".mainClass=ans.Main"` | Quote the `-D` argument: `"-Dexec.mainClass=ans.Main"` (PowerShell requirement) |
| Negotiation tab does not appear for Buyer | Ensure the Dealer has registered listings before the Buyer clicks Search |
| Broker Log is empty | Click View Broker Log only after a negotiation has started; events populate in real time |
| GUI buttons unresponsive after launch | JADE takes ~1 second to initialise; wait briefly before clicking |


---


## Project Structure

See `docs/technical_design.md` for the full annotated project structure and class-level
responsibilities.


---


## Configuration

Edit `src/main/resources/config.properties` to adjust system parameters. Restart the
application for changes to take effect.

```properties
broker.fixedFee=500.00              # Fixed fee per connected negotiation (RM)
broker.commissionRate=0.02          # Commission on deal closure (2%)
negotiation.maxRounds=20            # Hard round limit per negotiation
negotiation.budgetTolerance=1.25    # KA filters listings up to 125% of buyer's max budget
gui.accentColour=#1E3A5F            # Button and tab accent colour (hex)
```

See `docs/technical_design.md` — Configuration section for the full parameter reference.
