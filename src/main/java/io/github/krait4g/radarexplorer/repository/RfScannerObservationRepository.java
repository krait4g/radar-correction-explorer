package io.github.krait4g.radarexplorer.repository;

import io.github.krait4g.radarexplorer.config.ViewerProperties;
import io.github.krait4g.radarexplorer.config.ViewerProperties.RfScannerColumns;
import io.github.krait4g.radarexplorer.model.ApiModels.Coordinate;
import io.github.krait4g.radarexplorer.model.ApiModels.MatchMode;
import io.github.krait4g.radarexplorer.model.ApiModels.RfScannerPoint;
import io.github.krait4g.radarexplorer.model.ApiModels.RfScannerSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Read-only access to an optional, generically mapped RF scanner observation table. */
@Repository
public class RfScannerObservationRepository {

    private static final String VALID_COMPACT_TIME = "^[0-9]{17}$";
    private static final String EVENT_ID = "event_id";
    private static final String EVENT_TIME = "event_time";
    private static final String TIME_SOURCE = "time_source";
    private static final String SCANNER_ID = "scanner_id";
    private static final String TRACK_ID = "scanner_track_id";
    private static final String OBJECT_ID = "object_id";
    private static final String LONGITUDE = "longitude";
    private static final String LATITUDE = "latitude";
    private static final String ALTITUDE = "altitude";
    private static final String HOME_LONGITUDE = "home_longitude";
    private static final String HOME_LATITUDE = "home_latitude";
    private static final String HOME_ALTITUDE = "home_altitude";

    private final JdbcTemplate jdbcTemplate;
    private final ViewerProperties properties;
    private final Object schemaCacheMonitor = new Object();
    private volatile CachedSchema cachedSchema;

