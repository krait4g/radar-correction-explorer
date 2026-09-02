package io.github.krait4g.radarexplorer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.viewer")
public class ViewerProperties {

    @Valid
    private final Database database = new Database();

    @Valid
    private final Limits limits = new Limits();

    @Valid
    private final Map map = new Map();

    public Database getDatabase() {
        return database;
    }

    public Limits getLimits() {
        return limits;
    }

    public Map getMap() {
        return map;
    }

    public static class Database {
        private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String schema = "public";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String table = "radar_observation";

        @Min(0)
        @Max(3_600)
        private int schemaCacheSeconds = 60;

        @Valid
        private final Columns columns = new Columns();

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public int getSchemaCacheSeconds() {
            return schemaCacheSeconds;
        }

        public void setSchemaCacheSeconds(int schemaCacheSeconds) {
            this.schemaCacheSeconds = schemaCacheSeconds;
        }

        public Columns getColumns() {
            return columns;
        }

        public String qualifiedTable() {
            return schema + "." + table;
        }
    }

    /**
     * Maps a deployment's physical database identifiers to the explorer's canonical model.
     * Values are configuration only, never request parameters, and are constrained to simple SQL
     * identifiers before a repository can interpolate them into a statement.
     */
    public static class Columns {
        private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String eventId = "event_id";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String sourceEventId = "source_event_id";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String observedAt = "observed_at";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String sensorId = "sensor_id";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String sensorTrackId = "sensor_track_id";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String objectId = "object_id";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String rawLongitude = "raw_longitude";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String rawLatitude = "raw_latitude";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String rawAltitude = "raw_altitude";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String correctedLongitude = "corrected_longitude";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String correctedLatitude = "corrected_latitude";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String correctedAltitude = "corrected_altitude";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String primaryFlag = "primary_flag";

        @NotBlank
        @Pattern(regexp = IDENTIFIER_PATTERN, message = "must be a simple SQL identifier")
        private String referenceAltitude = "reference_altitude";

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getSourceEventId() {
            return sourceEventId;
        }

        public void setSourceEventId(String sourceEventId) {
            this.sourceEventId = sourceEventId;
        }

        public String getObservedAt() {
            return observedAt;
        }

        public void setObservedAt(String observedAt) {
            this.observedAt = observedAt;
        }

        public String getSensorId() {
            return sensorId;
        }

        public void setSensorId(String sensorId) {
            this.sensorId = sensorId;
        }

        public String getSensorTrackId() {
            return sensorTrackId;
        }

        public void setSensorTrackId(String sensorTrackId) {
            this.sensorTrackId = sensorTrackId;
        }

        public String getObjectId() {
            return objectId;
        }

        public void setObjectId(String objectId) {
            this.objectId = objectId;
        }

        public String getRawLongitude() {
            return rawLongitude;
        }

        public void setRawLongitude(String rawLongitude) {
            this.rawLongitude = rawLongitude;
        }

        public String getRawLatitude() {
            return rawLatitude;
        }

        public void setRawLatitude(String rawLatitude) {
            this.rawLatitude = rawLatitude;
        }

        public String getRawAltitude() {
            return rawAltitude;
        }

        public void setRawAltitude(String rawAltitude) {
            this.rawAltitude = rawAltitude;
        }

        public String getCorrectedLongitude() {
            return correctedLongitude;
        }

        public void setCorrectedLongitude(String correctedLongitude) {
            this.correctedLongitude = correctedLongitude;
        }

        public String getCorrectedLatitude() {
            return correctedLatitude;
        }

        public void setCorrectedLatitude(String correctedLatitude) {
            this.correctedLatitude = correctedLatitude;
        }

        public String getCorrectedAltitude() {
            return correctedAltitude;
        }

        public void setCorrectedAltitude(String correctedAltitude) {
            this.correctedAltitude = correctedAltitude;
        }

        public String getPrimaryFlag() {
            return primaryFlag;
        }

        public void setPrimaryFlag(String primaryFlag) {
            this.primaryFlag = primaryFlag;
        }

        public String getReferenceAltitude() {
            return referenceAltitude;
        }

        public void setReferenceAltitude(String referenceAltitude) {
            this.referenceAltitude = referenceAltitude;
        }
    }

    public static class Limits {
        @Min(1)
        @Max(30_000)
        private int maxToleranceMs = 30_000;

