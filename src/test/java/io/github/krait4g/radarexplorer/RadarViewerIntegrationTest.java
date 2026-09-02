package io.github.krait4g.radarexplorer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.viewer.database.schema=public",
        "app.viewer.database.table=radar_observation",
        "app.viewer.limits.max-query-rows=0",
        "app.viewer.limits.max-overview-points=5",
        "app.viewer.limits.max-range-seconds=0",
        "radar.db.display-label=Integration fixture",
        "radar.db.demo-enabled=false"
})
@AutoConfigureMockMvc
class RadarViewerIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestDataSourceConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        javax.sql.DataSource testDataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .driverClassName("org.h2.Driver")
                    .url("jdbc:h2:mem:radarviewer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
                    .username("sa")
                    .password("")
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RadarEventRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @BeforeEach
    void createBaseTableWithoutOptionalColumns() {
        repository.invalidateSchemaCache();
        jdbcTemplate.execute("DROP TABLE IF EXISTS radar_observation");
        jdbcTemplate.execute("""
                CREATE TABLE radar_observation (
                    event_id BIGINT NOT NULL,
                    source_event_id BIGINT NOT NULL,
                    observed_at VARCHAR(17) NOT NULL,
                    sensor_id VARCHAR(64) NOT NULL,
                    sensor_track_id BIGINT NOT NULL,
                    object_id BIGINT NOT NULL,
                    raw_longitude NUMERIC(13,8),
                    raw_latitude NUMERIC(13,8),
                    raw_altitude NUMERIC(13,3)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX ix_observation_time_object ON radar_observation(observed_at, object_id, sensor_id)");
    }

    @Test
    void largeJsonResponsesUseCompressionConfigurationAndOmitNullProperties() throws Exception {
        insert(1, "20260813120000000", "R1", "1", "1", "127.10000000", "37.20000000", "123.500");

        String body = mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813120000000")
                        .param("to", "20260813120000001"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals("true", environment.getProperty("server.compression.enabled"));
        assertEquals("1024", environment.getProperty("server.compression.min-response-size"));
        assertEquals(
                JsonInclude.Include.NON_NULL,
                objectMapper.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion()
        );
        assertFalse(body.contains("\"referenceAltitude\":null"));
        assertFalse(body.contains("\"horizontalCorrectionMeters\":null"));
        assertFalse(body.contains("\"longitude\":null"));
    }

    @Test
    void schemaCapabilitiesAreCachedAndCanBeExplicitlyRefreshed() {
        assertFalse(repository.inspectSchema().has("CORRECTED_LONGITUDE"));
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN corrected_longitude NUMERIC(13,8)");

        assertFalse(repository.inspectSchema().has("CORRECTED_LONGITUDE"));
        repository.invalidateSchemaCache();
        assertTrue(repository.inspectSchema().has("CORRECTED_LONGITUDE"));
    }

    @Test
    void missingCalcColumnsAreReportedAndReturnedAsNull() throws Exception {
        insert(1, "20260813120000000", "R1", "1", "1", "127.10000000", "37.20000000", "123.500");

        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.capabilities.correctedLongitude").value(false))
                .andExpect(jsonPath("$.capabilities.correctedLatitude").value(false))
                .andExpect(jsonPath("$.capabilities.correctedAltitude").value(false))
                .andExpect(jsonPath("$.map.maxNativeZoom").value(19))
                .andExpect(jsonPath("$.map.maxZoom").value(24));

        mockMvc.perform(get("/api/snapshot")
                        .param("at", "20260813120000000")
                        .param("toleranceMs", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.points[0].raw.longitude").value(127.1))
                .andExpect(jsonPath("$.points[0].corrected.longitude").doesNotExist())
                .andExpect(jsonPath("$.points[0].corrected.latitude").doesNotExist())
                .andExpect(jsonPath("$.points[0].corrected.altitude").doesNotExist())
                .andExpect(jsonPath("$.points[0].correctionStatus").value("RAW_ONLY"));
    }

    @Test
    void snapshotChoosesNearestRowPerObjectAndUsesEarlierRowForTie() throws Exception {
        insert(10, "20260813115959900", "R1", "101", "1001", "127.00000000", "37.00000000", "100.000");
        insert(11, "20260813120000100", "R1", "101", "1001", "128.00000000", "38.00000000", "110.000");
        insert(12, "20260813120000200", "R1", "102", "1002", "129.00000000", "39.00000000", "120.000");

        mockMvc.perform(get("/api/snapshot")
                        .param("at", "20260813120000000")
                        .param("toleranceMs", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.sourceRows").value(3))
                .andExpect(jsonPath("$.summary.objectCount").value(2))
                .andExpect(jsonPath("$.points", hasSize(2)))
                .andExpect(jsonPath("$.points[0].objectNo").value("1001"))
                .andExpect(jsonPath("$.points[0].eventTime").value("20260813115959900"))
                .andExpect(jsonPath("$.points[0].raw.longitude").value(127.0))
                .andExpect(jsonPath("$.points[1].objectNo").value("1002"));
    }

    @Test
    void presentCalcColumnsReturnCorrectedPositionAndAltitudeDelta() throws Exception {
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN corrected_longitude NUMERIC(13,8)");
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN corrected_latitude NUMERIC(13,8)");
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN corrected_altitude NUMERIC(13,3)");
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN primary_flag VARCHAR(1)");
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN reference_altitude NUMERIC(13,3)");
        insert(20, "20260813120000000", "R1", "201", "2001", "127.10000000", "37.20000000", "100.000");
        jdbcTemplate.update("""
                UPDATE radar_observation
                   SET corrected_longitude = 127.10010000,
                       corrected_latitude = 37.20010000,
                       corrected_altitude = 110.000,
                       primary_flag = 'Y',
                       reference_altitude = 109.500
                 WHERE event_id = 20
                """);

        mockMvc.perform(get("/api/snapshot")
                        .param("at", "20260813120000000")
                        .param("toleranceMs", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.points[0].corrected.longitude").value(127.1001))
                .andExpect(jsonPath("$.points[0].corrected.latitude").value(37.2001))
                .andExpect(jsonPath("$.points[0].corrected.altitude").value(110.0))
                .andExpect(jsonPath("$.points[0].altitudeDeltaMeters").value(10.0))
                .andExpect(jsonPath("$.points[0].horizontalCorrectionMeters").isNumber())
                .andExpect(jsonPath("$.points[0].correctionStatus").value("CORRECTED"));
    }

    @Test
    void nonNumericObjectFilterIsRejected() throws Exception {
        mockMvc.perform(get("/api/snapshot")
                        .param("at", "20260813120000000")
                        .param("objectNo", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OBJECT_ID"));
    }

    @ParameterizedTest
    @CsvSource({
            "20260813,20260813000000000",
            "2026081314,20260813140000000",
            "202608131417,20260813141700000",
            "20260813141733,20260813141733000",
            "20260813141733123,20260813141733123"
    })
    void compactTimeVariantsAreStrictlyNormalized(String requested, String normalized) throws Exception {
        mockMvc.perform(get("/api/tracks")
                        .param("from", requested)
                        .param("to", requested)
                        .param("toleranceMs", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SNAPSHOT"))
                .andExpect(jsonPath("$.requestedFrom").value(requested))
                .andExpect(jsonPath("$.normalizedFrom").value(normalized))
                .andExpect(jsonPath("$.normalizedTo").value(normalized));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026081",
            "202608131",
            "20260813141",
            "2026081314173",
            "202608131417331",
            "20260230",
            "20261301",
            "2026081324",
            "202608131460",
            "20260813141760",
            "2026O813"
    })
    void invalidCompactTimesAreRejected(String invalid) throws Exception {
        mockMvc.perform(get("/api/tracks")
                        .param("from", invalid)
                        .param("to", invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OBSERVED_TIME"));
    }

    @Test
    void rangeReturnsEveryRowWhileEqualTimesCollapseToNearestObjectRow() throws Exception {
        insert(30, "20260813141700000", "R1", "301", "3001", "127.00000000", "37.00000000", "100.000");
        insert(31, "20260813141710000", "R1", "301", "3001", "127.00010000", "37.00010000", "101.000");
        insert(32, "20260813141720000", "R1", "301", "3001", "127.00020000", "37.00020000", "102.000");

        mockMvc.perform(get("/api/tracks")
                        .param("from", "202608131417")
                        .param("to", "20260813141730"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RANGE"))
                .andExpect(jsonPath("$.normalizedFrom").value("20260813141700000"))
                .andExpect(jsonPath("$.normalizedTo").value("20260813141730000"))
                .andExpect(jsonPath("$.summary.sourceRows").value(3))
                .andExpect(jsonPath("$.summary.objectCount").value(1))
                .andExpect(jsonPath("$.points", hasSize(3)))
                .andExpect(jsonPath("$.points[0].eventTime").value("20260813141700000"))
                .andExpect(jsonPath("$.points[2].eventTime").value("20260813141720000"));

        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813141710")
                        .param("to", "20260813141710")
                        .param("toleranceMs", "15000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SNAPSHOT"))
                .andExpect(jsonPath("$.summary.sourceRows").value(3))
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.points[0].eventTime").value("20260813141710000"));
    }

    @Test
    void rangeOverviewStreamsAndDeterministicallySamplesEachPhysicalTrack() throws Exception {
        for (int index = 0; index < 5; index++) {
            insert(100 + index, "2026081314180" + index + "000", "RA3", "31", "9001",
                    "127.0000000" + index, "37.0000000" + index, Integer.toString(100 + index));
        }
        for (int index = 0; index < 3; index++) {
            insert(200 + index, "2026081314180" + index + "500", "RA5", "51", "9002",
                    "128.0000000" + index, "38.0000000" + index, Integer.toString(200 + index));
        }

        String first = mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813141800000")
                        .param("to", "20260813141810000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampling.sampled").value(true))
                .andExpect(jsonPath("$.sampling.strategy").value("TRACK_ENDPOINT_PERIODIC"))
                .andExpect(jsonPath("$.sampling.sourceRows").value(8))
                .andExpect(jsonPath("$.sampling.returnedRows").value(5))
                .andExpect(jsonPath("$.sampling.trackCount").value(2))
                .andExpect(jsonPath("$.sampling.representedTracks").value(2))
                .andExpect(jsonPath("$.sampling.allTracksRepresented").value(true))
                .andExpect(jsonPath("$.sampling.metricsScope").value("FULL_RANGE"))
                .andExpect(jsonPath("$.sampling.p95Approximate").value(false))
                .andExpect(jsonPath("$.summary.sourceRows").value(8))
                .andExpect(jsonPath("$.summary.rawPositionCount").value(8))
                .andExpect(jsonPath("$.summary.objectCount").value(2))
                .andExpect(jsonPath("$.points", hasSize(5)))
                .andExpect(jsonPath("$.points[0].eventId").value(100))
                .andExpect(jsonPath("$.points[1].eventId").value(200))
                .andExpect(jsonPath("$.points[2].eventId").value(102))
                .andExpect(jsonPath("$.points[3].eventId").value(202))
                .andExpect(jsonPath("$.points[4].eventId").value(104))
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813141800000")
                        .param("to", "20260813141810000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
    }

    @Test
    void overviewSpreadsBudgetDeterministicallyWhenTrackCountExceedsPointBudget() throws Exception {
        for (int index = 0; index < 6; index++) {
            insert(250 + index, "2026081314182" + index + "000", "RA" + index,
                    Integer.toString(80 + index), Integer.toString(9200 + index),
                    "127.2000000" + index, "37.2000000" + index, Integer.toString(250 + index));
        }

        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813141820000")
                        .param("to", "20260813141830000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampling.sampled").value(true))
                .andExpect(jsonPath("$.sampling.strategy").value("TRACK_SPREAD_FIRST"))
                .andExpect(jsonPath("$.sampling.sourceRows").value(6))
                .andExpect(jsonPath("$.sampling.returnedRows").value(5))
                .andExpect(jsonPath("$.sampling.trackCount").value(6))
                .andExpect(jsonPath("$.sampling.representedTracks").value(5))
                .andExpect(jsonPath("$.sampling.allTracksRepresented").value(false))
                .andExpect(jsonPath("$.sampling.metricsScope").value("FULL_RANGE"))
                .andExpect(jsonPath("$.summary.sourceRows").value(6))
                .andExpect(jsonPath("$.summary.objectCount").value(6))
                .andExpect(jsonPath("$.points", hasSize(5)))
                .andExpect(jsonPath("$.points[0].eventId").value(250))
                .andExpect(jsonPath("$.points[1].eventId").value(251))
                .andExpect(jsonPath("$.points[2].eventId").value(253))
                .andExpect(jsonPath("$.points[3].eventId").value(254))
                .andExpect(jsonPath("$.points[4].eventId").value(255));
    }

    @Test
    void exactRangeRequiresObjectAndReturnsAllRowsBeyondOverviewBudget() throws Exception {
        for (int index = 0; index < 7; index++) {
            insert(300 + index, "2026081314190" + index + "000", "RA5", "71", "9100",
                    "127.1000000" + index, "37.1000000" + index, Integer.toString(300 + index));
        }
        insert(399, "20260813141908000", "RA5", "72", "9100",
                "127.20000000", "37.20000000", "399");

        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813141900000")
                        .param("to", "20260813141910000")
                        .param("overview", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OBJECT_FILTER_REQUIRED"));

        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813141900000")
                        .param("to", "20260813141910000")
                        .param("objectNo", "9100")
                        .param("radarId", "RA5")
                        .param("radarObjectNo", "71")
                        .param("overview", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampling.sampled").value(false))
                .andExpect(jsonPath("$.sampling.strategy").value("NONE"))
                .andExpect(jsonPath("$.sampling.sourceRows").value(7))
                .andExpect(jsonPath("$.sampling.returnedRows").value(7))
                .andExpect(jsonPath("$.sampling.metricsScope").value("FULL_RANGE"))
                .andExpect(jsonPath("$.summary.sourceRows").value(7))
                .andExpect(jsonPath("$.points", hasSize(7)))
                .andExpect(jsonPath("$.points[0].eventId").value(300))
                .andExpect(jsonPath("$.points[6].eventId").value(306));
    }

    @Test
    void primaryFlagAndReferenceAltitudeSurviveOverviewExactSnapshotAndDetailWithZeroDistinctFromNull() throws Exception {
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN primary_flag VARCHAR(1)");
        jdbcTemplate.execute("ALTER TABLE radar_observation ADD COLUMN reference_altitude NUMERIC(13,3)");
        repository.invalidateSchemaCache();

        for (int index = 0; index < 7; index++) {
            insert(500 + index, "2026081314200" + index + "000", "RA9", "99", "9400",
                    "127.3000000" + index, "37.3000000" + index, Integer.toString(500 + index));
        }
        jdbcTemplate.update("UPDATE radar_observation SET primary_flag = 'Y', reference_altitude = 0.000 WHERE event_id = 500");
        jdbcTemplate.update("UPDATE radar_observation SET primary_flag = 'N' WHERE event_id = 501");
        jdbcTemplate.update("UPDATE radar_observation SET primary_flag = 'N', reference_altitude = 102.250 WHERE event_id = 502");
        jdbcTemplate.update("UPDATE radar_observation SET primary_flag = 'Y', reference_altitude = 105.500 WHERE event_id = 505");
        jdbcTemplate.update("UPDATE radar_observation SET primary_flag = 'N', reference_altitude = 106.500 WHERE event_id = 506");

        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813142000000")
                        .param("to", "20260813142010000")
                        .param("primaryOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampling.sampled").value(true))
                .andExpect(jsonPath("$.sampling.returnedRows").value(5))
                .andExpect(jsonPath("$.points[0].eventId").value(500))
                .andExpect(jsonPath("$.points[0].primaryFlag").value("Y"))
                .andExpect(jsonPath("$.points[0].referenceAltitude").value(0.0))
                .andExpect(jsonPath("$.points[1].eventId").value(502))
                .andExpect(jsonPath("$.points[1].primaryFlag").value("N"))
                .andExpect(jsonPath("$.points[1].referenceAltitude").value(102.25))
                .andExpect(jsonPath("$.points[2].eventId").value(503))
                .andExpect(jsonPath("$.points[2].primaryFlag").doesNotExist())
                .andExpect(jsonPath("$.points[2].referenceAltitude").doesNotExist());

        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813142000000")
                        .param("to", "20260813142010000")
                        .param("radarId", "RA9")
                        .param("radarObjectNo", "99")
                        .param("objectNo", "9400")
                        .param("primaryOnly", "false")
                        .param("overview", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampling.sampled").value(false))
                .andExpect(jsonPath("$.points", hasSize(7)))
                .andExpect(jsonPath("$.points[0].primaryFlag").value("Y"))
                .andExpect(jsonPath("$.points[0].referenceAltitude").value(0.0))
                .andExpect(jsonPath("$.points[1].primaryFlag").value("N"))
                .andExpect(jsonPath("$.points[1].referenceAltitude").doesNotExist())
                .andExpect(jsonPath("$.points[3].primaryFlag").doesNotExist())
                .andExpect(jsonPath("$.points[3].referenceAltitude").doesNotExist());

        mockMvc.perform(get("/api/snapshot")
                        .param("at", "20260813142000000")
                        .param("toleranceMs", "0")
                        .param("objectNo", "9400")
                        .param("primaryOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.points[0].primaryFlag").value("Y"))
                .andExpect(jsonPath("$.points[0].referenceAltitude").value(0.0));

        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813142000000")
                        .param("to", "20260813142000000")
                        .param("toleranceMs", "0")
                        .param("objectNo", "9400")
                        .param("primaryOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SNAPSHOT"))
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.points[0].primaryFlag").value("Y"))
                .andExpect(jsonPath("$.points[0].referenceAltitude").value(0.0));

        mockMvc.perform(get("/api/objects/9400/detail")
                        .param("at", "20260813142003000")
                        .param("windowSeconds", "30")
                        .param("radarId", "RA9")
                        .param("radarObjectNo", "99")
                        .param("primaryOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(7)))
                .andExpect(jsonPath("$.points[0].primaryFlag").value("Y"))
                .andExpect(jsonPath("$.points[0].referenceAltitude").value(0.0))
                .andExpect(jsonPath("$.points[1].primaryFlag").value("N"))
                .andExpect(jsonPath("$.points[1].referenceAltitude").doesNotExist())
                .andExpect(jsonPath("$.points[3].primaryFlag").doesNotExist())
                .andExpect(jsonPath("$.points[3].referenceAltitude").doesNotExist());
    }

    @Test
    void reverseRangeIsRejected() throws Exception {
        mockMvc.perform(get("/api/tracks")
                        .param("from", "202608131418")
                        .param("to", "202608131417"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void zeroRangeLimitAllowsTheRequestedEightyMinuteRange() throws Exception {
        mockMvc.perform(get("/api/tracks")
                        .param("from", "20260813160000000")
                        .param("to", "20260813172000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RANGE"))
                .andExpect(jsonPath("$.normalizedFrom").value("20260813160000000"))
                .andExpect(jsonPath("$.normalizedTo").value("20260813172000000"))
                .andExpect(jsonPath("$.summary.sourceRows").value(0))
                .andExpect(jsonPath("$.points", hasSize(0)));

        mockMvc.perform(get("/api/radars")
                        .param("from", "20260813160000000")
                        .param("to", "20260813172000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RANGE"))
                .andExpect(jsonPath("$.radars", hasSize(0)));
    }

    @Test
    void radarsEndpointCountsEventsAndDistinctObjectsInTheRequestedRange() throws Exception {
        insert(40, "20260813141700000", "R2", "401", "4001", "127.0", "37.0", "100");
        insert(41, "20260813141701000", "R1", "402", "4002", "127.0", "37.0", "100");
        insert(42, "20260813141702000", "R1", "403", "4002", "127.0", "37.0", "100");
        insert(43, "20260813141703000", "R1", "404", "4003", "127.0", "37.0", "100");

        org.junit.jupiter.api.Assertions.assertEquals(
                2,
                repository.findRadarsBetween(
                        repository.inspectSchema(),
                        "20260813141700000",
                        "20260813141710000",
                        true
                ).size()
        );

        mockMvc.perform(get("/api/radars")
                        .param("from", "202608131417")
                        .param("to", "20260813141710"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RANGE"))
                .andExpect(jsonPath("$.radars", hasSize(2)))
                .andExpect(jsonPath("$.radars[0].radarId").value("R1"))
                .andExpect(jsonPath("$.radars[0].eventCount").value(3))
                .andExpect(jsonPath("$.radars[0].objectCount").value(2))
                .andExpect(jsonPath("$.radars[1].radarId").value("R2"))
                .andExpect(jsonPath("$.radars[1].eventCount").value(1));
    }

    @Test
    void metaExposesOnlySafeConnectionLabelAndDemoMode() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database.connectionLabel").value("Integration fixture"))
                .andExpect(jsonPath("$.database.syntheticDemo").value(false))
                .andExpect(jsonPath("$.database.host").doesNotExist())
                .andExpect(jsonPath("$.database.port").doesNotExist())
                .andExpect(jsonPath("$.database.name").doesNotExist())
                .andExpect(jsonPath("$.database.username").doesNotExist())
                .andExpect(jsonPath("$.database.password").doesNotExist())
                .andExpect(jsonPath("$.limits.maxRangeSeconds").value(0))
                .andExpect(jsonPath("$.limits.maxQueryRows").value(0))
                .andExpect(jsonPath("$.limits.maxOverviewPoints").value(5));
    }

    private void insert(
            long sequence,
            String eventTime,
            String radarId,
            String radarObjectNo,
            String objectNo,
            String longitude,
            String latitude,
            String altitude
    ) {
        jdbcTemplate.update("""
                        INSERT INTO radar_observation (
                            event_id, source_event_id, observed_at, sensor_id,
                            sensor_track_id, object_id, raw_longitude, raw_latitude, raw_altitude
                        ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)
                        """,
                sequence, eventTime, radarId, radarObjectNo, objectNo,
                new java.math.BigDecimal(longitude), new java.math.BigDecimal(latitude), new java.math.BigDecimal(altitude)
        );
    }
}
