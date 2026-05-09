# System Design

---

## Documentation Structure

| Document | Contents |
|---|---|
| [v1.md](v1.md) | v1 system flow, agent design, matching criteria, negotiation rules, GUI design, v1 edge cases |
| [v2.md](v2.md) | v2 system flow, concession strategy, regression prediction, DA shortening, v2 edge cases, simulation parameters |
| [extension.md](extension.md) | Extension 1 concurrent negotiation design, AgentRegistry, walk-away protocol, concurrent edge cases |
| [technical_design.md](technical_design.md) | Technology stack, data models, ACL message protocol, agent behaviour design, strategy implementation, GUI implementation, build instructions |

---

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
- **α (alpha)** — Concession personality parameter in the Faratin formula. Controls the shape of the concession curve.
- **Boulware** — Negotiation style where an agent holds near its opening offer for most rounds and only concedes at the end (α >> 1).
- **Conceder** — Negotiation style where an agent makes large concessions early then slows (α < 1).

---

## Roadmap

1. Brainstorm all cases and edge cases ✅
2. Design the system ✅
3. Implement Basic v1 ✅
4. Design and implement Basic v2 ← **current stage**
5. Design and implement Extension 1
6. Finish report

---

## To-Do List

- Setup repo and docs ✅ (Law)
- Prepping report (Jon)
- Brainstorm cases and edge cases ✅ (All)
- Design v1 ✅ (All)
- Implement v1 ✅
- Design v2 ✅ (All)
- Implement v2 ← current
- Design and implement Extension 1
- Report write-up

---

## Key Design Decisions (Locked In)

| Decision | Agreed |
|---|---|
| Framework | JADE |
| Language | Java |
| Negotiation is price only | Yes: other attributes (model, year, etc.) are for display and information only |
| Negotiation strategy | Faratin time-based concession formula; α configurable per agent |
| Prediction algorithm | Linear regression on live opponent offer history; gated by R², slope threshold, min data points |
| v1 negotiation | Manual: human operates BA and DA via GUI |
| v2 negotiation | Automated: BA and DA negotiate autonomously; BA is the required automation; DA automated to enable simulation and report analysis |
| Extension | Concurrent negotiations (multiple dealers at the same time) |
| No machine learning | Rejected: no training data; Extension 1 is the research contribution; regression satisfies the prediction requirement |
| KA facilitation | KA gives DA the AID of BA and vice versa, then steps back |
| Fixed fee | Charged each time KA connects a DA–BA pair, regardless of deal outcome |
| Commission | Charged only when a deal successfully closes |
| Floor price | Per car: DA sets a different minimum for each car in its inventory |
| DA listings | Fixed on startup: DA registers all listings when it boots up |
| KA budget filter | Exclude listings above 125% of BA's reserve price |
| Round limit | Round-based, default 20 rounds (see Round Limit section below) |
| Shortlist ranking | Human ranks the shortlist manually via GUI in v1; automated ranking by match quality in v2 |
| v2 shortlisting | BA shortlists up to 3 dealers; v2 sequential (one at a time); Extension 1 concurrent (all at once) |
| DA position in shortlist | DA is never told whether it is BA's first, second, or third choice |
| KA re-facilitation (v2 sequential) | If a deal fails, KA steps back in to connect BA with the next dealer; each new connection incurs a new fixed fee |
| DA α default | 0.7 (conceder-leaning): per Dr. Lee's directive to push toward faster deal closure |
| BA α default | 1.0 (linear): neutral balanced concession |
| GUI | Custom Java Swing; JADE's default platform window stays in background |
| KA window | Log only: connections made, fees recorded, commissions earned, deal outcomes |
| Machine setup | DA and BA run on the same machine with separate windows to simulate different parties |

---

## Why Other Designs Were Ruled Out

**Why not a real-time clock timer as the round limit?**
- In v1 a human is typing. A clock timer would unfairly pressure the human and end deals if they step away briefly. Round-based is fairer because the limit only advances when an actual offer is made.
- In v2 the automated agent responds immediately each round so a clock adds no value. Round-based also maps directly to the Faratin concession formula which uses rounds as its core variable.

**Why not resource-based as the primary limit?**
- Resource-based means the limit shifts depending on how many agents are currently running. This makes the round limit unpredictable across sessions, harder to test, and harder to explain in the report.

**Why not shortlist only 1 dealer in v2?**
- If that one dealer rejects BA or the deal fails, BA has no fallback and must restart the entire process from scratch. The shortlisting step already exists in v1 so using it properly in v2 costs nothing extra.

**Why not concurrent for v2?**
- The assignment explicitly states v2 assumes a single negotiation with one dealer. Concurrent is reserved for Extension 1. v2 is sequential, one at a time.

**Why not machine learning for prediction?**
- ML requires labelled historical negotiation data, which does not exist for this system prior to deployment.
- Generating synthetic training data would undermine academic validity.
- Extension 1 (concurrent negotiations) is the research contribution; ML is a separate research track that conflicts with available time and scope.
- Linear regression on live offer history achieves the same goal — predicting the opponent's limit — in real time, from data that is naturally available during negotiation, with no training pipeline.

---

## Round Limit — Reasoning and Justification

The Faratin, Sierra, and Jennings (1998) concession formula uses rounds as its core time variable:

```
factor = ( t / T ) ^ ( 1 / α )
```

Where `t` is the current round and `T` is the maximum rounds (the deadline). The shape of the concession curve entirely depends on `T`.

**Why 20 rounds (default):**
- Large enough that a human in v1 does not feel rushed
- Small enough that in v2 the automated strategy has real incentive to concede at round 10 (halfway)
- Provides enough resolution for a clear, visible concession curve when graphed in the report
- The GENIUS platform (academic negotiation testbed) treats round limits as configurable per experiment, confirming 20 is a legitimate designer choice

The round limit is configurable via GUI so it can be adjusted for testing and demonstration without recompilation.

**References:**
- Faratin, P., Sierra, C., and Jennings, N. R. (1998). Negotiation Decision Functions for Autonomous Agents. *International Journal of Robotics and Autonomous Systems*, 24(3–4), 159–182.
- Fatima, S., Wooldridge, M., and Jennings, N. R. Multi-Issue Negotiation with Deadlines: establishes that deadlines are essential to negotiation and that agent behaviour with deadlines differs significantly from behaviour without them.
- GENIUS: Java-based academic negotiation testbed used in ANAC; treats round limits as a configurable experiment parameter.
