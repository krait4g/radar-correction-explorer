package io.github.krait4g.radarexplorer.service;

import io.github.krait4g.radarexplorer.config.RadarDatabaseProperties;
import io.github.krait4g.radarexplorer.config.ViewerProperties;
import io.github.krait4g.radarexplorer.model.ApiModels.Coordinate;
import io.github.krait4g.radarexplorer.model.ApiModels.DatabaseStatus;
import io.github.krait4g.radarexplorer.model.ApiModels.DetailResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.Limits;
import io.github.krait4g.radarexplorer.model.ApiModels.MapConfig;
import io.github.krait4g.radarexplorer.model.ApiModels.MatchMode;
import io.github.krait4g.radarexplorer.model.ApiModels.MetaResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.ObservationSource;
import io.github.krait4g.radarexplorer.model.ApiModels.ObservationSummary;
import io.github.krait4g.radarexplorer.model.ApiModels.ObservationsResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarPoint;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarSummary;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarsResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.RfScannerDataSummary;
import io.github.krait4g.radarexplorer.model.ApiModels.RfScannerPoint;
import io.github.krait4g.radarexplorer.model.ApiModels.RfScannerSummary;
import io.github.krait4g.radarexplorer.model.ApiModels.Sampling;
import io.github.krait4g.radarexplorer.model.ApiModels.SensorsResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.SnapshotResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.Summary;
import io.github.krait4g.radarexplorer.model.ApiModels.TimeRange;
import io.github.krait4g.radarexplorer.model.ApiModels.TracksResponse;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackCount;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackKey;
import io.github.krait4g.radarexplorer.repository.RfScannerObservationRepository;
import io.github.krait4g.radarexplorer.repository.RfScannerObservationRepository.RfTrackCount;
import io.github.krait4g.radarexplorer.repository.RfScannerObservationRepository.RfTrackKey;
import io.github.krait4g.radarexplorer.repository.RfScannerSchemaCapabilities;
import io.github.krait4g.radarexplorer.repository.SchemaCapabilities;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.BadRequest;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.LimitExceeded;
import io.github.krait4g.radarexplorer.service.ViewerExceptions.Unavailable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Service
public class RadarViewerService {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final int P95_RESERVOIR_SIZE = 20_000;
    private static final int MAX_SENSOR_FILTER_COUNT = 100;
    private static final Comparator<RadarPoint> POINT_ORDER = Comparator
            .comparing(RadarPoint::eventTime, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::objectNo, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::radarId, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::radarObjectNo, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::eventId, Comparator.nullsLast(Long::compareTo))
            .thenComparing(RadarPoint::sourceEventId, Comparator.nullsLast(Long::compareTo));
    private static final Comparator<RfScannerPoint> RFSCNR_POINT_ORDER = Comparator
            .comparing(RfScannerPoint::eventTime, Comparator.nullsLast(String::compareTo))
            .thenComparing(RfScannerPoint::rfScannerId, Comparator.nullsLast(String::compareTo))
            .thenComparing(RfScannerPoint::trackId, Comparator.nullsLast(String::compareTo))
            .thenComparing(RfScannerPoint::eventId, Comparator.nullsLast(Long::compareTo));

    private final RadarEventRepository repository;
    private final RfScannerObservationRepository rfScannerRepository;
    private final ViewerProperties properties;
    private final RadarDatabaseProperties radarDatabase;
    private final TransactionTemplate exactObservationTransaction;
    private final TransactionTemplate overviewTransaction;
    private final Semaphore overviewSlots;

    public RadarViewerService(
            RadarEventRepository repository,
            RfScannerObservationRepository rfScannerRepository,
            ViewerProperties properties,
            RadarDatabaseProperties radarDatabase,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.rfScannerRepository = rfScannerRepository;
        this.properties = properties;
        this.radarDatabase = radarDatabase;
        this.exactObservationTransaction = new TransactionTemplate(transactionManager);
        this.exactObservationTransaction.setReadOnly(true);
        this.exactObservationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.overviewTransaction = new TransactionTemplate(transactionManager);
        this.overviewTransaction.setReadOnly(true);
        this.overviewTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.overviewSlots = new Semaphore(
                properties.getLimits().getMaxConcurrentOverviewQueries(),
                true
        );
    }

    public MetaResponse meta() {
        ViewerProperties.Database database = properties.getDatabase();
        Limits limits = apiLimits();
        MapConfig map = apiMap();
        List<String> warnings = new ArrayList<>();
        SchemaCapabilities schema = SchemaCapabilities.unavailable();
        RfScannerSchemaCapabilities rfScannerSchema = RfScannerSchemaCapabilities.unavailable();
        boolean radarSchemaFailed = false;
        boolean rfScannerSchemaFailed = false;

        try {
            schema = repository.inspectSchema();
        } catch (DataAccessException exception) {
            radarSchemaFailed = true;
            warnings.add("RADAR schema inspection could not be completed.");
        }
        try {
            rfScannerSchema = rfScannerRepository.inspectSchema();
        } catch (DataAccessException exception) {
            rfScannerSchemaFailed = true;
            warnings.add("RFSCNR schema inspection could not be completed.");
        }

        TimeRange radarTimeRange = emptyTimeRange();
        if (schema.isReady()) {
            try {
                radarTimeRange = timeRange(repository.findTimeRange());
            } catch (DataAccessException exception) {
                warnings.add("RADAR time range could not be loaded.");
            }
        }
        TimeRange rfScannerTimeRange = emptyTimeRange();
        if (rfScannerSchema.isReady()) {
            try {
                rfScannerTimeRange = timeRange(rfScannerRepository.findTimeRange(rfScannerSchema));
            } catch (DataAccessException exception) {
                warnings.add("RFSCNR time range could not be loaded.");
            }
        }

        boolean anyReady = schema.isReady() || rfScannerSchema.isReady();
        String status = anyReady
                ? "UP"
                : (radarSchemaFailed || rfScannerSchemaFailed ? "DOWN" : "SCHEMA_MISMATCH");
        String message = metaStatusMessage(
                schema, radarSchemaFailed, rfScannerSchema, rfScannerSchemaFailed
        );
        if (!warnings.isEmpty()) {
            message += " " + String.join(" ", warnings);
        }
        return new MetaResponse(
                databaseStatus(status, message, database),
                schema.toApiCapabilities(), rfScannerSchema.toApiCapabilities(),
                combinedTimeRange(radarTimeRange, rfScannerTimeRange),
                radarTimeRange, rfScannerTimeRange, List.copyOf(warnings), limits, map
        );
    }

    public SnapshotResponse snapshot(
            String at,
            int toleranceMs,
            String radarId,
            String objectNo,
            boolean primaryOnly
    ) {
        if (toleranceMs < 0 || toleranceMs > properties.getLimits().getMaxToleranceMs()) {
            throw new BadRequest("INVALID_TOLERANCE", "toleranceMs must be between 0 and " + properties.getLimits().getMaxToleranceMs() + ".");
        }
        LocalDateTime requestedAt = CompactEventTimeParser.parse("at", at).value();
        String rangeStart = format(requestedAt.minusNanos(toleranceMs * 1_000_000L));
        String rangeEnd = format(requestedAt.plusNanos(toleranceMs * 1_000_000L));
        List<RadarPoint> source = load(
                rangeStart, rangeEnd, normalize(radarId), null, parseOptionalObjectNo(objectNo), primaryOnly
        );

        Map<String, RadarPoint> nearestByObject = new LinkedHashMap<>();
        for (RadarPoint point : source) {
            String key = objectKey(point);
            RadarPoint previous = nearestByObject.get(key);
            if (previous == null || compareNearness(point, previous, requestedAt) < 0) {
                nearestByObject.put(key, point);
            }
        }

        List<RadarPoint> points = nearestByObject.values().stream()
                .map(this::enrich)
                .sorted(pointOrder())
                .toList();
        return new SnapshotResponse(
                at, toleranceMs, rangeStart, rangeEnd, primaryOnly,
                summarize(source.size(), points), points
        );
    }

