# Database Module — Configuration Resolution

## Overview
The Database module provides abstracted data access for SQLite, MariaDB, and PostgreSQL backends.

## Configuration Resolution Chain

```
Step 1: System base defaults
       classpath:/conf/system/logging/logging-default.properties

Step 2: Database profile defaults
       classpath:/conf/database/profiles/profile-default.properties

Step 3: Database logging defaults
       classpath:/conf/database/logging/logging-default.properties

Step 4: User override (empty at init)
       conf/database/database.properties    ← runtime file, created if missing
```

## Key Files

| Resource (JAR) | Runtime Override | Purpose |
|---|---|---|
| `/conf/database/profiles/profile-default.properties` | `conf/database/database.properties` | DB backend config |
| `/conf/database/logging/logging-default.properties` | - | Database logging levels |

## Database Profiles

A database profile is a folder under `./database/<Engine>/<profile>/`:

| Engine | Profile identity | `profile.json` |
|---|---|---|
| **SQLite** (file-based) | the folder itself (the per-profile convention, `./database/SQLite/<profile>/`) | **optional** — carries tuning only (`DB.Url`, `DB.SqliteJournalMode`, `DB.SqliteCacheSize`); a folder without one is still a valid profile (the module materializes the file with its defaults on first save) |
| **MariaDB / PostgreSQL** (server-based) | the `profile.json` | **required** — it enumerates the databases created on the server (instances + credentials) |

Consequence for link resolution (node configuration UI): a SQLite database link
(`SQLite:<profile>`) materializes when the convention folder exists; a server-engine link
materializes only when the profile's `profile.json` exists and is loadable. Deleting a
profile is deleting its folder in both cases (the link then falls back to manual mode).