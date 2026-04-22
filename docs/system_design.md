# System Design

## Glossary
- **BA** — Buyer Agent
- **DA** — Dealer Agent
- **KA** — Broker Agent
- **AID** — Agent Identifier (JADE's unique network address for each agent)
- **Floor price** — DA's private minimum price per car. DA will never sell below this.
- **Reserve price** — BA's private maximum budget. BA will never pay above this.
- **Retail price** — DA's public asking price. The opening number in negotiation.
- **First offer** — BA's opening bid. Low and aggressive. Sent to KA as part of the shortlist.
- **Round** — One exchange of offers between DA and BA. Each time one party sends a price to the other, that counts as one round.

---

## Roadmap
1. Brainstorm all cases and edge cases
2. Design the system ← current stage
3. Implement Basic v1 (consult Dr Lee once done)
4. Implement Basic v2 (consult Dr Lee once done)
5. Extension 1 (consult Dr Lee once done)
6. Finish report

---

## To-Do List
- Setup repo and docs (Law)
- Prepping report (Jon)
- Brainstorm cases and edge cases (All)
- Design negotiation system (All)

---

## Key Design Decisions (Locked In)

| Decision                   | Agreed                                                                                                                                                                      |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Framework                  | JADE                                                                                                                                                                        |
| Language                   | Java                                                                                                                                                                        |
| Negotiation is price only  | Yes : other attributes (model, year, etc.) are for display and information only                                                                                             |
| Negotiation strategy       | worry later for v2                                                                                                                                                          |
| v1 negotiation             | Manual : human operates BA via GUI, reads offers, types counters                                                                                                            |
| v2 negotiation             | Automated : BA negotiates on its own given first offer + reserve price                                                                                                      |
| Extension 1                | Concurrent negotiations (multiple dealers at the same time)                                                                                                                 |
| KA facilitation            | KA gives DA the AID of BA and vice versa, then steps back                                                                                                                   |
| Fixed fee                  | Charged each time KA connects a DA–BA pair, regardless of deal outcome                                                                                                      |
| Commission                 | Charged only when a deal successfully closes                                                                                                                                |
| Floor price                | Per car : DA sets a different minimum for each car in its inventory                                                                                                         |
| DA listings                | Fixed on startup : DA registers all listings when it boots up                                                                                                               |
| KA budget filter           | Exclude listings above ~120–130% of BA's reserve price                                                                                                                      |
| Round limit                | Round-based, default 20 rounds : justification is below                                                                                                                     |
| Shortlist ranking          | Human ranks the shortlist manually via GUI in v1                                                                                                                            |
| v2 shortlisting            | BA shortlists up to 3 dealers; KA contacts them sequentially; next dealer only contacted if previous deal fails                                                             |
| DA position in shortlist   | DA is never told whether it is BA's first, second, or third choice                                                                                                          |
| KA re-facilitation         | If a deal fails or breaks down, KA steps back in to connect BA with the next dealer in the shortlist; each new connection incurs a new fixed fee                            |
| BA counter-offer direction | System does not prevent BA from raising their own offer (this happens in real life); the only hard constraint is BA never exceeds her own reserve price, enforced privately |
| GUI                        | Custom Java Swing, JADE's default platform window stays in the background; all negotiation screens are built from scratch                                                   |
| KA window                  | Log only : shows connections made, fees recorded, commissions earned, and deal outcomes; no live negotiation state to reduce complexity                                     |
| Machine setup              | DA and BA run on the same machine with separate windows to simulate different parties                                                                                       |

---

## Why Other Designs Were Ruled Out
**Why not a real-time clock timer as the round limit?** 
- In v1 a human is typing. A clock timer would unfairly pressure the human and end deals if they step away briefly. Round-based is fairer because the limit only advances when an actual offer is made. 
- In v2 the automated agent responds immediately each round so a clock adds no value. Round-based also maps directly to the Faratin concession formula from lectures which uses rounds as its core variable.

**Why not resource-based as the primary limit?** 
- Resource-based means the limit shifts depending on how many agents are currently running. This makes the round limit unpredictable across sessions, harder to test, and harder to explain in the report. It adds complexity without benefiting the core scope.

**Why not shortlist only 1 dealer in v2?** 
- If that one dealer rejects BA or the deal fails, BA has no fallback and must restart the entire process from scratch, re-search, re-shortlist, re-connect. Inefficient and unrealistic. The shortlisting step already exists in Task 1 so using it properly in v2 costs nothing extra.

**Why not shortlist 3, automate only with first dealer that contacts BA?**
- DA2 and DA3 are already notified and waiting while BA negotiates with DA1. They sit idle with no explanation. Handling that idle state adds complexity with no benefit over shortlist 3.

**Why not concurrent for v2?** 
- The assignment explicitly states v2 assumes a single negotiation with one dealer. Concurrent means multiple negotiations simultaneously, which the assignemnt reserves for Extension 1. v2 design currently is sequential, one at a time, so it stays within v2 scope.

---

## Round Limit — Reasoning and Justification
**The formula context**
The Faratin, Sierra, and Jennings (1998) concession formula uses rounds as its core time variable:

```
factor = ( t / T ) ^ ( 1 / α )
```

Where t is the current round and T is the maximum rounds (the deadline). The shape of the concession curve entirely depends on T. Faratin et al. treat T as a configurable parameter that the system designer sets based on the negotiation context — the original paper does not prescribe a fixed number. This is important: the choice of T is a deliberate design decision, not an arbitrary one.

**Why T being configurable matters**
If T is too small, agents barely have time to move toward each other and the system terminates deals prematurely. If T is too large, there is insufficient pressure on either party to concede and a Boulware agent would rationally sit near its opening offer for most of the session. The deadline must be large enough to allow meaningful concession movement but small enough to create genuine negotiation pressure.

**Why 20?**
20 rounds is chosen based on the following reasoning:
- It is large enough that a human in v1 does not feel rushed, a person typing offers across 20 exchanges has sufficient time to read, think, and respond without artificial pressure. It is small enough that in v2 the automated strategy has real incentive to concede at round 10 (halfway), a Boulware agent with α=0.5 has only moved a small fraction of its range, which creates visible negotiation dynamics in the report's analysis. It gives the Faratin formula enough resolution to produce a clear, visible concession curve when graphed therefore this is directly relevant to the report's critical analysis section. 
- The GENIUS platform (the standard academic testbed for negotiation agents, mentioned in W3 lecture notes) treats round limits as configurable per experiment, confirming that 20 is a legitimate designer choice rather than a violation of any standard.

The round limit will be configurable via the GUI so it can be adjusted during testing and demonstration without changing code.

**References supporting this design**
- Faratin, P., Sierra, C., and Jennings, N. R. (1998). Negotiation Decision Functions for Autonomous Agents. International Journal of Robotics and Autonomous Systems, 24(3–4), 159–182.
- Fatima, S., Wooldridge, M., and Jennings, N. R. Multi-Issue Negotiation with Deadlines : establishes that deadlines are an essential element of negotiation since it cannot go on indefinitely, and that agent behaviour with deadlines differs significantly from behaviour without them.
- GENIUS (General Environment for Negotiation with Intelligent multi-purpose Usage Simulation) : Java-based academic negotiation testbed used in the International Automated Negotiation Agents Competition (ANAC); treats round limits as a configurable experiment parameter.

---

## System Flow (v1 End to End)
```
1. DA boots up
        → registers all car listings with KA
        → floor price per car stored privately inside DA only, never sent anywhere

2. BA boots up
        → enters car requirements + first offer via GUI
        → sends requirements to KA

3. KA filters DA listings against BA's requirements
        → hard cutoffs applied first (see Matching Criteria below)
        → soft preferences used for ranking
        → budget filter: exclude listings above ~120–130% (Let's go with 125%) of BA's reserve price
        → sends matching listings back to BA

4. BA reviews matches via GUI
        → selects up to 3 dealers to negotiate with
        → human ranks the shortlist manually in the order they prefer
        → sends shortlist + first offer per car back to KA

5. KA contacts DA1 only (first in BA's ranked shortlist)
        → forwards BA's interest + first offer to DA1
        → DA2 and DA3 are held back, they do not know about BA yet

6. DA1 reviews BA's first offer
        → if first offer is BELOW floor price → reject immediately, no negotiation
        → if first offer is at or above floor price → eligible to engage
        → DA1 tells KA whether it accepts or rejects BA

7a. If DA1 rejects BA
        → KA moves to DA2 and repeats from step 5
        → if all 3 dealers reject BA → BA notified: no dealer chose to engage
        → each new connection attempt incurs a new fixed fee for KA

7b. If DA1 accepts BA
        → KA exchanges AIDs between DA1 and BA
        → KA records fixed fee for this connection
        → KA steps back

8. DA1 contacts BA directly
        → manual negotiation begins, price only
        → human on BA's side reads each offer and types a counter via GUI
        → BA can raise or lower counter-offer freely — system does not restrict direction
        → only hard constraint is BA never exceeds her own reserve price, enforced privately
        → each exchange counts as one round
        → round limit is 20 by default, configurable via GUI

9. Negotiation ends — one of the following happens:
        → deal reached (see Cases below)
        → round limit reached, no deal (see Cases below)
        → one party stops responding (see Cases below)

10. If deal reached
        → DA notifies KA
        → KA records commission

11. If deal fails for any reason
        → KA is notified
        → KA steps back in to facilitate the next dealer in BA's shortlist if any remain
        → repeat from step 5 with the next DA
        → if no dealers remain → BA notified: no deal could be reached
```

---

## Agent Design
### Broker Agent (KA) : 1 agent
- Stores AID of every DA and BA that registers
- Receives DA listings on startup
- Receives BA requirements + first offer
- Filters and matches listings to BA requirements
- Contacts dealers sequentially based on BA's ranked shortlist
- Exchanges AIDs between matched DA–BA pairs
- Steps back during negotiation — not involved in offer rounds
- Steps back in if a deal fails, to facilitate the next dealer in the shortlist
- Records a fixed fee per connection attempt (each time KA connects a new DA–BA pair)
- Records commission only when a deal successfully closes

### Dealer Agent (DA) : at least 3
**Private  (never transmitted to anyone):**
- Floor price per car

**Public listing fields sent to KA:**
- Make
- Model
- Year
- Mileage
- Colour
- Condition
- Retail price

### Buyer Agent (BA) : at least 5
**Private (never transmitted to anyone):**
- Reserve price

**Sent to KA:**
- First offer (opening bid)
- Car specifications (hard cutoffs + soft preferences below)

---

## BA Car Specifications (Matching Criteria)
**Hard cutoffs — listing excluded immediately if any of these fail:**
- Model (e.g. Camry) : a different model is a completely different car
- Year range (e.g. 2018–2022) : outside range means wrong generation or too old
- Mileage maximum (e.g. under 80k km) : above cap means too worn
- Condition (new / used) : fundamentally different purchase type

**Soft preferences — used for ranking only, not for exclusion:**
- Make / Brand (e.g. Toyota) : buyer may accept a similar brand if price is right
- Colour : preference only, not a dealbreaker

**Budget / Reserve Price filter (KA applies this):**
- KA does not cut strictly at reserve price because retail price is just the dealer's opening ask and negotiation brings it down
- KA excludes listings above ~120–130% of reserve price (too far out of range even with negotiation)
- Listings at or below ~120–130% of reserve are included as potential matches

---

## Negotiation Rules
- Price only — model, year, colour etc. play no role in negotiation; they are display and information only
- v1 is manual — human reads and types offers via GUI
- v2 is automated — BA negotiates autonomously (strategy designed during v2 phase)
- Floor price (DA) and reserve price (BA) are never revealed to the other party
- Round limit is 20 by default, configurable via GUI — when hit, negotiation ends automatically
- BA can raise or lower their counter-offer freely — system does not restrict direction; only hard constraint is BA's own reserve price enforced privately inside BA
- DA is never told whether it is BA's first, second, or third choice

---

## GUI Design
**Setup**
- DA and BA run on the same machine with separate windows to simulate different parties
- JADE's default platform window stays in the background — users do not interact with it during normal use
- All screens are built from scratch using custom Java Swing
- Design principle: white background, black text, one neutral accent colour (dark blue or slate) for buttons and highlights; tables for all list data; no clutter

**Main Launcher Window**
- Three buttons: Start as Dealer, Start as Buyer, View Broker Log
- Opens the corresponding window for each role

**DA Window — two tabs**
Listings tab:
- A table showing all registered cars (make, model, year, mileage, colour, condition, retail price)
- A form to add a car with fields for all listing attributes plus floor price (floor price visible to DA only, never shown elsewhere)

Negotiation tab:
- Hidden until a negotiation is active
- Shows incoming buyer interest and their first offer
- Accept or reject buttons to respond to KA
- Once negotiation starts: current round number, full offer history as a table, input field for next counter-offer, accept and walk away buttons

**BA Window — two tabs**
Search tab:
- Input fields for requirements: make, model, year range, mileage maximum, condition, colour, first offer, reserve price (reserve price visible to BA only, never shown elsewhere)
- Results table showing matched listings returned by KA
- Checkboxes to select up to 3 dealers; drag or number input to set ranking order
- Confirm button to send shortlist to KA

Negotiation tab:
- Hidden until a negotiation is active
- Shows current dealer offer, round number, and full offer history as a table
- Input field to type a counter-offer
- Accept, counter, and walk away buttons

**KA Window — log only**
- A single table showing all events in order: connections made, fixed fees recorded, deal outcomes, and commissions earned
- Read only — no interaction required

---

## Cases and Edge Cases
### Normal Outcomes

|Case|Situation|Outcome|
|---|---|---|
|Deal reached|DA and BA agree on a price within the round limit|Deal recorded; KA collects fixed fee + commission|
|Negotiation breakdown|Round limit reached with no agreement|No deal; KA notified; KA facilitates next dealer in BA's shortlist if any remain|

### Edge Cases

|Case|Situation|Outcome|
|---|---|---|
|No matching listings|KA finds no listings matching BA's hard criteria|BA notified: no matching cars currently available|
|DA rejects all buyers sequentially|All dealers in BA's shortlist reject BA before negotiation starts — either first offer is below floor price or DA chooses not to engage|BA notified: no dealer chose to engage|
|First offer below floor price|BA's first offer is already below DA's floor price|DA rejects immediately — no negotiation attempted; KA moves to next dealer|
|BA stops responding mid-negotiation|BA goes silent during offer rounds|Deal marked as failed; KA notified; KA facilitates next dealer in BA's shortlist if any remain|
|DA stops responding mid-negotiation|DA goes silent during offer rounds|Deal marked as failed; KA notified; BA freed up|

---
