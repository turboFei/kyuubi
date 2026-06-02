# Proposal: Native Prometheus Metrics with Labels in Kyuubi

**Author:** Fei Wang  
**Date:** 2026-05-12  
**Status:** Draft — Community Discussion  
**Target Version:** Kyuubi 1.12.0  

---

## 1. Motivation

Kyuubi's current metrics system is built on [Dropwizard Metrics](https://metrics.dropwizard.io/) and exposed to Prometheus via the `DropwizardExports` bridge (`io.prometheus:simpleclient_dropwizard`). While functional, this approach has fundamental limitations that make it hard to build reliable monitoring and alerting.

### 1.1 Dimensional Data Is Encoded in Metric Names

In Prometheus, dimensional attributes — things like operation type, user, session type, or error class — should be expressed as **labels** on a single metric family. The DropwizardExports bridge cannot produce labels because Dropwizard has no concept of them; it can only translate a flat string key into a flat metric name.

As a result, Kyuubi today produces:

```
# Separate counters for each user and each error type
kyuubi_engine_failed_alice_total          3
kyuubi_engine_failed_bob_total            1
kyuubi_engine_failed_SparkException_total 2
kyuubi_engine_failed_ClassNotFoundException_total 1

# Separate counters for each operation type × state combination
kyuubi_operation_state_ExecuteStatement_running_total 5
kyuubi_operation_state_ExecuteStatement_finished_total 120
kyuubi_operation_state_BatchJobSubmission_pending_total 2
```

The intended Prometheus format would be:

```
kyuubi_engine_failed_total{user="alice", error="SparkException"}       2
kyuubi_engine_failed_total{user="alice", error="ClassNotFoundException"} 1
kyuubi_engine_failed_total{user="bob",   error="SparkException"}       1

kyuubi_operation_state_transitions_total{type="ExecuteStatement",    state="running"}  5
kyuubi_operation_state_transitions_total{type="ExecuteStatement",    state="finished"} 120
kyuubi_operation_state_transitions_total{type="BatchJobSubmission",  state="pending"}  2
```

With label-based metrics, PromQL queries become straightforward:

```promql
# Alert: any single user causing > 10 engine failures in 5 minutes
rate(kyuubi_engine_failed_total[5m]) > 10

# Dashboard: engine failure breakdown by user
sum by (user) (rate(kyuubi_engine_failed_total[5m]))

# Alert: more than 20% of operations failing across ALL types
rate(kyuubi_operation_failed_total[5m])
  / rate(kyuubi_operation_total[5m]) > 0.2
```

With the current flat-name format, the equivalent query requires either a brittle regex like `{__name__=~"kyuubi_engine_failed_.*_total"}` or maintaining a separate list of known users and error types — neither of which is maintainable.

### 1.2 Timer and Meter Types Lose Prometheus Semantics

Dropwizard's `Timer` and `Meter` types use exponentially-weighted moving averages (EWMA) for rate calculation. The `DropwizardExports` bridge serializes these into multiple flat gauges:

```
kyuubi_backend_service_open_session_count     # total count
kyuubi_backend_service_open_session_mean_rate # EWMA rate (not Prometheus-compatible)
kyuubi_backend_service_open_session_m1_rate   # 1-minute EWMA
kyuubi_backend_service_open_session_m5_rate   # 5-minute EWMA
kyuubi_backend_service_open_session_m15_rate  # 15-minute EWMA
kyuubi_backend_service_open_session_min       # snapshot min
kyuubi_backend_service_open_session_max       # snapshot max
kyuubi_backend_service_open_session_mean      # snapshot mean
kyuubi_backend_service_open_session_stddev
kyuubi_backend_service_open_session_p50
kyuubi_backend_service_open_session_p75
kyuubi_backend_service_open_session_p95
kyuubi_backend_service_open_session_p98
kyuubi_backend_service_open_session_p99
kyuubi_backend_service_open_session_p999      # 15 series per timer
```

None of these are a native Prometheus `histogram` type, so `histogram_quantile()` cannot be used. The EWMA-based rate metrics (`m1_rate`, `m5_rate`) are also semantically different from Prometheus's counter-based `rate()` function and can mislead dashboard authors.

### 1.3 Poor `# HELP` Documentation

Every metric scraped today has auto-generated help text such as:

