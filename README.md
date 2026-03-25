Clone of [PlantUML](https://github.com/plantuml/plantuml) (v1.2025.9) with a custom UML type classification tool.

# UmlDiagramClassifier

A tool for classifying PlantUML diagram files into one of nine standard UML diagram types. It reuses PlantUML's own internal parser to achieve compiler-grade accuracy, with no modifications to PlantUML's source code.

## Classification Method

The classifier feeds each `.puml` file through PlantUML's parser (`SourceStringReader`) and inspects the resulting internal diagram object. Classification is performed in two stages.

### Stage 1: Diagram class dispatch

PlantUML parses each diagram into a specific Java class. Five of nine types map directly to a unique class or `UmlDiagramType` value and are classified deterministically:

| Diagram Type | Internal Class | `UmlDiagramType` |
|---|---|---|
| sequence | `SequenceDiagram` | `SEQUENCE` |
| activity | `ActivityDiagram3` | `ACTIVITY` |
| state | `StateDiagram` (via `CucaDiagram`) | `STATE` |
| timing | `TimingDiagram` | `TIMING` |

### Stage 2: Heuristic disambiguation

The remaining types share internal representations and require entity-level inspection.

#### Class vs Object (`UmlDiagramType.CLASS`)

PlantUML uses the same `ClassDiagram` class for both class and object diagrams. The classifier iterates over all leaf entities and checks their `LeafType`:

- If all entities are `LeafType.OBJECT` or `LeafType.MAP` (and none are class-like) → **object**
- Otherwise → **class**

Class-like types include: `CLASS`, `ABSTRACT_CLASS`, `INTERFACE`, `ENUM`, `ANNOTATION`, `ENTITY`, `PROTOCOL`, `STRUCT`, `EXCEPTION`, `METACLASS`, `STEREOTYPE`, `DATACLASS`, `RECORD`.

#### Component vs Deployment vs Use Case (`UmlDiagramType.DESCRIPTION`)

PlantUML uses the same `DescriptionDiagram` class for component, deployment, and use case diagrams. The classifier iterates over all leaf entities and checks both their `LeafType` and `USymbol`:

**Use case** is detected when:
- Any entity has `LeafType.USECASE` or `LeafType.USECASE_BUSINESS`, or
- Only actor symbols are present (no component or deployment symbols)

**Deployment** is detected when deployment-specific symbols are present:
- `NODE`, `CLOUD`, `DATABASE`, `ARTIFACT`, `STORAGE`, `FOLDER`, `FRAME`

**Component** is detected when component-specific symbols are present:
- `COMPONENT1`, `COMPONENT2`, `COMPONENT_RECTANGLE`

When both component and deployment symbols are present, the diagram is classified as **deployment** (since deployment diagrams commonly embed components). When no distinguishing symbols are found, the default is **component**.

## Supported Diagram Types

| Output Label | PlantUML Syntax | Classification |
|---|---|---|
| `sequence` | `Alice -> Bob: msg` | Deterministic |
| `activity` | `start` / `:action;` / `if ... then` | Deterministic |
| `state` | `[*] --> State` | Deterministic |
| `timing` | `@starttiming` | Deterministic |
| `class` | `class Foo` / `interface Bar` | Heuristic (entity types) |
| `object` | `object foo` / `map m` | Heuristic (entity types) |
| `usecase` | `usecase "Login"` / `actor User` | Heuristic (entity symbols) |
| `component` | `component "Auth"` | Heuristic (entity symbols) |
| `deployment` | `node "Server"` / `cloud "CDN"` | Heuristic (entity symbols) |

## Prerequisites

- Java 8 or later

## Build

```bash
./gradlew build -x test -x javadoc
```

This produces an executable JAR at `build/libs/plantuml-1.2025.9.jar`.

## Usage

```bash
# Single file
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.classifier.UmlDiagramClassifier diagram.puml

# Multiple files
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.classifier.UmlDiagramClassifier file1.puml file2.puml

# All PlantUML files in a directory
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.classifier.UmlDiagramClassifier --dir /path/to/puml/files/
```

The `--dir` option processes all files with extensions `.puml`, `.plantuml`, `.pu`, `.wsd`, `.uml`, and `.iuml`.

## Output Format

One JSON object per diagram to standard output (JSON Lines format):

```json
{"file":"example.puml","diagram_type":"class","error":null}
{"file":"sequence.puml","diagram_type":"sequence","error":null}
{"file":"broken.puml","diagram_type":null,"error":"parse_error"}
```

| Field | Description |
|---|---|
| `file` | Input filename. Multi-diagram files receive a numeric suffix (e.g., `file.puml_1`). |
| `diagram_type` | One of: `sequence`, `activity`, `state`, `timing`, `class`, `object`, `usecase`, `component`, `deployment`. Null on failure. |
| `error` | Null on success. Descriptive string on failure (e.g., `parse_error`, `unsupported:MindMapDiagram`). |

## Changes from Upstream PlantUML

**1 new class** added: `net.sourceforge.plantuml.classifier.UmlDiagramClassifier` — the classification tool entry point. No modifications were made to any existing PlantUML source files.

## License

This clone retains PlantUML's original GNU General Public License v3 (GPL-3.0).
