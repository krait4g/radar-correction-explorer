package io.github.krait4g.radarexplorer.repository;

import io.github.krait4g.radarexplorer.config.ViewerProperties;
import io.github.krait4g.radarexplorer.config.ViewerProperties.Columns;
import io.github.krait4g.radarexplorer.model.ApiModels.Coordinate;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarPoint;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

@Repository
public class RadarEventRepository {

    private static final String EVENT_ID = "event_id";
    private static final String SOURCE_EVENT_ID = "source_event_id";
    private static final String OBSERVED_AT = "observed_at";
    private static final String SENSOR_ID = "sensor_id";
    private static final String SENSOR_TRACK_ID = "sensor_track_id";
    private static final String OBJECT_ID = "object_id";
    private static final String RAW_LONGITUDE = "raw_longitude";
    private static final String RAW_LATITUDE = "raw_latitude";
    private static final String RAW_ALTITUDE = "raw_altitude";
    private static final String CORRECTED_LONGITUDE = "corrected_longitude";
    private static final String CORRECTED_LATITUDE = "corrected_latitude";
    private static final String CORRECTED_ALTITUDE = "corrected_altitude";
    private static final String PRIMARY_FLAG = "primary_flag";
    private static final String REFERENCE_ALTITUDE = "reference_altitude";

    private final JdbcTemplate jdbcTemplate;
    private final ViewerProperties properties;
    private final Object schemaCacheMonitor = new Object();
    private volatile CachedSchema cachedSchema;

