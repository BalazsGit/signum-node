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