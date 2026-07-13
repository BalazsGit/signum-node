# Signum Node — Module Documentation

This directory contains high-level architecture and operational documentation for each module in the Signum Node project.

## Structure

```
doc/modules/
├── README.md                  ← You are here
├── node/                      ← Node module docs
│   ├── README.md
│   ├── architecture.puml      ← PlantUML component diagram
│   ├── architecture.mmd       ← Mermaid component diagram
│   └── config-resolution.puml ← Config resolution chain
├── database/                  ← Database module docs
│   ├── README.md
│   └── architecture.puml
└── system/                    ← System/console module docs
    ├── README.md
    └── console-logging.puml
```

## Diagram Formats

| Format | Extension | Viewer |
|--------|-----------|--------|
| PlantUML | `.puml` | VS Code extension, IntelliJ plugin |
| Mermaid | `.mmd` | GitHub, VS Code, web browsers |

Both formats are maintained in parallel for maximum compatibility.

## Conventions

- **Current state only**: Diagrams reflect the actual running code, not planned features.
- **One file per concept**: Each diagram covers a single architectural concern.
- **README per module**: Textual summary alongside diagrams for quick reference.