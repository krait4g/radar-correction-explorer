# Radar Correction Explorer

[English README](README.md)

Radar Correction Explorer는 레이다 원본·보정 트랙과 선택적인 RF 스캐너 관측을 2D 지도와 고도 차트에서 함께 비교하는 로컬 읽기 전용 도구입니다. 별도 설정 없이 결정적인 합성 데이터로 시작하므로 데이터베이스, 계정, 사설 인프라 없이 전체 UI를 확인할 수 있습니다.

![Radar Correction Explorer 합성 데이터 데모](docs/images/radar-correction-explorer-demo.jpg)

## 프로젝트 목적

보정 좌표는 같은 샘플의 원본 좌표, 이동 거리, 트랙 이력, 고도 변화를 함께 볼 때 더 쉽게 검증할 수 있습니다. 이 도구는 데이터를 생성하는 시스템과 분리된 상태에서 그 비교를 시각적·수치적으로 제공합니다.

주요 기능:

- 원본과 보정 샘플을 서로 다른 마커 모양으로 표시합니다.
- Radar와 RF scanner 레이어를 독립적으로 켜고 끄며 소스별 장비 ID를 여러 개 선택할 수 있습니다.
- 같은 오브젝트는 항상 같은 결정적 색상을 사용합니다.
- 원본/보정 한 쌍을 선택하고 연결해 수치를 확인할 수 있습니다.
- 저장된 글로벌 Object ID로 기존 소스 간 연결을 보여 주되 새로운 매칭을 추론하거나 저장하지 않습니다.
- 글로벌 오브젝트를 선택하면 현재 표시 표본 중 반대 소스의 시간상 최근접 관측 하나만 연결해 선의 폭증을 막습니다.
- 원본과 보정 고도를 같은 시간축에 표시합니다.
- RF 타깃의 홈 기준 상대고도와 홈의 평균 해수면 기준 고도는 별도 값으로 유지하며 임의로 더하지 않습니다.
- 범위 조회는 제한된 개요 데이터를 사용하고, 선택 트랙은 정밀 데이터로 다시 조회합니다.
- 보정 좌표가 없으면 결측 상태를 유지하며 `0`으로 바꾸지 않습니다.
- 사용자가 범위를 요청하기 전에는 트랙 데이터를 자동 조회하지 않습니다.

## 60초 데모

준비물:

- JDK 21
- Git

Maven Wrapper가 첫 실행 시 프로젝트에 고정된 Maven 버전을 내려받습니다.

### Windows PowerShell

```powershell
git clone https://github.com/krait4g/radar-correction-explorer.git
Set-Location radar-correction-explorer
.\mvnw.cmd spring-boot:run
```

### macOS 또는 Linux

```bash
git clone https://github.com/krait4g/radar-correction-explorer.git
cd radar-correction-explorer
./mvnw spring-boot:run
```

