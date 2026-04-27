# Getting Started


## Prerequisites

- Java 17 or higher : verify with `java -version`
- Maven 3.8+ : verify with `mvn -version` ([download Maven](https://maven.apache.org/download.cgi))
- Git
- JADE 4.6.0 : already included at `lib/jade.jar`, no download needed


---


## First-time JADE setup (run once, any IDE)

JADE is not published on Maven Central, so you need to register it with your local Maven repository once before the build system can resolve it.

**Step 1 : Register JADE in your local Maven repository:**

```bash
mvn install:install-file "-Dfile=lib/jade.jar" "-DgroupId=com.tilab.jade" "-DartifactId=jade" "-Dversion=4.6.0" "-Dpackaging=jar"
```

**Step 2 : Update the JADE dependency scope in `pom.xml`:**

Find the JADE dependency block and change `system` to `compile`, then remove the
`<systemPath>` line entirely:

```xml
<!-- Before (system scope — for IDE-only use, no fat jar) -->
<dependency>
	<groupId>com.tilab.jade</groupId>
	<artifactId>jade</artifactId>
	<version>4.6.0</version>
	<scope>system</scope>
	<systemPath>${project.basedir}/lib/jade.jar</systemPath>
</dependency>

<!-- After (compile scope — required for mvn package to bundle JADE) -->
<dependency>
	<groupId>com.tilab.jade</groupId>
	<artifactId>jade</artifactId>
	<version>4.6.0</version>
	<scope>compile</scope>
</dependency>
```

> **Can I skip this?**  
> Yes, if you are setting up to explore or compile inside the IDE only. The `system` scope
> already points to `lib/jade.jar` so the project compiles and the IDE resolves types
> without running install-file. This step is only required when building the final
> deployable jar with `mvn package`.


---


## For VS Code


### Setup

1. Install **Extension Pack for Java** (by Microsoft) from the Extensions sidebar
   (`Ctrl+Shift+X` → search 'Extension Pack for Java')
2. Open the project root folder — VS Code detects `pom.xml` automatically and imports
   it as a Maven project
3. Wait for the Java extension to finish indexing — watch the progress indicator in the
   bottom-right corner of the window
4. If source files are not picked up after indexing:
   `Ctrl+Shift+P` → **Java: Force Java Compilation**


### Compile

Use the Maven sidebar (`M` icon on the left) → **Lifecycle → compile**, or run in the
integrated terminal:

```bash
mvn clean compile
```


### Run

> Run configuration for `ans.Main` will be documented here once `Main.java` is
> implemented.


---


## For IntelliJ IDEA


### Setup

1. **File → Open** the project root folder
2. IntelliJ detects `pom.xml` and shows an **'Open as Maven Project'** notification —
   click **Trust Project**
   - If the notification does not appear: right-click `pom.xml` in the Project panel →
     **Add as Maven Project**
3. IntelliJ automatically marks `src/main/java` as Sources Root and
   `src/main/resources` as Resources Root
   - If not applied automatically: right-click each folder →
     **Mark Directory as → Sources Root** / **Resources Root**


### Compile

Open the **Maven panel** (right side of the IDE) → **Lifecycle → compile**, or run in
the terminal:

```bash
mvn clean compile
```


### Run

> Run configuration for `ans.Main` will be documented here once `Main.java` is
> implemented.


---


## Build (terminal)

```bash
# Compile only
mvn clean compile

# Full deployable jar — requires JADE compile-scope setup (see First-time JADE setup above)
mvn clean package
java -jar target/ans-1.0-SNAPSHOT-jar-with-dependencies.jar
```


---