    public RadarEventRepository(JdbcTemplate jdbcTemplate, ViewerProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public SchemaCapabilities inspectSchema() {
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

            SchemaCapabilities capabilities = querySchemaCapabilities();
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

    private SchemaCapabilities querySchemaCapabilities() {
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
                database().getTable()
        );
        Set<String> columns = new HashSet<>();
        rows.forEach(column -> columns.add(column.toUpperCase(Locale.ROOT)));
        return new SchemaCapabilities(!columns.isEmpty(), Set.copyOf(columns), mapping());
    }

    public String[] findTimeRange() {
        String observedAt = mapping().getObservedAt();
        String minimumSql = "SELECT " + observedAt + " FROM " + qualifiedTable()
                + " WHERE " + observedAt + " IS NOT NULL ORDER BY " + observedAt + " ASC LIMIT 1";
        String maximumSql = "SELECT " + observedAt + " FROM " + qualifiedTable()
                + " WHERE " + observedAt + " IS NOT NULL ORDER BY " + observedAt + " DESC LIMIT 1";
        List<String> minimum = jdbcTemplate.queryForList(minimumSql, String.class);
        List<String> maximum = jdbcTemplate.queryForList(maximumSql, String.class);
        return new String[]{
                minimum.isEmpty() ? null : minimum.getFirst(),
                maximum.isEmpty() ? null : maximum.getFirst()
        };
    }

    public List<RadarPoint> findBetween(
            SchemaCapabilities schema,
            String from,
            String to,
            String radarId,
            Long radarObjectNo,
            Long objectNo,
            boolean primaryOnly,
            int limit
    ) {
        FilteredQuery filter = filteredQuery(schema, from, to, radarId, radarObjectNo, objectNo, primaryOnly);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(selectColumns(schema))
                .append(" FROM ").append(qualifiedTable())
                .append(filter.whereClause())
                .append(pointOrder(schema));

        List<Object> arguments = new ArrayList<>(filter.arguments());
        if (limit > 0) {
            sql.append(" LIMIT ?");
            arguments.add(limit);
        }
        return jdbcTemplate.query(sql.toString(), this::mapRow, arguments.toArray());
    }

    public List<TrackCount> findTrackCountsBetween(
            SchemaCapabilities schema,
            String from,
            String to,
            String radarId,
            Long radarObjectNo,
            Long objectNo,
            boolean primaryOnly
    ) {
        Columns columns = mapping();
        FilteredQuery filter = filteredQuery(schema, from, to, radarId, radarObjectNo, objectNo, primaryOnly);
        String sql = "SELECT "
                + alias(columns.getObjectId(), OBJECT_ID) + ", "
                + alias(columns.getSensorId(), SENSOR_ID) + ", "
                + alias(columns.getSensorTrackId(), SENSOR_TRACK_ID) + ", COUNT(*) AS row_count FROM "
                + qualifiedTable() + filter.whereClause()
                + " GROUP BY " + columns.getObjectId() + ", " + columns.getSensorId() + ", " + columns.getSensorTrackId()
                + trackIdentityOrder();
        return jdbcTemplate.query(
                sql,
                statement -> configureOverviewStatement(statement, filter.arguments(), 1_000),
                (resultSet, rowNumber) -> new TrackCount(
                        new TrackKey(
                                resultSet.getString(OBJECT_ID),
                                resultSet.getString(SENSOR_ID),
                                resultSet.getString(SENSOR_TRACK_ID)
                        ),
                        resultSet.getLong("row_count")
                )
        );
    }

    public void streamBetween(
            SchemaCapabilities schema,
            String from,
            String to,
            String radarId,
            Long radarObjectNo,
            Long objectNo,
            boolean primaryOnly,
            Consumer<RadarPoint> consumer
    ) {
        FilteredQuery filter = filteredQuery(schema, from, to, radarId, radarObjectNo, objectNo, primaryOnly);
        String sql = "SELECT " + selectColumns(schema) + " FROM " + qualifiedTable()
                + filter.whereClause() + pointOrder(schema);
        jdbcTemplate.query(
                sql,
                statement -> configureOverviewStatement(statement, filter.arguments(), 2_000),
                (RowCallbackHandler) resultSet -> consumer.accept(mapRow(resultSet, 0))
        );
    }

    public List<RadarSummary> findRadarsBetween(
            SchemaCapabilities schema,
            String from,
            String to,
            boolean primaryOnly
    ) {
        Columns columns = mapping();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(alias(columns.getSensorId(), SENSOR_ID))
                .append(", COUNT(*) AS event_count, COUNT(DISTINCT ")
                .append(columns.getObjectId()).append(") AS object_count FROM ")
                .append(qualifiedTable())
                .append(" WHERE ").append(columns.getObservedAt()).append(" BETWEEN ? AND ?")
                .append(" AND ").append(columns.getSensorId()).append(" IS NOT NULL");

        List<Object> arguments = new ArrayList<>();
        arguments.add(from);
        arguments.add(to);
        if (primaryOnly && schema.has(columns.getPrimaryFlag())) {
            sql.append(" AND ").append(columns.getPrimaryFlag()).append(" = ?");
            arguments.add("Y");
        }
        sql.append(" GROUP BY ").append(columns.getSensorId())
                .append(" ORDER BY ").append(columns.getSensorId()).append(" ASC");

        return jdbcTemplate.query(
                sql.toString(),
                (resultSet, rowNumber) -> new RadarSummary(
                        resultSet.getString(SENSOR_ID),
                        resultSet.getLong("event_count"),
                        resultSet.getLong("object_count")
                ),
                arguments.toArray()
        );
    }

    private String selectColumns(SchemaCapabilities schema) {
        Columns columns = mapping();
        return String.join(", ",
                optional(schema, columns.getEventId(), EVENT_ID, "BIGINT"),
                optional(schema, columns.getSourceEventId(), SOURCE_EVENT_ID, "BIGINT"),
                alias(columns.getObservedAt(), OBSERVED_AT),
                alias(columns.getSensorId(), SENSOR_ID),
                alias(columns.getSensorTrackId(), SENSOR_TRACK_ID),
                alias(columns.getObjectId(), OBJECT_ID),
                alias(columns.getRawLongitude(), RAW_LONGITUDE),
                alias(columns.getRawLatitude(), RAW_LATITUDE),
                alias(columns.getRawAltitude(), RAW_ALTITUDE),
                optional(schema, columns.getCorrectedLongitude(), CORRECTED_LONGITUDE, "NUMERIC"),
                optional(schema, columns.getCorrectedLatitude(), CORRECTED_LATITUDE, "NUMERIC"),
                optional(schema, columns.getCorrectedAltitude(), CORRECTED_ALTITUDE, "NUMERIC"),
                optional(schema, columns.getReferenceAltitude(), REFERENCE_ALTITUDE, "NUMERIC"),
                optional(schema, columns.getPrimaryFlag(), PRIMARY_FLAG, "VARCHAR(1)")
        );
    }

    private FilteredQuery filteredQuery(
            SchemaCapabilities schema,
            String from,
            String to,
            String radarId,
            Long radarObjectNo,
            Long objectNo,
            boolean primaryOnly
    ) {
        Columns columns = mapping();
        StringBuilder where = new StringBuilder(" WHERE ")
                .append(columns.getObservedAt()).append(" BETWEEN ? AND ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(from);
        arguments.add(to);
        if (radarId != null) {
            where.append(" AND ").append(columns.getSensorId()).append(" = ?");
            arguments.add(radarId);
        }
        if (radarObjectNo != null) {
            where.append(" AND ").append(columns.getSensorTrackId()).append(" = ?");
            arguments.add(radarObjectNo);
        }
        if (objectNo != null) {
            where.append(" AND ").append(columns.getObjectId()).append(" = ?");
            arguments.add(objectNo);
        }
        if (primaryOnly && schema.has(columns.getPrimaryFlag())) {
            where.append(" AND ").append(columns.getPrimaryFlag()).append(" = ?");
            arguments.add("Y");
        }
        return new FilteredQuery(where.toString(), List.copyOf(arguments));
    }

    private String pointOrder(SchemaCapabilities schema) {
        Columns columns = mapping();
        StringBuilder order = new StringBuilder(" ORDER BY ")
                .append(columns.getObservedAt()).append(" ASC, ")
                .append(columns.getObjectId()).append(" ASC, ")
                .append(columns.getSensorId()).append(" ASC, ")
                .append(columns.getSensorTrackId()).append(" ASC");
        if (schema.has(columns.getEventId())) {
            order.append(", ").append(columns.getEventId()).append(" ASC");
        }
        if (schema.has(columns.getSourceEventId())) {
            order.append(", ").append(columns.getSourceEventId()).append(" ASC");
        }
        return order.toString();
    }

    private String trackIdentityOrder() {
        Columns columns = mapping();
        return " ORDER BY CASE WHEN " + columns.getObjectId() + " IS NULL THEN 1 ELSE 0 END, "
                + columns.getObjectId() + " ASC, CASE WHEN " + columns.getSensorId() + " IS NULL THEN 1 ELSE 0 END, "
                + columns.getSensorId() + " ASC, CASE WHEN " + columns.getSensorTrackId()
                + " IS NULL THEN 1 ELSE 0 END, " + columns.getSensorTrackId() + " ASC";
    }

    private void configureOverviewStatement(PreparedStatement statement, List<Object> arguments, int fetchSize)
            throws SQLException {
        for (int index = 0; index < arguments.size(); index++) {
            statement.setObject(index + 1, arguments.get(index));
        }
        statement.setFetchSize(fetchSize);
        statement.setQueryTimeout(properties.getLimits().getOverviewStatementTimeoutSeconds());
    }

    private String alias(String physicalColumn, String canonicalAlias) {
        return physicalColumn + " AS " + canonicalAlias;
    }

    private String optional(
            SchemaCapabilities schema,
            String physicalColumn,
            String canonicalAlias,
            String sqlType
    ) {
        return schema.has(physicalColumn)
                ? alias(physicalColumn, canonicalAlias)
                : "CAST(NULL AS " + sqlType + ") AS " + canonicalAlias;
    }

    private RadarPoint mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        BigDecimal rawLongitude = resultSet.getBigDecimal(RAW_LONGITUDE);
        BigDecimal rawLatitude = resultSet.getBigDecimal(RAW_LATITUDE);
        BigDecimal rawAltitude = resultSet.getBigDecimal(RAW_ALTITUDE);
        BigDecimal correctedLongitude = resultSet.getBigDecimal(CORRECTED_LONGITUDE);
        BigDecimal correctedLatitude = resultSet.getBigDecimal(CORRECTED_LATITUDE);
        BigDecimal correctedAltitude = resultSet.getBigDecimal(CORRECTED_ALTITUDE);

        return new RadarPoint(
                nullableLong(resultSet, EVENT_ID),
                nullableLong(resultSet, SOURCE_EVENT_ID),
                resultSet.getString(OBSERVED_AT),
                resultSet.getString(SENSOR_ID),
                resultSet.getString(SENSOR_TRACK_ID),
                resultSet.getString(OBJECT_ID),
                resultSet.getString(PRIMARY_FLAG),
                new Coordinate(rawLongitude, rawLatitude, rawAltitude),
                new Coordinate(correctedLongitude, correctedLatitude, correctedAltitude),
                resultSet.getBigDecimal(REFERENCE_ALTITUDE),
                null,
                null,
                null
        );
    }

    private Long nullableLong(ResultSet resultSet, String label) throws SQLException {
        Number value = (Number) resultSet.getObject(label);
        return value == null ? null : value.longValue();
    }

    private ViewerProperties.Database database() {
        return properties.getDatabase();
    }

    private Columns mapping() {
        return database().getColumns();
    }

    private String qualifiedTable() {
        return database().qualifiedTable();
    }

    public record TrackKey(String objectNo, String radarId, String radarObjectNo) {
    }

    public record TrackCount(TrackKey key, long rowCount) {
    }

    private record FilteredQuery(String whereClause, List<Object> arguments) {
    }

    private record CachedSchema(SchemaCapabilities capabilities, long expiresAtNanos) {
        boolean isValidAt(long now) {
            return expiresAtNanos - now > 0;
        }
    }
}