        @Min(1)
        @Max(300)
        private int maxWindowSeconds = 300;

        @Min(0)
        @Max(86_400)
        private int maxRangeSeconds = 0;

        /** Zero disables the exact-query row limit. */
        @Min(0)
        @Max(10_000_000)
        private int maxQueryRows = 0;

        @Min(2)
        @Max(1_000_000)
        private int maxOverviewPoints = 100_000;

        /** Bounds long overview scans so abandoned browser requests cannot exhaust the DB pool. */
        @Min(1)
        @Max(16)
        private int maxConcurrentOverviewQueries = 1;

        @Min(1)
        @Max(60)
        private int statementTimeoutSeconds = 10;

        @Min(1)
        @Max(600)
        private int overviewStatementTimeoutSeconds = 60;

        public int getMaxToleranceMs() {
            return maxToleranceMs;
        }

        public void setMaxToleranceMs(int maxToleranceMs) {
            this.maxToleranceMs = maxToleranceMs;
        }

        public int getMaxWindowSeconds() {
            return maxWindowSeconds;
        }

        public void setMaxWindowSeconds(int maxWindowSeconds) {
            this.maxWindowSeconds = maxWindowSeconds;
        }

        public int getMaxRangeSeconds() {
            return maxRangeSeconds;
        }

        public void setMaxRangeSeconds(int maxRangeSeconds) {
            this.maxRangeSeconds = maxRangeSeconds;
        }

        public int getMaxQueryRows() {
            return maxQueryRows;
        }

        public void setMaxQueryRows(int maxQueryRows) {
            this.maxQueryRows = maxQueryRows;
        }

        public int getMaxOverviewPoints() {
            return maxOverviewPoints;
        }

        public void setMaxOverviewPoints(int maxOverviewPoints) {
            this.maxOverviewPoints = maxOverviewPoints;
        }

        public int getMaxConcurrentOverviewQueries() {
            return maxConcurrentOverviewQueries;
        }

        public void setMaxConcurrentOverviewQueries(int maxConcurrentOverviewQueries) {
            this.maxConcurrentOverviewQueries = maxConcurrentOverviewQueries;
        }

        public int getStatementTimeoutSeconds() {
            return statementTimeoutSeconds;
        }

        public void setStatementTimeoutSeconds(int statementTimeoutSeconds) {
            this.statementTimeoutSeconds = statementTimeoutSeconds;
        }

        public int getOverviewStatementTimeoutSeconds() {
            return overviewStatementTimeoutSeconds;
        }

        public void setOverviewStatementTimeoutSeconds(int overviewStatementTimeoutSeconds) {
            this.overviewStatementTimeoutSeconds = overviewStatementTimeoutSeconds;
        }
    }

    public static class Map {
        private String tileUrl = "";

        private String attribution = "";

        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        private double initialLongitude = 0.0;

        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        private double initialLatitude = 0.0;

        @DecimalMin("0.0")
        @DecimalMax("24.0")
        private double initialZoom = 2.0;

        @Min(2)
        @Max(24)
        private int maxNativeZoom = 19;

        @Min(2)
        @Max(24)
        private int maxZoom = 24;

        public String getTileUrl() {
            return tileUrl;
        }

        public void setTileUrl(String tileUrl) {
            this.tileUrl = tileUrl;
        }

        public String getAttribution() {
            return attribution;
        }

        public void setAttribution(String attribution) {
            this.attribution = attribution;
        }

        public double getInitialLongitude() {
            return initialLongitude;
        }

        public void setInitialLongitude(double initialLongitude) {
            this.initialLongitude = initialLongitude;
        }

        public double getInitialLatitude() {
            return initialLatitude;
        }

        public void setInitialLatitude(double initialLatitude) {
            this.initialLatitude = initialLatitude;
        }

        public double getInitialZoom() {
            return initialZoom;
        }

        public void setInitialZoom(double initialZoom) {
            this.initialZoom = initialZoom;
        }

        public int getMaxNativeZoom() {
            return maxNativeZoom;
        }

        public void setMaxNativeZoom(int maxNativeZoom) {
            this.maxNativeZoom = maxNativeZoom;
        }

        public int getMaxZoom() {
            return maxZoom;
        }

        public void setMaxZoom(int maxZoom) {
            this.maxZoom = maxZoom;
        }
    }
}