    public TracksResponse tracks(
            String from,
            String to,
            int toleranceMs,
            String radarId,
            String radarObjectNo,
            String objectNo,
            boolean primaryOnly,
            boolean overview
    ) {
        QueryRange queryRange = queryRange(from, to, toleranceMs);
        String normalizedRadarId = normalize(radarId);
        Long parsedRadarObjectNo = parseOptionalRadarObjectNo(radarObjectNo);
        String normalizedObjectNo = normalize(objectNo);
        Long parsedObjectNo = normalizedObjectNo == null ? null : parseObjectNo(normalizedObjectNo);
        if (!overview && parsedObjectNo == null) {
            throw new BadRequest(
                    "OBJECT_FILTER_REQUIRED",
                    "objectNo is required when overview=false so an exact range cannot load every object."
            );
        }

        List<RadarPoint> points;
        Summary summary;
        Sampling sampling;
        if (queryRange.snapshot()) {
            List<RadarPoint> source = load(
                    queryRange.rangeStart(), queryRange.rangeEnd(), normalizedRadarId,
                    parsedRadarObjectNo, parsedObjectNo, primaryOnly
            );
            points = nearestPerObject(source, queryRange.from().value());
            summary = summarize(source.size(), points);
            sampling = exactSampling(source, points);
        } else if (overview) {
            Overview overviewResult = overview(
                    queryRange.rangeStart(), queryRange.rangeEnd(), normalizedRadarId,
                    parsedRadarObjectNo, parsedObjectNo, primaryOnly
            );
            points = overviewResult.points();
            summary = overviewResult.summary();
            sampling = overviewResult.sampling();
        } else {
            List<RadarPoint> source = load(
                    queryRange.rangeStart(), queryRange.rangeEnd(), normalizedRadarId,
                    parsedRadarObjectNo, parsedObjectNo, primaryOnly
            );
            // The repository query already guarantees POINT_ORDER, and mapping preserves order.
            points = source.stream().map(this::enrich).toList();
            summary = summarize(source.size(), points);
            sampling = exactSampling(source, points);
        }

        return new TracksResponse(
                queryRange.mode(),
                from,
                to,
                queryRange.from().normalized(),
                queryRange.to().normalized(),
                toleranceMs,
                queryRange.rangeStart(),
                queryRange.rangeEnd(),
                primaryOnly,
                summary,
                sampling,
                points
        );
    }

    public ObservationsResponse observations(
            String from,
            String to,
            int toleranceMs,
            List<String> requestedSources,
            List<String> requestedRadarIds,
            List<String> requestedRfScannerIds,
            String radarObjectNo,
            String objectNo,
            String trackId,
            String requestedMatchMode,
            boolean primaryOnly,
            boolean overview
    ) {
        QueryRange queryRange = queryRange(from, to, toleranceMs);
        SourceContext sourceContext = resolveSourceContext(requestedSources);
        List<String> radarIds = normalizeFilterList("radarId", requestedRadarIds);
        List<String> rfScannerIds = normalizeFilterList("rfScannerId", requestedRfScannerIds);
        Long parsedRadarObjectNo = parseOptionalRadarObjectNo(radarObjectNo);
        Long parsedObjectNo = parseOptionalObjectNo(objectNo);
        String normalizedTrackId = normalize(trackId);
        MatchMode matchMode = parseMatchMode(requestedMatchMode);

        if (parsedObjectNo != null && matchMode == MatchMode.UNMATCHED) {
            throw new BadRequest(
                    "INVALID_MATCH_FILTER",
                    "objectNo cannot be combined with matchMode=UNMATCHED."
            );
        }
        if (!overview) {
            boolean identifiesRadarTrack = parsedRadarObjectNo != null && radarIds.size() == 1;
            boolean radarNeedsObject = sourceContext.includes(ObservationSource.RADAR)
                    && parsedObjectNo == null && !identifiesRadarTrack;
            boolean identifiesRfTrack = normalizedTrackId != null && rfScannerIds.size() == 1;
            boolean rfNeedsTrack = sourceContext.includes(ObservationSource.RFSCNR)
                    && parsedObjectNo == null && !identifiesRfTrack;
            if (radarNeedsObject || rfNeedsTrack) {
                throw new BadRequest(
                        "OBJECT_FILTER_REQUIRED",
                        "overview=false requires objectNo or one radarId plus radarObjectNo for RADAR,"
                                + " and objectNo or one rfScannerId plus trackId for RFSCNR."
                );
            }
        }

        ObservationResult result;
        if (queryRange.snapshot()) {
            result = exactObservations(
                    sourceContext, queryRange, radarIds, rfScannerIds, parsedRadarObjectNo, parsedObjectNo,
                    normalizedTrackId, matchMode, primaryOnly, true
            );
        } else if (overview) {
            result = observationOverview(
                    sourceContext, queryRange, radarIds, rfScannerIds, parsedRadarObjectNo, parsedObjectNo,
                    normalizedTrackId, matchMode, primaryOnly
            );
        } else {
            result = exactObservations(
                    sourceContext, queryRange, radarIds, rfScannerIds, parsedRadarObjectNo, parsedObjectNo,
                    normalizedTrackId, matchMode, primaryOnly, false
            );
        }

        return new ObservationsResponse(
                queryRange.mode(),
                from,
                to,
                queryRange.from().normalized(),
                queryRange.to().normalized(),
                toleranceMs,
                queryRange.rangeStart(),
                queryRange.rangeEnd(),
                sourceContext.selectedSources().stream().map(Enum::name).toList(),
                matchMode.name(),
                primaryOnly,
                sourceContext.warnings(),
                result.summary(),
                result.radarSummary(),
                result.rfScannerSummary(),
                result.sampling(),
                result.radarPoints(),
                result.rfScannerPoints()
        );
    }

    private ObservationResult exactObservations(
            SourceContext sourceContext,
            QueryRange queryRange,
            List<String> radarIds,
            List<String> rfScannerIds,
            Long radarObjectNo,
            Long objectNo,
            String trackId,
            MatchMode matchMode,
            boolean primaryOnly,
            boolean snapshot
    ) {
        ObservationResult result = exactObservationTransaction.execute(status -> snapshot
                ? querySnapshotObservations(
                        sourceContext, queryRange, radarIds, rfScannerIds, radarObjectNo, objectNo,
                        trackId, matchMode, primaryOnly
                )
                : queryExactObservations(
                        sourceContext, queryRange, radarIds, rfScannerIds, radarObjectNo, objectNo,
                        trackId, matchMode, primaryOnly
                ));
        return Objects.requireNonNull(result, "Exact observation transaction returned no result.");
    }

    public SensorsResponse sensors(String from, String to, int toleranceMs, boolean primaryOnly) {
        return sensors(from, to, toleranceMs, List.of(), primaryOnly);
    }

    public SensorsResponse sensors(
            String from,
            String to,
            int toleranceMs,
            List<String> requestedSources,
            boolean primaryOnly
    ) {
        QueryRange queryRange = queryRange(from, to, toleranceMs);
        SourceContext sourceContext = resolveSourceContext(requestedSources);
        List<RadarSummary> radars = List.of();
        List<RfScannerSummary> rfScanners = List.of();
        List<String> warnings = new ArrayList<>(sourceContext.warnings());
        int attemptedSources = 0;
        int successfulSources = 0;
        if (sourceContext.includes(ObservationSource.RADAR)) {
            attemptedSources++;
            try {
                radars = repository.findRadarsBetween(
                        sourceContext.radarSchema(), queryRange.rangeStart(), queryRange.rangeEnd(), primaryOnly
                );
                successfulSources++;
            } catch (DataAccessException exception) {
                warnings.add("RADAR sensor inventory could not be loaded.");
            }
        }
        if (sourceContext.includes(ObservationSource.RFSCNR)) {
            attemptedSources++;
            try {
                rfScanners = rfScannerRepository.findRfScannersBetween(
                        sourceContext.rfScannerSchema(), queryRange.rangeStart(), queryRange.rangeEnd()
                );
                successfulSources++;
            } catch (DataAccessException exception) {
                warnings.add("RFSCNR sensor inventory could not be loaded.");
            }
        }
        if (attemptedSources > 0 && successfulSources == 0) {
            throw new Unavailable("DATABASE_UNAVAILABLE", "Sensor inventory query is unavailable.");
        }
        return new SensorsResponse(
                queryRange.mode(), from, to,
                queryRange.from().normalized(), queryRange.to().normalized(), toleranceMs,
                queryRange.rangeStart(), queryRange.rangeEnd(), primaryOnly,
                sourceContext.selectedSources().stream().map(Enum::name).toList(),
                List.copyOf(warnings), radars, rfScanners
        );
    }