[http://127.0.0.1:28080](http://127.0.0.1:28080)을 열고 다음 범위를 조회합니다.

```text
From  202601011200
To    202601011210
```

기본 데이터는 고정 시드로 로컬에서 생성됩니다. 애플리케이션과 API에는 합성 데모 데이터임이 표시됩니다.

두 소스 토글을 모두 켜면 합성 Radar와 RF 스캐너 관측을 함께 볼 수 있습니다. 장비 선택기에는 여러 합성 ID가 있으며 매칭·미매칭 RF 트랙과 대체 시각 예제도 포함됩니다.

포그라운드 프로세스는 `Ctrl+C`로 종료합니다.

## 빌드와 테스트

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

macOS 또는 Linux:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

패키징된 애플리케이션 실행:

```bash
java -jar target/radar-correction-explorer.jar
```

패키징된 JAR도 별도 설정이 없으면 합성 데모로 시작합니다.

### Windows 포그라운드 런처

저장소에는 콘솔을 유지하고, 상태 엔드포인트가 준비된 뒤 브라우저를 열며, `Ctrl+C`로 종료되는 포그라운드 런처도 포함됩니다.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress package
.\start-viewer.cmd
```

`start-viewer.cmd`를 더블 클릭해도 같은 방식으로 실행됩니다. 무시되는 `dist/` 디렉터리에 휴대용 Windows 압축 파일을 만들려면 다음 명령을 실행합니다.

```powershell
.\scripts\package-viewer.ps1
```

## PostgreSQL 연결

합성 모드가 의도적인 기본값입니다. PostgreSQL을 사용하려면 다음과 같이 설정합니다.

1. `viewer.config.example.json`을 `viewer.config.json`으로 복사합니다.
2. JDBC URL, 읽기 전용 DB 사용자, Radar 논리 필드 매핑, 민감하지 않은 표시명을 설정합니다. RF 스캐너 관측 테이블이 있으면 선택적인 `database.rfScanner` 블록을 추가합니다.
3. `viewer.config.json`은 로컬에만 둡니다. 이 파일은 `.gitignore`에서 제외됩니다.
4. 비밀번호는 `RADAR_DB_PASSWORD` 또는 런처의 보안 프롬프트로 전달합니다. JSON 파일에는 비밀번호를 넣을 수 없습니다.
5. `.\start-viewer.cmd`로 포그라운드 런처를 실행합니다.

런처는 `viewer.config.json`이 없으면 합성 모드, 파일이 있으면 PostgreSQL 모드로 실행합니다. 한 번만 로컬 설정을 무시하려면 `.\launcher\start-viewer.ps1 -Demo`를 실행합니다.

JAR, Maven, 컨테이너 또는 CI에서 직접 실행할 때는 같은 설정을 환경변수로 전달할 수 있습니다. Windows 런처는 JSON을 검증한 뒤 비밀값이 아닌 설정을 이 환경변수로 자식 프로세스에 전달하고, 비밀번호는 런타임에만 유지합니다.

| 환경변수 | 용도 |
|---|---|
| `RADAR_DEMO_ENABLED` | 결정적 합성 모드 활성화 여부 |
| `RADAR_DB_JDBC_URL` | PostgreSQL JDBC URL |
| `RADAR_DB_USERNAME` | 읽기 전용 DB 사용자 |
| `RADAR_DB_PASSWORD` | 현재 프로세스의 DB 비밀번호 |
| `RADAR_DB_DISPLAY_LABEL` | UI에 보이는 민감하지 않은 표시명 |
| `RADAR_DB_SCHEMA` | 스키마 식별자 |
| `RADAR_DB_TABLE` | 이벤트 테이블 식별자 |
| `RADAR_DB_COLUMN_*` | 논리 필드와 물리 필드의 매핑 |
| `RADAR_DB_RF_SCANNER_TABLE` | 선택적인 RF 스캐너 관측 테이블 식별자 |
| `RADAR_DB_RF_SCANNER_COLUMN_*` | 선택적인 RF 스캐너 논리 필드와 물리 필드의 매핑 |
| `RADAR_VIEWER_HOST` | HTTP 바인드 주소, 기본값 `127.0.0.1` |
| `RADAR_VIEWER_PORT` | HTTP 포트, 기본값 `28080` |
| `RADAR_VIEWER_MAP_TILE_URL` | 선택적인 지도 타일 URL 템플릿, 비워 두면 좌표 격자 폴백 사용 |
| `RADAR_VIEWER_MAP_ATTRIBUTION` | 설정한 타일 공급자의 저작자 표시 |

지원하는 매핑은 예제 설정 파일에 설명되어 있습니다. 값이 채워진 로컬 설정은 커밋하지 마세요. PostgreSQL을 환경변수만으로 설정한다면 `RADAR_DEMO_ENABLED=false`와 필요한 DB·컬럼 매핑을 모두 전달해야 합니다.

현재 Content Security Policy는 애플리케이션과 동일한 오리진 또는 `https://` URL의 타일 이미지만 허용합니다. 다른 오리진의 `http://` 타일 URL은 브라우저에서 차단되므로 동일 오리진 엔드포인트, HTTPS 공급자를 사용하거나 `RADAR_VIEWER_MAP_TILE_URL`을 비워 좌표 격자 폴백을 사용하세요.

선택적인 `database.rfScanner` 블록에는 `table`과 `columns`가 있습니다. 필수 논리 컬럼은 `eventId`, `observedAt`, `scannerId`, `trackId`, `longitude`, `latitude`, `altitude`입니다. `fallbackObservedAt`, `objectId`, `homeLongitude`/`homeLatitude`, `homeAltitude`는 실제 물리 컬럼이 있을 때 선택 기능을 활성화합니다. JSON에는 모든 매핑 키를 남겨 두며, 매핑된 물리 컬럼이 없으면 해당 선택 기능만 비활성화됩니다. 대체 시각은 기본 시각이 없거나 유효하지 않을 때만 사용합니다. RF 스캐너 테이블이 없거나 불완전해도 준비된 Radar 소스는 계속 사용할 수 있습니다.

> 이 애플리케이션에는 인증 기능이 없으며 루프백 사용을 전제로 합니다. 인증과 전송 보안을 추가하지 않은 상태로 공용 또는 공유 네트워크 인터페이스에 바인드하지 마세요.

## 수치 해석

수평 값은 원본 좌표와 보정 좌표 사이의 측지 이동 거리입니다. 고도 차는 `보정 고도 - 원본 고도`입니다.

이 값은 보정으로 샘플이 얼마나 이동했는지를 나타낼 뿐, 보정 좌표가 더 정확하다는 사실을 증명하지 않습니다. 정확도 판단에는 독립적인 기준 위치와 서로 호환되는 고도 기준면이 필요합니다.

## 아키텍처

애플리케이션은 작은 Spring Boot API, 읽기 전용 JDBC 저장소, 별도 프레임워크 빌드가 필요 없는 브라우저 UI, Leaflet 지도, Canvas 고도 차트로 구성됩니다. Radar와 선택적인 RF 스캐너 어댑터는 인메모리 데모와 PostgreSQL 모드에서 하나의 논리 관측 계약을 제공합니다.

구성요소, 조회 흐름, 신뢰 경계와 설계 결정은 [아키텍처 문서](docs/ARCHITECTURE.md)를 참고하세요.

## 성능

긴 시간 범위는 앞이나 뒤를 잘라내지 않고 트랙별로 결정적인 대표점을 선택합니다. Radar와 RF 스캐너 포인트는 전역 응답 예산 하나를 공유합니다. UI는 전체 범위의 원본 통계와 대표점을 구분해 표시하고, 선택한 관측과 최근접 반대 소스 한 쌍은 보존하면서 화면 크기에 맞게 표시 밀도를 조절합니다.

실운영 성능 수치는 공개하지 않습니다. [성능 문서](docs/PERFORMANCE.md)는 누구나 실행할 수 있는 합성 벤치마크 절차와 공정한 비교에 필요한 측정 항목을 정의합니다.

## 의존성 투명성

- Maven 의존성은 `pom.xml`에 선언하며 애플리케이션 JAR는 커밋하지 않습니다.
- `package`와 `verify`는 런타임 의존성의 CycloneDX SBOM인 `target/bom.cdx.json`을 생성합니다.
- CI는 Linux와 Windows 빌드를 검증하고 합성 모드 API 스모크 테스트를 수행합니다.
- Dependabot이 Maven 및 GitHub Actions 의존성을 매주 확인합니다.
- 릴리스 관리자는 SBOM과 업스트림 라이선스 조건을 대조하고 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)를 최신 상태로 유지해야 합니다. 자동 생성 SBOM은 검토 근거이지 법적 검토를 대신하지 않습니다.

