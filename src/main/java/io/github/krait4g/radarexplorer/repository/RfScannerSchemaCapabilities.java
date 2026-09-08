package io.github.krait4g.radarexplorer.repository;

import io.github.krait4g.radarexplorer.config.ViewerProperties.RfScannerColumns;
import io.github.krait4g.radarexplorer.model.ApiModels.RfScannerCapabilities;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Database capabilities resolved through deployment-specific RF scanner mappings. */
public record RfScannerSchemaCapabilities(
        boolean tableExists,
        Set<String> columns,
        RfScannerColumns mapping
) {

    public RfScannerSchemaCapabilities {
        columns = columns == null ? Set.of() : columns.stream()
                .map(column -> column.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        mapping = mapping == null ? new RfScannerColumns() : mapping;
    }

    public boolean has(String physicalColumn) {
        return physicalColumn != null && columns.contains(physicalColumn.toUpperCase(Locale.ROOT));
    }

    public Set<String> missingRequiredColumns() {
        Set<String> missing = new LinkedHashSet<>();
        require(missing, mapping.getEventId(), "event_id");
        require(missing, mapping.getObservedAt(), "observed_at");
        require(missing, mapping.getScannerId(), "scanner_id");
        require(missing, mapping.getTrackId(), "scanner_track_id");
        require(missing, mapping.getLongitude(), "longitude");
        require(missing, mapping.getLatitude(), "latitude");
        require(missing, mapping.getAltitude(), "altitude");
        return missing;
    }

    public boolean isReady() {
        return tableExists && missingRequiredColumns().isEmpty();
    }

    public RfScannerCapabilities toApiCapabilities() {
        return new RfScannerCapabilities(
                tableExists,
                isReady(),
                has(mapping.getObjectId()),
                has(mapping.getFallbackObservedAt()),
                has(mapping.getHomeLongitude()) && has(mapping.getHomeLatitude()),
                has(mapping.getHomeAltitude()),
                java.util.List.copyOf(missingRequiredColumns())
        );
    }

    public static RfScannerSchemaCapabilities unavailable() {
        return new RfScannerSchemaCapabilities(false, Set.of(), new RfScannerColumns());
    }

    private void require(Set<String> missing, String physicalColumn, String logicalName) {
        if (!has(physicalColumn)) {
            missing.add(logicalName);
        }
    }
}
