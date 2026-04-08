# Automated Negotiation System - Intelligent Systems (COS30018)

This documentation serves to describe the design and the implementation of the Automated Negotiation System for our Intelligent Systems (COS30018) unit.

## Outline
1. [Project Overview](docs/project_overview.md)
2. [Getting Started](docs/getting_started.md)
3. [Development Convention](docs/development_convention.md)
4. [Architecture/System Design](docs/system_design.md)


## To setup:

Please run these to setup the environment:

```javac -cp "lib/jade.jar" -d bin (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName })```

```java -cp "bin;lib/jade.jar" jade.Boot -gui```