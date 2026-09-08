# API Reference

Radar Correction Explorer exposes a local, read-only HTTP API at `http://127.0.0.1:28080`. Responses are JSON and use logical field names rather than deployment-specific table or column names. The API never returns database credentials.

Every `/api` and `/api/**` response includes `Cache-Control: no-store, max-age=0` and `Pragma: no-cache`. Clients that send `Accept-Encoding: gzip` can receive compressed supported responses of at least 1 KiB. JSON uses non-null serialization, so an unavailable optional field can be omitted rather than emitted as `null`.

The browser requests only `/api/meta` at startup. It does not load observations automatically. Opening an empty sensor picker can request `/api/sensors` for that source and the current valid range. A normal load waits for `/api/observations` before refreshing `/api/sensors`; the two database queries do not start simultaneously. After the first load, changing a source toggle immediately reruns the observation query.

## Compact time format

The `from`, `to`, and `at` parameters accept ASCII digits in these exact lengths:

| Length | Format | Normalized example |
|---:|---|---|
| 8 | `yyyyMMdd` | `20260101` → `20260101000000000` |
| 10 | `yyyyMMddHH` | `2026010112` → `20260101120000000` |
| 12 | `yyyyMMddHHmm` | `202601011205` → `20260101120500000` |
| 14 | `yyyyMMddHHmmss` | `20260101120530` → `20260101120530000` |
| 17 | `yyyyMMddHHmmssSSS` | `20260101120530123` → unchanged |

Omitted components are filled with zero. Timestamps are database-local values without an offset; the API does not perform a time-zone conversion.

## `GET /api/meta`

Returns connection mode, independent source capabilities, combined and per-source time ranges, limits, warnings, and map configuration. The server can start and return this response even when no external observation table is ready.

```json
{
  "database": {
    "status": "UP",
    "message": "Available observation sources: RADAR, RFSCNR.",
    "connectionLabel": "Synthetic demo",
    "syntheticDemo": true
  },
  "capabilities": {
    "correctedLongitude": true,
    "correctedLatitude": true,
    "correctedAltitude": true,
    "primaryFlag": true,
    "referenceAltitude": true,
    "radarEvents": true,
    "radarReady": true
  },
  "rfScannerCapabilities": {
    "tableExists": true,
    "ready": true,
    "objectMatch": true,
    "fallbackTime": true,
    "homePosition": true,
    "homeAltitude": true,
    "missingRequiredColumns": []
  },
  "timeRange": {
    "min": "20260101120000000",
    "max": "20260101121000000"
  },
  "radarTimeRange": {
    "min": "20260101120000000",
    "max": "20260101121000000"
  },
  "rfScannerTimeRange": {
    "min": "20260101120000000",
    "max": "20260101121000000"
  },
  "warnings": [],
  "limits": {
    "maxToleranceMs": 30000,
    "maxWindowSeconds": 300,
    "maxRangeSeconds": 0,
    "maxQueryRows": 0,
    "maxOverviewPoints": 100000
  },
  "map": {
    "tileUrl": "",
    "attribution": "",
    "initialLongitude": 0.0,
    "initialLatitude": 0.0,
    "initialZoom": 2.0,
    "maxNativeZoom": 19,
    "maxZoom": 24
  }
}
```

`timeRange` combines the minimum and maximum available across both sources. `radarTimeRange` and `rfScannerTimeRange` remain source-specific. RF rows use a valid primary observation time; a configured fallback time is used only when the primary value is missing or invalid.

Radar and RF scanner schema inspection and time-range lookup are isolated. If one fails, a ready source remains usable and the failure is recorded in `warnings`. The connection status is `UP` when at least one source is ready, `DOWN` when neither source is ready and at least one schema inspection failed, or `SCHEMA_MISMATCH` when inspections completed but neither required schema is ready.

RF scanner readiness requires logical `eventId`, `observedAt`, `scannerId`, `trackId`, `longitude`, `latitude`, and `altitude` mappings. Fallback time, stored global object ID, home longitude/latitude, and home altitude are optional capabilities.

The default `maxRangeSeconds=0` and `maxQueryRows=0` disable hard duration and exact-query row limits. Large overview responses remain bounded by `maxOverviewPoints`, concurrency control, statement timeouts, and deterministic sampling.

## `GET /api/observations`

Queries Radar and RF scanner observations through one range and one shared overview-point budget.

```http
GET /api/observations?from=202601011200&to=202601011210&source=RADAR&source=RFSCNR&radarId=RADAR-01&rfScannerId=RF-01&matchMode=ALL&primaryOnly=false
```

