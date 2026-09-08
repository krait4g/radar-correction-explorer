# Radar Correction Explorer

[한국어 문서](README.ko.md)

[![CI](https://github.com/krait4g/radar-correction-explorer/actions/workflows/ci.yml/badge.svg)](https://github.com/krait4g/radar-correction-explorer/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-007396.svg)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Radar Correction Explorer is a local, read-only web application for comparing raw and corrected radar tracks and optionally correlating RF scanner observations on a 2D map and an altitude chart. It starts with deterministic synthetic data, so the complete UI can be evaluated without a database, credentials, or private infrastructure.

![Radar Correction Explorer synthetic demo](docs/images/radar-correction-explorer-demo.jpg)

## Why this project exists

A corrected coordinate is easier to trust when the original sample, corrected sample, displacement, track history, and altitude change can be inspected together. The explorer provides that visual and numerical comparison while remaining independent from the system that produces the data.

Key capabilities:

- Raw samples and corrected samples use different marker shapes.
- Radar and RF scanner layers can be toggled independently, with multiple sensor IDs selected per source.
- Samples from the same object keep the same deterministic color.
- Every raw/corrected pair can be selected, connected, and inspected.
- Stored global object IDs visualize known cross-source associations; the explorer does not infer or persist new matches.
- A selected global object connects only the nearest currently displayed observation from the other source, avoiding all-to-all line clutter.
- Altitude series show raw and corrected values on the same time axis.
- RF target altitude relative to home and home altitude above mean sea level remain separate values and are never silently added together.
- Range queries use bounded overview data, while a selected track can be loaded precisely.
- Missing corrected coordinates remain missing; they are never replaced with zero.
- The application does not query tracks until the user requests a range.

## 60-second demo

Requirements:

- JDK 21
- Git

The Maven Wrapper downloads the project-pinned Maven version on first use.

### macOS or Linux

```bash
git clone https://github.com/krait4g/radar-correction-explorer.git
cd radar-correction-explorer
./mvnw spring-boot:run
```

### Windows PowerShell

```powershell
git clone https://github.com/krait4g/radar-correction-explorer.git
Set-Location radar-correction-explorer
.\mvnw.cmd spring-boot:run
```

Open [http://127.0.0.1:28080](http://127.0.0.1:28080), then query:

```text
From  202601011200
To    202601011210
```

The default dataset is generated locally from a fixed seed. The application and API identify it as synthetic demo data.

Keep both source toggles enabled to see synthetic Radar and RF scanner observations together. The sensor pickers contain multiple synthetic IDs, including matched and unmatched RF tracks and a fallback-time example.

Stop the foreground process with `Ctrl+C`.

## Build and test

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

On Windows:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Run the packaged application:

```bash
java -jar target/radar-correction-explorer.jar
```

The same synthetic demo is the default for the packaged JAR.

### Windows foreground launcher

The repository also includes a foreground launcher that keeps its console open, opens the browser after the health endpoint is ready, and stops with `Ctrl+C`:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress package
.\start-viewer.cmd
```

Double-clicking `start-viewer.cmd` provides the same foreground behavior. To create a portable Windows archive under the ignored `dist/` directory, run:

```powershell
.\scripts\package-viewer.ps1
```

## Connecting PostgreSQL

Synthetic mode is intentionally the default. To use a PostgreSQL database:

1. Copy `viewer.config.example.json` to `viewer.config.json`.
2. Configure the JDBC URL, a read-only database user, radar logical field mappings, and a non-sensitive display label. Add the optional `database.rfScanner` block when an RF scanner observation table is available.
3. Keep `viewer.config.json` local. It is excluded by `.gitignore`.
4. Supply the password through `RADAR_DB_PASSWORD` or the launcher's secure prompt; passwords are not accepted in the JSON file.

5. Start the foreground launcher with `.\start-viewer.cmd`.

The launcher uses synthetic mode when `viewer.config.json` is absent and PostgreSQL mode when the file is present. Run `.\launcher\start-viewer.ps1 -Demo` to explicitly ignore a local configuration for one run.

For direct JAR, Maven, container, or CI runs, the same settings can be supplied with environment variables. The Windows launcher validates the JSON file and passes its non-secret settings to the child process through these variables; the password remains runtime-only.

| Variable | Purpose |
|---|---|
| `RADAR_DEMO_ENABLED` | Enables or disables deterministic synthetic mode |
| `RADAR_DB_JDBC_URL` | PostgreSQL JDBC URL |
| `RADAR_DB_USERNAME` | Read-only database user |
| `RADAR_DB_PASSWORD` | Database password for the current process |
| `RADAR_DB_DISPLAY_LABEL` | Non-sensitive label shown in the UI |
| `RADAR_DB_SCHEMA` | Schema identifier |
| `RADAR_DB_TABLE` | Event table identifier |
| `RADAR_DB_COLUMN_*` | Logical-to-physical field mappings |
| `RADAR_DB_RF_SCANNER_TABLE` | Optional RF scanner observation table identifier |
| `RADAR_DB_RF_SCANNER_COLUMN_*` | Optional RF scanner logical-to-physical field mappings |
| `RADAR_VIEWER_HOST` | HTTP bind address; default `127.0.0.1` |
| `RADAR_VIEWER_PORT` | HTTP port; default `28080` |
| `RADAR_VIEWER_MAP_TILE_URL` | Optional map tile URL template; empty uses the coordinate-grid fallback |
| `RADAR_VIEWER_MAP_ATTRIBUTION` | Attribution displayed for the configured tile provider |

The example configuration documents every supported mapping. Do not commit a populated local configuration. If PostgreSQL is configured entirely through environment variables, set `RADAR_DEMO_ENABLED=false` and provide every required database and column mapping.

The Content Security Policy permits tile images from the application's own origin or an `https://` URL. A cross-origin `http://` tile URL is blocked by the browser; use a same-origin endpoint, HTTPS provider, or leave `RADAR_VIEWER_MAP_TILE_URL` empty for the coordinate-grid fallback.

The optional `database.rfScanner` block contains `table` and `columns`. Its required logical columns are `eventId`, `observedAt`, `scannerId`, `trackId`, `longitude`, `latitude`, and `altitude`. `fallbackObservedAt`, `objectId`, `homeLongitude`/`homeLatitude`, and `homeAltitude` enable optional capabilities when those physical columns exist. Every mapping key remains present in JSON; an optional capability is disabled when its mapped physical column is absent. A fallback timestamp is used only when the primary timestamp is missing or invalid. If the RF scanner table is absent or incomplete, a ready Radar source remains available.

> The application is unauthenticated and designed for loopback use. Do not bind it to a public or shared network interface without adding an authentication and transport-security layer.

## How to read the metrics

The horizontal value is the geodesic displacement between the raw and corrected coordinates. The altitude delta is `corrected altitude - raw altitude`.

These values measure how far a correction moved a sample. They do **not** prove that the corrected sample is more accurate. Accuracy requires an independent ground-truth position and compatible altitude reference systems.

## Architecture

The application uses a small Spring Boot API, read-only JDBC repositories, a dependency-free browser UI, Leaflet for the map, and a canvas altitude chart. Radar and optional RF scanner adapters expose one logical observation contract in both the in-memory demo and PostgreSQL modes.

See [Architecture](docs/ARCHITECTURE.md) for components, query flow, trust boundaries, and design decisions.

## Performance

Large time ranges are summarized with deterministic, track-aware overview selection rather than truncating the beginning or end of the range. Radar and RF scanner points share one global response budget. The UI reports full-range source statistics separately from representative points and further reduces visual density according to the viewport while preserving the selected observation and its nearest cross-source pair.

No production benchmark is claimed. [Performance](docs/PERFORMANCE.md) defines a reproducible synthetic benchmark protocol and the measurements required for a fair comparison.

## Dependency transparency

- Maven dependencies are declared in `pom.xml`; no application JAR is committed.
- `package` and `verify` generate `target/bom.cdx.json`, a CycloneDX SBOM for runtime dependencies.
- CI verifies Linux and Windows builds and performs a synthetic-mode API smoke test.
- Dependabot checks Maven and GitHub Actions dependencies every week.
- A release maintainer must review the SBOM against upstream license terms and keep [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) current. An automated SBOM is evidence, not a substitute for that legal review.

## API

The browser uses these read-only endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /api/meta` | Mode, source capabilities, combined and per-source time ranges, limits, and map configuration |
| `GET /api/observations` | Integrated Radar/RFSCNR snapshot or range observations |
| `GET /api/sensors` | Radar and RF scanner identifiers present in a range |
| `GET /api/tracks` | Legacy Radar-only snapshot or range samples |
| `GET /api/radars` | Legacy Radar-only identifiers in a range |
| `GET /api/objects/{objectNo}/detail` | Precise samples for one selected track |

Compact time inputs accept `yyyyMMdd`, `yyyyMMddHH`, `yyyyMMddHHmm`, `yyyyMMddHHmmss`, or `yyyyMMddHHmmssSSS`. Missing time components are filled with zero. Equal `from` and `to` values select snapshot behavior; different values select an inclusive range. Repeated or comma-separated `source=RADAR` and `source=RFSCNR` values, plus repeated or comma-separated `radarId` and `rfScannerId` values, support independent source and sensor selection.

At startup the browser requests only `/api/meta`; it does not load observations automatically. Opening an empty sensor picker can request `/api/sensors` for that source and the current valid range. A normal load handles `/api/observations` before refreshing `/api/sensors`, so the two database queries do not start simultaneously. After the first load, changing a source toggle immediately reruns the observation query.

All `/api` responses include `Cache-Control: no-store, max-age=0`. See the [API reference](docs/API.md) for parameters, response fields, snapshot candidate semantics, source warnings, and error responses.

## Security and privacy

- Synthetic mode is safe to demonstrate and contains no operational data.
- The server binds to `127.0.0.1` by default and has no authentication layer.
- External database access should use a dedicated role with `SELECT` only.
- JDBC read-only mode is a safety hint, not a replacement for database permissions.
- Secrets must be supplied at runtime and must not be committed, logged, or included in screenshots.
- Map tiles may be requested from the configured provider. Use an approved HTTPS or same-origin provider, or the coordinate-grid fallback for offline or sensitive environments.

Please read [SECURITY.md](SECURITY.md) before reporting a vulnerability or connecting non-demo data.

## Project documentation

- [Korean README](README.ko.md)
- [Architecture](docs/ARCHITECTURE.md)
- [API reference](docs/API.md)
- [Performance and benchmark protocol](docs/PERFORMANCE.md)
- [Security policy](SECURITY.md)
- [Third-party notices](THIRD-PARTY-NOTICES.md)

## License

The project is licensed under the [Apache License 2.0](LICENSE).

Map data and third-party libraries retain their own licenses and attribution requirements. OpenStreetMap attribution remains visible when its standard tile service is configured. Release archives should include `LICENSE`, [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), relevant license texts, and the CycloneDX SBOM.
