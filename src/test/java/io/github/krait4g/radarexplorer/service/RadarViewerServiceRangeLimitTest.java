package io.github.krait4g.radarexplorer.service;

import io.github.krait4g.radarexplorer.config.RadarDatabaseProperties;
import io.github.krait4g.radarexplorer.config.ViewerProperties;
import io.github.krait4g.radarexplorer.model.ApiModels.Coordinate;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarPoint;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackCount;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackKey;
import io.github.krait4g.radarexplorer.repository.SchemaCapabilities;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.BadRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
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
                repository, properties, new RadarDatabaseProperties(), transactionManager
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
}
