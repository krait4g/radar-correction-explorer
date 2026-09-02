package io.github.krait4g.radarexplorer.config;

import io.github.krait4g.radarexplorer.repository.RadarEventRepository;
import io.github.krait4g.radarexplorer.repository.SchemaCapabilities;
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
}
