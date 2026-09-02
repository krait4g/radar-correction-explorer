# Performance and Reproducible Benchmarking

## What this document claims

Radar Correction Explorer is designed to keep large time ranges inspectable through streaming, deterministic track-aware overview selection, and viewport-aware rendering.

This repository does not claim unpublished production throughput or accuracy numbers. Any result presented in a release or résumé should be produced from synthetic data with the complete environment and command recorded.

## Performance model

The application has two different workloads.

### Overview workload

An overview request reads the selected source range, distributes a configured point budget across physical tracks, and returns representative samples. It is designed to preserve time coverage and track identity without returning every row to the browser.

Server cost is still related to source rows examined. A bounded response does not make an unindexed or extremely broad source query free.

### Exact-track workload

After the user selects an object, the browser requests precise samples for that physical track. This response is intentionally more detailed and should be measured separately from an overview.

## Baseline environment

Record at least the following for every benchmark:

- Git commit and application version;
- JDK vendor and version;
- operating system and CPU model;
- available memory and JVM heap flags;
- data mode, generator seed, object count, and source row count;
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
curl -sS -o /tmp/radar-tracks.json \
  -w 'status=%{http_code} total=%{time_total}s bytes=%{size_download}\n' \
  'http://127.0.0.1:28080/api/tracks?from=202601011200&to=202601011210&overview=true'
```

### Windows PowerShell

```powershell
curl.exe -sS -o "$env:TEMP\radar-tracks.json" `
  -w "status=%{http_code} total=%{time_total}s bytes=%{size_download}`n" `
  "http://127.0.0.1:28080/api/tracks?from=202601011200&to=202601011210&overview=true"
```

Perform one warm-up request, then record at least 20 measured requests. Report median and p95 rather than a single best run. Run one client at a time for interactive latency; use a separate, clearly labeled test for concurrency.

For exact-track latency, first choose an object returned by the overview and use the detail request made by the browser. Record the exact URL, selected physical-track identity, and returned sample count.

## Browser benchmark

API latency does not describe rendering cost. Measure the browser separately with the same saved synthetic response.

Record:

- time from clicking **Load** to the first complete map render;
- response transfer size and decoded JSON size;
- JavaScript heap after the first render and after selecting a track;
- number of source rows, overview points, rendered markers, and rendered path segments;
- table filter and page-change latency;
- pan and zoom responsiveness after `moveend` or `zoomend`; and
- altitude chart update latency for the selected track.

Use a fresh browser profile, disable unrelated extensions, keep the viewport size constant, and state whether external map tiles were enabled. Tile latency must not be mixed into radar overlay latency.

## Large synthetic workload

The default demo is optimized for a quick visual review. A large benchmark fixture must remain deterministic and synthetic.

A valid large-fixture generator must accept and report:

- fixed random seed;
- number of radars and objects;
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
| Overview latency |  |  |  |
| Exact-track latency |  |  |  |
| Overview response bytes |  |  |  |
| First complete browser render |  |  |  |
| Heap after overview render |  |  |  |

Also report source rows, returned overview points, represented tracks, exact-track rows, and whether percentile values are exact or approximate.

## Interpreting regressions

- Higher source-row time with stable response size usually points to query planning, indexing, network, or summary work.
- Higher response size with unchanged source rows usually points to an overview-budget or serialization change.
- Stable API time with slower interaction points to marker, path, table, or chart rendering.
- A fast response that represents fewer tracks or loses endpoints is a correctness regression, not a performance improvement.
- A lower correction displacement is not an accuracy improvement unless an independent ground truth is part of the fixture.

## CI boundary

CI runs correctness tests and a deterministic demo smoke test. Hosted-runner timing is too variable to serve as a strict performance gate. A dedicated benchmark environment may compare results against a reviewed baseline, but it must fail on lost coverage or changed sampling semantics before evaluating speed.
