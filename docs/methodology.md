# Methodology

This document records the design choices made for the Automated Negotiation System and the justifications for each. It is intended to defend implementation decisions against academic review and to serve as the primary reference for writing the project report.

Sections 1–6 cover foundational and v1 decisions. Sections 7–9 cover v2 additions. Section 10 covers Extension 1.

---

## 1. Technology Stack

### Agent Framework: JADE 4.6.0

**Chosen because:**
- Implements the FIPA standard for Agent Communication Language (ACL), which is the
  reference standard in multi-agent systems research
- Provides built-in Agent Management System (AMS), Directory Facilitator (DF), agent
  lifecycle management, behaviour scheduling, and MessageTemplate filtering out of the box
- Reduces the system to protocol design; infrastructure is not re-invented

**Alternatives rejected:**

| Alternative | Reason Rejected |
|---|---|
| Akka (Java actor framework) | Does not implement FIPA ACL; no standardised performative vocabulary (INFORM, PROPOSE, ACCEPT_PROPOSAL); protocol enforcement is entirely manual |
| Custom HTTP/REST agents | No FIPA contract; message routing and performative semantics must be hand-built; substantially more code for the same result |
| Repast / NetLogo | Simulation-oriented frameworks; not designed for interactive GUI-backed negotiation with real human input; unsuitable for v1 manual negotiation requirement |

---

### Language: Java 17 LTS

**Chosen because:**
- Long-Term Support release (September 2021 – September 2026); maximally stable build
  ecosystem (IDE support, Maven plugins, Stack Overflow coverage)
- Provides records, enhanced switch expressions, and sealed types — sufficient for this
  project's data models and behaviour state machines
- JADE 4.6.0 was released November 2022 and is validated against Java 17; it is the
  most recently confirmed combination

**Alternatives rejected:**

| Alternative | Reason Rejected |
|---|---|
| Java 21 | Introduces Virtual Threads (Project Loom) and structured concurrency — features with no benefit for this system's synchronous per-agent behaviour model; JADE 4.6.0 predates Java 21 and is not validated against it |
| Java 11 | Pre-records; less expressive data classes; further from active security patching at time of project setup |

---

### Serialisation: Gson 2.10.1

**Chosen because:**
- Zero-configuration POJO ↔ JSON serialisation; reflects private fields by default,
  matching the project's direct-field model classes
- ACL message bodies are small (under 50 fields); no performance concern
- No annotation overhead on model classes

**Alternatives rejected:**

| Alternative | Reason Rejected |
|---|---|
| Jackson | Requires `@JsonProperty` annotations for consistent field naming; adds dependency weight for no practical gain |
| FIPA SL (content language) | Verbose; minimal Java tooling; JSON achieves the same structured content with far less parsing complexity |

---

### GUI: Java Swing

**Chosen because:**
- Bundled with the JDK; no additional dependency
- Sufficient for a single-machine prototype with known EDT threading model
- Tight integration with JADE via `SwingUtilities.invokeLater()` dispatch from agent hooks

**Alternatives rejected:**

| Alternative | Reason Rejected |
|---|---|
| JavaFX | Not bundled since Java 11; requires separate dependency; FXML adds designer–developer split unwarranted for a prototype |
| Web UI (React + REST/WebSocket) | Introduces server layer, HTTP lifecycle, and cross-origin complexity; decouples GUI from the JADE container; testing and debugging overhead disproportionate to v1 scope |

---

### Build: Maven 3.9.x

**Chosen because:**
- Standard project structure (`src/main/java`, `src/test/java`); supported universally
  by university labs, CI environments, and common IDEs
- `exec:java` plugin runs agents without an IDE; `assembly:single` produces a
  self-contained fat jar for submission

**Alternative rejected:** Gradle — Groovy/Kotlin DSL adds complexity; XML-based Maven
POM is more transparent for academic review.

---

## 2. Interaction Protocol Design

### Three-Phase Custom FIPA ACL Protocol

The system implements a custom FIPA ACL protocol across three sequential phases. This is
permitted by the project specification: "interaction protocols can be FIPA predefined
(e.g. CNP, iterated CNP), nested, or newly specified."