    public RfScannerObservationRepository(JdbcTemplate jdbcTemplate, ViewerProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public RfScannerSchemaCapabilities inspectSchema() {
        long now = System.nanoTime();
        CachedSchema current = cachedSchema;
        if (current != null && current.isValidAt(now)) {
            return current.capabilities();
        }
        synchronized (schemaCacheMonitor) {
            now = System.nanoTime();
            current = cachedSchema;
            if (current != null && current.isValidAt(now)) {
                return current.capabilities();
            }
            RfScannerSchemaCapabilities capabilities = querySchemaCapabilities();
            int cacheSeconds = properties.getDatabase().getSchemaCacheSeconds();
            if (cacheSeconds > 0) {
                cachedSchema = new CachedSchema(
                        capabilities,
                        now + java.time.Duration.ofSeconds(cacheSeconds).toNanos()
                );
            }
            return capabilities;
        }
    }

    public void invalidateSchemaCache() {
        synchronized (schemaCacheMonitor) {
            cachedSchema = null;
        }
    }

    private RfScannerSchemaCapabilities querySchemaCapabilities() {
        String sql = """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE LOWER(table_schema) = LOWER(?)
                   AND LOWER(table_name) = LOWER(?)
                """;
        List<String> rows = jdbcTemplate.queryForList(
                sql,
                String.class,
                database().getSchema(),
                database().getRfScannerTable()
        );
        Set<String> columns = new HashSet<>();
        rows.forEach(column -> columns.add(column.toUpperCase(Locale.ROOT)));
        return new RfScannerSchemaCapabilities(!columns.isEmpty(), Set.copyOf(columns), mapping());
    }

    public String[] findTimeRange(RfScannerSchemaCapabilities schema) {
        if (!schema.has(mapping().getObservedAt())) {
            return new String[]{null, null};
        }
        String primary = mapping().getObservedAt();
        String primaryValid = validTime(primary);
        String earliestPrimary = findBoundary(primary, primaryValid, "ASC");
        String latestPrimary = findBoundary(primary, primaryValid, "DESC");

        String earliestFallback = null;
        String latestFallback = null;
        if (schema.has(mapping().getFallbackObservedAt())) {
            String fallback = mapping().getFallbackObservedAt();
            String fallbackValid = "(" + primary + " IS NULL OR NOT (" + primaryValid + "))"
                    + " AND " + validTime(fallback);
            earliestFallback = findBoundary(fallback, fallbackValid, "ASC");
            latestFallback = findBoundary(fallback, fallbackValid, "DESC");
        }
        return new String[]{earlier(earliestPrimary, earliestFallback), later(latestPrimary, latestFallback)};
    }

    private String findBoundary(String timeColumn, String predicate, String direction) {
        String sql = "SELECT " + timeColumn + " FROM " + qualifiedTable()
                + " WHERE " + predicate
                + " ORDER BY " + timeColumn + " " + direction + " LIMIT 1";
        List<String> values = jdbcTemplate.queryForList(sql, String.class);
        return values.isEmpty() ? null : values.getFirst();
    }

    public List<RfScannerPoint> findBetween(
            RfScannerSchemaCapabilities schema,
            String from,
            String to,
            List<String> rfScannerIds,
            String trackId,
            Long objectNo,
            MatchMode matchMode,
            int limit
    ) {
        FilteredUnion query = filteredUnion(schema, from, to, rfScannerIds, trackId, objectNo, matchMode);
        StringBuilder sql = new StringBuilder("SELECT * FROM (")
                .append(query.sql()).append(") rf_events").append(pointOrder());
        List<Object> arguments = new ArrayList<>(query.arguments());
        if (limit > 0) {
            sql.append(" LIMIT ?");
            arguments.add(limit);
        }
        return jdbcTemplate.query(sql.toString(), this::mapRow, arguments.toArray());
    }

    public List<RfTrackCount> findTrackCountsBetween(
            RfScannerSchemaCapabilities schema,
            String from,
            String to,
            List<String> rfScannerIds,
            String trackId,
            Long objectNo,
            MatchMode matchMode
    ) {
        FilteredUnion query = filteredUnion(schema, from, to, rfScannerIds, trackId, objectNo, matchMode);
        String sql = "SELECT " + SCANNER_ID + ", " + TRACK_ID + ", COUNT(*) AS row_count FROM ("
                + query.sql() + ") rf_events GROUP BY " + SCANNER_ID + ", " + TRACK_ID + rfTrackOrder();
        return jdbcTemplate.query(
                sql,
                statement -> configureStreamingStatement(statement, query.arguments(), 1_000),
                (resultSet, rowNumber) -> new RfTrackCount(
                        new RfTrackKey(resultSet.getString(SCANNER_ID), resultSet.getString(TRACK_ID)),
                        resultSet.getLong("row_count")
                )
        );
    }

    public void streamBetween(
            RfScannerSchemaCapabilities schema,
            String from,
            String to,
            List<String> rfScannerIds,
            String trackId,
            Long objectNo,
            MatchMode matchMode,
            Consumer<RfScannerPoint> consumer
    ) {
        FilteredUnion query = filteredUnion(schema, from, to, rfScannerIds, trackId, objectNo, matchMode);
        String sql = "SELECT * FROM (" + query.sql() + ") rf_events" + pointOrder();
        jdbcTemplate.query(
                sql,
                statement -> configureStreamingStatement(statement, query.arguments(), 2_000),
                (RowCallbackHandler) resultSet -> consumer.accept(mapRow(resultSet, 0))
        );
    }

    public List<RfScannerSummary> findRfScannersBetween(
            RfScannerSchemaCapabilities schema,
            String from,
            String to
    ) {
        FilteredUnion query = filteredUnion(schema, from, to, List.of(), null, null, MatchMode.ALL);
        String sql = "SELECT " + SCANNER_ID + ", COUNT(*) AS event_count,"
                + " COUNT(DISTINCT " + TRACK_ID + ") AS track_count,"
                + " COUNT(DISTINCT " + OBJECT_ID + ") AS matched_object_count"
                + " FROM (" + query.sql() + ") rf_events"
                + " WHERE " + SCANNER_ID + " IS NOT NULL"
                + " GROUP BY " + SCANNER_ID + " ORDER BY " + SCANNER_ID + " ASC";
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new RfScannerSummary(
                        resultSet.getString(SCANNER_ID),
                        resultSet.getLong("event_count"),
                        resultSet.getLong("track_count"),
                        resultSet.getLong("matched_object_count")
                ),
                query.arguments().toArray()
        );
    }

