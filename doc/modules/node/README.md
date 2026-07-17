# Node Module — Configuration Resolution

## Overview
The Node module is the core blockchain component. It manages profiles, lifecycle, networking, block processing, and mining.

## Configuration Resolution Chain (Hybrid)

```
Step 1: System base defaults
       classpath:/conf/system/logging/logging-default.properties

Step 2: Module profile defaults
       classpath:/conf/node/profiles/profile-default.properties

Step 3: Module logging defaults
       classpath:/conf/node/logging/logging-default.properties

Step 4: User override (empty at init)
       conf/node/{profile}.properties      ← runtime file, created if missing
       conf/node/logging.properties         ← runtime file, created if missing
```

## Key Files

| Resource (JAR) | Runtime Override | Purpose |
|---|---|---|
| `/conf/node/profiles/profile-default.properties` | `conf/node/{profile}.properties` | Node config values |
| `/conf/node/logging/logging-default.properties` | `conf/node/logging.properties` | Logging levels |

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