**Phase 1 : Discovery:**
- DA sends inventory to KA (INFORM); BA sends requirements to KA (INFORM)
- KA matches by make, model, year, and budget tolerance (125% of BA's max budget),
  returns filtered listing set to BA (INFORM)
- *Rationale:* Centralised matching avoids BA broadcasting to all DAs; KA filters
  irrelevant listings before BA ever sees them, reducing total message traffic

**Phase 2 : Interest & AID Exchange:**
- BA sends ranked shortlist (up to 3 dealers) with first offers to KA (INFORM)
- KA forwards interest to each shortlisted DA sequentially in rank order (INFORM)
- DA accepts or declines; if accepted, KA charges the fixed fee and sends the
  AID exchange INFORM to both DA and BA
- *Rationale:* KA acts as a neutral intermediary; neither DA nor BA need to contact
  each other before both parties have consented to negotiate; this preserves
  information asymmetry (neither sees the other's private constraints before committing)

**Phase 3 : Direct Negotiation:**
- DA initiates at retail price (CFP); DA and BA exchange PROPOSE, ACCEPT_PROPOSAL,
  and REJECT_PROPOSAL messages directly
- KA charges commission (2%) on deal closure; informed via INFORM from the closing party
- *Rationale:* Once AIDs are exchanged, direct messaging reduces latency and removes KA
  as a message bottleneck; KA only needs the outcome, not every round

---

### Sequential Shortlist (not parallel)

KA contacts shortlisted dealers one at a time in BA's ranked order. If a dealer declines
or a negotiation fails, KA advances to the next dealer.

**Rationale:**
- Parallel negotiations per BA require concurrent `NegotiationState` tracking, message
  routing disambiguation (which dealer's PROPOSE should BA accept?), and a risk of BA
  being simultaneously committed to multiple deals
- Sequential is deterministic, testable, and sufficient to demonstrate the full protocol
  in v1
- Extension 1 (selected research component) extends this to concurrent negotiations; the
  sequential design is an explicit architectural baseline, not a limitation

---

### Message Ontology Tagging

All ACL message bodies include a JSON-level `ontology` field in addition to the FIPA
performative. This disambiguates messages that share the same `conversationId`.

**Rationale:** Phase 2 (KA → DA buyer interest INFORM) and Phase 2B (KA → DA AID
exchange INFORM) both arrive as INFORM from KA with `conversationId = carId`. Without
ontology tagging, JADE's `MessageTemplate` cannot distinguish them by performative alone.

**Alternative rejected:** Separate conversation IDs per phase — would require KA to
maintain a phase-to-conversationId mapping per ongoing negotiation, adding state
complexity to an already stateful broker.

---

### Price-Only Negotiation

Negotiation is over a single numeric attribute (price in RM). Car attributes (make,
model, year, mileage, colour, condition) are matching criteria only and are not
renegotiated.

**Rationale:** This satisfies the v1 requirement ("manual negotiation between a dealer
and a buyer"). Multi-attribute negotiation is the scope of Extension 2, which is not the
selected extension. Keeping v1 to price-only reduces state complexity and makes the
negotiation outcome unambiguous.

---

## 3. Agent Behaviour Design

### Protected Hook + Public API Pattern

Each agent exposes two complementary interfaces:

- **Protected hooks** (`onBuyerInterestReceived`, `onNegotiationOfferReceived`,
  `onNegotiationEnded`, etc.): fired by the behaviour when a protocol event occurs;
  base class logs to console; subclass (GUI window agent or test agent) overrides to
  drive the UI or make an automated decision
- **Public response methods** (`acceptBuyerInterest`, `submitOffer`, `acceptDeal`,
  `walkAway`): called by button handlers (GUI) or overriding test agents; send the
  ACL response and advance the state machine

**Rationale:**
- Decouples protocol logic (behaviours) from decision logic (GUI or automated strategy)
  without modifying the base agent class
- Supports both v1 manual (GUI subclass) and v2 automated (strategy subclass) without
  any change to core agent code
- Autonomous concession strategies (v2) are injected by overriding `onNegotiationOfferReceived`
  and calling `submitOffer()` — the hook provides current offer; the method sends the response

**Alternative rejected — JADE O2A (Object-to-Agent) messaging:** O2A requires explicit
queue management on the agent side and blocks on `getO2AObject()`; it is designed for
cross-thread object passing in long-running behaviours, not for the synchronous
decision-response pattern needed here.

---

### Phase 2 State Machine (InterestPhase)

`ReceiveBuyerInterestBehaviour` is governed by a three-state machine on the outer
`DealerAgent`:

```
WAITING_FOR_INTEREST
  → (KA forwards buyer interest) → WAITING_FOR_HUMAN_DECISION
  → (accept/decline called)      → WAITING_FOR_AID  |  WAITING_FOR_INTEREST
  → (AID exchange arrives)       → WAITING_FOR_INTEREST
```

**Rationale:** Both the buyer-interest INFORM (Phase 2A) and the AID-exchange INFORM
(Phase 2B) arrive as INFORM from KA with `conversationId = carId`. Without a state
machine, the behaviour cannot determine which payload to expect from the next message.

**Synchronous-path guard:** Test agents call `acceptBuyerInterest()` synchronously
inside the `onBuyerInterestReceived()` hook. After the hook returns, the behaviour checks
whether the phase was already advanced before setting `WAITING_FOR_HUMAN_DECISION`. If
already advanced, it does not block — preventing a deadlock where the behaviour waits for
a human decision that will never arrive.

---

### Swing EDT Safety

All JADE hook callbacks dispatch Swing updates via `SwingUtilities.invokeLater()`.

**Rationale:** JADE behaviours execute on JADE's internal thread pool, not the Swing
Event Dispatch Thread (EDT). Direct Swing component mutation from a non-EDT thread causes
unpredictable rendering corruption or `IllegalStateException`. JADE's `send()` is
documented as thread-safe, so EDT button handlers can call agent public methods directly
without additional synchronisation.

---

### Static-Link Window↔Agent Coupling (v1 only)

A static field on each window class (`pendingWindow`) is set immediately before
`container.createNewAgent()` is called. The agent's `setup()` reads and clears it.

**Rationale:** JADE constructs agents by class name string; no constructor arguments can
carry a window reference. The static field is the minimal-complexity solution for passing
the reference during the brief construction window.

**Acknowledged limitation:** This is not safe for concurrent window creation (two windows
opened simultaneously could overwrite each other's static field). Extension 1
(concurrent negotiations) will replace this with an agent registry
(`Map<String, AgentWindow>`). This is a deliberate v1 constraint, not an oversight.

---

## 4. Information Asymmetry

Private constraints are never serialised into any ACL message:

| Constraint | Holder | Enforcement Point |
|---|---|---|
| Floor price (minimum sale price per car) | DA only | DA checks offer against floor locally before responding to KA; never transmitted |
| Reserve price (maximum buy price) | BA only | BA checks final deal against reserve locally before sending ACCEPT_PROPOSAL; never transmitted |

**Rationale:** Information asymmetry is a foundational property of negotiation theory.
If either party's limit is known to the counterpart, rational play collapses to bidding
exactly at the boundary, eliminating bargaining surplus. FIPA ACL has no encrypted-field
construct; the only enforcement mechanism is to never include the value in any message.

Note: `maxBudget` (transmitted to KA as a matching filter) is distinct from
`reservePrice` (private negotiation limit). Matching on budget is necessary for KA to
filter listings; the reserve is the buyer's true walk-away point and remains private.

---

## 5. Test Strategy

### Integration-First

`NegotiationFlowTest` runs the complete three-phase protocol in a single JVM with no
networking or GUI:
- All three agents (KA, DA1, BA1) in one JADE container
- Verifies: inventory registration → search → shortlist → interest → AID exchange → CFP
  → counter-offer → deal closure → commission charge
- Regression baseline: run before every GUI launch to confirm the core protocol has not
  regressed

**Why integration-first (not unit-first):** The primary complexity of this system is the
message sequence and state transitions between agents, not individual agent computations.
Unit tests of isolated logic (floor price check, offer arithmetic) are trivial. Protocol
sequencing errors — a misrouted message, a missed `restart()` call, a wrong phase
transition — are the real failure modes and are only detectable at integration level.

**Why not GUI automation testing:** Swing testing frameworks (e.g. FEST, AssertJ Swing)
require accessibility hooks and are fragile across JRE versions. The GUI is a thin view
layer over the proven agent protocol; manual checklist verification is sufficient for v1.

**Why not property or fuzz testing:** The negotiation protocol is deterministic and
finite (bounded by `negotiation.maxRounds = 20`). Property testing is warranted for v2's
autonomous concession strategies where the parameter space is large and edge cases in
concession curves need systematic exploration.

---

## 6. Configuration Externalisation

All tunable system values are stored in `src/main/resources/config.properties` and
loaded once at startup via `Config.java`.

```properties
broker.fixedFee=500.00         # Fixed fee (RM) charged to DA on each connected negotiation
broker.commissionRate=0.02     # Commission rate (2%) charged to DA on deal closure
negotiation.maxRounds=20       # Hard round limit per negotiation
negotiation.budgetTolerance=1.25  # KA includes listings up to 125% of BA's max budget
gui.accentColour=#1E3A5F       # Accent colour applied across all Swing windows
```

**Rationale:**
- Fee structures and round limits can be adjusted for different demonstration scenarios
  without recompilation
- Version-controlled alongside source; values are self-documenting
- Good practice for any configurable system: no magic numbers in agent code

**Alternatives rejected:**
- *Hardcoded values:* inflexible; changing any parameter requires a full rebuild
- *Command-line arguments:* ephemeral and not self-documenting; values are lost between
  runs unless scripted
- *Database config table:* introduces a persistence dependency unwarranted for a
  single-machine prototype

---

## 7. Concession Strategy — Faratin Time-Based Formula (v2)

### Choice: Faratin et al. (1998) Time-Based Concession

**Chosen because:**
- Directly referenced in course lecture materials; using it demonstrates engagement with
  the academic content of the unit
- Round-based: maps cleanly to the system's existing round-limit design; `t` and `T`
  are already tracked in `NegotiationState`
- Single parameter `α` controls the full concession personality (Conceder / Linear /
  Boulware) without requiring multiple tuning values
- The resulting concession curves are easy to graph and analyse in the project report,
  which requires a critical analysis section

**Formula:**
```
factor(t, T, α) = ( t / T ) ^ ( 1 / α )

DA offer at round t = floorPrice + (retailPrice − floorPrice) × (1 − factor)
BA offer at round t = firstOffer + (reservePrice − firstOffer) × factor
```

**α parameter design decision:**
- DA default α = 0.7 (Conceder-leaning): per Dr. Lee's directive that the dealer should
  push to shorten negotiation time. A conceder-leaning DA makes larger early concessions,
  signalling deal attractiveness and creating incentive for BA to accept sooner.
- BA default α = 1.0 (Linear): neutral starting point; configurable via GUI before each
  negotiation to enable controlled experiments.
- Both α values are locked during a negotiation once it starts, and are configurable
  before each negotiation via the GUI.

**Alternatives rejected:**

| Alternative | Reason Rejected |
|---|---|
| Fixed step concession (offer − constant) | Does not account for deadline pressure; agent behaves identically at round 1 and round 19; no urgency signal |
| Random concession | Non-reproducible; cannot produce clean concession curves for report analysis |
| Multi-issue utility function (e.g. Nash bargaining) | Requires multi-attribute negotiation, which is Extension 2 (not chosen); adds complexity with no benefit for price-only negotiation |

**References:**
- Faratin, P., Sierra, C., and Jennings, N. R. (1998). Negotiation Decision Functions for
  Autonomous Agents. *International Journal of Robotics and Autonomous Systems*, 24(3–4), 159–182.

---

## 8. Prediction Algorithm — Linear Regression on Offer History (v2)

### Choice: Real-Time Linear Regression on Opponent Offer Sequence

**Chosen because:**
- The project report explicitly requires "Implemented prediction algorithm/s"; regression
  directly satisfies this requirement
- Requires zero training data: the regressor fits in real time using only the offers
  exchanged in the current negotiation
- Interpretable: slope and predicted limit are explainable in plain language and
  graphable alongside the concession curves in the report
- Lightweight: computable in 6 lines of arithmetic (slope = Σ(xi−x̄)(yi−ȳ) / Σ(xi−x̄)²,
  intercept = ȳ − slope×x̄); no library dependency required

**How it works:**
- `x` = round number, `y` = opponent offer at that round
- Fits a least-squares line `y = a + bx` to the opponent's offer history
- Extrapolates to round `T` to estimate the opponent's likely limit at the deadline
- Used to enhance the accept condition: if the predicted opponent limit has crossed the
  agent's own current Faratin offer, accept now rather than waiting for further rounds

**Reliability gates (all three must pass before prediction is used):**

| Gate | Parameter | Default | Purpose |
|---|---|---|---|
| Minimum data | `negotiation.regressionMinPoints` | 3 | Too few points makes regression meaningless |
| R² threshold | `negotiation.regressionMinR2` | 0.80 | Low R² means noisy/non-linear data; prediction unreliable |
| Slope threshold | `negotiation.boulwareSlopeThreshold` | 50.0 RM/round | Near-zero slope means opponent is Boulware; regression mistakes "not moving yet" for "nearly at limit" |

**Boulware bias handling:**
A Boulware opponent holds near their opening offer for most rounds. Naive regression on
their flat offer sequence would predict their current offer as their limit, causing the
observing agent to over-concede. When the slope gate fails (Boulware detected), the
prediction is suppressed entirely and the agent falls back to the base Faratin strategy.
This is the correct rational response: hold position and wait for the opponent's
late-round movement.

**Bounds clamping:**
The predicted value is clamped to the physically possible range (opponent's first
observed offer as the outer bound) to prevent nonsensical extrapolations.

**No-overlap early exit:**
When both agents' regression predictions are reliable and the predicted DA limit exceeds
the predicted BA limit, a deal is mathematically impossible. The agent walks away early
rather than grinding to the round limit. This is demonstrable in the report's critical
analysis section.

**Alternatives rejected:**

| Alternative | Reason Rejected |
|---|---|
| Machine learning (neural network, random forest) | Requires labelled historical training data that does not exist prior to deployment; training pipeline adds infrastructure complexity disproportionate to scope |
| Moving average | Smooths noise but does not extrapolate; cannot predict where offers are heading, only where they have been |
| No prediction (Faratin base only) | Satisfies the strategy requirement but not the prediction requirement; misses the opportunity to demonstrate analytical capability |

---

## 9. Dealer Shortening Strategy (v2)

### Choice: Conceder-Leaning α with Regression-Driven Early Commitment

Per Dr. Lee's directive, the dealer agent is designed to push toward faster deal closure
rather than passively conceding toward the deadline. Three mechanisms implement this:

**Mechanism 1 — Conceder-leaning α (default 0.7):**
A conceder DA makes larger concessions in early rounds. From BA's perspective, the
dealer is visibly moving, which creates an incentive to accept before the best offer
passes. This is the primary mechanism and is controlled entirely by the α parameter.

**Mechanism 2 — Regression-driven final commitment:**
When the regression predictor estimates that BA's ceiling is within
`negotiation.bestOfferThreshold` (default RM500) of DA's current Faratin offer,
DA makes one aggressive final move to just above the predicted BA ceiling rather than
continuing incremental concessions. This collapses the remaining gap in one step.

**Mechanism 3 — Early walk-away on predicted no-overlap:**
If regression predicts BA will never reach DA's floor price, DA exits early. This saves
pointless rounds when a deal is impossible.

**Report framing:**
In the report, DA's regression is framed as a *negotiation strategy* (not the prediction
algorithm). BA's regression is the *prediction algorithm* satisfying the report
requirement. Both use the same `RegressionPredictor` class — the distinction is framing,
not implementation.

---

## 10. Extension 1 — Concurrent Negotiations

### Choice: Concurrent over Multi-Attribute Negotiation

**Extension 1 chosen because:**
- The team's engineering strength is in the multi-agent protocol and concurrency design,
  not in utility function modelling or preference elicitation
- Concurrent negotiations produce measurable outcomes (which deal closes first, how many
  rounds, fee distribution) that are directly comparable and reportable
- The concurrency model extends the existing v2 design cleanly: same Faratin formula,
  same regression predictor, just multiple independent instances per BA

**Multi-attribute (Extension 2) rejected because:**
- Requires a utility function over car attributes (make, year, mileage, colour, condition)
  whose weights are subjective and hard to validate academically
- Adds preference elicitation complexity to the GUI that is disproportionate to the
  time available before submission

### Why No Machine Learning (repeated for extension context)

The team considered ML prediction as an alternative to linear regression, particularly
for predicting opponent strategy type (Boulware vs Conceder) from early offer patterns.
This was declined for two reasons:

1. The selected extension (concurrent negotiations) introduces substantial engineering
   complexity in protocol design and concurrent state management. This is the primary
   research contribution. Adding an ML training pipeline alongside it would split focus
   without adding proportionate academic value.

2. The concurrent negotiation scenario itself eliminates a key motivation for ML: in
   concurrent mode, BA can simply accept the best deal that closes first rather than
   needing to predict which dealer will offer the best outcome. The competitive pressure
   of concurrent negotiations is a stronger mechanism than prediction for obtaining a
   good deal.

**Justification for report:**
*"Linear regression on the live offer history is a principled statistical prediction
method that achieves the prediction requirement without a training pipeline. The
research contribution of this project is the concurrent negotiation protocol design,
which is a different dimension of complexity from predictive modelling."*

### Concurrent Design Key Decisions

| Decision | Agreed |
|---|---|
| BA runs up to 3 simultaneous negotiations | Matches the shortlist size already established in v1/v2 |
| KA contacts all shortlisted DAs at once | Replaces the sequential "advance on failure" pattern from v2 |
| Each negotiation is independent | Separate NegotiationState and RegressionPredictor per dealer |
| Accept one → walk away from all others immediately | Prevents BA from being committed to multiple deals simultaneously |
| Fixed fee charged per connected DA | Up to 3 fees possible; consistent with the fee model and explicitly noted in report |
| AgentRegistry replaces static window coupling | Static field is not safe for concurrent window creation; registry keyed by agent name |
| JADE serialisation handles thread safety | JADE serialises behaviour execution per agent; no additional synchronisation needed inside agent state maps |
