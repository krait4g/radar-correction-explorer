# Third-party notices

Radar Correction Explorer is licensed under Apache-2.0. Its executable distribution includes
third-party components under their own licenses. The following list covers the principal runtime
components; the build also creates `target/bom.cdx.json`, a CycloneDX inventory of direct and
transitive dependencies with exact versions.

| Component | Version family | License |
|---|---:|---|
| Spring Boot | 3.5.5 | Apache-2.0 |
| Spring Framework | 6.2.10 | Apache-2.0 |
| Apache Tomcat | 10.1.44 | Apache-2.0 |
| Jackson | 2.19.2 | Apache-2.0 |
| HikariCP | 6.3.2 | Apache-2.0 |
| Hibernate Validator | 8.0.3.Final | Apache-2.0 |
| PostgreSQL JDBC Driver | 42.7.7 | BSD-2-Clause |
| H2 Database Engine | 2.3.232 | [MPL-2.0](THIRD-PARTY-LICENSES/H2-2.3.232-LICENSE.txt), selected for this distribution; upstream also offers EPL-1.0 |
| Leaflet | 1.9.4 | [BSD-2-Clause](THIRD-PARTY-LICENSES/LEAFLET-1.9.4-LICENSE.txt) |
| Logback | 1.5.18 | [EPL-1.0](THIRD-PARTY-LICENSES/EPL-1.0.txt), selected for this distribution; see the [upstream dual-license notice](THIRD-PARTY-LICENSES/LOGBACK-1.5.18-LICENSE.txt) |
| SLF4J | 2.0.17 | MIT |
| Apache Log4j API/SLF4J bridge | 2.24.3 | Apache-2.0 |
| SnakeYAML | 2.4 | Apache-2.0 |
| Micrometer | 1.15.3 | Apache-2.0 |
| Checker Framework Qualifiers | 3.49.3 | MIT |

## Binary-distribution choices and source availability

Where an upstream component offers alternative licenses, the choice below applies only to that
component in this binary distribution. It does not change the Apache-2.0 license of Radar
Correction Explorer or the licenses offered by upstream projects.

### H2 Database Engine 2.3.232

- Copyright 2004-2024 H2 Group. Initial Developer: H2 Group.
- H2 is dual-licensed under MPL-2.0 or EPL-1.0. This distribution selects **MPL-2.0**.
- The complete license file from the exact upstream tag, including both upstream alternatives, is
  [included here](THIRD-PARTY-LICENSES/H2-2.3.232-LICENSE.txt).
- Corresponding source code is available from the
  [upstream `version-2.3.232` tag](https://github.com/h2database/h2database/tree/version-2.3.232)
  and the [Maven Central source archive](https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232-sources.jar).
- Verified upstream revision: `2e46a1c9680089098d756435ae94193ee72c1334`.

### Logback 1.5.18

- Copyright (C) 1999-2024, QOS.ch. All rights reserved.
- Logback is dual-licensed under EPL-1.0 or LGPL-2.1. This distribution selects **EPL-1.0**.
- The [upstream dual-license declaration](THIRD-PARTY-LICENSES/LOGBACK-1.5.18-LICENSE.txt)
  and the [complete EPL-1.0 terms](THIRD-PARTY-LICENSES/EPL-1.0.txt) are included.
- Corresponding source code is available from the
  [upstream `v_1.5.18` tag](https://github.com/qos-ch/logback/tree/v_1.5.18), the
  [logback-classic source archive](https://repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.5.18/logback-classic-1.5.18-sources.jar),
  and the [logback-core source archive](https://repo1.maven.org/maven2/ch/qos/logback/logback-core/1.5.18/logback-core-1.5.18-sources.jar).
- The license steward's canonical EPL-1.0 publication is available from the
  [Eclipse Foundation](https://www.eclipse.org/legal/epl/epl-v10.html).
- Verified upstream revision: `b2a02f065379a9b1ba5ff837fc08913b744774bc`.

### Leaflet 1.9.4

- Copyright (c) 2010-2023, Volodymyr Agafonkin.
- Copyright (c) 2010-2011, CloudMade. All rights reserved.
- The complete [BSD-2-Clause notice and disclaimer](THIRD-PARTY-LICENSES/LEAFLET-1.9.4-LICENSE.txt)
  from the exact release tag is included.
- Corresponding source code is available from the
  [upstream `v1.9.4` tag](https://github.com/Leaflet/Leaflet/tree/v1.9.4).
- Verified upstream revision: `d15112c9e8ac339f0f74f563959d0423d291308d`.

The CycloneDX SBOM is the authoritative inventory of exact resolved component versions. The files
under `THIRD-PARTY-LICENSES/` preserve the notices and license terms that are not embedded in some
upstream binary JARs. Upstream project names and trademarks belong to their respective owners.
OpenStreetMap tiles are not bundled; when an operator opts into an OpenStreetMap tile endpoint,
the attribution configured in the application must remain visible.
