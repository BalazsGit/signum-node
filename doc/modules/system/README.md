# System Module — Console & System-Level Logging

## Overview
The System module provides the global console infrastructure, log routing, and
the system-level (JVM-global) logging configuration layer.

## System-Level Logging Configuration

The system level owns the JVM-global logging context (every non-node-engine
class: GUI, utils, launcher, database, browser, …). Its on-disk layout mirrors
the node module (SSOT: `application.utils.config.LoggingPaths`):

```
conf/system/logging/
├── default.properties          ← System L1: user-editable, global scope
│                                  (auto-created at first start, never
│                                  overwritten by the classpath sync)
├── system-default.properties   ← Reference template (system L0 documentation,
│                                  read-only, SHA-256 synced from resources)
└── runtime/
    └── global.log4j2.properties ← GENERATED global Log4j2 config (NOT SSOT)
```

Layer order for the GLOBAL context (per key, the highest wins):
L0 (built-in defaults of the non-node module providers) ← L1 node
(`conf/node/logging/default.properties`, legacy compatibility) ← L1 system
(`conf/system/logging/default.properties`, wins for global-scope keys).

The system-level (global) file log destination is `logs/system/` — OUTSIDE
the configuration tree, referenced relatively (disk-portable).

## Key Files

| Resource (JAR) | Purpose |
|---|---|
| `/conf/system/logging/default.properties` | System L1 auto-create seed (header + comments, 0 active lines) |
| `/conf/system/logging/system-default.properties` | System-level (global) reference template |

## Console Architecture

```
System Console (all logs)
├── Profile 1 Console (filtered subset)
├── Profile 2 Console (filtered subset)
└── ...
```

The System Console is the superset containing all log events. Each Node Profile console shows only its relevant subset.