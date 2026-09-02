package io.github.krait4g.radarexplorer.repository;

import io.github.krait4g.radarexplorer.config.DemoDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "radar.db.jdbc-url=jdbc:h2:mem:custom_mapping;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "radar.db.demo-enabled=true",
                "app.viewer.database.table=sample_track",
                "app.viewer.database.schema-cache-seconds=0",
                "app.viewer.database.columns.event-id=sample_id",
                "app.viewer.database.columns.source-event-id=origin_id",
                "app.viewer.database.columns.observed-at=sample_time",
                "app.viewer.database.columns.sensor-id=device_name",
                "app.viewer.database.columns.sensor-track-id=device_track",
                "app.viewer.database.columns.object-id=target_number",
                "app.viewer.database.columns.raw-longitude=input_lon",
                "app.viewer.database.columns.raw-latitude=input_lat",
                "app.viewer.database.columns.raw-altitude=input_alt",
                "app.viewer.database.columns.corrected-longitude=output_lon",
                "app.viewer.database.columns.corrected-latitude=output_lat",
                "app.viewer.database.columns.corrected-altitude=output_alt",
                "app.viewer.database.columns.primary-flag=selected_flag",
                "app.viewer.database.columns.reference-altitude=aux_alt"
        }
)
class CustomColumnMappingIntegrationTest {

    @Autowired
    private RadarEventRepository repository;

    @Test
    void queriesCustomPhysicalIdentifiersThroughCanonicalAliases() {
        SchemaCapabilities schema = repository.inspectSchema();

        assertThat(schema.isReady()).isTrue();
        assertThat(repository.findTimeRange())
                .containsExactly("20260101120000000", "20260101121000000");
        assertThat(repository.findBetween(
                schema,
                "20260101120000000",
                "20260101121000000",
                "SENSOR-B",
                null,
                null,
                false,
                0
        )).hasSize(DemoDataInitializer.EXPECTED_ROWS / 4);
    }
}
