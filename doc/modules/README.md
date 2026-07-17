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

## Cross-Cutting Concerns

### Logging Architecture

The Signum Node uses a centralized, subscriber-based logging framework that replaces legacy MDC-based routing. See the detailed implementation plan:

- **[LOGGER_ARCHITECTURE_PLAN.md](./LOGGER_ARCHITECTURE_PLAN.md)** — Full architecture, implementation phases, migration status

**Key Components:**

| Component | Purpose | Status |
|-----------|---------|--------|
| `ModuleLogger` (interface) | General logger API used by all modules | Active |
| `LoggerImpl` (abstract) | Common subscriber management + dispatch | Active |
| `SystemLogger` (singleton) | Global/system-level logging | Active |
| `ProfileLogger` (per-profile) | Per-profile isolated logging | Active |
| `ProfileLogRouter` | Legacy JUL→SLF4J bridge handler | @Deprecated |
| `ProfileThreadContext` | Legacy MDC wrapper for routing | @Deprecated |
| `ProfileLogContext` | Legacy subscriber registry | @Deprecated |

**Migration Status:** Phase 1–4 Complete. Legacy classes marked `@Deprecated` with migration guidance. Full MDC bridge removal planned as Phase 4.5.
