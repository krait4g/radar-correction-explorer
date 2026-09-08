package io.github.krait4g.radarexplorer.service;

import io.github.krait4g.radarexplorer.config.RadarDatabaseProperties;
import io.github.krait4g.radarexplorer.config.ViewerProperties;
import io.github.krait4g.radarexplorer.model.ApiModels.Coordinate;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarPoint;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarSummary;
import io.github.krait4g.radarexplorer.model.ApiModels.RfScannerSummary;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository;
import io.github.krait4g.radarexplorer.repository.RfScannerObservationRepository;
import io.github.krait4g.radarexplorer.repository.RfScannerSchemaCapabilities;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackCount;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackKey;
import io.github.krait4g.radarexplorer.repository.SchemaCapabilities;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.BadRequest;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.Unavailable;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RadarViewerServiceRangeLimitTest {

    @Test
    void defaultLimitsUseUnlimitedTimeRangeAndExactRowsWithBoundedOverview() {
        ViewerProperties properties = new ViewerProperties();

        assertEquals(0, properties.getLimits().getMaxRangeSeconds());
        assertEquals(0, properties.getLimits().getMaxQueryRows());
        assertEquals(100_000, properties.getLimits().getMaxOverviewPoints());
        assertEquals(1, properties.getLimits().getMaxConcurrentOverviewQueries());
        assertEquals(60, properties.getLimits().getOverviewStatementTimeoutSeconds());
    }

    @Test
    void positiveRangeLimitStillRejectsLongerRanges() {
        ViewerProperties properties = new ViewerProperties();
        properties.getLimits().setMaxRangeSeconds(60);
        RadarViewerService service = new RadarViewerService(
                mock(RadarEventRepository.class),
                mock(RfScannerObservationRepository.class),
                properties,
                new RadarDatabaseProperties(),
                mock(org.springframework.transaction.PlatformTransactionManager.class)
        );

        BadRequest exception = assertThrows(
                BadRequest.class,
                () -> service.tracks(
                        "202608131417",
                        "20260813141801",
                        750,
                        null,
                        null,
                        null,
                        true,
                        true
                )
        );

        assertEquals("TIME_RANGE_TOO_LARGE", exception.code());
        assertEquals("The requested range must not exceed 60 seconds.", exception.getMessage());
    }

    @Test
    void negativeRangeLimitFailsBeanValidation() {
        ViewerProperties properties = new ViewerProperties();
        properties.getLimits().setMaxRangeSeconds(-1);

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = validatorFactory.getValidator();
            var violations = validator.validate(properties);
            assertTrue(
                    violations.stream().anyMatch(violation ->
                            violation.getPropertyPath().toString().equals("limits.maxRangeSeconds")
                                    && violation.getInvalidValue().equals(-1)
                    ),
                    () -> "Expected a maxRangeSeconds violation but got " + violations
            );
        }
    }

    @Test
    void rfScannerPhysicalIdentifiersRejectSqlFragments() {
        ViewerProperties properties = new ViewerProperties();
        properties.getDatabase().setRfScannerTable("rf_scanner_observation;drop_table");
        properties.getDatabase().getRfScannerColumns().setObservedAt("observed_at desc");

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(properties);
            assertTrue(violations.stream().anyMatch(violation ->
                    violation.getPropertyPath().toString().equals("database.rfScannerTable")
            ));
            assertTrue(violations.stream().anyMatch(violation ->
                    violation.getPropertyPath().toString().equals("database.rfScannerColumns.observedAt")
            ));
        }
    }

    @Test
    void unsampledOverviewAboveP95ReservoirKeepsExactP95Metadata() {
        int rowCount = 20_001;
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository repository = mock(RadarEventRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        when(repository.inspectSchema()).thenReturn(new SchemaCapabilities(true, Set.of(
                "OBSERVED_AT", "SENSOR_ID", "SENSOR_TRACK_ID", "OBJECT_ID",
                "RAW_LONGITUDE", "RAW_LATITUDE", "RAW_ALTITUDE"
        )));
        when(repository.findTrackCountsBetween(
                any(SchemaCapabilities.class), anyString(), anyString(), isNull(), isNull(), isNull(), anyBoolean()
        )).thenReturn(List.of(new TrackCount(new TrackKey("9001", "RA3", "31"), rowCount)));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<RadarPoint> consumer = invocation.getArgument(7, Consumer.class);
            Coordinate coordinate = new Coordinate(
                    new BigDecimal("127.0"), new BigDecimal("37.0"), new BigDecimal("100.0")
            );
            for (int index = 0; index < rowCount; index++) {
                consumer.accept(new RadarPoint(
                        (long) index, 1L, "20260813141700000", "RA3", "31", "9001", "Y",
                        coordinate, coordinate, null, null, null, null
                ));
            }
            return null;
        }).when(repository).streamBetween(
                any(SchemaCapabilities.class), anyString(), anyString(), isNull(), isNull(), isNull(), eq(true), any()
        );

        RadarViewerService service = new RadarViewerService(
                repository, mock(RfScannerObservationRepository.class), properties,
                new RadarDatabaseProperties(), transactionManager
        );
        var response = service.tracks(
                "20260813141700000", "20260813141800000", 750,
                null, null, null, true, true
        );

        assertFalse(response.sampling().sampled());
        assertFalse(response.sampling().p95Approximate());
        assertEquals(rowCount, response.sampling().returnedRows());
        assertEquals(rowCount, response.summary().sourceRows());
        assertEquals(new BigDecimal("0.000"), response.summary().p95HorizontalCorrectionMeters());
    }

    @Test
    void metaKeepsRfScannerUsableWhenRadarSchemaInspectionFails() {
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository radar = mock(RadarEventRepository.class);
        RfScannerObservationRepository rf = mock(RfScannerObservationRepository.class);
        when(radar.inspectSchema()).thenThrow(new DataAccessResourceFailureException("offline"));
        when(rf.inspectSchema()).thenReturn(readyRfSchema());
        when(rf.findTimeRange(any(RfScannerSchemaCapabilities.class)))
                .thenReturn(new String[]{"20260813120000000", "20260813121000000"});

        RadarViewerService service = service(radar, rf, properties);
        var meta = service.meta();

        assertEquals("UP", meta.database().status());
        assertFalse(meta.capabilities().radarReady());
        assertTrue(meta.rfScannerCapabilities().ready());
        assertEquals("20260813120000000", meta.timeRange().min());
        assertEquals("20260813121000000", meta.rfScannerTimeRange().max());
        assertTrue(meta.warnings().stream().anyMatch(value -> value.contains("RADAR schema")));
    }

    @Test
    void metaIsolatesOneSourceTimeRangeFailure() {
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository radar = mock(RadarEventRepository.class);
        RfScannerObservationRepository rf = mock(RfScannerObservationRepository.class);
        when(radar.inspectSchema()).thenReturn(readyRadarSchema());
        when(rf.inspectSchema()).thenReturn(readyRfSchema());
        when(radar.findTimeRange()).thenThrow(new DataAccessResourceFailureException("range failed"));
        when(rf.findTimeRange(any(RfScannerSchemaCapabilities.class)))
                .thenReturn(new String[]{"20260813120000000", "20260813121000000"});

        var meta = service(radar, rf, properties).meta();

        assertEquals("UP", meta.database().status());
        assertTrue(meta.capabilities().radarReady());
        assertEquals(null, meta.radarTimeRange().min());
        assertEquals("20260813120000000", meta.timeRange().min());
        assertTrue(meta.warnings().stream().anyMatch(value -> value.contains("RADAR time range")));
    }

    @Test
    void radarOnlySensorInventoryNeverInspectsRfScanner() {
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository radar = mock(RadarEventRepository.class);
        RfScannerObservationRepository rf = mock(RfScannerObservationRepository.class);
        when(radar.inspectSchema()).thenReturn(readyRadarSchema());
        when(radar.findRadarsBetween(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of(new RadarSummary("R1", 2, 1)));

        var response = service(radar, rf, properties).sensors(
                "20260813120000000", "20260813120100000", 750, List.of("RADAR"), false
        );

        assertEquals(List.of("RADAR"), response.selectedSources());
        assertEquals(1, response.radars().size());
        assertTrue(response.rfScanners().isEmpty());
        verifyNoInteractions(rf);
    }

    @Test
    void sensorInventoryReturnsHealthySourceWhenOtherAggregationFails() {
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository radar = mock(RadarEventRepository.class);
        RfScannerObservationRepository rf = mock(RfScannerObservationRepository.class);
        when(radar.inspectSchema()).thenReturn(readyRadarSchema());
        when(rf.inspectSchema()).thenReturn(readyRfSchema());
        when(radar.findRadarsBetween(any(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new DataAccessResourceFailureException("aggregate failed"));
        when(rf.findRfScannersBetween(any(), anyString(), anyString()))
                .thenReturn(List.of(new RfScannerSummary("RF-A", 3, 1, 1)));

        var response = service(radar, rf, properties).sensors(
                "20260813120000000", "20260813120100000", 750,
                List.of("RADAR", "RFSCNR"), false
        );

        assertTrue(response.radars().isEmpty());
        assertEquals(1, response.rfScanners().size());
        assertTrue(response.warnings().stream().anyMatch(value -> value.contains("RADAR sensor inventory")));
    }

    @Test
    void explicitlyRequestedSourceInspectionFailureUsesSourceSpecificUnavailableCode() {
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository radar = mock(RadarEventRepository.class);
        RfScannerObservationRepository rf = mock(RfScannerObservationRepository.class);
        when(radar.inspectSchema()).thenThrow(new DataAccessResourceFailureException("offline"));

        Unavailable exception = assertThrows(
                Unavailable.class,
                () -> service(radar, rf, properties).sensors(
                        "20260813120000000", "20260813120100000", 750,
                        List.of("RADAR"), false
                )
        );

        assertEquals("RADAR_SCHEMA_UNAVAILABLE", exception.code());
        verifyNoInteractions(rf);
    }

    @Test
    void automaticSourceSelectionOmitsInspectionFailureAndReturnsReadySourceWithWarning() {
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository radar = mock(RadarEventRepository.class);
        RfScannerObservationRepository rf = mock(RfScannerObservationRepository.class);
        when(radar.inspectSchema()).thenThrow(new DataAccessResourceFailureException("offline"));
        when(rf.inspectSchema()).thenReturn(readyRfSchema());
        when(rf.findRfScannersBetween(any(), anyString(), anyString()))
                .thenReturn(List.of(new RfScannerSummary("RF-A", 3, 1, 1)));

        var response = service(radar, rf, properties).sensors(
                "20260813120000000", "20260813120100000", 750, List.of(), false
        );

        assertEquals(List.of("RFSCNR"), response.selectedSources());
        assertTrue(response.radars().isEmpty());
        assertEquals(1, response.rfScanners().size());
        assertTrue(response.warnings().stream().anyMatch(value -> value.contains("RADAR schema inspection")));
    }

    @Test
    void explicitlyRequestedRfScannerInspectionFailureIsNotSilentlyOmitted() {
        ViewerProperties properties = new ViewerProperties();
        RadarEventRepository radar = mock(RadarEventRepository.class);
        RfScannerObservationRepository rf = mock(RfScannerObservationRepository.class);
        when(rf.inspectSchema()).thenThrow(new DataAccessResourceFailureException("offline"));

        Unavailable exception = assertThrows(
                Unavailable.class,
                () -> service(radar, rf, properties).sensors(
                        "20260813120000000", "20260813120100000", 750,
                        List.of("RFSCNR"), false
                )
        );

        assertEquals("RFSCNR_SCHEMA_UNAVAILABLE", exception.code());
        verifyNoInteractions(radar);
    }

    private RadarViewerService service(
            RadarEventRepository radar,
            RfScannerObservationRepository rf,
            ViewerProperties properties
    ) {
        return new RadarViewerService(
                radar, rf, properties, new RadarDatabaseProperties(), mock(PlatformTransactionManager.class)
        );
    }

    private SchemaCapabilities readyRadarSchema() {
        return new SchemaCapabilities(true, Set.of(
                "OBSERVED_AT", "SENSOR_ID", "SENSOR_TRACK_ID", "OBJECT_ID",
                "RAW_LONGITUDE", "RAW_LATITUDE", "RAW_ALTITUDE"
        ));
    }

    private RfScannerSchemaCapabilities readyRfSchema() {
        return new RfScannerSchemaCapabilities(true, Set.of(
                "EVENT_ID", "OBSERVED_AT", "FALLBACK_OBSERVED_AT", "SCANNER_ID",
                "SCANNER_TRACK_ID", "OBJECT_ID", "LONGITUDE", "LATITUDE", "ALTITUDE",
                "HOME_LONGITUDE", "HOME_LATITUDE", "HOME_ALTITUDE"
        ), new ViewerProperties.RfScannerColumns());
    }
}