    private ObservationResult queryExactObservations(
            SourceContext sourceContext,
            QueryRange queryRange,
            List<String> radarIds,
            List<String> rfScannerIds,
            Long radarObjectNo,
            Long objectNo,
            String trackId,
            MatchMode matchMode,
            boolean primaryOnly
    ) {
        int maximum = properties.getLimits().getMaxQueryRows();
        int databaseLimit = maximum > 0 ? maximum + 1 : 0;
        List<RadarPoint> radarSource = List.of();
        List<RfScannerPoint> rfScannerSource = List.of();
        try {
            if (sourceContext.includes(ObservationSource.RADAR)) {
                radarSource = repository.findBetweenAnyRadar(
                        sourceContext.radarSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                        radarIds, radarObjectNo, objectNo, primaryOnly, databaseLimit
                );
            }
            if (sourceContext.includes(ObservationSource.RFSCNR)) {
                rfScannerSource = rfScannerRepository.findBetween(
                        sourceContext.rfScannerSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                        rfScannerIds, trackId, objectNo, matchMode, databaseLimit
                );
            }
        } catch (DataAccessException exception) {
            throw new Unavailable("DATABASE_UNAVAILABLE", "Observation query is unavailable.", exception);
        }

        long sourceRows = (long) radarSource.size() + rfScannerSource.size();
        if (maximum > 0 && sourceRows > maximum) {
            throw new LimitExceeded(
                    "Query exceeded the " + maximum
                            + " row safety limit. Narrow the time range or add a sensor/object filter."
            );
        }

        List<RadarPoint> radarPoints = radarSource.stream().map(this::enrich).toList();
        List<RfScannerPoint> rfScannerPoints = List.copyOf(rfScannerSource);

        Summary radarSummary = summarize(radarSource.size(), radarPoints);
        RfScannerDataSummary rfScannerSummary = summarizeRfScanner(rfScannerSource, rfScannerPoints.size());
        Sampling sampling = exactObservationSampling(
                radarSource, rfScannerSource, radarPoints, rfScannerPoints, false
        );
        return observationResult(radarPoints, rfScannerPoints, radarSummary, rfScannerSummary, sampling);
    }

    private ObservationResult querySnapshotObservations(
            SourceContext sourceContext,
            QueryRange queryRange,
            List<String> radarIds,
            List<String> rfScannerIds,
            Long radarObjectNo,
            Long objectNo,
            String trackId,
            MatchMode matchMode,
            boolean primaryOnly
    ) {
        int maximum = properties.getLimits().getMaxQueryRows();
        long[] sourceRows = {0L};
        LocalDateTime requestedAt = queryRange.from().value();
        Map<TrackKey, RadarPoint> nearestRadar = new LinkedHashMap<>();
        Map<RfTrackKey, RfScannerPoint> nearestRfScanner = new LinkedHashMap<>();
        SummaryAccumulator radarAccumulator = new SummaryAccumulator();
        RfScannerSummaryAccumulator rfScannerAccumulator = new RfScannerSummaryAccumulator();

        try {
            if (sourceContext.includes(ObservationSource.RADAR)) {
                repository.streamBetweenAnyRadar(
                        sourceContext.radarSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                        radarIds, radarObjectNo, objectNo, primaryOnly, sourcePoint -> {
                            enforceObservationRowLimit(++sourceRows[0], maximum);
                            RadarPoint point = enrich(sourcePoint);
                            radarAccumulator.add(point);
                            TrackKey key = trackKey(point);
                            RadarPoint previous = nearestRadar.get(key);
                            if (previous == null || compareNearness(point, previous, requestedAt) < 0) {
                                nearestRadar.put(key, point);
                            }
                        }
                );
            }
            if (sourceContext.includes(ObservationSource.RFSCNR)) {
                rfScannerRepository.streamBetween(
                        sourceContext.rfScannerSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                        rfScannerIds, trackId, objectNo, matchMode, point -> {
                            enforceObservationRowLimit(++sourceRows[0], maximum);
                            rfScannerAccumulator.add(point);
                            RfTrackKey key = rfScannerTrackKey(point);
                            RfScannerPoint previous = nearestRfScanner.get(key);
                            if (previous == null || compareRfScannerNearness(point, previous, requestedAt) < 0) {
                                nearestRfScanner.put(key, point);
                            }
                        }
                );
            }
        } catch (DataAccessException exception) {
            throw new Unavailable("DATABASE_UNAVAILABLE", "Observation snapshot query is unavailable.", exception);
        }

        List<RadarPoint> radarPoints = nearestRadar.values().stream().sorted(POINT_ORDER).toList();
        List<RfScannerPoint> rfScannerPoints = nearestRfScanner.values().stream().sorted(RFSCNR_POINT_ORDER).toList();
        Summary radarSummary = radarAccumulator.toSummary();
        RfScannerDataSummary rfScannerSummary = rfScannerAccumulator.toSummary(rfScannerPoints.size());
        int returnedRows = radarPoints.size() + rfScannerPoints.size();
        long trackCount = (long) nearestRadar.size() + nearestRfScanner.size();
        Sampling sampling = new Sampling(
                false,
                sourceRows[0] == returnedRows ? "NONE" : "SNAPSHOT_NEAREST_PER_PHYSICAL_TRACK",
                sourceRows[0],
                returnedRows,
                trackCount,
                trackCount,
                true,
                "FULL_RANGE",
                radarAccumulator.p95Approximate()
        );
        return observationResult(radarPoints, rfScannerPoints, radarSummary, rfScannerSummary, sampling);
    }

    private void enforceObservationRowLimit(long sourceRows, int maximum) {
        if (maximum > 0 && sourceRows > maximum) {
            throw new LimitExceeded(
                    "Query exceeded the " + maximum
                            + " row safety limit. Narrow the time range or add a sensor/object filter."
            );
        }
    }

