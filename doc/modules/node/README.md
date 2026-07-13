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

## Utility Class
- `application.utils.config.ModuleConfigPath` — Path resolution and stream loading