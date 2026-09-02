package io.github.krait4g.radarexplorer.service;

import io.github.krait4g.radarexplorer.config.RadarDatabaseProperties;
import io.github.krait4g.radarexplorer.config.ViewerProperties;
import io.github.krait4g.radarexplorer.model.ApiModels.Coordinate;
import io.github.krait4g.radarexplorer.model.ApiModels.DatabaseStatus;
import io.github.krait4g.radarexplorer.model.ApiModels.DetailResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.Limits;
import io.github.krait4g.radarexplorer.model.ApiModels.MapConfig;
import io.github.krait4g.radarexplorer.model.ApiModels.MetaResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarPoint;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarSummary;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarsResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.Sampling;
import io.github.krait4g.radarexplorer.model.ApiModels.SnapshotResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.Summary;
import io.github.krait4g.radarexplorer.model.ApiModels.TimeRange;
import io.github.krait4g.radarexplorer.model.ApiModels.TracksResponse;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackCount;
import io.github.krait4g.radarexplorer.repository.RadarEventRepository.TrackKey;
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
    private static final Comparator<RadarPoint> POINT_ORDER = Comparator
            .comparing(RadarPoint::eventTime, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::objectNo, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::radarId, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::radarObjectNo, Comparator.nullsLast(String::compareTo))
            .thenComparing(RadarPoint::eventId, Comparator.nullsLast(Long::compareTo))
            .thenComparing(RadarPoint::sourceEventId, Comparator.nullsLast(Long::compareTo));

    private final RadarEventRepository repository;
    private final ViewerProperties properties;
    private final RadarDatabaseProperties radarDatabase;
    private final TransactionTemplate overviewTransaction;
    private final Semaphore overviewSlots;

    public RadarViewerService(
            RadarEventRepository repository,
            ViewerProperties properties,
            RadarDatabaseProperties radarDatabase,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.properties = properties;
        this.radarDatabase = radarDatabase;
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
        try {
            SchemaCapabilities schema = repository.inspectSchema();
            if (!schema.tableExists()) {
                return new MetaResponse(
                        databaseStatus("SCHEMA_MISMATCH", "The configured observation table was not found.", database),
                        schema.toApiCapabilities(), new TimeRange(null, null), limits, map
                );
            }
            if (!schema.isReady()) {
                return new MetaResponse(
                        databaseStatus("SCHEMA_MISMATCH", "The configured table is missing required mapped columns.", database),
                        schema.toApiCapabilities(), new TimeRange(null, null), limits, map
                );
            }
            String[] timeRange = repository.findTimeRange();
            return new MetaResponse(
                    databaseStatus("UP", "The observation schema is ready.", database),
                    schema.toApiCapabilities(), new TimeRange(timeRange[0], timeRange[1]), limits, map
            );
        } catch (DataAccessException exception) {
            return new MetaResponse(
                    databaseStatus("DOWN", "Database connection is unavailable.", database),
                    SchemaCapabilities.unavailable().toApiCapabilities(), new TimeRange(null, null), limits, map
            );
        }
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
            throw new Unavailable("OBSERVATION_SCHEMA_UNAVAILABLE", "The configured observation table was not found.");
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

    private record QuotaRemainder(TrackKey key, BigInteger remainder, int stableIndex) {
    }

    private record MetricSample(long hash, long ordinal, BigDecimal value) {
    }
}
