package io.github.krait4g.radarexplorer.config;

import io.github.krait4g.radarexplorer.repository.RadarEventRepository;
import io.github.krait4g.radarexplorer.repository.RfScannerObservationRepository;
import io.github.krait4g.radarexplorer.repository.RfScannerSchemaCapabilities;
import io.github.krait4g.radarexplorer.repository.SchemaCapabilities;
import io.github.krait4g.radarexplorer.service.RadarViewerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "radar.db.demo-enabled=true",
                "app.viewer.database.schema-cache-seconds=0"
        }
)
class SyntheticDemoIntegrationTest {

    @Autowired
    private RadarEventRepository repository;

    @Autowired
    private RfScannerObservationRepository rfScannerRepository;

    @Autowired
    private RadarViewerService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ViewerProperties viewerProperties;

    @Test
    void seedsDeterministicTracksAcrossTheDocumentedTenMinuteRange() {
        SchemaCapabilities schema = repository.inspectSchema();
        String[] range = repository.findTimeRange();

        assertThat(schema.tableExists()).isTrue();
        assertThat(schema.isReady()).isTrue();
        assertThat(schema.toApiCapabilities().correctedLongitude()).isTrue();
        assertThat(schema.toApiCapabilities().correctedLatitude()).isTrue();
        assertThat(schema.toApiCapabilities().correctedAltitude()).isTrue();
        assertThat(schema.toApiCapabilities().primaryFlag()).isTrue();
        assertThat(schema.toApiCapabilities().referenceAltitude()).isTrue();
        assertThat(range).containsExactly("20260101120000000", "20260101121000000");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + viewerProperties.getDatabase().qualifiedTable(),
                Integer.class
        );
        assertThat(count).isEqualTo(DemoDataInitializer.EXPECTED_ROWS);
    }

    @Test
    void includesSeveralTracksAndIntentionalCorrectionGaps() {
        SchemaCapabilities schema = repository.inspectSchema();
        var points = repository.findBetween(
                schema,
                "20260101120000000",
                "20260101121000000",
                null,
                null,
                null,
                false,
                0
        );

        assertThat(points).hasSize(DemoDataInitializer.EXPECTED_ROWS);
        assertThat(points).extracting(point -> point.objectNo()).contains("1001", "1002", "2001", "3001");
        assertThat(points).anyMatch(point -> point.corrected().longitude() == null);
        assertThat(points).anyMatch(point -> point.corrected().latitude() == null);
        assertThat(points).anyMatch(point -> point.corrected().altitude() == null);
        assertThat(points).anyMatch(point -> point.referenceAltitude() == null);
        assertThat(points).anyMatch(point -> "Y".equals(point.primaryFlag()));
        assertThat(points).anyMatch(point -> "N".equals(point.primaryFlag()));
    }

    @Test
    void seedsGenericRfScannerTracksAndServesTheUnifiedApiContract() {
        RfScannerSchemaCapabilities schema = rfScannerRepository.inspectSchema();
        String[] range = rfScannerRepository.findTimeRange(schema);

        assertThat(schema.isReady()).isTrue();
        assertThat(schema.toApiCapabilities().objectMatch()).isTrue();
        assertThat(schema.toApiCapabilities().fallbackTime()).isTrue();
        assertThat(schema.toApiCapabilities().homePosition()).isTrue();
        assertThat(range).containsExactly("20260101120000000", "20260101121000000");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + viewerProperties.getDatabase().qualifiedRfScannerTable(),
                Integer.class
        );
        assertThat(count).isEqualTo(DemoDataInitializer.EXPECTED_RF_SCANNER_ROWS);

        var response = service.observations(
                "20260101120000000", "20260101121000000", 750,
                java.util.List.of("RADAR", "RFSCNR"), java.util.List.of(), java.util.List.of(),
                null, null, null, "ALL", false, true
        );
        assertThat(response.selectedSources()).containsExactly("RADAR", "RFSCNR");
        assertThat(response.radarPoints()).hasSize(DemoDataInitializer.EXPECTED_ROWS);
        assertThat(response.rfScannerPoints()).hasSize(DemoDataInitializer.EXPECTED_RF_SCANNER_ROWS);
        assertThat(response.summary().sourceRows())
                .isEqualTo(DemoDataInitializer.EXPECTED_ROWS + DemoDataInitializer.EXPECTED_RF_SCANNER_ROWS);
        assertThat(response.rfScannerPoints()).anyMatch(point -> "FALLBACK_OBSERVED_AT".equals(point.timeSource()));
        assertThat(response.rfScannerPoints()).allMatch(point -> "RELATIVE_TO_HOME".equals(point.altitudeReference()));
        assertThat(response.rfScannerPoints()).allMatch(point -> point.position() != null);
        assertThat(response.rfScannerPoints()).allMatch(point -> point.home() != null);
        assertThat(response.rfScannerPoints()).anyMatch(point -> point.matched() && "1001".equals(point.objectNo()));
        assertThat(response.rfScannerPoints()).anyMatch(point -> !point.matched() && point.objectNo() == null);
    }

    @Test
    void metaAndSensorsExposeBothReadySourcesWithoutPhysicalDatabaseDetails() {
        var meta = service.meta();
        assertThat(meta.database().status()).isEqualTo("UP");
        assertThat(meta.capabilities().radarReady()).isTrue();
        assertThat(meta.rfScannerCapabilities().ready()).isTrue();
        assertThat(meta.timeRange()).isEqualTo(new io.github.krait4g.radarexplorer.model.ApiModels.TimeRange(
                "20260101120000000", "20260101121000000"
        ));

        var sensors = service.sensors(
                "20260101120000000", "20260101121000000", 750,
                java.util.List.of("RADAR", "RFSCNR"), false
        );
        assertThat(sensors.radars()).hasSize(3);
        assertThat(sensors.rfScanners()).hasSize(2);
        assertThat(sensors.selectedSources()).containsExactly("RADAR", "RFSCNR");
    }
}