    private FilteredUnion filteredUnion(
            RfScannerSchemaCapabilities schema,
            String from,
            String to,
            List<String> rfScannerIds,
            String trackId,
            Long objectNo,
            MatchMode matchMode
    ) {
        StringBuilder sql = new StringBuilder();
        List<Object> arguments = new ArrayList<>();
        RfScannerColumns columns = mapping();
        appendBranch(
                sql, arguments, schema, columns.getObservedAt(), "OBSERVED_AT",
                validTime(columns.getObservedAt()), from, to, rfScannerIds, trackId, objectNo, matchMode
        );
        if (schema.has(columns.getFallbackObservedAt())) {
            sql.append(" UNION ALL ");
            String fallbackPredicate = "(" + columns.getObservedAt() + " IS NULL OR NOT ("
                    + validTime(columns.getObservedAt()) + ")) AND " + validTime(columns.getFallbackObservedAt());
            appendBranch(
                    sql, arguments, schema, columns.getFallbackObservedAt(), "FALLBACK_OBSERVED_AT",
                    fallbackPredicate, from, to, rfScannerIds, trackId, objectNo, matchMode
            );
        }
        return new FilteredUnion(sql.toString(), List.copyOf(arguments));
    }

    private void appendBranch(
            StringBuilder sql,
            List<Object> arguments,
            RfScannerSchemaCapabilities schema,
            String timeColumn,
            String timeSource,
            String validTimePredicate,
            String from,
            String to,
            List<String> rfScannerIds,
            String trackId,
            Long objectNo,
            MatchMode matchMode
    ) {
        sql.append("SELECT ").append(selectColumns(schema, timeColumn, timeSource))
                .append(" FROM ").append(qualifiedTable())
                .append(" WHERE ").append(validTimePredicate)
                .append(" AND ").append(timeColumn).append(" BETWEEN ? AND ?");
        arguments.add(from);
        arguments.add(to);
        appendFilters(sql, arguments, schema, rfScannerIds, trackId, objectNo, matchMode);
    }

    private void appendFilters(
            StringBuilder sql,
            List<Object> arguments,
            RfScannerSchemaCapabilities schema,
            List<String> rfScannerIds,
            String trackId,
            Long objectNo,
            MatchMode matchMode
    ) {
        RfScannerColumns columns = mapping();
        if (rfScannerIds != null && !rfScannerIds.isEmpty()) {
            sql.append(" AND ").append(columns.getScannerId()).append(" IN (")
                    .append(placeholders(rfScannerIds.size())).append(')');
            arguments.addAll(rfScannerIds);
        }
        if (trackId != null) {
            sql.append(" AND ").append(columns.getTrackId()).append(" = ?");
            arguments.add(trackId);
        }
        if (objectNo != null) {
            if (schema.has(columns.getObjectId())) {
                sql.append(" AND ").append(columns.getObjectId()).append(" = ?");
                arguments.add(objectNo);
            } else {
                sql.append(" AND 1 = 0");
            }
        }
        if (matchMode == MatchMode.MATCHED) {
            sql.append(schema.has(columns.getObjectId())
                    ? " AND " + columns.getObjectId() + " IS NOT NULL"
                    : " AND 1 = 0");
        } else if (matchMode == MatchMode.UNMATCHED && schema.has(columns.getObjectId())) {
            sql.append(" AND ").append(columns.getObjectId()).append(" IS NULL");
        }
    }

    private String selectColumns(RfScannerSchemaCapabilities schema, String timeColumn, String timeSource) {
        RfScannerColumns columns = mapping();
        return String.join(", ",
                alias(columns.getEventId(), EVENT_ID),
                alias(timeColumn, EVENT_TIME),
                "CAST('" + timeSource + "' AS VARCHAR(32)) AS " + TIME_SOURCE,
                alias(columns.getScannerId(), SCANNER_ID),
                alias(columns.getTrackId(), TRACK_ID),
                optional(schema, columns.getObjectId(), OBJECT_ID, "VARCHAR(128)"),
                alias(columns.getLongitude(), LONGITUDE),
                alias(columns.getLatitude(), LATITUDE),
                alias(columns.getAltitude(), ALTITUDE),
                optional(schema, columns.getHomeLongitude(), HOME_LONGITUDE, "NUMERIC"),
                optional(schema, columns.getHomeLatitude(), HOME_LATITUDE, "NUMERIC"),
                optional(schema, columns.getHomeAltitude(), HOME_ALTITUDE, "NUMERIC")
        );
    }