```
# HELP kyuubi_buffer_pool_mapped_count Generated from Dropwizard metric import
  (metric=kyuubi.buffer_pool.mapped.count, type=com.codahale.metrics.jvm.JmxAttributeGauge)
```

This is unhelpful for operators configuring Prometheus alerting rules.

---

## 2. Goals

1. Support native Prometheus metric types (`Counter`, `Gauge`, `Histogram`) with label dimensions for Kyuubi's business metrics.
2. Enable idiomatic PromQL queries and alerting rules without regex hacks.
3. Maintain full backward compatibility — existing Dropwizard-based reporters (JSON, SLF4J, JMX, Console) must continue to work without change.
4. Provide a clear, incremental migration path so the change can be reviewed and merged in multiple PRs.

## 3. Non-Goals

- Replacing or removing the Dropwizard `MetricRegistry`. Other reporters depend on it and it remains unchanged.
- Migrating JVM / GC / memory metrics. These come from Dropwizard's JVM instrumentation sets and are already reasonable as flat gauges.
- Upgrading to Prometheus Java client v1.x. The current `simpleclient` v0.x is already in the dependency tree; migrating to v1.x is a separate, larger effort.
- Adopting Micrometer as a metrics facade. While Micrometer is well-designed, adding a new framework dependency and its Spring-centric ecosystem to an ASF data platform server is out of scope here.

---

## 4. Scope Analysis — Three Approaches

### Option A: Label Injection at the Reporter Layer Only

**Idea:** Modify `PrometheusReporterService` to parse existing metric names, infer which segments are labels, and rewrite the output.

**Pros:** No changes to `MetricsSystem` API or call sites. Minimal diff.

**Cons:**
- The naming convention is inconsistent. Some metrics append user as a suffix (`kyuubi.connection.opened.${user}`), others append error type (`kyuubi.engine.failed.${errorType}`), and some append multiple segments (`kyuubi.operation.failed.${opType}.${errorType}`). Reliable automated parsing is not feasible.
- Timers still produce 15 flat gauges rather than a proper Prometheus `Histogram`. This problem cannot be solved at the reporter layer.
- `# HELP` text still comes from DropwizardExports auto-generation.
- Future metrics added by contributors follow no enforced convention, making the problem worse over time.

**Verdict:** Short-term workaround, does not solve the root cause. Not recommended.

---

### Option B: Full Replacement of Dropwizard with Prometheus Java Client

**Idea:** Replace `com.codahale.metrics.MetricRegistry` throughout `MetricsSystem` with a native `io.prometheus.client.CollectorRegistry`. Redefine all metric constants as typed Prometheus objects with explicit label definitions. Remove `DropwizardExports`.

**Pros:**
- Complete solution. All metrics use proper Prometheus types and labels.
- Eliminates the bridge layer entirely.
- Enables `histogram_quantile()` on all timing metrics.

**Cons:**
- **Breaking change for all non-Prometheus reporters.** `JsonReporterService`, `Slf4jReporterService`, `JMXReporterService`, and `ConsoleReporterService` all depend on `MetricRegistry`. They would require complete rewrites or removal.
- **Very large changeset.** Every call to `MetricsSystem.tracing`, `incCount`, `markMeter`, `updateTimer`, `updateHistogram`, and `registerGauge` across `kyuubi-server` and `kyuubi-metrics` must be updated simultaneously.
- High regression risk. A single large PR touching metric instrumentation across the entire server codebase is hard to review and revert.

**Verdict:** The correct end state, but too risky as a single step. Consider as a future long-term goal after Option C is complete.

---

### Option C: Native Prometheus Registry Coexisting with Dropwizard (Recommended)

**Idea:** Introduce a second, native `CollectorRegistry` inside `MetricsSystem` alongside the existing `MetricRegistry`. Add new API methods that register and update native Prometheus metrics with explicit label definitions. `PrometheusReporterService` merges both registries when serving `/metrics`. Other reporters are unaffected.

Existing Dropwizard metrics remain as-is. Key business metrics are migrated to native Prometheus incrementally, PR by PR, with the old Dropwizard metric deprecated and eventually removed.

**Pros:**
- Zero breaking changes. All existing reporters continue to work.
- Incremental migration. Each PR is focused and reviewable.
- Proper Prometheus types (`Counter`, `Gauge`, `Histogram`) for migrated metrics.
- Contributors can start using the new API immediately; not everything needs to migrate at once.

