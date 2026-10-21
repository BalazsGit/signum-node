# Node Module — Configuration Resolution

## Overview
The Node module is the core blockchain component. It manages profiles, lifecycle, networking, block processing, and mining.

## Configuration Resolution Chain (3-layer)

Node configuration is loaded per key, the highest layer wins:

```
L0: Hardcoded defaults
        Props.java (Prop.getDefaultValue) — never materialized to disk

L1: User-editable layer (F7)
        conf/node/profiles/default.properties
        auto-created at first start, never overwritten by the classpath sync;
        an active line overrides L0 for EVERY profile

L2: Profile file (GUI-editable)
        conf/node/profiles/{profileName}.properties
        active lines override L0 and L1
```

The single loading path is `EffectiveNodeProps` (headless SSOT):

- runtime: `Signum.loadPropertiesForProfile` → `EffectiveNodeProps.loadEffective`
- GUI "Default" baselines (edit / delete / reset / clone diff):
  `EffectiveNodeProps.baselineValue`

Both template and profile files are optional — deleting any layer degrades
gracefully to the layers below (worst case: pure L0).

Logging configuration follows the same model: hardcoded logging defaults (L0,
`NodeLoggingProfile.getDefaults`) ← `conf/node/logging/default.properties`
(L1, user-editable, F7) ← the logging profile file (L2). The merge lives in
`ConfigurationUtils.loadEffectiveLoggerProfile`; the read-only "effective view"
composition is `EffectiveProfileResolver` + `LoggingProfileManager`.

### Per-Node File Log

Every running node writes its own log file (INFO and above) via the
`ProfileFileAppender` (a `LogSubscriber` on the node's `ProfileLogger`):

```
{node.fileLog.dir}/{profileName}.log    (default: logs/node/{profileName}.log)
```

Size-based rotation (`node.fileLog.limitBytes`, `node.fileLog.count`); the
appender attaches on node start and detaches on stop/dispose.

## Logging Architecture

The Node module uses the centralized logging framework (`ModuleLogger` hierarchy) for all log output:

### Active Components

| Class | Role |
|-------|------|
| `NodeLoggingProvider` | Provides node-specific logging presets to `LoggingProfileManager` |
| `NodeLoggingProfile` | Defines node module log levels, appenders, patterns |
| `ProfileLogger` | Per-profile logger instance held by each `NodeCoreContext` |

### GUI Console Integration

| Component | Subscribes To | Purpose |
|-----------|--------------|---------|
| `SystemConsoleSubscriber` | `SystemLogger` | Displays all logs from all modules/profiles |
| `ProfileConsoleSubscriber` | `ProfileLogger` | Displays logs for a specific node profile only |
| `NodeConsolePanel` | Both above | Tabbed console view in GUI |

### Deprecated (Legacy Bridge)

The following classes are marked `@Deprecated` and retained for backward compatibility during the SLF4J→JUL bridge migration period:

- `ProfileLogRouter` — JUL handler that routes events to profile contexts
- `ProfileThreadContext` — MDC wrapper for thread-local routing
- `ProfileLogContext` — Legacy subscriber registry per profile

New code should use `ProfileLogger` directly (constructor injection) rather than relying on MDC-based routing.

### Diagrams

- **[architecture.puml](./architecture.puml)** / **[architecture.mmd](./architecture.mmd)** — Component diagram including logging framework