    private String validTime(String column) {
        return column + " ~ '" + VALID_COMPACT_TIME + "'";
    }

    private String alias(String physicalColumn, String canonicalAlias) {
        return physicalColumn + " AS " + canonicalAlias;
    }

    private String optional(
            RfScannerSchemaCapabilities schema,
            String physicalColumn,
            String canonicalAlias,
            String sqlType
    ) {
        return schema.has(physicalColumn)
                ? alias(physicalColumn, canonicalAlias)
                : "CAST(NULL AS " + sqlType + ") AS " + canonicalAlias;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private String pointOrder() {
        return " ORDER BY " + EVENT_TIME + " ASC, " + SCANNER_ID + " ASC, "
                + TRACK_ID + " ASC, " + EVENT_ID + " ASC";
    }

    private String rfTrackOrder() {
        return " ORDER BY CASE WHEN " + SCANNER_ID + " IS NULL THEN 1 ELSE 0 END, "
                + SCANNER_ID + " ASC, CASE WHEN " + TRACK_ID + " IS NULL THEN 1 ELSE 0 END, "
                + TRACK_ID + " ASC";
    }

    private void configureStreamingStatement(PreparedStatement statement, List<Object> arguments, int fetchSize)
            throws SQLException {
        for (int index = 0; index < arguments.size(); index++) {
            statement.setObject(index + 1, arguments.get(index));
        }
        statement.setFetchSize(fetchSize);
        statement.setQueryTimeout(properties.getLimits().getOverviewStatementTimeoutSeconds());
    }

    private RfScannerPoint mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String objectNo = resultSet.getString(OBJECT_ID);
        Coordinate home = coordinateOrNull(
                resultSet.getBigDecimal(HOME_LONGITUDE),
                resultSet.getBigDecimal(HOME_LATITUDE),
                resultSet.getBigDecimal(HOME_ALTITUDE)
        );
        return new RfScannerPoint(
                nullableLong(resultSet, EVENT_ID),
                resultSet.getString(EVENT_TIME),
                resultSet.getString(TIME_SOURCE),
                resultSet.getString(SCANNER_ID),
                resultSet.getString(TRACK_ID),
                objectNo,
                objectNo != null && !objectNo.isBlank(),
                new Coordinate(
                        resultSet.getBigDecimal(LONGITUDE),
                        resultSet.getBigDecimal(LATITUDE),
                        resultSet.getBigDecimal(ALTITUDE)
                ),
                home,
                "RELATIVE_TO_HOME"
        );
    }

    private Coordinate coordinateOrNull(
            java.math.BigDecimal longitude,
            java.math.BigDecimal latitude,
            java.math.BigDecimal altitude
    ) {
        return longitude == null && latitude == null && altitude == null
                ? null
                : new Coordinate(longitude, latitude, altitude);
    }

    private Long nullableLong(ResultSet resultSet, String label) throws SQLException {
        Number value = (Number) resultSet.getObject(label);
        return value == null ? null : value.longValue();
    }

    private String earlier(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) <= 0 ? first : second;
    }

    private String later(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }

    private ViewerProperties.Database database() {
        return properties.getDatabase();
    }

    private RfScannerColumns mapping() {
        return database().getRfScannerColumns();
    }

    private String qualifiedTable() {
        return database().qualifiedRfScannerTable();
    }

    public record RfTrackKey(String rfScannerId, String trackId) {
    }

    public record RfTrackCount(RfTrackKey key, long rowCount) {
    }

    private record FilteredUnion(String sql, List<Object> arguments) {
    }

    private record CachedSchema(RfScannerSchemaCapabilities capabilities, long expiresAtNanos) {
        boolean isValidAt(long now) {
            return expiresAtNanos - now > 0;
        }
    }
}