## API

브라우저가 사용하는 읽기 전용 API입니다.

| 엔드포인트 | 용도 |
|---|---|
| `GET /api/meta` | 모드, 소스별 기능, 통합·소스별 시간 범위, 제한, 지도 설정 |
| `GET /api/observations` | Radar/RFSCNR 통합 스냅샷 또는 범위 관측 |
| `GET /api/sensors` | 범위에 존재하는 Radar와 RF 스캐너 식별자 |
| `GET /api/tracks` | 기존 Radar 전용 스냅샷 또는 범위 샘플 |
| `GET /api/radars` | 기존 Radar 전용 식별자 |
| `GET /api/objects/{objectNo}/detail` | 선택한 한 트랙의 정밀 샘플 |

시각 입력은 `yyyyMMdd`, `yyyyMMddHH`, `yyyyMMddHHmm`, `yyyyMMddHHmmss`, `yyyyMMddHHmmssSSS`를 지원합니다. 생략한 구성요소는 `0`으로 채웁니다. `from`과 `to`가 같으면 스냅샷, 다르면 양 끝을 포함하는 범위 조회입니다. `source=RADAR`와 `source=RFSCNR`, `radarId`, `rfScannerId`를 반복하거나 쉼표로 구분해 소스와 장비를 독립적으로 선택합니다.

브라우저는 시작할 때 `/api/meta`만 요청하며 관측을 자동 조회하지 않습니다. 비어 있는 장비 선택기를 열면 현재 유효한 범위와 해당 소스만 사용해 `/api/sensors`를 요청할 수 있습니다. 일반 조회는 `/api/observations`를 처리한 뒤 `/api/sensors`를 갱신하므로 두 DB 쿼리를 동시에 시작하지 않습니다. 첫 조회 이후 소스 토글을 바꾸면 관측을 즉시 다시 조회합니다.

모든 `/api` 응답에는 `Cache-Control: no-store, max-age=0`이 적용됩니다. 파라미터, 응답 필드, 스냅샷 후보 의미, 소스 경고와 오류 응답은 [API 문서](docs/API.md)를 참고하세요.

## 보안과 개인정보

- 합성 모드에는 운영 데이터가 없으므로 데모에 사용할 수 있습니다.
- 서버는 기본적으로 `127.0.0.1`에만 바인드하며 인증 계층이 없습니다.
- 외부 DB는 `SELECT`만 허용한 전용 역할을 사용해야 합니다.
- JDBC 읽기 전용 설정은 힌트일 뿐 DB 권한을 대체하지 않습니다.
- 비밀값을 커밋, 로그, 스크린샷에 포함하지 마세요.
- 지도 타일은 설정한 공급자에 요청될 수 있습니다. 오프라인 또는 민감 환경에서는 승인된 HTTPS·동일 오리진 공급자나 좌표 격자 폴백을 사용하세요.

취약점 제보나 비데모 데이터 연결 전 [보안 정책](SECURITY.md)을 확인하세요.

## 문서

- [English README](README.md)
- [아키텍처](docs/ARCHITECTURE.md)
- [API 문서](docs/API.md)
- [성능과 벤치마크 절차](docs/PERFORMANCE.md)
- [보안 정책](SECURITY.md)
- [제3자 고지](THIRD-PARTY-NOTICES.md)

## 라이선스

이 프로젝트는 [Apache License 2.0](LICENSE)으로 배포됩니다.

지도 데이터와 제3자 라이브러리는 각각의 라이선스와 저작자 표시 조건을 유지합니다. 표준 OpenStreetMap 타일을 사용할 때는 화면의 저작자 표시를 유지해야 합니다. 릴리스 압축 파일에는 `LICENSE`, [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), 관련 라이선스 원문과 CycloneDX SBOM을 포함해야 합니다.