**Cons:**
- During the transition, some metrics appear twice in `/metrics` output (old flat Dropwizard name + new labelled name). This requires a deprecation period and documentation.
- Dual-registry complexity inside `MetricsSystem` is temporary but real.
- The migration to full native Prometheus is multi-release work.

**Verdict: Recommended.** This is the pragmatic path for an established open-source project with users depending on existing metric names.

---

## 5. Proposed Design (Option C)

Option C is implemented in two sub-phases with increasing depth:

- **Phase C1 (label-aware wrappers)**: Modelled on Apache Celeborn's proven approach. Adds label support by wrapping Dropwizard metric objects in typed case classes that carry a `labels: Map[String, String]`. Prometheus output is rendered directly without `DropwizardExports`. No new library dependency.
- **Phase C2 (native Prometheus Histogram)**: Extends Phase C1 by replacing Dropwizard `Timer` and `Histogram` with native `io.prometheus.client.Histogram` for latency metrics, enabling `histogram_quantile()` in PromQL. Uses the `simpleclient` v0.x dependency already in the pom.

### 5.1 Phase C1: Label-Aware Wrappers (Celeborn Pattern)

#### New typed wrapper classes in `kyuubi-metrics`

```scala
// Named wrappers carry labels alongside the Dropwizard metric object
case class NamedCounter(name: String, help: String, counter: Counter,
                        labels: Map[String, String]) extends MetricLabels
case class NamedGauge[T](name: String, help: String, gauge: Gauge[T],
                          labels: Map[String, String]) extends MetricLabels
case class NamedTimer(name: String, help: String, timer: Timer,
                      labels: Map[String, String]) extends MetricLabels

trait MetricLabels {
  val labels: Map[String, String]
  final val labelString: String = MetricLabels.labelString(labels)
}
object MetricLabels {
  def labelString(labels: Map[String, String]): String =
    labels.map { case (k, v) => s"""$k="$v"""" }.toArray.sorted.mkString("{", ",", "}")
}
```

#### New API on `MetricsSystem`

The existing Dropwizard-based `incCount(key: String)` / `markMeter(key: String)` API is kept unchanged. New overloads accept explicit labels:

```scala
class MetricsSystem extends CompositeService("MetricsSystem") {

  private val registry = new MetricRegistry  // unchanged

  // Static labels applied to every metric (instance hostname, configurable extras)
  private var staticLabels: Map[String, String] = Map.empty

  // Storage for named wrappers — keyed by (name + label string) for dedup
  private val namedCounters = new ConcurrentHashMap[String, NamedCounter]()
  private val namedGauges   = new ConcurrentHashMap[String, NamedGauge[_]]()
  private val namedTimers   = new ConcurrentHashMap[String, NamedTimer]()

  def addCounter(name: String, help: String, labels: Map[String, String] = Map.empty): Unit = {
    val allLabels = labels ++ staticLabels
    val key = name + MetricLabels.labelString(allLabels)
    namedCounters.putIfAbsent(key,
      NamedCounter(name, help, registry.counter(key), allLabels))
  }

  def incCounter(name: String, labels: Map[String, String] = Map.empty): Unit = {
    val key = name + MetricLabels.labelString(labels ++ staticLabels)
    Option(namedCounters.get(key)).foreach(_.counter.inc())
  }

  // ... addGauge, addTimer, updateTimer, incCounter with n:Long overload

  // Existing Dropwizard-only API — kept for backward compatibility
  def incCount(key: String): Unit = registry.counter(key).inc()
  def markMeter(key: String, value: Long = 1): Unit = registry.meter(key).mark(value)
  // ...
}
```

#### `PrometheusReporterService` renders labels directly

Replace the `DropwizardExports` bridge with a custom renderer for the named wrappers, while keeping `DropwizardExports` for the legacy Dropwizard-only metrics during the transition:

```scala
private def getMetricsSnapshot: String = {
  val sb = new StringBuilder
  val ts = System.currentTimeMillis()

  // New label-aware metrics (rendered directly)
  metricsSystem.namedCounters.values.asScala.foreach { nc =>
    sb.append(s"# HELP ${normalizeKey(nc.name)} ${nc.help}\n")
    sb.append(s"# TYPE ${normalizeKey(nc.name)} counter\n")
    sb.append(s"${normalizeKey(nc.name)}${nc.labelString} ${nc.counter.getCount} $ts\n")
  }
  // ... similarly for gauges, timers

  // Legacy Dropwizard bridge (suppressed when kyuubi.metrics.prometheus.legacy.enabled=false)
  if (legacyEnabled) {
    TextFormat.write004(sb.writer, bridgeRegistry.metricFamilySamples())
  }
  sb.toString()
}
```

