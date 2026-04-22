# Automated Negotiation System - Intelligent Systems (COS30018)

This documentation serves to describe the design and the implementation of the Automated Negotiation System for our Intelligent Systems (COS30018) unit.

## Outline
1. [Project Overview](docs/project_overview.md)
2. [Getting Started](docs/getting_started.md)
3. [Development Convention](docs/development_convention.md)
4. [Architecture/System Design](docs/system_design.md)
5. [Architecture/Techhnical Design](docs/technical_design.md)

---

## Project structure

```
automated-negotiation-system/
├── pom.xml
├── lib/
│   └── jade.jar                             ← JADE 4.6.0, bundled in repo
├── src/main/resources/
│   └── config.properties                    ← all adjustable values, loaded on startup
└── src/main/java/ans/
    ├── Main.java                            ← boots JADE, launches GUI launcher
    ├── Config.java                          ← properties loader utility
    ├── agent/
    │   ├── BrokerAgent.java                 ← KA
    │   ├── DealerAgent.java                 ← DA
    │   └── BuyerAgent.java                  ← BA
    ├── model/
    │   ├── CarListing.java                  ← public DA listing fields
    │   ├── BuyerRequirements.java           ← BA search criteria + matching logic
    │   ├── Offer.java                       ← price offer passed between DA and BA
    │   └── NegotiationState.java            ← internal negotiation tracker (not transmitted)
    ├── strategy/
    │   ├── ConcessionStrategy.java          ← interface
    │   └── TimeBasedStrategy.java           ← Faratin formula implementation
    ├── util/
    │   └── MessageBuilder.java              ← ACL message factory
    └── gui/
        ├── LauncherWindow.java              ← main launcher
        ├── DealerWindow.java                ← DA GUI
        ├── BuyerWindow.java                 ← BA GUI
        └── BrokerLogWindow.java             ← KA log view
```