| Parameter | Required | Default or range | Meaning |
|---|---|---|---|
| `from`, `to` | yes | compact time | Inclusive range; `from` must not be after `to` |
| `toleranceMs` | no | `750`, `0..maxToleranceMs` | Candidate width on each side when `from == to` |
| `source` | no | every ready source | `RADAR` or `RFSCNR`; repeat or use comma-separated values |
| `radarId` | no | all | Radar sensor IDs; repeat or use comma-separated values, at most 100 distinct values |
| `rfScannerId` | no | all | RF scanner IDs; repeat or use comma-separated values, at most 100 distinct values |
| `radarObjectNo` | no | all | Signed 64-bit Radar-local track number; use with exactly one `radarId` for an exact physical track |
| `objectNo` | no | all | Signed 64-bit stored global object ID applied independently to both sources |
| `trackId` | no | all | RF scanner-local track ID, at most 128 characters |
| `matchMode` | no | `ALL` | RF rows: `ALL`, `MATCHED`, or `UNMATCHED` |
| `primaryOnly` | no | `true` | Apply the configured primary-row flag to Radar only when supported |
| `overview` | no | `true` | Return deterministic representative points for a range |

Each repeated or comma-separated filter value is at most 128 characters. `objectNo` cannot be combined with `matchMode=UNMATCHED`. With `overview=false`, Radar requires `objectNo` or one `radarId` plus `radarObjectNo`; RFSCNR requires `objectNo` or exactly one `rfScannerId` plus `trackId`.

```json
{
  "mode": "RANGE",
  "requestedFrom": "202601011200",
  "requestedTo": "202601011210",
  "normalizedFrom": "20260101120000000",
  "normalizedTo": "20260101121000000",
  "toleranceMs": 750,
  "rangeStart": "20260101120000000",
  "rangeEnd": "20260101121000000",
  "selectedSources": ["RADAR", "RFSCNR"],
  "matchMode": "ALL",
  "primaryOnly": false,
  "warnings": [],
  "summary": {
    "sourceRows": 1800,
    "radarSourceRows": 1200,
    "rfScannerSourceRows": 600,
    "radarObjectCount": 12,
    "rfScannerTrackCount": 4,
    "matchedRfScannerPointCount": 420,
    "unmatchedRfScannerPointCount": 180
  },
  "radarSummary": {
    "sourceRows": 1200,
    "objectCount": 12,
    "rawPositionCount": 1200,
    "correctedPositionCount": 1100,
    "uncorrectedCount": 100,
    "averageHorizontalCorrectionMeters": 4.125,
    "maxHorizontalCorrectionMeters": 18.4,
    "p95HorizontalCorrectionMeters": 11.2,
    "averageAbsoluteAltitudeDeltaMeters": 1.8,
    "maxAbsoluteAltitudeDeltaMeters": 7.5
  },
  "rfScannerSummary": {
    "sourceRows": 600,
    "representedRows": 600,
    "trackCount": 4,
    "positionCount": 600,
    "matchedPointCount": 420,
    "matchedObjectCount": 3
  },
  "sampling": {
    "sampled": false,
    "strategy": "NONE",
    "sourceRows": 1800,
    "returnedRows": 1800,
    "trackCount": 16,
    "representedTracks": 16,
    "allTracksRepresented": true,
    "metricsScope": "FULL_RANGE",
    "p95Approximate": false
  },
  "radarPoints": [
    {
      "eventId": 101,
      "sourceEventId": 1001,
      "eventTime": "20260101120500100",
      "radarId": "RADAR-01",
      "radarObjectNo": "77",
      "objectNo": "123",
      "primaryFlag": "Y",
      "raw": {"longitude": 127.001, "latitude": 37.501, "altitude": 120.0},
      "corrected": {"longitude": 127.0011, "latitude": 37.5011, "altitude": 122.5},
      "horizontalCorrectionMeters": 14.197,
      "altitudeDeltaMeters": 2.5,
      "correctionStatus": "CORRECTED"
    }
  ],
  "rfScannerPoints": [
    {
      "eventId": 501,
      "eventTime": "20260101120500120",
      "timeSource": "OBSERVED_AT",
      "rfScannerId": "RF-01",
      "trackId": "TRACK-A",
      "objectNo": "123",
      "matched": true,
      "position": {"longitude": 127.0012, "latitude": 37.5012, "altitude": 25.0},
      "home": {"longitude": 127.0, "latitude": 37.5, "altitude": 86.0},
      "altitudeReference": "RELATIVE_TO_HOME"
    }
  ]
}
```

`selectedSources` lists the ready sources used by the request. When `source` is omitted, unavailable sources are omitted and explained in `warnings`; explicitly requesting an unavailable source returns the corresponding schema error.

### Range sampling

For `from != to`, `overview=true` uses one `maxOverviewPoints` budget across both source arrays. It preserves track coverage and endpoints when the budget permits, then selects deterministic time-distributed samples. `sampling.returnedRows` equals `radarPoints.length + rfScannerPoints.length`.