Output for a migrated counter:
```
# HELP kyuubi_engine_failed_total Cumulative number of engine startup failures
# TYPE kyuubi_engine_failed_total counter
kyuubi_engine_failed_total{user="alice",error="SparkException",instance="host:10019"} 2 1715000000000
kyuubi_engine_failed_total{user="bob",error="SparkException",instance="host:10019"}   1 1715000000000
```

#### Call-site migration example

```scala
// Before: two separate incCount calls for two dimensions
ms.incCount(MetricRegistry.name(ENGINE_FAIL, appUser))
ms.incCount(MetricRegistry.name(ENGINE_FAIL, error.getClass.getSimpleName))

// After: one incCounter call with two labels
MetricsSystem.tracing(
  _.incCounter(ENGINE_FAIL, Map("user" -> appUser, "error" -> error.getClass.getSimpleName))
)
```

### 5.2 Phase C2: Native Prometheus Histogram for Latency Metrics

Dropwizard `Timer` and `Histogram` can only be rendered as flat percentile gauges. For latency metrics, Prometheus's native `Histogram` type supports `histogram_quantile()` and is the correct representation.

In Phase C2, `MetricsSystem` additionally holds a native `CollectorRegistry` for timing metrics only:

```scala
private[metrics] val prometheusRegistry = new CollectorRegistry

def registerLatencyHistogram(
    name: String,
    help: String,
    buckets: Array[Double],
    labelNames: String*): io.prometheus.client.Histogram =
  io.prometheus.client.Histogram.build()
    .name(name).help(help).buckets(buckets: _*)
    .labelNames(labelNames: _*)
    .register(prometheusRegistry)
```

The backend service timers (16 Dropwizard Timers) and operation exec time histogram are migrated to this API:

```scala
// Registered once at startup
val backendServiceLatency: io.prometheus.client.Histogram =
  MetricsSystem.registerLatencyHistogram(
    name       = "kyuubi_backend_service_duration_seconds",
    help       = "Latency distribution of Kyuubi backend service RPC calls",
    buckets    = Array(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 30.0),
    labelNames = "operation"
  )

// At call sites in BackendServiceMetric:
backendServiceLatency.labels("execute_statement").observe(durationSeconds)
```

`PrometheusReporterService` merges the output of all three sources:

```
[Phase C1 named-wrapper output]  +  [Native Prometheus Histogram output]  +  [Legacy Dropwizard bridge (optional)]
```

This enables proper PromQL queries:
```promql
# P99 latency for executeStatement over the last 5 minutes
histogram_quantile(0.99,
  rate(kyuubi_backend_service_duration_seconds_bucket{operation="execute_statement"}[5m])
)
```

### 5.3 Metric Mapping: Before and After (Phase C1 + C2)

The following table shows the planned transformation for Kyuubi's core business metrics.

| Current Dropwizard Name | Type | New Prometheus Name | Labels |
|---|---|---|---|
| `kyuubi.connection.opened` | gauge | `kyuubi_connection_opened` (gauge) | — |
| `kyuubi.connection.opened.${user}` | counter | `kyuubi_connection_opened` (gauge) | `user` |
| `kyuubi.connection.opened.${user}.${sessionType}` | counter | `kyuubi_connection_opened` (gauge) | `user`, `type` |
| `kyuubi.connection.failed` | counter | `kyuubi_connection_failed_total` | — |
| `kyuubi.connection.failed.${user}` | counter | `kyuubi_connection_failed_total` | `user` |
| `kyuubi.connection.total` | counter | `kyuubi_connection_total` | — |
| `kyuubi.connection.total.${sessionType}` | counter | `kyuubi_connection_total` | `type` |
| `kyuubi.operation.opened.${opType}` | counter | `kyuubi_operation_opened` (gauge) | `type` |
| `kyuubi.operation.total.${opType}` | counter | `kyuubi_operation_total` | `type` |
| `kyuubi.operation.failed.${opType}.${errorType}` | counter | `kyuubi_operation_failed_total` | `type`, `error` |
| `kyuubi.operation.state.${opType}.${state}` | meter | `kyuubi_operation_state_transitions_total` | `type`, `state` |
| `kyuubi.operation.exec_time.${opType}` | histogram | `kyuubi_operation_exec_time_seconds` (Histogram) | `type` |
| `kyuubi.engine.failed.${user}` | counter | `kyuubi_engine_failed_total` | `user`, `error` |
| `kyuubi.engine.failed.${errorType}` | counter | merged into above | `user`, `error` |
| `kyuubi.engine.timeout` | counter | `kyuubi_engine_timeout_total` | — |
| `kyuubi.engine.total` | counter | `kyuubi_engine_total` | — |
| `kyuubi.backend_service.${op}` | timer | `kyuubi_backend_service_duration_seconds` (Histogram) | `operation` |
| `kyuubi.metadata.request.opened` | counter | `kyuubi_metadata_request_opened` (gauge) | — |
| `kyuubi.metadata.request.failed` | meter | `kyuubi_metadata_request_failed_total` | — |
| `kyuubi.metadata.request.retrying` | meter | `kyuubi_metadata_request_retrying` (gauge) | — |

