# P0 Spike — log4j2 isolated LoggerContext gates

Throwaway verification for `implementation_plan/node_isolated_loggercontext_logging_plan.md`
(P0 section, D7 object-reference architecture). Delete this package after P0 is closed.

Run:

```
.\gradlew.bat runTest --tests "application.module.logging.spike.*"
```

| Gate | Test class | Question |
|------|------------|----------|
| (1)+(2) | `SpikeQ0TwoContextIsolationTest` | two contexts with the SAME logger name: independent levels + independent reconfigure? |
| Q1 | `SpikeQ1DefaultContextContractTest` | Do null-name and FQCN lookups converge? Does a P1-style single-context selector force convergence? |
| Q2a | `SpikeQ2aContextFromPropertiesTest` | named `LoggerContext` from generated .properties + `start()` applies the config? |
| Q2b | `SpikeQ2bReconfigureTest` | existing logger instances reflect the new level after `reconfigure()`? (hot reapply, GUI) |
| Q3 | `SpikeQ3ConfigWatchTest` | does the properties configurator auto-reload by default? is `configWatch = false` honored? |
| Q4 | `SpikeQ4ProgrammaticAppenderTest` | does `reconfigure()` detach (not stop) programmatic appenders; re-attach delivers exactly once? (GUI buffer, P2) |
| Q6 | `SpikeQ6StopRestartTest` | `stop()` → `start()` → `reconfigure()` on the same instance works without duplicate appenders? (risk K8) |

Q5 (SLF4J/JUL binding set, `log4j-slf4j2-impl` + `log4j-jul`) is NOT covered here: it requires
swapping the SLF4J provider and is verified in phase P3.5 (gate: no multiple-provider
warning, SLF4J + JUL logs land in the global context).

All spike contexts are created directly (object reference, D7) and are never registered with
`LogManager` / a `ContextSelector` — exactly how `NodeLoggingContextManager` will own them in P1.

## log4j 2.26.1 findings pinned by these tests (P1/P2 must honor)

- **Q1**: the default selector on 2.26.1 is `ClassLoaderContextSelector` (NOT
  BasicContextSelector) and the null-name vs FQCN lookups do NOT converge by default.
  A custom `ContextSelector` serving one shared context for ANY name DOES converge both
  paths → `SignumGlobalContextSelector` must serve the global context for every name
  (never key off the name).
- **Q2a**: `new LoggerContext(name, null, uri)` does NOT auto-start; call `start()`
  explicitly. `start()` internally re-applies the configuration (`reconfigure()`).
- **Q3**: the .properties configurator does NOT auto-reload by default (no watcher within
  15 s); `reconfigure()` is the only re-apply path. `configWatch = false` is accepted.
- **Properties syntax**: every appender block MUST carry an explicit `name` key
  (`appender.file.name = file`) — the 2.26 builder throws `ConfigurationException`
  ("No name attribute provided for Appender X") without it. Backslashes in property
  VALUES are escape characters: emit forward slashes for Windows file paths.
- **Q4**: `reconfigure()` detaches programmatic appenders from the regenerated
  configuration but leaves them started; `addAppender` de-duplicates by name, so
  re-attaching the same started instance after each reconfigure is safe and delivers
  exactly once.
- **Q6**: `stop()` → `start()` → `reconfigure()` on the same instance works and the file
  appender reopens without duplicates (risk K8 closed — no fallback needed).
- **API drift vs older log4j2 docs**: `AbstractAppender` is non-generic,
  `LoggerConfig.addAppender(Appender, org.apache.logging.log4j.Level, Filter)`,
  `LoggerConfig.getAppenders()` (not `getConfiguredAppenders()`), `Log4jContextFactory
  .getSelector()` (not `getContextSelector()`), `ExtendedLogger.getLoggerContext()` is gone
  (use `org.apache.logging.log4j.core.Logger.getContext()`).
