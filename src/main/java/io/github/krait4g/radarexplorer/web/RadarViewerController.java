package io.github.krait4g.radarexplorer.web;

import io.github.krait4g.radarexplorer.model.ApiModels.DetailResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.MetaResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.RadarsResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.SnapshotResponse;
import io.github.krait4g.radarexplorer.model.ApiModels.TracksResponse;
import io.github.krait4g.radarexplorer.service.RadarViewerService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class RadarViewerController {

    private final RadarViewerService service;

    public RadarViewerController(RadarViewerService service) {
        this.service = service;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        return service.meta();
    }

    @GetMapping("/snapshot")
    public SnapshotResponse snapshot(
            @RequestParam String at,
            @RequestParam(defaultValue = "750") @Min(0) @Max(30_000) int toleranceMs,
            @RequestParam(required = false) @Size(max = 128) String radarId,
            @RequestParam(required = false) @Size(max = 128) String objectNo,
            @RequestParam(defaultValue = "true") boolean primaryOnly
    ) {
        return service.snapshot(at, toleranceMs, radarId, objectNo, primaryOnly);
    }

    @GetMapping("/tracks")
    public TracksResponse tracks(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "750") @Min(0) @Max(30_000) int toleranceMs,
            @RequestParam(required = false) @Size(max = 128) String radarId,
            @RequestParam(required = false) @Size(max = 128) String radarObjectNo,
            @RequestParam(required = false) @Size(max = 128) String objectNo,
            @RequestParam(defaultValue = "true") boolean primaryOnly,
            @RequestParam(defaultValue = "true") boolean overview
    ) {
        return service.tracks(from, to, toleranceMs, radarId, radarObjectNo, objectNo, primaryOnly, overview);
    }

    @GetMapping("/radars")
    public RadarsResponse radars(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "750") @Min(0) @Max(30_000) int toleranceMs,
            @RequestParam(defaultValue = "true") boolean primaryOnly
    ) {
        return service.radars(from, to, toleranceMs, primaryOnly);
    }

    @GetMapping("/objects/{objectNo}/detail")
    public DetailResponse detail(
            @PathVariable @Size(min = 1, max = 128) String objectNo,
            @RequestParam String at,
            @RequestParam(defaultValue = "30") @Min(1) @Max(300) int windowSeconds,
            @RequestParam(required = false) @Size(max = 128) String radarId,
            @RequestParam(required = false) @Size(max = 128) String radarObjectNo,
            @RequestParam(defaultValue = "true") boolean primaryOnly
    ) {
        return service.detail(objectNo, at, windowSeconds, radarId, radarObjectNo, primaryOnly);
    }
}