#### Notable Consolidations

**Engine failures**: Currently two separate counters (`ENGINE_FAIL.${user}` and `ENGINE_FAIL.${errorType}`) are written in separate `incCount` calls. They will be unified into a single counter with two labels: `{user, error}`. This eliminates a long-standing inconsistency where neither counter alone tells you the full story of a failure.

**Backend service timers**: 16 Dropwizard Timers (`open_session`, `close_session`, …) each producing 15 flat gauges (240 series total) are replaced by a single `Histogram` with an `operation` label (9 bucket series × 16 operations = ~144 series, with proper P99 support via `histogram_quantile()`).

**Operation state**: The `markMeter` pattern (which Dropwizard uses for EWMA rates) is replaced by a `Counter` with `(type, state)` labels. Prometheus's `rate()` function computes rates from counters correctly.

### 5.4 Deprecation Strategy

1. When a metric is migrated to native Prometheus, the corresponding Dropwizard `incCount`/`markMeter`/`updateTimer` call is **retained for one release cycle** alongside the new native call.
2. The old metric name is marked `@deprecated` in a new `@deprecated` annotation in `MetricsConstants` and documented in the upgrade guide.
3. In the following release, the Dropwizard call and the `MetricsConstants` constant are removed.

A new config key controls the transition:

```
kyuubi.metrics.prometheus.legacy.enabled   (default: true)
```

Setting this to `false` disables the `DropwizardExports` bridge entirely, suppressing the legacy flat names from the scrape output. This is intended for users who have fully migrated their dashboards.

### 5.5 No Changes to JVM / GC Metrics (Both Phases)

JVM, GC, memory pool, thread state, and class loading metrics are registered via Dropwizard's `JvmAttributeGaugeSet`, `GarbageCollectorMetricSet`, etc. These map reasonably to Prometheus gauges and are already well-understood by operators. They are out of scope for this proposal.

---

## 6. Migration Plan (Phased)

### Phase 1 — Infrastructure: Label-Aware Wrappers (C1, 1 PR)

- Introduce `MetricLabels` trait and `NamedCounter`/`NamedGauge`/`NamedTimer` wrapper case classes.
- Add `addCounter(name, help, labels)` / `incCounter(name, labels)` API to `MetricsSystem`.
- Add `staticLabels` support (instance, operator-configurable extras via `kyuubi.metrics.extra.labels`).
- Modify `PrometheusReporterService` to render named-wrapper metrics directly (no `DropwizardExports` for these).
- Add `kyuubi.metrics.prometheus.legacy.enabled` config key (default `true`).
- Unit tests for label rendering and the merged servlet output.

### Phase 2 — Engine and Connection Metrics (C1, 1–2 PRs)

- Migrate `kyuubi.engine.failed` (consolidate user+error into two labels on one counter).
- Migrate `kyuubi.engine.timeout`, `kyuubi.engine.total`.
- Migrate `kyuubi.connection.*` family.
- Retain legacy Dropwizard `incCount` calls with `@deprecated` ScalaDoc annotation.
- Update docs with the new metric names and example PromQL alert rules.

### Phase 3 — Operation and Metadata Metrics (C1, 1–2 PRs)

- Migrate `kyuubi.operation.*` family (opened, total, failed, state).
- Migrate `kyuubi.metadata.request.*`.
- Keep legacy writes for one release cycle.

