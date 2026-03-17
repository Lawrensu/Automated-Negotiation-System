# Project Scope

**Project Title:** Automated Negotiation System  
**Course:** COS30018 – Intelligent Systems  
**Due:** 11:59 pm 29/5/2026 (Friday, Week 12)  
**Weight:** 50% of final result  

---

## Introduction

Implement and demonstrate a platform for automated negotiation to trade automotive vehicles. The platform enables any number of car dealers and buyers to sell or buy cars through agents.

- Dealers list available cars at retail prices with a broker agent
- Buyers contact the broker agent to view listings before engaging with up to three dealers in direct negotiations
- **Dealer objective:** Maximise profit
- **Buyer objective:** Buy desired car at the lowest price

---

## Roles

**Broker Agent (KA)** : 1 agent
- Receives listings from dealers
- Sends relevant deals to buyers
- Facilitates negotiations between a pair of buyer and dealer
- Receives commissions for each successful deal and a fixed fee for each negotiation

**Dealer Agent (DA)** : at least 3
- Sends listings to KA
- Receives a list of potential buyers and their offers
- Selects the buyers he wants to engage and returns this list to KA
- Initiates the negotiations with the selected buyers

**Buyer Agent (BA)** : at least 5
- Contacts KA with the specifications of the cars she wants to buy
- Receives a list of dealers whose cars match her specifications
- Sends back to KA the cars she is willing to negotiate with her first offers
- Receives the requests from dealers to negotiate (if any)

---

## Constraints

- Interaction protocols can be FIPA predefined (e.g. CNP, iterated CNP), nested, or newly specified
- Agents can use any standard content language
- Sequence diagrams describing the implemented interaction protocols are included in the report
- A GUI will be available for user input, parameter settings, and visualisation (and a configuration file for the defaults)

---

## System Requirements

**Basic v1** — Provide the trading platform with the broker agent KA. Dealers and buyers can join the platform. The GUI will allow DAs and BAs to input their information (e.g. dealer's listings or buyer's requirements for a car). The platform will also support the manual negotiation between a dealer and a buyer.

**Basic v2** — Provide automated negotiation capability for a buyer. The buyer provides initial information such as her first offer and her reserve price and the agent will automatically negotiate with the dealer to get the best deal for her. The agent is only engaging in a single negotiation with one dealer.

### (Will have to choose only one path to extension)
**Extension 1** — Extend the automated negotiation functionality to include multiple concurrent negotiations.

**Extension 2** — Extend the automated negotiation functionality to allow for multi-attribute negotiation. Implement a system that allows the agent to achieve win-win negotiation outcomes (integrative negotiation).

---

## Project Requirements

- Source code maintained on a Git-based VCS (GitHub / Bitbucket / GitLab)
- Running illustrative demo of a working prototype; demonstrable with a well-designed and realistic example
- **Project report (8–10 pages)** containing:
  - Cover page (with team details) and Table of Contents
  - Introduction
  - Overall system architecture
  - Implemented interaction protocols
  - Implemented negotiation strategies
  - Implemented prediction algorithm/s
  - Scenarios / examples to demonstrate how the system works
  - Critical analysis of how the system performs with and without negotiation (experimentation: edge cases)
  - Summary / Conclusion
  - Presentation + demo video link (10 minutes duration)

---

## Marking Scheme

| Requirement | Marks |
|---|---|
| **Task 1** — Basic v1: multi-agent trading platform with KA and GUI. KA–DA and KA–BA interactions working fully according to well-defined interaction protocols (sequence diagrams in report). KA receives DA listings and BA requests, updates BAs with relevant offers, receives shortlists. DAs engage selected BAs to negotiate. | 40 |
| **Task 2** — Basic v2: automated negotiation program allowing a BA to automatically negotiate with a DA. | 20 |
| Project Report | 10 |
| Project Presentation (Video) | 10 |
| **Subtotal** | **80** |
| **Research Component** — Select one Extension (tutor approval required) and complete it thoroughly. | up to 40 |
| **Total** | **120 / 100** |
| Poor programming practice (design, structure, comments) | up to −20 |
| Failure to demonstrate weekly progress to tutor | up to −50 |

---

## Notes

- Individual marks are proportionally adjusted based on each member's overall contribution as indicated in the "Who did what" declaration
- Provide read-only Git access to `shlee@swinburne.edu.my` within 1 week of forming teams

---

## Submission

- Upload a single `.zip` (code + working system) to Canvas by **11:59 pm 29/5/2026**
- Late penalty: −10% per day; more than 5 days late = 0%
- Video link (10 minutes) must be stated in the project report