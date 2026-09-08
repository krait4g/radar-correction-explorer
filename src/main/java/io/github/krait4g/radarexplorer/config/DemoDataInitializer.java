package io.github.krait4g.radarexplorer.config;

import io.github.krait4g.radarexplorer.config.ViewerProperties.Columns;
import io.github.krait4g.radarexplorer.config.ViewerProperties.RfScannerColumns;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Creates deterministic, synthetic tracks only for the default in-memory demo datasource. */
@Component
@ConditionalOnProperty(prefix = "radar.db", name = "demo-enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    static final LocalDateTime DEMO_START = LocalDateTime.of(2026, 1, 1, 12, 0);
    static final LocalDateTime DEMO_END = LocalDateTime.of(2026, 1, 1, 12, 10);
    public static final int EXPECTED_ROWS = 244;
    public static final int EXPECTED_RF_SCANNER_ROWS = 122;

    private static final DateTimeFormatter OBSERVED_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final long RANDOM_SEED = 20_260_101L;

    private final JdbcTemplate jdbcTemplate;
    private final ViewerProperties viewerProperties;
    private final RadarDatabaseProperties databaseProperties;

    public DemoDataInitializer(
            JdbcTemplate jdbcTemplate,
            ViewerProperties viewerProperties,
            RadarDatabaseProperties databaseProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.viewerProperties = viewerProperties;
        this.databaseProperties = databaseProperties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!databaseProperties.isEmbeddedDemoDatabase()) {
            throw new IllegalStateException(
                    "RADAR_DEMO_ENABLED=true is allowed only with the bundled in-memory H2 demo datasource."
            );
        }
        createSchema();
        if (rowCount() == 0) {
            seedTracks();
        }
        if (rfScannerRowCount() == 0) {
            seedRfScannerTracks();
        }
    }

    private void createSchema() {
        ViewerProperties.Database database = viewerProperties.getDatabase();
        Columns columns = database.getColumns();
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + database.getSchema());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + database.qualifiedTable() + " ("
                + columns.getEventId() + " BIGINT PRIMARY KEY, "
                + columns.getSourceEventId() + " BIGINT, "
                + columns.getObservedAt() + " VARCHAR(17) NOT NULL, "
                + columns.getSensorId() + " VARCHAR(64) NOT NULL, "
                + columns.getSensorTrackId() + " BIGINT NOT NULL, "
                + columns.getObjectId() + " BIGINT NOT NULL, "
                + columns.getRawLongitude() + " NUMERIC(13,8), "
                + columns.getRawLatitude() + " NUMERIC(13,8), "
                + columns.getRawAltitude() + " NUMERIC(13,3), "
                + columns.getCorrectedLongitude() + " NUMERIC(13,8), "
                + columns.getCorrectedLatitude() + " NUMERIC(13,8), "
                + columns.getCorrectedAltitude() + " NUMERIC(13,3), "
                + columns.getPrimaryFlag() + " VARCHAR(1), "
                + columns.getReferenceAltitude() + " NUMERIC(13,3)"
                + ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_demo_observation_time ON "
                + database.qualifiedTable() + " (" + columns.getObservedAt() + ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_demo_observation_object ON "
                + database.qualifiedTable() + " (" + columns.getObjectId() + ", "
                + columns.getObservedAt() + ")");

        RfScannerColumns rf = database.getRfScannerColumns();
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + database.qualifiedRfScannerTable() + " ("
                + rf.getEventId() + " BIGINT PRIMARY KEY, "
                + rf.getObservedAt() + " VARCHAR(17), "
                + rf.getFallbackObservedAt() + " VARCHAR(17), "
                + rf.getScannerId() + " VARCHAR(64) NOT NULL, "
                + rf.getTrackId() + " VARCHAR(128) NOT NULL, "
                + rf.getObjectId() + " BIGINT, "
                + rf.getLongitude() + " NUMERIC(13,8), "
                + rf.getLatitude() + " NUMERIC(13,8), "
                + rf.getAltitude() + " NUMERIC(13,3), "
                + rf.getHomeLongitude() + " NUMERIC(13,8), "
                + rf.getHomeLatitude() + " NUMERIC(13,8), "
                + rf.getHomeAltitude() + " NUMERIC(13,3)"
                + ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_demo_rf_scanner_time ON "
                + database.qualifiedRfScannerTable() + " (" + rf.getObservedAt() + ", "
                + rf.getScannerId() + ", " + rf.getTrackId() + ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_demo_rf_scanner_fallback_time ON "
                + database.qualifiedRfScannerTable() + " (" + rf.getFallbackObservedAt() + ", "
                + rf.getScannerId() + ", " + rf.getTrackId() + ")");
    }

    private int rowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + viewerProperties.getDatabase().qualifiedTable(),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private int rfScannerRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + viewerProperties.getDatabase().qualifiedRfScannerTable(),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private void seedTracks() {
        Columns columns = viewerProperties.getDatabase().getColumns();
        String insert = "INSERT INTO " + viewerProperties.getDatabase().qualifiedTable() + " ("
                + String.join(", ",
                columns.getEventId(), columns.getSourceEventId(), columns.getObservedAt(),
                columns.getSensorId(), columns.getSensorTrackId(), columns.getObjectId(),
                columns.getRawLongitude(), columns.getRawLatitude(), columns.getRawAltitude(),
                columns.getCorrectedLongitude(), columns.getCorrectedLatitude(), columns.getCorrectedAltitude(),
                columns.getPrimaryFlag(), columns.getReferenceAltitude())
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        List<DemoTrack> tracks = List.of(
                new DemoTrack("SENSOR-A", 101, 1001, 126.9780, 37.5665, 0.000030, 0.000018, 118.0),
                new DemoTrack("SENSOR-A", 102, 1002, 126.9840, 37.5610, -0.000022, 0.000026, 156.0),
                new DemoTrack("SENSOR-B", 201, 2001, 126.9700, 37.5700, 0.000018, -0.000020, 92.0),
                new DemoTrack("SENSOR-C", 301, 3001, 126.9900, 37.5740, -0.000026, -0.000014, 205.0)
        );
        Random random = new Random(RANDOM_SEED);
        List<Object[]> rows = new ArrayList<>(EXPECTED_ROWS);
        long eventId = 1;
        for (int sample = 0; sample <= 60; sample++) {
            LocalDateTime observedAt = DEMO_START.plusSeconds(sample * 10L);
            for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
                DemoTrack track = tracks.get(trackIndex);
                double noiseLongitude = random.nextGaussian() * 0.000004;
                double noiseLatitude = random.nextGaussian() * 0.000004;
                double rawLongitude = track.startLongitude() + track.longitudeStep() * sample + noiseLongitude;
                double rawLatitude = track.startLatitude() + track.latitudeStep() * sample + noiseLatitude;
                double rawAltitude = track.startAltitude() + Math.sin((sample + trackIndex) / 6.0) * 5.0;

                boolean missingHorizontal = trackIndex == 2 && sample % 5 == 0
                        || trackIndex == 3 && sample >= 48;
                boolean partialHorizontal = trackIndex == 1 && sample % 17 == 0;
                boolean missingCorrectedAltitude = sample % 9 == 0 || trackIndex == 3 && sample >= 52;
                BigDecimal correctedLongitude = missingHorizontal
                        ? null
                        : decimal(rawLongitude - noiseLongitude * 0.7 + (trackIndex - 1.5) * 0.000006, 8);
                BigDecimal correctedLatitude = missingHorizontal || partialHorizontal
                        ? null
                        : decimal(rawLatitude - noiseLatitude * 0.7 - (trackIndex - 1.5) * 0.000005, 8);
                BigDecimal correctedAltitude = missingCorrectedAltitude
                        ? null
                        : decimal(rawAltitude + Math.cos(sample / 8.0) * 1.8, 3);
                BigDecimal referenceAltitude = sample % 4 == 0
                        ? null
                        : decimal(rawAltitude + 0.75 + trackIndex * 0.2, 3);

                rows.add(new Object[]{
                        eventId,
                        100_000L + eventId,
                        OBSERVED_TIME_FORMATTER.format(observedAt),
                        track.sensorId(),
                        track.sensorTrackId(),
                        track.objectId(),
                        decimal(rawLongitude, 8),
                        decimal(rawLatitude, 8),
                        decimal(rawAltitude, 3),
                        correctedLongitude,
                        correctedLatitude,
                        correctedAltitude,
                        sample % 3 == 0 || trackIndex == 0 ? "Y" : "N",
                        referenceAltitude
                });
                eventId++;
            }
        }
        jdbcTemplate.batchUpdate(insert, rows);
    }

    private void seedRfScannerTracks() {
        ViewerProperties.Database database = viewerProperties.getDatabase();
        RfScannerColumns columns = database.getRfScannerColumns();
        String insert = "INSERT INTO " + database.qualifiedRfScannerTable() + " ("
                + String.join(", ",
                columns.getEventId(), columns.getObservedAt(), columns.getFallbackObservedAt(),
                columns.getScannerId(), columns.getTrackId(), columns.getObjectId(),
                columns.getLongitude(), columns.getLatitude(), columns.getAltitude(),
                columns.getHomeLongitude(), columns.getHomeLatitude(), columns.getHomeAltitude())
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        List<DemoRfTrack> tracks = List.of(
                new DemoRfTrack("RF-SENSOR-A", "RF-TRACK-101", 1001L,
                        126.9782, 37.5667, 0.000030, 0.000018, 42.0),
                new DemoRfTrack("RF-SENSOR-B", "RF-TRACK-UNMATCHED", null,
                        126.9902, 37.5742, -0.000026, -0.000014, 68.0)
        );
        List<Object[]> rows = new ArrayList<>(EXPECTED_RF_SCANNER_ROWS);
        long eventId = 10_001;
        for (int sample = 0; sample <= 60; sample++) {
            LocalDateTime observedAt = DEMO_START.plusSeconds(sample * 10L);
            String compactTime = OBSERVED_TIME_FORMATTER.format(observedAt);
            for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
                DemoRfTrack track = tracks.get(trackIndex);
                boolean useFallback = sample % 10 == 5;
                double longitude = track.startLongitude() + track.longitudeStep() * sample;
                double latitude = track.startLatitude() + track.latitudeStep() * sample;
                double altitude = track.startAltitude() + Math.sin((sample + trackIndex) / 7.0) * 3.5;
                rows.add(new Object[]{
                        eventId++,
                        useFallback ? null : compactTime,
                        useFallback ? compactTime : null,
                        track.scannerId(),
                        track.trackId(),
                        track.objectId(),
                        decimal(longitude, 8),
                        decimal(latitude, 8),
                        decimal(altitude, 3),
                        decimal(126.9779 + trackIndex * 0.012, 8),
                        decimal(37.5664 + trackIndex * 0.008, 8),
                        decimal(31.5 + trackIndex * 2.0, 3)
                });
            }
        }
        jdbcTemplate.batchUpdate(insert, rows);
    }

    private static BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private record DemoTrack(
            String sensorId,
            long sensorTrackId,
            long objectId,
            double startLongitude,
            double startLatitude,
            double longitudeStep,
            double latitudeStep,
            double startAltitude
    ) {
    }

    private record DemoRfTrack(
            String scannerId,
            String trackId,
            Long objectId,
            double startLongitude,
            double startLatitude,
            double longitudeStep,
            double latitudeStep,
            double startAltitude
    ) {
    }
}