### Phase 4 — Native Prometheus Histogram for Latency (C2, 1 PR)

- Add `prometheusRegistry: CollectorRegistry` to `MetricsSystem` for Histogram-type metrics.
- Introduce `registerLatencyHistogram(name, help, buckets, labelNames*)` API.
- Replace 16 Dropwizard Timers in `BackendServiceMetric` with a single `kyuubi_backend_service_duration_seconds{operation}` Histogram.
- Replace `kyuubi.operation.exec_time` Histogram with a native Prometheus Histogram.
- Enable `histogram_quantile()` queries on latency data.
- Update Grafana dashboard template with `histogram_quantile()` panels.

### Phase 5 — Legacy Cleanup (1 PR, next release)

- Remove deprecated `incCount`/`markMeter`/`updateTimer` Dropwizard calls.
- Remove deprecated constants from `MetricsConstants`.
- Consider setting `kyuubi.metrics.prometheus.legacy.enabled` default to `false`.

---

## 7. Backward Compatibility

| Concern | Impact | Mitigation |
|---|---|---|
| Existing Prometheus scrape configs | No change — endpoint URL and port unchanged | — |
| Existing Grafana dashboards using old flat names | Dashboards break after Phase 4 cleanup | Legacy names present for one full release cycle; updated dashboard template provided |
| Non-Prometheus reporters (JSON, SLF4J, JMX, Console) | No impact — Dropwizard MetricRegistry unchanged | — |
| Users relying on `DropwizardExports` `# HELP` text in alerts | Alerts using help text patterns may need update | Documented in upgrade guide |

---

## 8. Reference Implementation: Apache Celeborn