    private SourceContext resolveSourceContext(List<String> requestedSources) {
        List<String> normalized = normalizeSourceNames(requestedSources);
        boolean useAvailableSources = normalized.isEmpty();
        Set<ObservationSource> requested = new LinkedHashSet<>();
        for (String value : normalized) {
            try {
                requested.add(ObservationSource.valueOf(value.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new BadRequest(
                        "INVALID_SOURCE",
                        "source must be RADAR or RFSCNR."
                );
            }
        }

        boolean wantsRadar = useAvailableSources || requested.contains(ObservationSource.RADAR);
        boolean wantsRfScanner = useAvailableSources || requested.contains(ObservationSource.RFSCNR);
        SchemaCapabilities radarSchema = SchemaCapabilities.unavailable();
        RfScannerSchemaCapabilities rfScannerSchema = RfScannerSchemaCapabilities.unavailable();
        List<String> warnings = new ArrayList<>();
        boolean radarInspectionFailed = false;
        boolean rfScannerInspectionFailed = false;
        if (wantsRadar) {
            try {
                radarSchema = repository.inspectSchema();
            } catch (DataAccessException exception) {
                radarInspectionFailed = true;
                warnings.add("RADAR schema inspection could not be completed.");
            }
        }
        if (wantsRfScanner) {
            try {
                rfScannerSchema = rfScannerRepository.inspectSchema();
            } catch (DataAccessException exception) {
                rfScannerInspectionFailed = true;
                warnings.add("RFSCNR schema inspection could not be completed.");
            }
        }

        if (!useAvailableSources) {
            if (radarInspectionFailed && requested.contains(ObservationSource.RADAR)) {
                throw new Unavailable(
                        "RADAR_SCHEMA_UNAVAILABLE",
                        "RADAR source is unavailable because its schema inspection failed."
                );
            }
            if (rfScannerInspectionFailed && requested.contains(ObservationSource.RFSCNR)) {
                throw new Unavailable(
                        "RFSCNR_SCHEMA_UNAVAILABLE",
                        "RFSCNR source is unavailable because its schema inspection failed."
                );
            }
        }

        Set<ObservationSource> selected = new LinkedHashSet<>();
        if (!radarInspectionFailed) {
            selectSource(
                    ObservationSource.RADAR, radarSchema.isReady(), radarSchema.missingRequiredColumns(),
                    requested, useAvailableSources, selected, warnings
            );
        }
        if (!rfScannerInspectionFailed) {
            selectSource(
                    ObservationSource.RFSCNR, rfScannerSchema.isReady(), rfScannerSchema.missingRequiredColumns(),
                    requested, useAvailableSources, selected, warnings
            );
        }
        if (selected.isEmpty()) {
            if (radarInspectionFailed || rfScannerInspectionFailed) {
                throw new Unavailable(
                        "DATABASE_UNAVAILABLE",
                        "No requested observation source could be inspected."
                );
            }
            throw new Unavailable(
                    "OBSERVATION_SCHEMA_UNAVAILABLE",
                    "No requested observation source has a usable table and required columns."
            );
        }
        return new SourceContext(
                List.copyOf(selected), List.copyOf(warnings), radarSchema, rfScannerSchema
        );
    }

    private void selectSource(
            ObservationSource source,
            boolean ready,
            Set<String> missingColumns,
            Set<ObservationSource> requested,
            boolean useAvailableSources,
            Set<ObservationSource> selected,
            List<String> warnings
    ) {
        boolean wanted = useAvailableSources || requested.contains(source);
        if (!wanted) {
            return;
        }
        if (ready) {
            selected.add(source);
            return;
        }
        String detail = missingColumns.isEmpty()
                ? "configured table was not found"
                : "required columns are missing: " + String.join(", ", missingColumns);
        if (!useAvailableSources) {
            throw new Unavailable(
                    source.name() + "_SCHEMA_UNAVAILABLE",
                    source.name() + " source is unavailable because its " + detail + "."
            );
        }
        warnings.add(source.name() + " was omitted because its " + detail + ".");
    }

    private List<String> normalizeSourceNames(List<String> values) {
        return normalizeList("source", values, 2);
    }

    private List<String> normalizeFilterList(String parameterName, List<String> values) {
        return normalizeList(parameterName, values, MAX_SENSOR_FILTER_COUNT);
    }

    private List<String> normalizeList(String parameterName, List<String> values, int maximum) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String component : value.split(",", -1)) {
                String candidate = normalize(component);
                if (candidate == null) {
                    throw new BadRequest("INVALID_FILTER", parameterName + " must not contain blank values.");
                }
                if (candidate.length() > 128) {
                    throw new BadRequest("INVALID_FILTER", parameterName + " values must not exceed 128 characters.");
                }
                normalized.add(candidate);
            }
        }
        if (normalized.size() > maximum) {
            throw new BadRequest(
                    "TOO_MANY_FILTER_VALUES",
                    parameterName + " accepts at most " + maximum + " distinct values."
            );
        }
        return List.copyOf(normalized);
    }

    private MatchMode parseMatchMode(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return MatchMode.ALL;
        }
        try {
            return MatchMode.valueOf(normalized.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequest(
                    "INVALID_MATCH_MODE",
                    "matchMode must be ALL, MATCHED, or UNMATCHED."
            );
        }
    }

    private ObservationResult observationOverview(
            SourceContext sourceContext,
            QueryRange queryRange,
            List<String> radarIds,
            List<String> rfScannerIds,
            Long radarObjectNo,
            Long objectNo,
            String trackId,
            MatchMode matchMode,
            boolean primaryOnly
    ) {
        boolean acquired = false;
        try {
            overviewSlots.acquire();
            acquired = true;
            ObservationResult result = overviewTransaction.execute(status -> queryObservationOverview(
                    sourceContext, queryRange, radarIds, rfScannerIds, radarObjectNo, objectNo,
                    trackId, matchMode, primaryOnly
            ));
            return Objects.requireNonNull(result, "Observation overview transaction returned no result.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Unavailable(
                    "OVERVIEW_INTERRUPTED",
                    "Observation overview query was interrupted while waiting for capacity.",
                    exception
            );
        } finally {
            if (acquired) {
                overviewSlots.release();
            }
        }
    }

    private ObservationResult queryObservationOverview(
            SourceContext sourceContext,
            QueryRange queryRange,
            List<String> radarIds,
            List<String> rfScannerIds,
            Long radarObjectNo,
            Long objectNo,
            String trackId,
            MatchMode matchMode,
            boolean primaryOnly
    ) {
        try {
            List<TrackCount> radarCounts = sourceContext.includes(ObservationSource.RADAR)
                    ? repository.findTrackCountsBetweenAnyRadar(
                            sourceContext.radarSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                            radarIds, radarObjectNo, objectNo, primaryOnly
                    )
                    : List.of();
            List<RfTrackCount> rfScannerCounts = sourceContext.includes(ObservationSource.RFSCNR)
                    ? rfScannerRepository.findTrackCountsBetween(
                            sourceContext.rfScannerSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                            rfScannerIds, trackId, objectNo, matchMode
                    )
                    : List.of();

            List<TrackCount> combinedCounts = new ArrayList<>(radarCounts.size() + rfScannerCounts.size());
            radarCounts.forEach(count -> combinedCounts.add(new TrackCount(
                    observationRadarKey(count.key()), count.rowCount()
            )));
            rfScannerCounts.forEach(count -> combinedCounts.add(new TrackCount(
                    observationRfScannerKey(count.key()), count.rowCount()
            )));

            int budget = properties.getLimits().getMaxOverviewPoints();
            Map<TrackKey, Integer> quotas = allocateTrackQuotas(combinedCounts, budget);
            GlobalTrackSampler sampler = new GlobalTrackSampler(combinedCounts, quotas);
            SummaryAccumulator radarAccumulator = new SummaryAccumulator();
            RfScannerSummaryAccumulator rfScannerAccumulator = new RfScannerSummaryAccumulator();
            List<RadarPoint> radarPoints = new ArrayList<>(Math.min(budget, totalRowsAsInt(radarCounts)));
            List<RfScannerPoint> rfScannerPoints = new ArrayList<>(Math.min(budget, totalRfRowsAsInt(rfScannerCounts)));

            if (sourceContext.includes(ObservationSource.RADAR)) {
                repository.streamBetweenAnyRadar(
                        sourceContext.radarSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                        radarIds, radarObjectNo, objectNo, primaryOnly, sourcePoint -> {
                            RadarPoint point = enrich(sourcePoint);
                            radarAccumulator.add(point);
                            if (sampler.include(observationRadarKey(trackKey(point)))) {
                                radarPoints.add(point);
                            }
                        }
                );
            }
            if (sourceContext.includes(ObservationSource.RFSCNR)) {
                rfScannerRepository.streamBetween(
                        sourceContext.rfScannerSchema(), queryRange.rangeStart(), queryRange.rangeEnd(),
                        rfScannerIds, trackId, objectNo, matchMode, point -> {
                            rfScannerAccumulator.add(point);
                            if (sampler.include(observationRfScannerKey(rfScannerTrackKey(point)))) {
                                rfScannerPoints.add(point);
                            }
                        }
                );
            }

            radarPoints.sort(POINT_ORDER);
            rfScannerPoints.sort(RFSCNR_POINT_ORDER);
            long sourceRows = radarAccumulator.sourceRows() + rfScannerAccumulator.sourceRows();
            int returnedRows = radarPoints.size() + rfScannerPoints.size();
            long representedTracks = quotas.values().stream().filter(quota -> quota > 0).count();
            long trackCount = combinedCounts.size();
            boolean sampled = sourceRows > returnedRows;
            String strategy = !sampled
                    ? "NONE"
                    : (representedTracks == trackCount ? "TRACK_ENDPOINT_PERIODIC" : "TRACK_SPREAD_FIRST");
            Sampling sampling = new Sampling(
                    sampled,
                    strategy,
                    sourceRows,
                    returnedRows,
                    trackCount,
                    representedTracks,
                    representedTracks == trackCount,
                    "FULL_RANGE",
                    radarAccumulator.sourceRows() > radarPoints.size() && radarAccumulator.p95Approximate()
            );
            Summary radarSummary = radarAccumulator.sourceRows() > radarPoints.size()
                    ? radarAccumulator.toSummary()
                    : summarize(radarAccumulator.sourceRows(), radarPoints);
            RfScannerDataSummary rfScannerSummary = rfScannerAccumulator.toSummary(rfScannerPoints.size());
            return observationResult(radarPoints, rfScannerPoints, radarSummary, rfScannerSummary, sampling);
        } catch (Unavailable exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new Unavailable("DATABASE_UNAVAILABLE", "Observation overview query is unavailable.", exception);
        }
    }

    private ObservationResult observationResult(
            List<RadarPoint> radarPoints,
            List<RfScannerPoint> rfScannerPoints,
            Summary radarSummary,
            RfScannerDataSummary rfScannerSummary,
            Sampling sampling
    ) {
        ObservationSummary summary = new ObservationSummary(
                radarSummary.sourceRows() + rfScannerSummary.sourceRows(),
                radarSummary.sourceRows(),
                rfScannerSummary.sourceRows(),
                radarSummary.objectCount(),
                rfScannerSummary.trackCount(),
                rfScannerSummary.matchedPointCount(),
                Math.max(0L, rfScannerSummary.sourceRows() - rfScannerSummary.matchedPointCount())
        );
        return new ObservationResult(
                List.copyOf(radarPoints), List.copyOf(rfScannerPoints),
                summary, radarSummary, rfScannerSummary, sampling
        );
    }

    private int compareRfScannerNearness(RfScannerPoint left, RfScannerPoint right, LocalDateTime at) {
        long leftDistance = absoluteMillis(CompactEventTimeParser.parse("eventTime", left.eventTime()).value(), at);
        long rightDistance = absoluteMillis(CompactEventTimeParser.parse("eventTime", right.eventTime()).value(), at);
        int distanceComparison = Long.compare(leftDistance, rightDistance);
        return distanceComparison != 0 ? distanceComparison : RFSCNR_POINT_ORDER.compare(left, right);
    }

    private RfScannerDataSummary summarizeRfScanner(List<RfScannerPoint> source, int representedRows) {
        RfScannerSummaryAccumulator accumulator = new RfScannerSummaryAccumulator();
        source.forEach(accumulator::add);
        return accumulator.toSummary(representedRows);
    }

    private Sampling exactObservationSampling(
            List<RadarPoint> radarSource,
            List<RfScannerPoint> rfScannerSource,
            List<RadarPoint> radarPoints,
            List<RfScannerPoint> rfScannerPoints,
            boolean snapshot
    ) {
        Set<TrackKey> sourceTracks = new HashSet<>();
        Set<TrackKey> representedTracks = new HashSet<>();
        radarSource.forEach(point -> sourceTracks.add(observationRadarKey(trackKey(point))));
        rfScannerSource.forEach(point -> sourceTracks.add(observationRfScannerKey(rfScannerTrackKey(point))));
        radarPoints.forEach(point -> representedTracks.add(observationRadarKey(trackKey(point))));
        rfScannerPoints.forEach(point -> representedTracks.add(observationRfScannerKey(rfScannerTrackKey(point))));
        long sourceRows = (long) radarSource.size() + rfScannerSource.size();
        int returnedRows = radarPoints.size() + rfScannerPoints.size();
        boolean snapshotSelection = snapshot && sourceRows != returnedRows;
        return new Sampling(
                false,
                snapshotSelection ? "SNAPSHOT_NEAREST_PER_PHYSICAL_TRACK" : "NONE",
                sourceRows,
                returnedRows,
                sourceTracks.size(),
                representedTracks.size(),
                representedTracks.size() == sourceTracks.size(),
                snapshotSelection ? "SELECTED_SNAPSHOT" : "FULL_RANGE",
                false
        );
    }

    private TrackKey observationRadarKey(TrackKey key) {
        return new TrackKey("RADAR:" + Objects.toString(key.objectNo(), ""), key.radarId(), key.radarObjectNo());
    }

    private TrackKey observationRfScannerKey(RfTrackKey key) {
        return new TrackKey("RFSCNR", key.rfScannerId(), key.trackId());
    }

    private RfTrackKey rfScannerTrackKey(RfScannerPoint point) {
        return new RfTrackKey(point.rfScannerId(), point.trackId());
    }

    private int totalRfRowsAsInt(List<RfTrackCount> counts) {
        long total = counts.stream().mapToLong(RfTrackCount::rowCount).sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private Overview overview(
            String from,
            String to,
            String radarId,
            Long radarObjectNo,
            Long objectNo,
            boolean primaryOnly
    ) {
        boolean acquired = false;
        try {
            // Acquire before opening the repeatable-read transaction. Queued browser requests then
            // consume a servlet thread, but never a scarce Hikari/JDBC connection.
            overviewSlots.acquire();
            acquired = true;
            Overview result = overviewTransaction.execute(status -> queryOverview(
                    from, to, radarId, radarObjectNo, objectNo, primaryOnly
            ));
            return Objects.requireNonNull(result, "Overview transaction returned no result.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Unavailable(
                    "OVERVIEW_INTERRUPTED",
                    "Radar database overview query was interrupted while waiting for capacity.",
                    exception
            );
        } finally {
            if (acquired) {
                overviewSlots.release();
            }
        }
    }

    private Overview queryOverview(
            String from,
            String to,
            String radarId,
            Long radarObjectNo,
            Long objectNo,
            boolean primaryOnly
    ) {
        try {
            SchemaCapabilities schema = repository.inspectSchema();
            ensureReady(schema);
            List<TrackCount> trackCounts = repository.findTrackCountsBetween(
                    schema, from, to, radarId, radarObjectNo, objectNo, primaryOnly
            );
            int budget = properties.getLimits().getMaxOverviewPoints();
            Map<TrackKey, Integer> quotas = allocateTrackQuotas(trackCounts, budget);
            TrackSampler sampler = new TrackSampler(trackCounts, quotas);
            SummaryAccumulator accumulator = new SummaryAccumulator();
            List<RadarPoint> points = new ArrayList<>(Math.min(budget, totalRowsAsInt(trackCounts)));

            repository.streamBetween(schema, from, to, radarId, radarObjectNo, objectNo, primaryOnly, sourcePoint -> {
                RadarPoint point = enrich(sourcePoint);
                accumulator.add(point);
                if (sampler.include(point)) {
                    points.add(point);
                }
            });

            points.sort(POINT_ORDER);
            long representedTracks = quotas.values().stream().filter(quota -> quota > 0).count();
            long trackCount = trackCounts.size();
            boolean sampled = accumulator.sourceRows() > points.size();
            Summary summary = sampled
                    ? accumulator.toSummary()
                    // The complete result is already bounded by maxOverviewPoints and resident in
                    // memory, so keep its P95 exact instead of exposing the reservoir approximation.
                    : summarize(accumulator.sourceRows(), points);
            String strategy = !sampled
                    ? "NONE"
                    : (representedTracks == trackCount ? "TRACK_ENDPOINT_PERIODIC" : "TRACK_SPREAD_FIRST");
            Sampling sampling = new Sampling(
                    sampled,
                    strategy,
                    accumulator.sourceRows(),
                    points.size(),
                    trackCount,
                    representedTracks,
                    representedTracks == trackCount,
                    "FULL_RANGE",
                    sampled && accumulator.p95Approximate()
            );
            return new Overview(points, summary, sampling);
        } catch (Unavailable exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new Unavailable("DATABASE_UNAVAILABLE", "Radar database overview query is unavailable.", exception);
        }
    }

    private Map<TrackKey, Integer> allocateTrackQuotas(List<TrackCount> counts, int budget) {
        Map<TrackKey, Integer> quotas = new LinkedHashMap<>();
        counts.forEach(count -> quotas.put(count.key(), 0));
        if (counts.isEmpty() || budget <= 0) {
            return quotas;
        }

        long totalRows = counts.stream().mapToLong(TrackCount::rowCount).sum();
        if (totalRows <= budget) {
            counts.forEach(count -> quotas.put(count.key(), Math.toIntExact(count.rowCount())));
            return quotas;
        }

        if (counts.size() > budget) {
            for (int index : evenlySelectedIndices(counts.size(), budget)) {
                quotas.put(counts.get(index).key(), 1);
            }
            return quotas;
        }

        // Every physical track gets one point first. When the budget allows, a second point reserves
        // both endpoints before remaining capacity is divided proportionally for periodic samples.
        counts.forEach(count -> quotas.put(count.key(), 1));
        int remaining = budget - counts.size();
        List<Integer> multiRowTracks = new ArrayList<>();
        for (int index = 0; index < counts.size(); index++) {
            if (counts.get(index).rowCount() > 1) {
                multiRowTracks.add(index);
            }
        }
        int endpointSlots = Math.min(remaining, multiRowTracks.size());
        for (int selected : evenlySelectedIndices(multiRowTracks.size(), endpointSlots)) {
            TrackCount count = counts.get(multiRowTracks.get(selected));
            quotas.put(count.key(), 2);
        }
        remaining -= endpointSlots;
        if (remaining <= 0) {
            return quotas;
        }

        long residualTotal = counts.stream()
                .mapToLong(count -> count.rowCount() - quotas.get(count.key()))
                .sum();
        if (residualTotal <= 0) {
            return quotas;
        }

        List<QuotaRemainder> remainders = new ArrayList<>();
        int assigned = 0;
        BigInteger divisor = BigInteger.valueOf(residualTotal);
        for (int index = 0; index < counts.size(); index++) {
            TrackCount count = counts.get(index);
            long residual = count.rowCount() - quotas.get(count.key());
            if (residual <= 0) {
                continue;
            }
            BigInteger[] share = BigInteger.valueOf(remaining)
                    .multiply(BigInteger.valueOf(residual))
                    .divideAndRemainder(divisor);
            int extra = share[0].intValueExact();
            quotas.put(count.key(), quotas.get(count.key()) + extra);
            assigned += extra;
            remainders.add(new QuotaRemainder(count.key(), share[1], index));
        }
        int leftover = remaining - assigned;
        remainders.sort(Comparator
                .comparing(QuotaRemainder::remainder).reversed()
                .thenComparingInt(QuotaRemainder::stableIndex));
        for (int index = 0; index < leftover; index++) {
            TrackKey key = remainders.get(index).key();
            quotas.put(key, quotas.get(key) + 1);
        }
        return quotas;
    }

    private Set<Integer> evenlySelectedIndices(int size, int count) {
        Set<Integer> selected = new HashSet<>();
        if (size <= 0 || count <= 0) {
            return selected;
        }
        if (count >= size) {
            for (int index = 0; index < size; index++) {
                selected.add(index);
            }
            return selected;
        }
        if (count == 1) {
            selected.add(size / 2);
            return selected;
        }
        for (int ordinal = 0; ordinal < count; ordinal++) {
            selected.add((int) Math.round(ordinal * (size - 1.0) / (count - 1.0)));
        }
        return selected;
    }

    private int totalRowsAsInt(List<TrackCount> counts) {
        long total = counts.stream().mapToLong(TrackCount::rowCount).sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private Sampling exactSampling(List<RadarPoint> source, List<RadarPoint> points) {
        Set<TrackKey> sourceTracks = new HashSet<>();
        Set<TrackKey> representedTracks = new HashSet<>();
        source.forEach(point -> sourceTracks.add(trackKey(point)));
        points.forEach(point -> representedTracks.add(trackKey(point)));
        boolean snapshotSelection = source.size() != points.size();
        return new Sampling(
                false,
                snapshotSelection ? "SNAPSHOT_NEAREST" : "NONE",
                source.size(),
                points.size(),
                sourceTracks.size(),
                representedTracks.size(),
                representedTracks.size() == sourceTracks.size(),
                snapshotSelection ? "SELECTED_SNAPSHOT" : "FULL_RANGE",
                false
        );
    }

    private TrackKey trackKey(RadarPoint point) {
        return new TrackKey(point.objectNo(), point.radarId(), point.radarObjectNo());
    }

    public RadarsResponse radars(String from, String to, int toleranceMs, boolean primaryOnly) {
        QueryRange queryRange = queryRange(from, to, toleranceMs);
        List<RadarSummary> radars;
        try {
            SchemaCapabilities schema = repository.inspectSchema();
            ensureReady(schema);
            radars = repository.findRadarsBetween(
                    schema,
                    queryRange.rangeStart(),
                    queryRange.rangeEnd(),
                    primaryOnly
            );
        } catch (Unavailable exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new Unavailable("DATABASE_UNAVAILABLE", "Radar database query is unavailable.", exception);
        }

        return new RadarsResponse(
                queryRange.mode(),
                from,
                to,
                queryRange.from().normalized(),
                queryRange.to().normalized(),
                toleranceMs,
                queryRange.rangeStart(),
                queryRange.rangeEnd(),
                primaryOnly,
                radars
        );
    }

    public DetailResponse detail(
            String objectNo,
            String at,
            int windowSeconds,
            String radarId,
            String radarObjectNo,
            boolean primaryOnly
    ) {
        if (windowSeconds < 1 || windowSeconds > properties.getLimits().getMaxWindowSeconds()) {
            throw new BadRequest("INVALID_WINDOW", "windowSeconds must be between 1 and " + properties.getLimits().getMaxWindowSeconds() + ".");
        }
        String normalizedObjectNo = normalize(objectNo);
        if (normalizedObjectNo == null) {
            throw new BadRequest("INVALID_OBJECT_ID", "objectNo must not be blank.");
        }
        Long parsedObjectNo = parseObjectNo(normalizedObjectNo);
        LocalDateTime requestedAt = CompactEventTimeParser.parse("at", at).value();
        String rangeStart = format(requestedAt.minusSeconds(windowSeconds));
        String rangeEnd = format(requestedAt.plusSeconds(windowSeconds));
        List<RadarPoint> source = load(
                rangeStart, rangeEnd, normalize(radarId), parseOptionalRadarObjectNo(radarObjectNo),
                parsedObjectNo, primaryOnly
        );
        // The repository query already guarantees POINT_ORDER, and mapping preserves order.
        List<RadarPoint> points = source.stream().map(this::enrich).toList();
        return new DetailResponse(
                normalizedObjectNo, at, windowSeconds, rangeStart, rangeEnd, primaryOnly,
                summarize(source.size(), points), points
        );
    }

    private List<RadarPoint> load(
            String from,
            String to,
            String radarId,
            Long radarObjectNo,
            Long objectNo,
            boolean primaryOnly
    ) {
        try {
            SchemaCapabilities schema = repository.inspectSchema();
            ensureReady(schema);
            int maximum = properties.getLimits().getMaxQueryRows();
            int databaseLimit = maximum > 0 ? maximum + 1 : 0;
            List<RadarPoint> rows = repository.findBetween(
                    schema, from, to, radarId, radarObjectNo, objectNo, primaryOnly, databaseLimit
            );
            if (maximum > 0 && rows.size() > maximum) {
                throw new LimitExceeded("Query exceeded the " + maximum + " row safety limit. Narrow the time range or add a radar/object filter.");
            }
            return rows;
        } catch (LimitExceeded | Unavailable exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new Unavailable("DATABASE_UNAVAILABLE", "Radar database query is unavailable.", exception);
        }
    }

    private QueryRange queryRange(String from, String to, int toleranceMs) {
        validateTolerance(toleranceMs);
        CompactEventTimeParser.ParsedEventTime parsedFrom = CompactEventTimeParser.parse("from", from);
        CompactEventTimeParser.ParsedEventTime parsedTo = CompactEventTimeParser.parse("to", to);
        int comparison = parsedFrom.value().compareTo(parsedTo.value());
        if (comparison > 0) {
            throw new BadRequest("INVALID_TIME_RANGE", "from must be earlier than or equal to to.");
        }

        boolean snapshot = comparison == 0;
        if (!snapshot) {
            Duration requestedDuration = Duration.between(parsedFrom.value(), parsedTo.value());
            int maximum = properties.getLimits().getMaxRangeSeconds();
            if (maximum > 0 && requestedDuration.compareTo(Duration.ofSeconds(maximum)) > 0) {
                throw new BadRequest(
                        "TIME_RANGE_TOO_LARGE",
                        "The requested range must not exceed " + maximum + " seconds."
                );
            }
        }

        String rangeStart = snapshot
                ? format(parsedFrom.value().minusNanos(toleranceMs * 1_000_000L))
                : parsedFrom.normalized();
        String rangeEnd = snapshot
                ? format(parsedTo.value().plusNanos(toleranceMs * 1_000_000L))
                : parsedTo.normalized();
        return new QueryRange(parsedFrom, parsedTo, snapshot, rangeStart, rangeEnd);
    }

    private void validateTolerance(int toleranceMs) {
        if (toleranceMs < 0 || toleranceMs > properties.getLimits().getMaxToleranceMs()) {
            throw new BadRequest(
                    "INVALID_TOLERANCE",
                    "toleranceMs must be between 0 and " + properties.getLimits().getMaxToleranceMs() + "."
            );
        }
    }

    private List<RadarPoint> nearestPerObject(List<RadarPoint> source, LocalDateTime requestedAt) {
        Map<String, RadarPoint> nearestByObject = new LinkedHashMap<>();
        for (RadarPoint point : source) {
            String key = objectKey(point);
            RadarPoint previous = nearestByObject.get(key);
            if (previous == null || compareNearness(point, previous, requestedAt) < 0) {
                nearestByObject.put(key, point);
            }
        }
        return nearestByObject.values().stream().map(this::enrich).sorted(pointOrder()).toList();
    }

    private void ensureReady(SchemaCapabilities schema) {
        if (!schema.tableExists()) {
            throw new Unavailable(
                    "OBSERVATION_SCHEMA_UNAVAILABLE",
                    "The configured observation table was not found."
            );
        }
        if (!schema.isReady()) {
            throw new Unavailable(
                    "OBSERVATION_SCHEMA_UNAVAILABLE",
                    "The configured table is missing required mapped columns."
            );
        }
    }

    private RadarPoint enrich(RadarPoint point) {
        Coordinate raw = point.raw();
        Coordinate corrected = point.corrected();
        BigDecimal horizontal = horizontalDistance(raw, corrected);
        BigDecimal altitudeDelta = raw.altitude() == null || corrected.altitude() == null
                ? null
                : corrected.altitude().subtract(raw.altitude()).setScale(3, RoundingMode.HALF_UP);
        return new RadarPoint(
                point.eventId(), point.sourceEventId(), point.eventTime(), point.radarId(),
                point.radarObjectNo(), point.objectNo(), point.primaryFlag(), raw, corrected,
                point.referenceAltitude(), horizontal, altitudeDelta, correctionStatus(raw, corrected)
        );
    }

    private String correctionStatus(Coordinate raw, Coordinate corrected) {
        boolean hasRaw = raw.longitude() != null && raw.latitude() != null;
        boolean hasCalcLongitude = corrected.longitude() != null;
        boolean hasCalcLatitude = corrected.latitude() != null;
        if (!hasRaw) {
            return "NO_RAW_POSITION";
        }
        if (hasCalcLongitude && hasCalcLatitude) {
            return "CORRECTED";
        }
        if (hasCalcLongitude || hasCalcLatitude) {
            return "PARTIAL_CORRECTION";
        }
        if (corrected.altitude() != null) {
            return "ALTITUDE_ONLY";
        }
        return "RAW_ONLY";
    }

    private BigDecimal horizontalDistance(Coordinate raw, Coordinate corrected) {
        if (!validLonLat(raw.longitude(), raw.latitude()) || !validLonLat(corrected.longitude(), corrected.latitude())) {
            return null;
        }
        double lat1 = Math.toRadians(raw.latitude().doubleValue());
        double lat2 = Math.toRadians(corrected.latitude().doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(corrected.longitude().doubleValue() - raw.longitude().doubleValue());
        double sinLat = Math.sin(deltaLat / 2.0);
        double sinLon = Math.sin(deltaLon / 2.0);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double distance = 2.0 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1.0, a)));
        return BigDecimal.valueOf(distance).setScale(3, RoundingMode.HALF_UP);
    }

    private boolean validLonLat(BigDecimal longitude, BigDecimal latitude) {
        if (longitude == null || latitude == null) {
            return false;
        }
        double lon = longitude.doubleValue();
        double lat = latitude.doubleValue();
        return Double.isFinite(lon) && Double.isFinite(lat) && lon >= -180.0 && lon <= 180.0 && lat >= -90.0 && lat <= 90.0;
    }

    private Summary summarize(long sourceRows, List<RadarPoint> points) {
        Set<String> objects = new HashSet<>();
        int rawPositionCount = 0;
        int correctedPositionCount = 0;
        List<BigDecimal> horizontal = new ArrayList<>();
        BigDecimal horizontalTotal = BigDecimal.ZERO;
        BigDecimal horizontalMaximum = null;
        int altitudeCount = 0;
        BigDecimal altitudeTotal = BigDecimal.ZERO;
        BigDecimal altitudeMaximum = null;

        for (RadarPoint point : points) {
            objects.add(objectKey(point));
            if (validLonLat(point.raw().longitude(), point.raw().latitude())) {
                rawPositionCount++;
            }
            if (validLonLat(point.corrected().longitude(), point.corrected().latitude())) {
                correctedPositionCount++;
            }

            BigDecimal horizontalCorrection = point.horizontalCorrectionMeters();
            if (horizontalCorrection != null) {
                horizontal.add(horizontalCorrection);
                horizontalTotal = horizontalTotal.add(horizontalCorrection);
                horizontalMaximum = max(horizontalMaximum, horizontalCorrection);
            }

            BigDecimal altitudeDelta = point.altitudeDeltaMeters();
            if (altitudeDelta != null) {
                BigDecimal absoluteDelta = altitudeDelta.abs();
                altitudeCount++;
                altitudeTotal = altitudeTotal.add(absoluteDelta);
                altitudeMaximum = max(altitudeMaximum, absoluteDelta);
            }
        }

        // P95 is the only summary statistic that needs ordered correction values.
        horizontal.sort(BigDecimal::compareTo);
        return new Summary(
                sourceRows,
                objects.size(),
                rawPositionCount,
                correctedPositionCount,
                Math.max(0, rawPositionCount - correctedPositionCount),
                average(horizontalTotal, horizontal.size()),
                scale(horizontalMaximum),
                percentile95(horizontal),
                average(altitudeTotal, altitudeCount),
                scale(altitudeMaximum)
        );
    }

    private BigDecimal average(BigDecimal total, long count) {
        if (count == 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(count), 3, RoundingMode.HALF_UP);
    }

    private BigDecimal max(BigDecimal current, BigDecimal candidate) {
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal percentile95(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        int index = Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1);
        return values.get(index).setScale(3, RoundingMode.HALF_UP);
    }

    private final class SummaryAccumulator {
        private final Set<String> objects = new HashSet<>();
        private final PriorityQueue<MetricSample> horizontalSample = new PriorityQueue<>((left, right) -> {
            int hashOrder = Long.compareUnsigned(right.hash(), left.hash());
            return hashOrder != 0 ? hashOrder : Long.compare(right.ordinal(), left.ordinal());
        });
        private long sourceRows;
        private long rawPositionCount;
        private long correctedPositionCount;
        private long horizontalCount;
        private BigDecimal horizontalTotal = BigDecimal.ZERO;
        private BigDecimal horizontalMaximum;
        private long altitudeCount;
        private BigDecimal altitudeTotal = BigDecimal.ZERO;
        private BigDecimal altitudeMaximum;

        void add(RadarPoint point) {
            long ordinal = sourceRows++;
            objects.add(objectKey(point));
            if (validLonLat(point.raw().longitude(), point.raw().latitude())) {
                rawPositionCount++;
            }
            if (validLonLat(point.corrected().longitude(), point.corrected().latitude())) {
                correctedPositionCount++;
            }
            BigDecimal horizontal = point.horizontalCorrectionMeters();
            if (horizontal != null) {
                horizontalCount++;
                horizontalTotal = horizontalTotal.add(horizontal);
                horizontalMaximum = max(horizontalMaximum, horizontal);
                offerHorizontalSample(new MetricSample(mix64(ordinal), ordinal, horizontal));
            }
            BigDecimal altitudeDelta = point.altitudeDeltaMeters();
            if (altitudeDelta != null) {
                BigDecimal absolute = altitudeDelta.abs();
                altitudeCount++;
                altitudeTotal = altitudeTotal.add(absolute);
                altitudeMaximum = max(altitudeMaximum, absolute);
            }
        }

        long sourceRows() {
            return sourceRows;
        }

        boolean p95Approximate() {
            return horizontalCount > horizontalSample.size();
        }

        Summary toSummary() {
            List<BigDecimal> percentileValues = horizontalSample.stream()
                    .map(MetricSample::value)
                    .sorted(BigDecimal::compareTo)
                    .toList();
            return new Summary(
                    sourceRows,
                    objects.size(),
                    Math.toIntExact(rawPositionCount),
                    Math.toIntExact(correctedPositionCount),
                    Math.toIntExact(Math.max(0L, rawPositionCount - correctedPositionCount)),
                    average(horizontalTotal, horizontalCount),
                    scale(horizontalMaximum),
                    percentile95(percentileValues),
                    average(altitudeTotal, altitudeCount),
                    scale(altitudeMaximum)
            );
        }

        private void offerHorizontalSample(MetricSample candidate) {
            if (horizontalSample.size() < P95_RESERVOIR_SIZE) {
                horizontalSample.add(candidate);
                return;
            }
            MetricSample largest = horizontalSample.peek();
            int comparison = Long.compareUnsigned(candidate.hash(), largest.hash());
            if (comparison < 0 || comparison == 0 && candidate.ordinal() < largest.ordinal()) {
                horizontalSample.poll();
                horizontalSample.add(candidate);
            }
        }

        private long mix64(long value) {
            long mixed = value + 0x9E3779B97F4A7C15L;
            mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
            return mixed ^ (mixed >>> 31);
        }
    }

    private final class TrackSampler {
        private final Map<TrackKey, Long> rowCounts = new LinkedHashMap<>();
        private final Map<TrackKey, Integer> quotas;
        private final Map<TrackKey, Long> rowIndexes = new HashMap<>();
        private final Map<TrackKey, Integer> sampleOrdinals = new HashMap<>();

        TrackSampler(List<TrackCount> counts, Map<TrackKey, Integer> quotas) {
            counts.forEach(count -> rowCounts.put(count.key(), count.rowCount()));
            this.quotas = quotas;
        }

        boolean include(RadarPoint point) {
            TrackKey key = trackKey(point);
            long rowIndex = rowIndexes.getOrDefault(key, 0L);
            int sampleOrdinal = sampleOrdinals.getOrDefault(key, 0);
            int quota = quotas.getOrDefault(key, 0);
            long rowCount = rowCounts.getOrDefault(key, 0L);
            boolean selected = false;
            if (sampleOrdinal < quota && rowIndex == sampleIndex(rowCount, quota, sampleOrdinal)) {
                selected = true;
                sampleOrdinals.put(key, sampleOrdinal + 1);
            }
            rowIndexes.put(key, rowIndex + 1);
            return selected;
        }

        private long sampleIndex(long rowCount, int quota, int ordinal) {
            if (quota <= 1 || rowCount <= 1) {
                return 0;
            }
            return Math.round(ordinal * (rowCount - 1.0) / (quota - 1.0));
        }
    }

    private final class RfScannerSummaryAccumulator {
        private final Set<RfTrackKey> tracks = new HashSet<>();
        private final Set<String> matchedObjects = new HashSet<>();
        private long sourceRows;
        private long positionCount;
        private long matchedPointCount;

        void add(RfScannerPoint point) {
            sourceRows++;
            tracks.add(rfScannerTrackKey(point));
            if (validLonLat(point.position().longitude(), point.position().latitude())) {
                positionCount++;
            }
            if (point.matched()) {
                matchedPointCount++;
                matchedObjects.add(point.objectNo());
            }
        }

        long sourceRows() {
            return sourceRows;
        }

        RfScannerDataSummary toSummary(int representedRows) {
            return new RfScannerDataSummary(
                    sourceRows,
                    representedRows,
                    tracks.size(),
                    positionCount,
                    matchedPointCount,
                    matchedObjects.size()
            );
        }
    }

    private final class GlobalTrackSampler {
        private final Map<TrackKey, Long> rowCounts = new LinkedHashMap<>();
        private final Map<TrackKey, Integer> quotas;
        private final Map<TrackKey, Long> rowIndexes = new HashMap<>();
        private final Map<TrackKey, Integer> sampleOrdinals = new HashMap<>();

        GlobalTrackSampler(List<TrackCount> counts, Map<TrackKey, Integer> quotas) {
            counts.forEach(count -> rowCounts.put(count.key(), count.rowCount()));
            this.quotas = quotas;
        }

        boolean include(TrackKey key) {
            long rowIndex = rowIndexes.getOrDefault(key, 0L);
            int sampleOrdinal = sampleOrdinals.getOrDefault(key, 0);
            int quota = quotas.getOrDefault(key, 0);
            long rowCount = rowCounts.getOrDefault(key, 0L);
            boolean selected = false;
            if (sampleOrdinal < quota && rowIndex == sampleIndex(rowCount, quota, sampleOrdinal)) {
                selected = true;
                sampleOrdinals.put(key, sampleOrdinal + 1);
            }
            rowIndexes.put(key, rowIndex + 1);
            return selected;
        }

        private long sampleIndex(long rowCount, int quota, int ordinal) {
            if (quota <= 1 || rowCount <= 1) {
                return 0;
            }
            return Math.round(ordinal * (rowCount - 1.0) / (quota - 1.0));
        }
    }

    private int compareNearness(RadarPoint left, RadarPoint right, LocalDateTime at) {
        long leftDistance = absoluteMillis(CompactEventTimeParser.parse("eventTime", left.eventTime()).value(), at);
        long rightDistance = absoluteMillis(CompactEventTimeParser.parse("eventTime", right.eventTime()).value(), at);
        int distanceComparison = Long.compare(leftDistance, rightDistance);
        if (distanceComparison != 0) {
            return distanceComparison;
        }
        return POINT_ORDER.compare(left, right);
    }

    private long absoluteMillis(LocalDateTime value, LocalDateTime at) {
        return Math.abs(Duration.between(at, value).toMillis());
    }

    private Comparator<RadarPoint> pointOrder() {
        return POINT_ORDER;
    }

    private String objectKey(RadarPoint point) {
        if (point.objectNo() != null && !point.objectNo().isBlank()) {
            return point.objectNo();
        }
        return String.join(":",
                Objects.toString(point.radarId(), ""),
                Objects.toString(point.radarObjectNo(), ""),
                Objects.toString(point.eventId(), ""),
                Objects.toString(point.sourceEventId(), "")
        );
    }

    private String format(LocalDateTime value) {
        return CompactEventTimeParser.format(value);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Long parseOptionalObjectNo(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : parseObjectNo(normalized);
    }

    private Long parseOptionalRadarObjectNo(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException exception) {
            throw new BadRequest("INVALID_SENSOR_TRACK_ID", "radarObjectNo must be a signed 64-bit integer.");
        }
    }

    private Long parseObjectNo(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BadRequest("INVALID_OBJECT_ID", "objectNo must be a signed 64-bit integer.");
        }
    }

    private TimeRange timeRange(String[] values) {
        if (values == null || values.length < 2) {
            return emptyTimeRange();
        }
        return new TimeRange(values[0], values[1]);
    }

    private TimeRange emptyTimeRange() {
        return new TimeRange(null, null);
    }

    private TimeRange combinedTimeRange(TimeRange radar, TimeRange rfScanner) {
        return new TimeRange(
                earlier(radar.min(), rfScanner.min()),
                later(radar.max(), rfScanner.max())
        );
    }

    private String earlier(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    private String later(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private String metaStatusMessage(
            SchemaCapabilities radarSchema,
            boolean radarSchemaFailed,
            RfScannerSchemaCapabilities rfScannerSchema,
            boolean rfScannerSchemaFailed
    ) {
        List<String> ready = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (radarSchema.isReady()) {
            ready.add("RADAR");
        } else {
            unavailable.add(radarSchemaFailed
                    ? "RADAR schema inspection failed"
                    : "RADAR " + schemaIssue(
                            radarSchema.tableExists(), radarSchema.missingRequiredColumns()
                    ));
        }
        if (rfScannerSchema.isReady()) {
            ready.add("RFSCNR");
        } else {
            unavailable.add(rfScannerSchemaFailed
                    ? "RFSCNR schema inspection failed"
                    : "RFSCNR " + schemaIssue(
                            rfScannerSchema.tableExists(), rfScannerSchema.missingRequiredColumns()
                    ));
        }
        if (ready.isEmpty()) {
            return "No observation source schema is ready: " + String.join("; ", unavailable) + ".";
        }
        String message = "Available observation sources: " + String.join(", ", ready) + ".";
        return unavailable.isEmpty()
                ? message
                : message + " Unavailable: " + String.join("; ", unavailable) + ".";
    }

    private String schemaIssue(boolean tableExists, Set<String> missingRequiredColumns) {
        if (!tableExists) {
            return "table was not found";
        }
        return "is missing required columns " + String.join(", ", missingRequiredColumns);
    }

    private Limits apiLimits() {
        ViewerProperties.Limits source = properties.getLimits();
        return new Limits(
                source.getMaxToleranceMs(),
                source.getMaxWindowSeconds(),
                source.getMaxRangeSeconds(),
                source.getMaxQueryRows(),
                source.getMaxOverviewPoints()
        );
    }

    private DatabaseStatus databaseStatus(
            String status,
            String message,
            ViewerProperties.Database database
    ) {
        return new DatabaseStatus(
                status,
                message,
                radarDatabase.resolvedDisplayLabel(),
                radarDatabase.isDemo()
        );
    }

    private MapConfig apiMap() {
        ViewerProperties.Map source = properties.getMap();
        return new MapConfig(
                source.getTileUrl(),
                source.getAttribution(),
                source.getInitialLongitude(),
                source.getInitialLatitude(),
                source.getInitialZoom(),
                source.getMaxNativeZoom(),
                source.getMaxZoom()
        );
    }

    private record QueryRange(
            CompactEventTimeParser.ParsedEventTime from,
            CompactEventTimeParser.ParsedEventTime to,
            boolean snapshot,
            String rangeStart,
            String rangeEnd
    ) {
        String mode() {
            return snapshot ? "SNAPSHOT" : "RANGE";
        }
    }

    private record Overview(List<RadarPoint> points, Summary summary, Sampling sampling) {
    }

    private record SourceContext(
            List<ObservationSource> selectedSources,
            List<String> warnings,
            SchemaCapabilities radarSchema,
            RfScannerSchemaCapabilities rfScannerSchema
    ) {
        boolean includes(ObservationSource source) {
            return selectedSources.contains(source);
        }
    }

    private record ObservationResult(
            List<RadarPoint> radarPoints,
            List<RfScannerPoint> rfScannerPoints,
            ObservationSummary summary,
            Summary radarSummary,
            RfScannerDataSummary rfScannerSummary,
            Sampling sampling
    ) {
    }

    private record QuotaRemainder(TrackKey key, BigInteger remainder, int stableIndex) {
    }

    private record MetricSample(long hash, long ordinal, BigDecimal value) {
    }
}
