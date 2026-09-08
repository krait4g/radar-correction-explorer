# Performance and Reproducible Benchmarking

## What this document claims

Radar Correction Explorer is designed to keep large Radar and RF scanner time ranges inspectable through streaming, deterministic track-aware overview selection, one shared response budget, and viewport-aware rendering.

This repository does not claim unpublished production throughput or accuracy numbers. Any result presented in a release or résumé should be produced from synthetic data with the complete environment and command recorded.

## Performance model

The application has two different workloads.

### Overview workload

An overview request reads the selected source ranges, distributes one configured point budget across Radar and RF scanner physical tracks, and returns representative samples. It is designed to preserve time coverage and track identity without returning every row to the browser. Full-range row and correction/RF statistics are reported separately from the number of objects represented by returned points.

Server cost is still related to source rows examined. A bounded response does not make an unindexed or extremely broad source query free.

### Exact-track workload

After the user selects a physical track, the browser can request its precise samples with that source's sensor and track identity. Cross-source visualization is a separate client-side operation: for a selected stored global object ID, the UI connects only the nearest currently displayed observation from the other source. Exact-track latency and cross-source rendering should be measured separately from an overview.

## Baseline environment

Record at least the following for every benchmark:

- Git commit and application version;
- JDK vendor and version;
- operating system and CPU model;
- available memory and JVM heap flags;
- data mode, generator seed, Radar object count, RF scanner track count, and per-source row counts;
- requested time range and filters;
- overview point budget and statement timeouts;
- map tile mode; and
- whether the run is cold or warm.

Do not compare a cold first build, which downloads Maven dependencies, with an already warmed application.

## Correctness gate

Run the complete test suite before measuring:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

The benchmark is invalid if the test revision differs from the measured revision.

## Start the deterministic demo

```bash
./mvnw spring-boot:run
```

Wait until [http://127.0.0.1:28080/api/meta](http://127.0.0.1:28080/api/meta) returns HTTP 200. The metadata must identify synthetic demo mode.

The canonical demo range is:

```text
202601011200 through 202601011210
```

## API benchmark

Run the overview request and save the body instead of printing it to the terminal.

### macOS or Linux

```bash
curl -sS -o /tmp/observations.json \
  -w 'status=%{http_code} total=%{time_total}s bytes=%{size_download}\n' \
  'http://127.0.0.1:28080/api/observations?from=202601011200&to=202601011210&source=RADAR&source=RFSCNR&overview=true'
```

### Windows PowerShell

```powershell
curl.exe -sS -o "$env:TEMP\observations.json" `
  -w "status=%{http_code} total=%{time_total}s bytes=%{size_download}`n" `
  "http://127.0.0.1:28080/api/observations?from=202601011200&to=202601011210&source=RADAR&source=RFSCNR&overview=true"
```

Perform one warm-up request, then record at least 20 measured requests. Report median and p95 rather than a single best run. Run one client at a time for interactive latency; use a separate, clearly labeled test for concurrency.

For exact-track latency, first choose an observation returned by the overview and record the browser's `/api/observations?overview=false` request, selected source-specific physical-track identity, and returned sample count. Measure `/api/sensors` separately after the observation request; the normal UI flow does not start both database queries simultaneously.

## Browser benchmark

API latency does not describe rendering cost. Measure the browser separately with the same saved synthetic response.

Record:

- time from clicking **Load** to the first complete map render;
- response transfer size and decoded JSON size;
- JavaScript heap after the first render and after selecting a track;
- per-source candidate rows, returned overview points, represented objects, rendered markers, and rendered path segments;
- table filter and page-change latency;
- pan and zoom responsiveness after `moveend` or `zoomend`; and
- altitude chart update latency for the selected track.

Use a fresh browser profile, disable unrelated extensions, keep the viewport size constant, and state whether external map tiles were enabled. Tile latency must not be mixed into radar overlay latency.

## Large synthetic workload

The default demo is optimized for a quick visual review. A large benchmark fixture must remain deterministic and synthetic.

A valid large-fixture generator must accept and report:

- fixed random seed;
- number of Radar sensors and objects, and RF scanners and tracks;
- samples per object and sampling interval;
- ratio of missing, partial, and complete corrected values;
- altitude and displacement outlier ratios; and
- output row count and checksum.

Never derive the fixture from captured operational rows. If a generator configuration is published with benchmark results, commit the configuration and its checksum.

## Result template

Use this table in release notes or a pull request:

| Metric | Cold | Warm median | Warm p95 |
|---|---:|---:|---:|
| `/api/meta` latency |  |  |  |
| `/api/observations` overview latency |  |  |  |
| `/api/sensors` inventory latency |  |  |  |
| Exact-track latency |  |  |  |
| Overview response bytes |  |  |  |
| First complete browser render |  |  |  |
| Heap after overview render |  |  |  |

Also report Radar and RF scanner source rows, returned overview points, represented physical tracks and objects, exact-track rows, and whether percentile values are exact or approximate. For a snapshot, report the `±toleranceMs` candidate count separately from the nearest-per-track displayed count.

## Interpreting regressions

- Higher source-row time with stable response size usually points to query planning, indexing, network, or summary work.
- Higher response size with unchanged source rows usually points to an overview-budget or serialization change.
- Stable API time with slower interaction points to marker, path, table, or chart rendering.
- A fast response that represents fewer tracks or loses endpoints is a correctness regression, not a performance improvement.
- A lower correction displacement is not an accuracy improvement unless an independent ground truth is part of the fixture.

## CI boundary

CI runs correctness tests and a deterministic demo smoke test. Hosted-runner timing is too variable to serve as a strict performance gate. A dedicated benchmark environment may compare results against a reviewed baseline, but it must fail on lost coverage or changed sampling semantics before evaluating speed.