[Apache Celeborn](https://celeborn.apache.org/) is a remote shuffle service in the same ecosystem as Kyuubi. Its metrics system faces the same Dropwizard-vs-Prometheus label problem and has already implemented a label-aware solution that can serve as a direct reference for Kyuubi.

### 8.1 Celeborn's Approach

Celeborn keeps Dropwizard `MetricRegistry` as the internal backing store for all metric objects, but completely bypasses `DropwizardExports`. Instead it introduces two key patterns:

**Typed wrapper case classes that carry labels:**
```scala
case class NamedCounter(name: String, counter: Counter, labels: Map[String, String])
  extends MetricLabels
case class NamedTimer(name: String, timer: Timer, labels: Map[String, String])
  extends MetricLabels
// ... NamedGauge, NamedMeter, NamedHistogram
```

**`MetricLabels` renders labels as a Prometheus-compatible string:**
```scala
object MetricLabels {
  def labelString(labels: Map[String, String]): String =
    labels.map { case (k, v) => s"""$k="$v"""" }.toArray.sorted.mkString("{", ",", "}")
}
```

**Registration API accepts a `labels: Map[String, String]` parameter:**
```scala
// AbstractSource.scala
def addCounter(name: String, labels: Map[String, String] = Map.empty): Unit
def addTimer(name: String, labels: Map[String, String] = Map.empty): Unit
def addGauge[T](name: String, labels: Map[String, String] = Map.empty)(f: () => T): Unit
```

**Static labels (instance, role, extra labels configured by the operator) are merged in automatically:**
```scala
val staticLabels: Map[String, String] = conf.metricsExtraLabels + roleLabel ++ instanceLabel

// At registration time: user labels are merged with staticLabels
NamedCounter(name, counter, labels ++ staticLabels)
```

**Prometheus text format is rendered directly** (no `io.prometheus.client` library needed at the Prometheus output layer):
```scala
def addMetricsWithPrometheusHelpType(
    metricName: String, label: String, value: Any,
    timestamp: Long, promType: String): String = {
  s"# HELP $metricName\n# TYPE $metricName $promType\n$metricName$label $value $timestamp\n"
}
```

The final output looks like:
```
# HELP metrics_WorkerPausePushDataAndReplicateCount
# TYPE metrics_WorkerPausePushDataAndReplicateCount counter
metrics_WorkerPausePushDataAndReplicateCount{instance="host:9096",role="Worker",applicationId="app_001"} 3 1715000000000
```

### 8.2 What Kyuubi Can Adopt from Celeborn

| Pattern | Celeborn | Kyuubi (proposed) |
|---|---|---|
| Typed wrappers with labels | `NamedCounter`, `NamedTimer`, etc. | Same pattern in `kyuubi-metrics` |
| Static label merging | `staticLabels` in `AbstractSource` | `staticLabels` in `MetricsSystem` |
| Custom Prometheus text rendering | `getCounterMetrics`, `getTimerMetrics` | Similar in `PrometheusReporterService` |
| Drop `DropwizardExports` | Yes, entirely removed | Yes, removed in Phase 2+ |
| Native Prometheus `histogram_bucket` | **No** — still flat gauges | **Yes** — proposed for timing metrics (Phase 2) |

### 8.3 Where Kyuubi Goes Further

Celeborn's approach solves the label problem but retains the Dropwizard Histogram/Timer rendering as flat gauges (P50, P75, P95, P99 as separate series). This means `histogram_quantile()` is still not available.

Kyuubi's proposal extends beyond Celeborn's approach by using native `io.prometheus.client.Histogram` for timing metrics in Phase 2, enabling true Prometheus histogram semantics and `histogram_quantile()` support. This requires the `simpleclient` dependency that Kyuubi already has, making it a natural next step.

## 9. Alternatives Considered

### Micrometer as a Facade

Micrometer provides a vendor-neutral metrics API that supports both Prometheus and Dropwizard backends. However:
- It would add a significant new dependency to an ASF project.
- Kyuubi is not a Spring application, and Micrometer's auto-configuration is Spring-centric.
- It solves a vendor-portability problem Kyuubi does not have; Prometheus is already the only supported pull-based target.

### Upgrade to Prometheus Java Client v1.x

The Prometheus Java client was redesigned with a new `io.prometheus:prometheus-metrics-core` artifact in v1.x. The v0.x `simpleclient_dropwizard` bridge has no v1.x equivalent (though `prometheus-metrics-instrumentation-dropwizard5` covers Dropwizard 5). This migration is orthogonal to the label problem and should be addressed separately after this proposal lands.

### Name-Rewriting at the Reporter Layer Only (Option A)

Discussed in Section 4. Rejected because it cannot reliably handle inconsistent naming conventions and cannot solve the Timer metric explosion problem.

---

## 10. Open Questions for Community Discussion

1. **Metric naming convention**: Should we use `kyuubi_operation_failed_total` (following the `_total` suffix convention for counters) consistently, or is the shorter `kyuubi_operation_failed` acceptable?

2. **`engine.failed` label design**: Currently Kyuubi records two separate counters — one keyed by user, one by error type — because the call sites don't always have both. With a two-label metric `{user, error}`, should we use an empty string `""` for the missing dimension, or split into separate single-label metrics?

3. **Histogram bucket defaults**: The proposed buckets for `kyuubi_operation_exec_time_seconds` are `[0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 30.0]`. Are these appropriate for Kyuubi's typical operation latencies (which can range from milliseconds for metadata ops to minutes for batch jobs)?

4. **`kyuubi.metrics.prometheus.legacy.enabled` default**: Defaulting to `true` ensures zero breakage on upgrade. Should we instead default to `false` in a new major version to encourage users to migrate?

5. **Grafana dashboard migration timeline**: Should the updated dashboard template ship in the same release as Phase 3 or wait until Phase 4 cleanup?

---

## 11. References

- [Prometheus Metric Types](https://prometheus.io/docs/concepts/metric_types/)
- [Prometheus Naming Best Practices](https://prometheus.io/docs/practices/naming/)
- [Dropwizard Metrics](https://metrics.dropwizard.io/4.2.0/)
- [`io.prometheus:simpleclient_dropwizard` source](https://github.com/prometheus/client_java/tree/simpleclient/simpleclient_dropwizard)
- [Kyuubi Metrics Documentation](https://kyuubi.apache.org/docs/latest/monitor/metrics.html)
- [Kyuubi JIRA: KYUUBI-XXXX](https://issues.apache.org/jira/browse/KYUUBI-XXXX)
- [Apache Celeborn `AbstractSource.scala`](https://github.com/apache/celeborn/blob/main/common/src/main/scala/org/apache/celeborn/common/metrics/source/AbstractSource.scala) — reference implementation for label-aware Dropwizard wrappers
- [Apache Celeborn `MetricLabels.scala`](https://github.com/apache/celeborn/blob/main/common/src/main/scala/org/apache/celeborn/common/metrics/MetricLabels.scala) — label string rendering pattern
