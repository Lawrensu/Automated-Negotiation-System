# System Design — Extension 1: Concurrent Negotiations

---

## What This Extension Does

In v2, the buyer agent negotiates sequentially — one dealer at a time. If a deal fails, KA steps in and connects BA with the next dealer in the shortlist.

Extension 1 changes this: **BA negotiates with all shortlisted dealers simultaneously**. Up to 3 active negotiations run in parallel. BA accepts the best deal that closes, then immediately withdraws from the remaining negotiations.

This is the selected research extension. The assignment reserves concurrent negotiations explicitly for this extension task.

---

## What Changes from v2

| Aspect | v2 (Sequential) | Extension 1 (Concurrent) |
|---|---|---|
| Active negotiations per BA | 1 at a time | Up to 3 simultaneously |
| KA shortlist behaviour | Contacts DA1 only; advances on failure | Contacts DA1, DA2, DA3 at the same time |
| BA NegotiationState | 1 instance | 1 instance per dealer, keyed by dealerAIDName |
| RegressionPredictor | 1 instance | 1 instance per active negotiation |
| Accept outcome | Done; KA notifies if needed | Accept one → walk away from all others immediately |
| Fixed fee | Charged once per connected negotiation | Charged per dealer connected; up to 3 fees possible |
| Window coupling | Static field (v1 pattern) | AgentRegistry map (replaces static field) |

---

## Extension 1 System Flow

```
Steps 1–4 identical to v2.
(DA registers → BA searches → KA matches → BA sends shortlist of up to 3 dealers)

5. KA contacts DA1, DA2, DA3 simultaneously (not sequentially)
        → forwards BA's interest + first offer to all three
        → each DA independently accepts or rejects

6. For each DA that accepts:
        → KA exchanges AIDs between that DA and BA
        → KA records a fixed fee for each connection
        → negotiation begins directly between that DA and BA

7. BA runs up to 3 simultaneous automated negotiations
        → each negotiation is fully independent
        → each has its own NegotiationState, RegressionPredictor, and offer history
        → Faratin formula and regression layer run independently per negotiation
        → negotiations are keyed by (carId + dealerAIDName) to prevent cross-talk

8. BA accepts a deal from whichever DA reaches the accept condition first
        → BA sends ACCEPT_PROPOSAL to that DA
        → BA immediately sends REJECT_PROPOSAL (walk away) to all other active DAs
        → BA notifies KA of the accepted deal

9. KA records commission on the accepted deal
        → fixed fees already recorded at step 6 are not refunded
        → DAs that were walked away from receive no commission charge
```

---

## Concurrent State Management

Each BA maintains a map of active negotiation states:

```
Map<String, NegotiationState>    activeNegotiations   ← key: dealerAIDName
Map<String, RegressionPredictor> regressionPredictors ← key: dealerAIDName
```

Each incoming DA offer is routed to the correct state by matching `conversationId` (carId) and sender AID. This is why the existing design uses `conversationId = carId` — it is the natural routing key that extends cleanly to concurrent use.

**Thread safety:** JADE behaviours execute on JADE's internal thread pool. In v2 (single negotiation), only one behaviour fires at a time per agent. In Extension 1, multiple `NegotiationBehaviour` instances may fire in close succession. The state maps must be accessed only from JADE behaviour methods (which JADE serialises per agent), so no additional synchronisation is needed inside the agent. The Swing EDT updates still go through `SwingUtilities.invokeLater()` as before.

---

## AgentRegistry — Replacing Static Window Coupling

The v1/v2 static field pattern (`DealerWindow.pendingWindow`) is not safe for concurrent window creation. Two windows opened at the same time could overwrite each other's static field.

Extension 1 replaces this with an `AgentRegistry`:

```java
// AgentRegistry.java (singleton or static map)
public class AgentRegistry {
    private static final Map<String, AgentWindow> windows = new ConcurrentHashMap<>();

    public static void register(String agentName, AgentWindow window) { ... }
    public static AgentWindow get(String agentName) { ... }
    public static void deregister(String agentName) { ... }
}
```

Before `container.createNewAgent()` is called, the window is registered by its intended agent name. The agent's `setup()` calls `AgentRegistry.get(agentName)` instead of reading a static field. After setup completes, the registry entry is cleared.

This is safe for concurrent window creation because each window is keyed by its unique agent name, not a shared static slot.

---

## Walk-Away Protocol

When BA accepts a deal from one DA, it must cleanly exit all other active negotiations:

```
BA accepts deal from DA1:
  1. Send ACCEPT_PROPOSAL to DA1
  2. For each other active negotiation (DA2, DA3):
       send REJECT_PROPOSAL with isFinal = true
       remove from activeNegotiations map
       remove from regressionPredictors map
  3. Send INFORM "deal-closed" to KA with final amount
  4. KA records commission for the accepted deal only
```

DA2 and DA3 receive the REJECT_PROPOSAL and treat it as a walk-away. They notify KA with "deal-failed" for their respective negotiations. KA logs the outcomes. No commission is charged to DA2 or DA3; their fixed fees were already recorded at the time of connection.

---

## Fixed Fee Interpretation (Concurrent)

In concurrent mode, KA may charge up to 3 fixed fees in one shortlist cycle — one for each DA that accepts BA and enters negotiation. This is intentional:

- The fixed fee is the cost of KA facilitating a connection, not of a deal closing
- BA benefits from competitive parallel negotiations (better deal outcome)
- The cost of running concurrent negotiations is shared across the dealers who engage
- This interpretation is consistent with the v1/v2 fee model and is stated in the report

---

## Extension 1 Edge Cases

| Case | Situation | Handling |
|---|---|---|
| All 3 DAs decline | No DA accepts BA's first offer | BA notified: no dealer engaged (same as v2) |
| Only 1 DA accepts | 2 DAs decline, 1 engages | Single negotiation runs as v2 |
| BA accepts deal while other negotiations pending | Mid-round for DA2 and DA3 when BA accepts from DA1 | Walk-away sent immediately to DA2, DA3 |
| Two DAs reach accept condition at same round | Race condition — two PROPOSE messages processed in same JADE tick | JADE serialises behaviour execution per agent; first processed wins; second triggers walk-away |
| DA disconnects mid-concurrent negotiation | One DA goes silent while others are active | That negotiation marked failed; remaining continue normally |
