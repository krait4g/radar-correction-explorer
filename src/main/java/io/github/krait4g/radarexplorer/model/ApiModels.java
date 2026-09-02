package io.github.krait4g.radarexplorer.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    public record Coordinate(BigDecimal longitude, BigDecimal latitude, BigDecimal altitude) {
    }

    public record RadarPoint(
            Long eventId,
            Long sourceEventId,
            String eventTime,
            String radarId,
            String radarObjectNo,
            String objectNo,
            String primaryFlag,
            Coordinate raw,
            Coordinate corrected,
            BigDecimal referenceAltitude,
            BigDecimal horizontalCorrectionMeters,
            BigDecimal altitudeDeltaMeters,
            String correctionStatus
    ) {
    }

    public record Capabilities(
            boolean correctedLongitude,
            boolean correctedLatitude,
            boolean correctedAltitude,
            boolean primaryFlag,
            boolean referenceAltitude
    ) {
    }

    public record DatabaseStatus(
            String status,
            String message,
            String connectionLabel,
            boolean syntheticDemo
    ) {
    }

    public record TimeRange(String min, String max) {
    }

    public record Limits(
            int maxToleranceMs,
            int maxWindowSeconds,
            int maxRangeSeconds,
            int maxQueryRows,
            int maxOverviewPoints
    ) {
    }

    public record MapConfig(
            String tileUrl,
            String attribution,
            double initialLongitude,
            double initialLatitude,
            double initialZoom,
            int maxNativeZoom,
            int maxZoom
    ) {
    }

    public record MetaResponse(
            DatabaseStatus database,
            Capabilities capabilities,
            TimeRange timeRange,
            Limits limits,
            MapConfig map
    ) {
    }

    public record Summary(
            long sourceRows,
            int objectCount,
            int rawPositionCount,
            int correctedPositionCount,
            int uncorrectedCount,
            BigDecimal averageHorizontalCorrectionMeters,
            BigDecimal maxHorizontalCorrectionMeters,
            BigDecimal p95HorizontalCorrectionMeters,
            BigDecimal averageAbsoluteAltitudeDeltaMeters,
            BigDecimal maxAbsoluteAltitudeDeltaMeters
    ) {
    }

    public record SnapshotResponse(
            String requestedAt,
            int toleranceMs,
            String rangeStart,
            String rangeEnd,
            boolean primaryOnly,
            Summary summary,
            List<RadarPoint> points
    ) {
    }

    public record TracksResponse(
            String mode,
            String requestedFrom,
            String requestedTo,
            String normalizedFrom,
            String normalizedTo,
            int toleranceMs,
            String rangeStart,
            String rangeEnd,
            boolean primaryOnly,
            Summary summary,
            Sampling sampling,
            List<RadarPoint> points
    ) {
    }

    public record Sampling(
            boolean sampled,
            String strategy,
            long sourceRows,
            int returnedRows,
            long trackCount,
            long representedTracks,
            boolean allTracksRepresented,
            String metricsScope,
            boolean p95Approximate
    ) {
    }

    public record RadarSummary(String radarId, long eventCount, long objectCount) {
    }

    public record RadarsResponse(
            String mode,
            String requestedFrom,
            String requestedTo,
            String normalizedFrom,
            String normalizedTo,
            int toleranceMs,
            String rangeStart,
            String rangeEnd,
            boolean primaryOnly,
            List<RadarSummary> radars
    ) {
    }

    public record DetailResponse(
            String objectNo,
            String requestedAt,
            int windowSeconds,
            String rangeStart,
            String rangeEnd,
            boolean primaryOnly,
            Summary summary,
            List<RadarPoint> points
    ) {
    }

    public record ErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String code,
            String message,
            String path,
            List<String> details
    ) {
    }
}
