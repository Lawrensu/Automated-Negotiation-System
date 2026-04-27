# Methodology

This document records the design choices made for the Automated Negotiation System and the
justifications for each. It is intended to defend implementation decisions against academic
review and to guide the team in v2 and extension development.

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
