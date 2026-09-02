package io.github.krait4g.radarexplorer.repository;

import io.github.krait4g.radarexplorer.config.ViewerProperties.Columns;
import io.github.krait4g.radarexplorer.model.ApiModels.Capabilities;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Database capabilities resolved through deployment-specific, validated column mappings. */
public record SchemaCapabilities(boolean tableExists, Set<String> columns, Columns mapping) {

    public SchemaCapabilities {
        columns = columns == null ? Set.of() : columns.stream()
                .map(column -> column.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        mapping = mapping == null ? new Columns() : mapping;
    }

    /** Compatibility constructor for focused service tests that use the generic default mapping. */
    public SchemaCapabilities(boolean tableExists, Set<String> columns) {
        this(tableExists, columns, new Columns());
    }

    public boolean has(String physicalColumn) {
        return physicalColumn != null && columns.contains(physicalColumn.toUpperCase(Locale.ROOT));
    }

    public Set<String> missingRequiredColumns() {
        Set<String> missing = new LinkedHashSet<>();
        require(missing, mapping.getObservedAt(), "observed_at");
        require(missing, mapping.getSensorId(), "sensor_id");
        require(missing, mapping.getSensorTrackId(), "sensor_track_id");
        require(missing, mapping.getObjectId(), "object_id");
        require(missing, mapping.getRawLongitude(), "raw_longitude");
        require(missing, mapping.getRawLatitude(), "raw_latitude");
        require(missing, mapping.getRawAltitude(), "raw_altitude");
        return missing;
    }

    public boolean isReady() {
        return tableExists && missingRequiredColumns().isEmpty();
    }

    public Capabilities toApiCapabilities() {
        return new Capabilities(
                has(mapping.getCorrectedLongitude()),
                has(mapping.getCorrectedLatitude()),
                has(mapping.getCorrectedAltitude()),
                has(mapping.getPrimaryFlag()),
                has(mapping.getReferenceAltitude())
        );
    }

    public static SchemaCapabilities unavailable() {
        return new SchemaCapabilities(false, Set.of(), new Columns());
    }

    private void require(Set<String> missing, String physicalColumn, String logicalName) {
        if (!has(physicalColumn)) {
            // API diagnostics use stable logical names and never reveal private physical mappings.
            missing.add(logicalName);
        }
    }
}
