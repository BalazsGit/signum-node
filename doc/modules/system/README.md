# System Module — Console & Logging

## Overview
The System module provides the global console infrastructure, log routing, and cross-module logging defaults.

## Configuration Resolution Chain

```
Step 1: System logging defaults (always loaded first)
       classpath:/conf/system/logging/logging-default.properties
```

## Key Files

| Resource (JAR) | Purpose |
|---|---|
| `/conf/system/logging/logging-default.properties` | Global logging baseline (root level, handlers, formatters) |

## Console Architecture

```
System Console (all logs)
├── Profile 1 Console (filtered subset)
├── Profile 2 Console (filtered subset)
└── ...
```

The System Console is the superset containing all log events. Each Node Profile console shows only its relevant subset.