Even when sampling is applied, `summary`, Radar correction metrics, and RF row, track, position, and match statistics describe the full query range. `rfScannerSummary.representedRows` and the UI's displayed-object count describe returned representatives only. A sampled displayed-object count must not be read as the total number of objects in the range. `p95Approximate=true` explicitly identifies an approximated Radar percentile.

### Snapshot sampling

For `from == to`, the inclusive query window is the requested time plus or minus `toleranceMs`. The server chooses the nearest candidate for each Radar physical track `(objectNo, radarId, radarObjectNo)` and each RF physical track `(rfScannerId, trackId)`. When candidates are collapsed, the sampling strategy is `SNAPSHOT_NEAREST_PER_PHYSICAL_TRACK`.

`sampling.sourceRows` is the full candidate count and `sampling.returnedRows` is the nearest-per-track display count. `metricsScope=FULL_RANGE` means the summaries and correction/RF statistics cover every candidate in the window. The UI labels candidate and displayed counts separately.

### Stored cross-source matches

`objectNo` is a stored global ID, not a match calculated by this application. Radar and RF rows are queried independently rather than joined into an all-to-all result. When the user selects a global object, the UI draws one line to the nearest currently displayed observation from the other source with the same stored ID. It does not infer or persist a new match.

### RF altitude semantics

`rfScannerPoints[].position.altitude` is relative to the RF source's home position. `rfScannerPoints[].home.altitude` is the home's mean-sea-level altitude. The API keeps them separate and the UI does not add them to create a derived MSL target altitude. `timeSource` is `OBSERVED_AT` or, only when the primary time is missing or invalid, `FALLBACK_OBSERVED_AT`.

## `GET /api/sensors`

Returns the source-specific sensors present in the requested range.

```http
GET /api/sensors?from=202601011200&to=202601011210&source=RADAR&source=RFSCNR&primaryOnly=true
```

| Parameter | Required | Default | Meaning |
|---|---|---|---|
| `from`, `to` | yes | — | Inclusive range or equal-time snapshot window |
| `toleranceMs` | no | `750` | Snapshot width on each side |
| `source` | no | every ready source | Repeat or comma-separate `RADAR` and `RFSCNR` |
| `primaryOnly` | no | `true` | Apply the Radar primary-row filter when supported |

```json
{
  "mode": "RANGE",
  "requestedFrom": "202601011200",
  "requestedTo": "202601011210",
  "normalizedFrom": "20260101120000000",
  "normalizedTo": "20260101121000000",
  "toleranceMs": 750,
  "rangeStart": "20260101120000000",
  "rangeEnd": "20260101121000000",
  "primaryOnly": true,
  "selectedSources": ["RADAR", "RFSCNR"],
  "warnings": [],
  "radars": [
    {"radarId": "RADAR-01", "eventCount": 1200, "objectCount": 12}
  ],
  "rfScanners": [
    {"rfScannerId": "RF-01", "eventCount": 600, "trackCount": 4, "matchedObjectCount": 3}
  ]
}
```

Sensor inventory queries are isolated by source. If one aggregation fails, the successful list is returned and `warnings` records the failed source. `503 DATABASE_UNAVAILABLE` is returned only when every source attempted by the inventory request fails. An explicitly requested source whose required schema is unavailable fails before aggregation.

## Legacy Radar-only endpoints

These endpoints remain available for existing Radar-only clients. New browser functionality uses `/api/observations` and `/api/sensors`.

| Endpoint | Purpose |
|---|---|
| `GET /api/tracks` | Radar-only range or equal-time snapshot |
| `GET /api/radars` | Radar IDs present in a range |
| `GET /api/snapshot` | Radar-only snapshot compatibility endpoint |
| `GET /api/objects/{objectNo}/detail` | Precise Radar samples around one time |

## Errors

Expected failures use a structured body:

```json
{
  "timestamp": "2026-01-01T12:00:00Z",
  "status": 400,
  "code": "INVALID_TIME_RANGE",
  "message": "from must be earlier than or equal to to.",
  "path": "/api/observations",
  "details": []
}
```

| HTTP status | Typical meaning |
|---:|---|
| `400` | Invalid time, source, filter, or exact-query identity |
| `404` | Unknown endpoint or resource |
| `422` | An operator-configured positive exact-query row limit was exceeded |
| `503` | Requested schema is unavailable, all attempted inventory queries failed, or the database query could not complete |

The default exact-query row limit is disabled. A `422` response occurs only when an operator explicitly configures a positive limit.

## Map privacy

If an external tile URL is configured, the browser sends tile coordinates and network metadata to that provider. The current Content Security Policy permits tile images only from the application's own origin or an `https://` URL. The browser blocks a cross-origin `http://` tile URL. Use an approved same-origin or HTTPS provider, or the coordinate-grid fallback in offline or sensitive environments, and preserve all required map attribution.
