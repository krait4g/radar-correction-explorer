(function () {
  "use strict";

  const DEFAULT_CENTER = [37.5665, 126.9780];
  const DEFAULT_MAP_MAX_ZOOM = 24;
  const elements = {};
  const TABLE_PAGE_SIZE = 250;
  const TABLE_SEARCH_DELAY_MS = 180;
  const EXACT_TRACK_CACHE_SIZE = 8;
  const TABLE_COLLATOR = new Intl.Collator("ko", { numeric: true, sensitivity: "base" });
  const INTEGER_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
  const NUMBER_FORMATTERS = new Map();
  const OBJECT_COLOR_CACHE = new Map();
  const state = {
    meta: null,
    points: [],
    mapPoints: [],
    objectIndex: new Map(),
    pairTrackIndex: new Map(),
    mapBounds: null,
    dataVersion: 0,
    summary: {},
    sourceSummary: {},
    mode: "SNAPSHOT",
    toleranceMs: 750,
    rangeStart: "",
    rangeEnd: "",
    rowLimitReached: false,
    sampling: { applied: false, snapshotCollapsed: false, sourceRows: 0, returnedRows: 0, stride: 1, strategy: "NONE", trackCount: 0,
      representedTracks: 0, allTracksRepresented: true, metricsScope: "FULL_RANGE", p95Approximate: false },
    activeQuery: null,
    exactTrackCache: new Map(),
    crossSourceIndexes: new WeakMap(),
    exactTrackKey: null,
    exactStatus: "idle",
    exactError: "",
    radars: [],
    rfScanners: [],
    sensorInventoryLoading: new Set(),
    selectedRadarIds: new Set(),
    selectedRfScannerIds: new Set(),
    selectedObjectNo: null,
    selectedPoint: null,
    detailPoints: [],
    sortKey: "globalObjectNo",
    sortDirection: "asc",
    tableFilter: "",
    tablePage: 1,
    tableCache: null,
    tableSearchTimer: null,
    map: null,
    radarLayer: null,
    tileLayer: null,
    snapshotController: null,
    trackRequestVersion: 0,
    detailController: null,
    detailRequestVersion: 0,
    radarController: null,
    radarRequestVersion: 0,
    chart: null,
    samplePopover: { open: false, pinned: false, anchorKind: "corrected", point: null },
    samplePopoverFrame: null,
    pairPanelMover: null,
    samplePopoverMover: null,
    panelResizeObserver: null,
    hasQueried: false,
    initialFitDone: false,
    toastTimer: null,
    resizeObserver: null
  };

  document.addEventListener("DOMContentLoaded", init);

  function init() {
    cacheElements();
    setDefaultRange(new Date());
    bindEvents();
    initializeMap();
    initializeMovablePanels();
    state.chart = createAltitudeChart(elements.altitudeChart, elements.chartTooltip);
    renderInitialIdle();
    loadMetaOnly();
  }

  function cacheElements() {
    const ids = [
      "db-target", "db-status", "schema-status", "correction-status", "time-range", "query-mode", "query-form", "from-input",
      "to-input", "source-radar", "source-rf", "radar-picker-field", "radar-picker", "radar-picker-summary",
      "radar-select-all", "radar-clear", "radar-options", "rf-picker-field", "rf-picker", "rf-picker-summary",
      "rf-select-all", "rf-clear", "rf-options", "object-input", "match-mode-input", "primary-input",
      "match-links-input", "load-button",
      "query-limit-help", "query-message", "map", "map-fallback", "map-empty", "map-time", "map-count",
      "map-stage", "tile-status", "pair-focus", "pair-drag-handle", "pair-position", "pair-object", "pair-fit",
      "pair-prev", "pair-next", "pair-reset-position", "pair-time", "sample-popover", "sample-popover-drag-handle",
      "sample-popover-mode", "sample-popover-content", "sample-popover-reset", "sample-popover-close",
      "pair-horizontal", "pair-altitude", "pair-event-id", "pair-source-route", "result-badge", "metric-objects", "metric-sources",
      "metric-sources-sub", "metric-coverage", "metric-coverage-sub", "metric-average", "metric-p95", "metric-altitude",
      "metric-rf-match", "metric-rf-match-sub", "sampling-badge", "sampling-note", "capability-note", "table-search",
      "analysis-table", "analysis-body", "table-empty", "table-caption", "page-prev", "page-next", "page-status", "detail-panel",
      "selected-object-badge", "selected-radar-badge", "detail-resolution-badge", "detail-window-field", "window-select", "detail-empty",
      "detail-content", "detail-observed-title", "detail-home-alt-row", "detail-home-alt", "detail-raw-lat", "detail-raw-lon", "detail-raw-alt",
      "detail-calc-block", "detail-calc-lat", "detail-calc-lon", "detail-calc-alt", "detail-delta-block", "detail-horizontal", "detail-alt-delta",
      "detail-event-time", "chart-wrap", "altitude-chart", "chart-tooltip", "chart-empty",
      "series-range", "series-count", "toast"
    ];
    ids.forEach((id) => { elements[toCamel(id)] = document.getElementById(id); });
  }

  function bindEvents() {
    elements.queryForm.addEventListener("submit", (event) => {
      event.preventDefault();
      loadTracks({ preserveSelection: false, fitMap: true, refreshSensors: true });
    });
    elements.windowSelect.addEventListener("change", () => {
      if (state.selectedObjectNo) loadDetail(state.selectedObjectNo);
    });
    elements.sourceRadar.addEventListener("change", () => updateSourceControls("RADAR"));
    elements.sourceRf.addEventListener("change", () => updateSourceControls("RFSCNR"));
    elements.radarPicker.addEventListener("toggle", () => loadSensorInventoryOnOpen("RADAR", elements.radarPicker));
    elements.rfPicker.addEventListener("toggle", () => loadSensorInventoryOnOpen("RFSCNR", elements.rfPicker));
    elements.matchLinksInput.addEventListener("change", () => renderMap(false));
    elements.radarSelectAll.addEventListener("click", () => setAllDeviceSelections("RADAR", true));
    elements.radarClear.addEventListener("click", () => setAllDeviceSelections("RADAR", false));
    elements.rfSelectAll.addEventListener("click", () => setAllDeviceSelections("RFSCNR", true));
    elements.rfClear.addEventListener("click", () => setAllDeviceSelections("RFSCNR", false));
    document.addEventListener("click", (event) => {
      [elements.radarPicker, elements.rfPicker].forEach((picker) => {
        if (picker && picker.open && !picker.contains(event.target)) picker.open = false;
      });
    });
    elements.tableSearch.addEventListener("input", () => {
      clearTimeout(state.tableSearchTimer);
      state.tableSearchTimer = setTimeout(() => {
        state.tableFilter = elements.tableSearch.value.trim().toLowerCase();
        state.tablePage = 1;
        invalidateTableCache();
        renderAnalysisTable();
      }, TABLE_SEARCH_DELAY_MS);
    });
    elements.pagePrev.addEventListener("click", () => {
      state.tablePage = Math.max(1, state.tablePage - 1);
      renderAnalysisTable();
    });
    elements.pageNext.addEventListener("click", () => {
      state.tablePage += 1;
      renderAnalysisTable();
    });
    elements.pairPrev.addEventListener("click", () => selectAdjacentPair(-1));
    elements.pairNext.addEventListener("click", () => selectAdjacentPair(1));
    elements.pairFit.addEventListener("click", () => {
      if (state.selectedPoint) focusSelectedPair(state.selectedPoint);
    });
    elements.pairResetPosition.addEventListener("click", resetPairPanelPosition);
    elements.samplePopoverReset.addEventListener("click", resetSamplePopoverPosition);
    elements.samplePopoverClose.addEventListener("click", hideSamplePopover);
    elements.analysisTable.querySelectorAll("th[data-sort]").forEach((header) => {
      header.querySelector("button").addEventListener("click", () => sortTable(header.dataset.sort));
    });
    window.addEventListener("keydown", (event) => {
      if (event.defaultPrevented || event.key !== "Escape") return;
      const cancelledDrag = [state.pairPanelMover, state.samplePopoverMover]
        .some((mover) => mover && mover.cancelActiveDrag());
      if (cancelledDrag) {
        event.preventDefault();
        return;
      }
      if (state.selectedObjectNo) clearSelection();
    });
  }

  async function loadMetaOnly() {
    try {
      const meta = await fetchJson("/api/meta");
      state.meta = normalizeMeta(meta);
      renderMeta();
      configureBaseMap();
      const minTime = state.meta.timeRange.min;
      const maxTime = state.meta.timeRange.max;
      if (maxTime) {
        setRangeInputs(state.meta.database.syntheticDemo && minTime ? minTime : maxTime, maxTime);
      }
      setQueryMessage(maxTime
        ? (state.meta.database.syntheticDemo
          ? "합성 데모 범위를 입력했습니다. ‘범위 불러오기’를 눌러 시각화를 시작하세요."
          : "DB 최신 시각을 입력했습니다. 원하는 범위를 정한 뒤 ‘범위 불러오기’를 눌러주세요.")
        : "조회할 시간 범위를 입력한 뒤 ‘범위 불러오기’를 눌러주세요.");
    } catch (error) {
      state.meta = normalizeMeta({ database: { status: "ERROR", message: error.message } });
      renderMeta();
      setQueryMessage("DB 메타 정보를 확인하지 못했습니다. 연결 상태를 확인한 뒤 직접 범위를 입력해 조회해 주세요.", true);
      showToast(describeError(error, "DB 메타 정보를 불러오지 못했습니다."), true);
    }
  }

  async function loadTracks(options) {
    const settings = Object.assign({ preserveSelection: true, fitMap: false, refreshSensors: false }, options);
    const requestVersion = ++state.trackRequestVersion;
    if (state.snapshotController) state.snapshotController.abort();
    if (state.radarController) state.radarController.abort();
    const query = currentQuery();
    if (!query.from || !query.to) {
      setLoading(false);
      setQueryMessage("From과 To에 yyyyMMddHHmm 형식 이상의 유효한 시각을 입력해 주세요.", true);
      (!query.from ? elements.fromInput : elements.toInput).focus();
      return;
    }
    if (query.from > query.to) {
      setLoading(false);
      setQueryMessage("From은 To보다 늦을 수 없습니다.", true);
      elements.fromInput.focus();
      return;
    }
    if (!query.sources.length) {
      setLoading(false);
      setQueryMessage("Radar 또는 RF 데이터 소스를 하나 이상 선택해 주세요.", true);
      return;
    }
    if (query.sources.includes("RFSCNR") && query.objectNo && query.matchMode === "UNMATCHED") {
      setLoading(false);
      setQueryMessage("Global Object와 RF 미매칭 필터는 함께 사용할 수 없습니다. 오브젝트 번호를 비우거나 RF 매칭 조건을 변경해 주세요.", true);
      elements.matchModeInput.focus();
      return;
    }

    if (state.detailController) state.detailController.abort();
    state.detailRequestVersion += 1;
    setDetailLoading(false);
    const controller = new AbortController();
    state.snapshotController = controller;
    setLoading(true);
    setQueryMessage(query.mode === "RANGE"
      ? (query.objectNo ? "지정 글로벌 오브젝트의 관측을 정밀 조회하고 있습니다." : "Radar/RF 범위를 분석해 지도용 대표점을 구성하고 있습니다.")
      : "지정 시점의 Radar/RF 관측을 조회하고 있습니다.");

    try {
      const url = "/api/observations?" + observationSearchParams(query, query.mode !== "RANGE" || !query.objectNo).toString();
      const response = await fetchJson(url, { signal: controller.signal });
      if (requestVersion !== state.trackRequestVersion || controller.signal.aborted) return;
      const normalized = normalizeObservations(response, query);
      if (requestVersion !== state.trackRequestVersion || controller.signal.aborted) return;
      normalized.points.forEach((point) => {
        point._isRepresentative = normalized.sampling.applied;
        point._isExact = !normalized.sampling.applied;
      });
      installPoints(normalized.points, { assignPairOrdinals: !normalized.sampling.applied });
      state.summary = normalized.summary;
      state.sourceSummary = normalized.sourceSummary;
      state.sampling = normalized.sampling;
      state.activeQuery = Object.assign({}, query);
      state.exactTrackCache.clear();
      resetExactTrackState();
      state.mode = query.mode;
      state.toleranceMs = normalized.toleranceMs;
      state.rangeStart = normalized.rangeStart || query.from;
      state.rangeEnd = normalized.rangeEnd || query.to;
      state.rowLimitReached = normalized.rowLimitReached;
      state.hasQueried = true;
      state.tablePage = 1;
      if (!settings.preserveSelection || !state.objectIndex.has(state.selectedObjectNo)) {
        state.selectedObjectNo = null;
        state.selectedPoint = null;
        state.detailPoints = [];
        hideSamplePopover();
      } else {
        state.selectedPoint = nearestPointForSelection(state.selectedPoint, query.from);
        state.selectedObjectNo = state.selectedPoint ? state.selectedPoint.objectNo : null;
        updateSamplePopover(state.selectedPoint);
      }
      renderTracks(normalized, settings.fitMap);
      const resultMessage = formatQueryResult(normalized) + (normalized.warnings.length ? " · " + normalized.warnings.join(" · ") : "");
      setQueryMessage(resultMessage, false, normalized.rowLimitReached || normalized.warnings.length > 0);
      if (state.selectedObjectNo) await loadDetail(state.selectedObjectNo, { silent: true });
      else renderDetailEmpty();
      if (settings.refreshSensors) {
        void refreshSensors({ silent: true, query, trackRequestVersion: requestVersion, sources: query.sources });
      }
    } catch (error) {
      if (requestVersion !== state.trackRequestVersion || controller.signal.aborted || error.name === "AbortError") return;
      installPoints([]);
      state.sampling = { applied: false, snapshotCollapsed: false, sourceRows: 0, returnedRows: 0, stride: 1, strategy: "NONE", trackCount: 0,
        representedTracks: 0, allTracksRepresented: true, metricsScope: "FULL_RANGE", p95Approximate: false };
      state.activeQuery = null;
      state.exactTrackCache.clear();
      resetExactTrackState();
      state.selectedObjectNo = null;
      state.selectedPoint = null;
      state.detailPoints = [];
      hideSamplePopover();
      state.summary = normalizeSummary({}, []);
      state.sourceSummary = normalizeSourceSummary({}, []);
      state.rowLimitReached = false;
      state.hasQueried = true;
      renderTracks({ points: [], summary: {}, rangeStart: query.from, rangeEnd: query.to, mode: query.mode }, false);
      renderDetailEmpty();
      const message = describeError(error, "좌표 데이터를 불러오지 못했습니다.");
      setQueryMessage(message, true);
      showToast(message, true);
    } finally {
      if (requestVersion === state.trackRequestVersion) setLoading(false);
    }
  }

  async function loadDetail(objectNo, options) {
    if (!objectNo) return;
    const settings = Object.assign({ silent: false }, options);
    if (state.mode === "RANGE") {
      return loadExactRangeTrack(state.selectedPoint, settings);
    }
    if (state.detailController) state.detailController.abort();
    const requestVersion = ++state.detailRequestVersion;
    const controller = new AbortController();
    state.detailController = controller;
    const query = state.activeQuery || currentQuery();
    const selected = state.selectedPoint;
    if (!selected) return;
    const center = timeValue(selected.eventTime || query.from);
    const windowMs = Number(elements.windowSelect.value) * 1000;
    const params = toSearchParams({
      from: dateToEventTime(new Date(center - windowMs)),
      to: dateToEventTime(new Date(center + windowMs)),
      primaryOnly: query.primaryOnly,
      overview: false
    });
    params.append("source", selected.sourceType);
    if (selected.sourceType === "RFSCNR") {
      if (selected.rfScannerId) params.append("rfScannerId", selected.rfScannerId);
      if (selected.trackId) params.set("trackId", selected.trackId);
    } else {
      if (selected.radarId) params.append("radarId", selected.radarId);
      if (selected.radarObjectNo) params.set("radarObjectNo", selected.radarObjectNo);
      if (selected.globalObjectNo) params.set("objectNo", selected.globalObjectNo);
    }
    setDetailLoading(true);
    try {
      const response = await fetchJson("/api/observations?" + params.toString(), { signal: controller.signal });
      const detail = normalizeObservations(response, query);
      if (requestVersion !== state.detailRequestVersion || controller.signal.aborted || state.selectedObjectNo !== objectNo) return;
      state.detailPoints = detail.points
        .filter((point) => point._trackKey === selected._trackKey)
        .sort((a, b) => timeValue(a.eventTime) - timeValue(b.eventTime));
      renderDetail();
    } catch (error) {
      if (requestVersion !== state.detailRequestVersion || controller.signal.aborted || error.name === "AbortError") return;
      state.detailPoints = [];
      renderDetail();
      if (!settings.silent) showToast(describeError(error, "상세 궤적을 불러오지 못했습니다."), true);
    } finally {
      if (requestVersion === state.detailRequestVersion) setDetailLoading(false);
    }
  }

  async function loadExactRangeTrack(point, options) {
    if (!point) return;
    const settings = Object.assign({ silent: false }, options);
    const trackKey = point._trackKey;
    const representativeRows = state.pairTrackIndex.get(trackKey) || [];
    if (state.detailController) state.detailController.abort();
    const requestVersion = ++state.detailRequestVersion;

    if (state.exactStatus === "ready" && state.exactTrackKey === trackKey && state.detailPoints.length) {
      const matchingPoint = state.detailPoints.find((candidate) => sameSample(candidate, point)) ||
        nearestPointInRows(state.detailPoints, point.eventTime);
      if (matchingPoint) {
        state.selectedPoint = matchingPoint;
        updateSamplePopover(matchingPoint);
        revealSelectedPointInTable(matchingPoint);
        renderAnalysisTable();
      }
      renderDetail();
      setDetailLoading(false);
      return;
    }

    if (!state.sampling.applied) {
      applyExactTrack(trackKey, representativeRows, point);
      setDetailLoading(false);
      return;
    }

    const cached = state.exactTrackCache.get(trackKey);
    if (cached) {
      touchExactTrackCache(trackKey, cached);
      applyExactTrack(trackKey, cached, point);
      setDetailLoading(false);
      return;
    }

    state.exactTrackKey = trackKey;
    state.exactStatus = "loading";
    state.exactError = "";
    state.detailPoints = representativeRows;
    state.mapPoints = state.points;
    setDetailLoading(true);
    renderDetail();
    renderMap(false);

    const controller = new AbortController();
    state.detailController = controller;
    const query = state.activeQuery || currentQuery();
    const trackRequestVersion = state.trackRequestVersion;
    const params = toSearchParams({
      from: query.from,
      to: query.to,
      primaryOnly: query.primaryOnly,
      overview: false
    });
    params.append("source", point.sourceType);
    if (point.sourceType === "RFSCNR") {
      if (point.rfScannerId) params.append("rfScannerId", point.rfScannerId);
      if (point.trackId) params.set("trackId", point.trackId);
    } else {
      if (point.radarId) params.append("radarId", point.radarId);
      if (point.radarObjectNo) params.set("radarObjectNo", point.radarObjectNo);
      if (point.globalObjectNo) params.set("objectNo", point.globalObjectNo);
    }

    try {
      const response = await fetchJson("/api/observations?" + params.toString(), { signal: controller.signal });
      const exact = normalizeObservations(response, Object.assign({}, query, { mode: "RANGE" }));
      if (requestVersion !== state.detailRequestVersion || trackRequestVersion !== state.trackRequestVersion ||
          controller.signal.aborted || !state.selectedPoint || state.selectedPoint._trackKey !== trackKey) return;
      if (exact.sampling.applied) throw new Error("정밀 조회가 대표점 응답을 반환했습니다.");
      const rows = exact.points.filter((candidate) => candidate._trackKey === trackKey);
      if (!rows.length) throw new Error("선택 트랙의 정밀 샘플이 없습니다.");
      rows.forEach((candidate) => {
        candidate._isRepresentative = false;
        candidate._isExact = true;
      });
      preparePairOrdinals(rows);
      touchExactTrackCache(trackKey, rows);
      applyExactTrack(trackKey, rows, point);
    } catch (error) {
      if (requestVersion !== state.detailRequestVersion || trackRequestVersion !== state.trackRequestVersion ||
          controller.signal.aborted || error.name === "AbortError") return;
      state.exactTrackKey = trackKey;
      state.exactStatus = "error";
      state.exactError = describeError(error, "선택 트랙의 전체 샘플을 불러오지 못했습니다.");
      state.detailPoints = representativeRows;
      state.mapPoints = state.points;
      renderDetail();
      renderMap(false);
      if (!settings.silent) showToast(state.exactError + " 대표점은 계속 표시합니다.", true);
    } finally {
      if (requestVersion === state.detailRequestVersion) setDetailLoading(false);
    }
  }

  function applyExactTrack(trackKey, rows, preferredPoint) {
    const exactRows = (rows || []).slice().sort(pointOrder);
    preparePairOrdinals(exactRows);
    state.exactTrackKey = trackKey;
    state.exactStatus = "ready";
    state.exactError = "";
    state.detailPoints = exactRows;
    const matchingPoint = exactRows.find((candidate) => sameSample(candidate, preferredPoint)) ||
      nearestPointInRows(exactRows, preferredPoint && preferredPoint.eventTime);
    if (matchingPoint) {
      state.selectedPoint = matchingPoint;
      state.selectedObjectNo = matchingPoint.objectNo;
      updateSamplePopover(matchingPoint);
    }
    state.mapPoints = state.sampling.applied
      ? state.points.filter((candidate) => candidate._trackKey !== trackKey).concat(exactRows)
      : state.points;
    revealSelectedPointInTable(state.selectedPoint);
    renderAnalysisTable();
    renderDetail();
    renderMap(false);
  }

  function resetExactTrackState() {
    state.exactTrackKey = null;
    state.exactStatus = "idle";
    state.exactError = "";
    state.detailPoints = [];
    state.mapPoints = state.points;
  }

  function touchExactTrackCache(trackKey, rows) {
    state.exactTrackCache.delete(trackKey);
    state.exactTrackCache.set(trackKey, rows);
    while (state.exactTrackCache.size > EXACT_TRACK_CACHE_SIZE) {
      state.exactTrackCache.delete(state.exactTrackCache.keys().next().value);
    }
  }

  async function refreshSensors(options) {
    const settings = Object.assign({ silent: false, query: null, trackRequestVersion: null, sources: null }, options);
    const query = settings.query || currentQuery();
    if (!query.from || !query.to || query.from > query.to) return;
    const requestedSources = settings.sources || query.sources;
    const requestVersion = ++state.radarRequestVersion;
    if (state.radarController) state.radarController.abort();
    const controller = new AbortController();
    state.radarController = controller;
    state.sensorInventoryLoading.clear();
    requestedSources.forEach((source) => state.sensorInventoryLoading.add(source));
    renderDevicePickers();
    try {
      const radarSelectionWasAll = !state.selectedRadarIds.size ||
        Boolean(state.radars.length && state.radars.every((row) => state.selectedRadarIds.has(row.id)));
      const rfSelectionWasAll = !state.selectedRfScannerIds.size ||
        Boolean(state.rfScanners.length && state.rfScanners.every((row) => state.selectedRfScannerIds.has(row.id)));
      const params = toSearchParams({ from: query.from, to: query.to, primaryOnly: query.primaryOnly });
      requestedSources.forEach((source) => params.append("source", source));
      const payload = unwrap(await fetchJson("/api/sensors?" + params.toString(), { signal: controller.signal }));
      if (requestVersion !== state.radarRequestVersion || controller.signal.aborted ||
          (settings.trackRequestVersion != null && settings.trackRequestVersion !== state.trackRequestVersion)) return;
      const inventoryWarnings = (arrayValue(pick(payload, ["warnings", "notices"])) || []).map(textValue).filter(Boolean);
      if (requestedSources.includes("RADAR")) {
        state.radars = normalizeSensorRows(arrayValue(payload.radars) || [], "RADAR");
      }
      if (requestedSources.includes("RFSCNR")) {
        state.rfScanners = normalizeSensorRows(arrayValue(payload.rfScanners) || [], "RFSCNR");
      }
      if (requestedSources.includes("RADAR") && radarSelectionWasAll && state.selectedRadarIds.size) {
        state.selectedRadarIds = new Set(state.radars.map((row) => row.id));
      }
      if (requestedSources.includes("RFSCNR") && rfSelectionWasAll && state.selectedRfScannerIds.size) {
        state.selectedRfScannerIds = new Set(state.rfScanners.map((row) => row.id));
      }
      if (requestVersion === state.radarRequestVersion && !controller.signal.aborted) renderDevicePickers();
      if (inventoryWarnings.length && !settings.silent) showToast(inventoryWarnings.join(" · "), true);
    } catch (error) {
      if (error.name === "AbortError") return;
      if (!settings.silent) showToast(describeError(error, "장비 목록을 불러오지 못했습니다."), true);
    } finally {
      if (requestVersion === state.radarRequestVersion) {
        state.sensorInventoryLoading.clear();
        renderDevicePickers();
      }
    }
  }

  function loadSensorInventoryOnOpen(sourceType, picker) {
    const rows = sourceType === "RADAR" ? state.radars : state.rfScanners;
    if (!picker || !picker.open || rows.length) return;
    const query = currentQuery();
    if (!query.from || !query.to || query.from > query.to) {
      showToast("장비 목록을 조회하려면 먼저 유효한 From/To 범위를 입력해 주세요.", true);
      return;
    }
    void refreshSensors({ silent: false, query, sources: [sourceType] });
  }

  function normalizeSensorRows(rows, sourceType) {
    const idKey = sourceType === "RADAR" ? "radarId" : "rfScannerId";
    return rows.map((row) => typeof row === "string" ? { id: row, count: null } : {
      id: textValue(row[idKey]),
      count: finiteNumber(row.eventCount),
      trackCount: finiteNumber(sourceType === "RADAR" ? row.objectCount : row.trackCount),
      matchedObjectCount: finiteNumber(row.matchedObjectCount)
    }).filter((row) => row.id);
  }

  function renderDevicePickers() {
    renderDevicePicker("RADAR", state.radars, state.selectedRadarIds, elements.radarOptions, elements.radarPickerSummary);
    renderDevicePicker("RFSCNR", state.rfScanners, state.selectedRfScannerIds, elements.rfOptions, elements.rfPickerSummary);
  }

  function renderDevicePicker(sourceType, sourceRows, selectedIds, container, summary) {
    let rows = sourceRows.slice();
    const fragment = document.createDocumentFragment();
    const visibleIds = new Set(rows.map((row) => row.id));
    Array.from(selectedIds).filter((id) => !visibleIds.has(id)).forEach((id) => rows.push({ id, count: 0, retained: true }));
    rows.forEach((sensor) => {
      const label = document.createElement("label");
      const input = document.createElement("input");
      input.type = "checkbox";
      input.value = sensor.id;
      input.checked = !selectedIds.size || selectedIds.has(sensor.id);
      input.addEventListener("change", () => {
        if (!selectedIds.size) rows.forEach((row) => selectedIds.add(row.id));
        if (input.checked) selectedIds.add(sensor.id);
        else {
          selectedIds.delete(sensor.id);
          if (!selectedIds.size) {
            rows.forEach((row) => selectedIds.add(row.id));
            renderDevicePickers();
            showToast("장비를 하나 이상 선택하거나 데이터 소스 토글을 꺼 주세요.");
          }
        }
        updateDevicePickerSummary(sourceType);
      });
      const name = document.createElement("span");
      name.textContent = sensor.id;
      const count = document.createElement("small");
      count.textContent = sensor.count == null ? "" : formatInteger(sensor.count) + "행";
      if (sensor.retained) count.title = "현재 범위에는 관측이 없지만 선택을 유지합니다.";
      label.append(input, name, count);
      fragment.appendChild(label);
    });
    if (!rows.length) {
      const empty = document.createElement("span");
      empty.className = "multi-picker-empty";
      empty.textContent = state.sensorInventoryLoading.has(sourceType)
        ? "장비 목록을 조회하고 있습니다…"
        : "이 범위에 장비 관측이 없습니다.";
      fragment.appendChild(empty);
    }
    container.replaceChildren(fragment);
    updateDevicePickerSummary(sourceType);
  }

  function updateDevicePickerSummary(sourceType) {
    const selected = sourceType === "RADAR" ? state.selectedRadarIds : state.selectedRfScannerIds;
    const rows = sourceType === "RADAR" ? state.radars : state.rfScanners;
    const summary = sourceType === "RADAR" ? elements.radarPickerSummary : elements.rfPickerSummary;
    const label = sourceType === "RADAR" ? "레이더" : "RF 스캐너";
    if (!selected.size || rows.length && selected.size >= rows.length && rows.every((row) => selected.has(row.id))) {
      summary.textContent = "전체 " + label + (rows.length ? " (" + rows.length + ")" : "");
    } else if (selected.size <= 2) {
      summary.textContent = Array.from(selected).join(", ");
    } else {
      summary.textContent = Array.from(selected).slice(0, 2).join(", ") + " 외 " + (selected.size - 2) + "개";
    }
  }

  function setAllDeviceSelections(sourceType, checked) {
    const rows = sourceType === "RADAR" ? state.radars : state.rfScanners;
    const selected = sourceType === "RADAR" ? state.selectedRadarIds : state.selectedRfScannerIds;
    selected.clear();
    if (checked) rows.forEach((row) => selected.add(row.id));
    renderDevicePickers();
  }

  function normalizeMeta(payload) {
    const source = unwrap(payload);
    const database = source.database || source.db || {};
    const capabilities = source.capabilities || source.features || {};
    const rfCapabilities = source.rfScannerCapabilities || {};
    const timeRange = source.timeRange || source.range || {};
    const radarTimeRange = source.radarTimeRange || {};
    const rfScannerTimeRange = source.rfScannerTimeRange || {};
    const limits = source.limits || {};
    const map = source.map || source.mapConfig || {};
    return {
      database: {
        status: textValue(pick(database, ["status", "state", "connectionStatus"])) || "UNKNOWN",
        message: textValue(pick(database, ["message", "detail", "error"])),
        connectionLabel: textValue(pick(database, ["connectionLabel", "displayLabel"])) || "Configured datasource",
        syntheticDemo: booleanValue(pick(database, ["syntheticDemo", "demo"]))
      },
      capabilities: {
        correctedLongitude: booleanValue(capabilities.correctedLongitude),
        correctedLatitude: booleanValue(capabilities.correctedLatitude),
        correctedAltitude: booleanValue(capabilities.correctedAltitude),
        primaryFlag: booleanValue(capabilities.primaryFlag),
        referenceAltitude: booleanValue(capabilities.referenceAltitude),
        radarEvents: booleanValue(capabilities.radarEvents),
        radarReady: booleanValue(capabilities.radarReady)
      },
      rfScannerCapabilities: {
        tableExists: booleanValue(rfCapabilities.tableExists),
        ready: booleanValue(rfCapabilities.ready),
        objectMatch: booleanValue(rfCapabilities.objectMatch),
        fallbackTime: booleanValue(rfCapabilities.fallbackTime),
        homePosition: booleanValue(rfCapabilities.homePosition),
        homeAltitude: booleanValue(rfCapabilities.homeAltitude),
        missingRequiredColumns: (arrayValue(rfCapabilities.missingRequiredColumns) || []).map(textValue).filter(Boolean)
      },
      timeRange: {
        min: eventTimeString(pick(timeRange, ["min", "start", "from", "minEventTime"])),
        max: eventTimeString(pick(timeRange, ["max", "end", "to", "maxEventTime"]))
      },
      radarTimeRange: {
        min: eventTimeString(pick(radarTimeRange, ["min", "start", "from"])),
        max: eventTimeString(pick(radarTimeRange, ["max", "end", "to"]))
      },
      rfScannerTimeRange: {
        min: eventTimeString(pick(rfScannerTimeRange, ["min", "start", "from"])),
        max: eventTimeString(pick(rfScannerTimeRange, ["max", "end", "to"]))
      },
      warnings: (arrayValue(source.warnings) || []).map(textValue).filter(Boolean),
      limits: {
        maxToleranceMs: finiteNumber(pick(limits, ["maxToleranceMs"])),
        maxWindowSeconds: finiteNumber(pick(limits, ["maxWindowSeconds"])),
        maxRangeSeconds: finiteNumber(pick(limits, ["maxRangeSeconds"])),
        maxQueryRows: finiteNumber(pick(limits, ["maxQueryRows"])),
        maxOverviewPoints: finiteNumber(pick(limits, ["maxOverviewPoints", "overviewPointLimit"]))
      },
      map: {
        tileUrl: textValue(pick(map, ["tileUrl", "tiles", "url", "styleUrl"])),
        attribution: textValue(pick(map, ["attribution", "tileAttribution"])),
        initialLongitude: finiteNumber(pick(map, ["initialLongitude", "longitude", "lng", "centerX"])),
        initialLatitude: finiteNumber(pick(map, ["initialLatitude", "latitude", "lat", "centerY"])),
        initialZoom: finiteNumber(pick(map, ["initialZoom", "zoom"])),
        maxNativeZoom: finiteNumber(pick(map, ["maxNativeZoom", "nativeZoom"])),
        maxZoom: finiteNumber(pick(map, ["maxZoom", "maximumZoom"]))
      }
    };
  }

  function normalizeSnapshot(payload, query) {
    const source = unwrap(payload);
    const rawPoints = arrayValue(pick(source, ["points", "items", "list", "events", "rows", "data"])) ||
      (Array.isArray(source) ? source : []);
    const points = rawPoints.map((row, index) => normalizePoint(row, index)).filter((point) => point.eventTime || point.hasRawPosition || point.hasCorrectedPosition);
    const backendSummary = source.summary || source.statistics || {};
    return {
      requestedAt: eventTimeString(pick(source, ["requestedAt", "at", "baseTime"])) || query.at || query.from,
      rangeStart: eventTimeString(pick(source, ["rangeStart", "from", "start"])),
      rangeEnd: eventTimeString(pick(source, ["rangeEnd", "to", "end"])),
      toleranceMs: finiteNumber(pick(source, ["toleranceMs"])) ?? Number(query.toleranceMs || 0),
      points,
      summary: normalizeSummary(backendSummary, points)
    };
  }

  function normalizeTracks(payload, query) {
    const source = unwrap(payload);
    const rawPoints = arrayValue(pick(source, ["points", "items", "list", "events", "rows", "data"])) ||
      (Array.isArray(source) ? source : []);
    const points = rawPoints.map((row, index) => normalizePoint(row, index)).filter((point) => point.eventTime || point.hasRawPosition || point.hasCorrectedPosition);
    const backendSummary = source.summary || source.statistics || {};
    const sampling = normalizeSampling(source.sampling || source.overview || {}, backendSummary, points);
    const limitFlag = booleanValue(pick(source, ["rowLimitReached", "truncated", "limited", "hasMore"])) ||
      booleanValue(pick(backendSummary, ["rowLimitReached", "truncated", "limited", "hasMore"]));
    const maximum = state.meta && state.meta.limits.maxQueryRows;
    return {
      mode: textValue(pick(source, ["mode", "queryMode"])) || query.mode,
      rangeStart: eventTimeString(pick(source, ["rangeStart", "from", "start", "requestedFrom"])) || query.from,
      rangeEnd: eventTimeString(pick(source, ["rangeEnd", "to", "end", "requestedTo"])) || query.to,
      points,
      summary: normalizeSummary(backendSummary, points),
      sampling,
      rowLimitReached: limitFlag || Boolean(maximum && points.length >= maximum)
    };
  }

  function normalizeObservations(payload, query) {
    const source = unwrap(payload);
    const radarRows = arrayValue(source.radarPoints) || [];
    const rfRows = arrayValue(source.rfScannerPoints) || [];
    const radarPoints = radarRows.map((row, index) => normalizePoint(row, index));
    const rfPoints = rfRows.map((row, index) => normalizeRfPoint(row, radarPoints.length + index));
    const points = radarPoints.concat(rfPoints)
      .filter((point) => point.eventTime || point.hasRawPosition || point.hasCorrectedPosition)
      .sort(pointOrder);
    const sourceSummary = normalizeSourceSummary(source.summary || {}, points);
    const summary = normalizeSummary(source.radarSummary || {}, radarPoints);
    summary.sourceRows = sourceSummary.radarRows;
    const sampling = normalizeSampling(source.sampling || {}, source.summary || {}, points);
    const warnings = (arrayValue(source.warnings) || []).map(textValue).filter(Boolean);
    const maximum = state.meta && state.meta.limits.maxQueryRows;
    return {
      mode: textValue(source.mode) || query.mode,
      toleranceMs: finiteNumber(source.toleranceMs) ?? finiteNumber(query.toleranceMs) ?? 750,
      rangeStart: eventTimeString(pick(source, ["rangeStart", "normalizedFrom", "requestedFrom"])) || query.from,
      rangeEnd: eventTimeString(pick(source, ["rangeEnd", "normalizedTo", "requestedTo"])) || query.to,
      points,
      summary,
      sourceSummary,
      sampling,
      warnings,
      rowLimitReached: booleanValue(pick(source, ["rowLimitReached", "truncated"])) ||
        Boolean(maximum && points.length >= maximum)
    };
  }

  function normalizeSampling(sampling, summary, points) {
    const sourceRows = finiteNumber(pick(sampling, ["sourceRows", "totalRows", "matchedRows"])) ??
      finiteNumber(pick(summary, ["sourceRows", "rowCount", "totalCount"])) ?? points.length;
    const returnedRows = finiteNumber(pick(sampling, ["returnedRows", "displayedRows", "sampleRows"])) ?? points.length;
    const strategy = textValue(pick(sampling, ["strategy", "method", "sampleMethod"])) || "NONE";
    const applied = booleanValue(pick(sampling, ["applied", "sampled", "overviewApplied"]));
    return {
      applied,
      snapshotCollapsed: !applied && sourceRows > returnedRows && strategy.toUpperCase().startsWith("SNAPSHOT"),
      sourceRows,
      returnedRows,
      stride: finiteNumber(pick(sampling, ["stride", "step", "interval"])) ?? 1,
      strategy,
      trackCount: finiteNumber(pick(sampling, ["trackCount", "sourceTracks", "totalTracks"])) ?? 0,
      representedTracks: finiteNumber(pick(sampling, ["representedTracks", "returnedTracks", "displayedTracks"])) ?? 0,
      allTracksRepresented: booleanValue(pick(sampling, ["allTracksRepresented", "allTracksIncluded"])),
      metricsScope: textValue(pick(sampling, ["metricsScope", "statisticsScope"])) || "DISPLAYED",
      p95Approximate: booleanValue(pick(sampling, ["p95Approximate", "approximateP95"]))
    };
  }

  function normalizePoint(row, sourceIndex) {
    const raw = row.raw || row.original || row.loc || {};
    const corrected = row.corrected || row.calc || row.calculated || {};
    const radarId = textValue(pick(row, ["radarId", "sensorId"]));
    const radarObjectNo = textValue(pick(row, ["radarObjectNo", "sensorTrackId", "trackId"]));
    const globalObjectNo = textValue(pick(row, ["objectNo", "objectId", "targetId"]));
    const objectNo = globalObjectNo || "RADAR:" + ([radarId, radarObjectNo].filter(Boolean).join(":") || sourceIndex);
    const rawLongitude = firstNumber(raw, ["longitude", "lon", "lng", "x"], row, ["rawLongitude", "longitude"]);
    const rawLatitude = firstNumber(raw, ["latitude", "lat", "y"], row, ["rawLatitude", "latitude"]);
    const rawAltitude = firstNumber(raw, ["altitude", "alt", "z"], row, ["rawAltitude", "altitude"]);
    const correctedLongitude = firstNumber(corrected, ["longitude", "lon", "lng", "x"], row, ["correctedLongitude"]);
    const correctedLatitude = firstNumber(corrected, ["latitude", "lat", "y"], row, ["correctedLatitude"]);
    const correctedAltitude = firstNumber(corrected, ["altitude", "alt", "z"], row, ["correctedAltitude"]);
    const calculatedHorizontal = validCoordinate(rawLatitude, rawLongitude) && validCoordinate(correctedLatitude, correctedLongitude)
      ? haversineMeters(rawLatitude, rawLongitude, correctedLatitude, correctedLongitude) : null;
    const calculatedAltitudeDelta = rawAltitude != null && correctedAltitude != null ? correctedAltitude - rawAltitude : null;
    const hasRawPosition = validCoordinate(rawLatitude, rawLongitude);
    const hasCorrectedPosition = validCoordinate(correctedLatitude, correctedLongitude);
    const statusValue = textValue(pick(row, ["correctionStatus", "status"]));
    const eventTime = eventTimeString(pick(row, ["eventTime", "time", "timestamp", "observedAt"]));
    const point = {
      eventId: textValue(pick(row, ["eventId"])),
      sourceEventId: textValue(pick(row, ["sourceEventId"])),
      eventTime,
      _timeMs: timeValue(eventTime),
      sourceType: "RADAR",
      deviceId: radarId,
      sourceTrackId: radarObjectNo,
      radarId,
      radarObjectNo,
      rfScannerId: "",
      trackId: "",
      objectNo,
      globalObjectNo,
      matched: null,
      primaryFlag: normalizePrimaryFlag(row.primaryFlag),
      rawLongitude,
      rawLatitude,
      rawAltitude,
      correctedLongitude,
      correctedLatitude,
      correctedAltitude,
      referenceAltitude: finiteNumber(row.referenceAltitude),
      rfAltitude: null,
      homeLongitude: null,
      homeLatitude: null,
      homeAltitude: null,
      altitudeReference: "RADAR_SENSOR",
      timeSource: "",
      horizontalCorrectionMeters: finiteNumber(pick(row, ["horizontalCorrectionMeters", "horizontalDeltaMeters", "distanceMeters"])) ?? calculatedHorizontal,
      altitudeDeltaMeters: finiteNumber(pick(row, ["altitudeDeltaMeters", "altitudeDifferenceMeters", "deltaAltitude"])) ?? calculatedAltitudeDelta,
      correctionStatus: normalizeCorrectionStatus(statusValue, hasCorrectedPosition, correctedAltitude),
      hasRawPosition,
      hasCorrectedPosition
    };
    point._sourceIndex = Number.isInteger(sourceIndex) ? sourceIndex : -1;
    point._trackKey = [point.sourceType, point.objectNo, point.radarId, point.radarObjectNo].join("\u0000");
    point._pairKey = sampleKey(point);
    point._searchText = [point.sourceType, point.objectNo, point.globalObjectNo, point.radarId, point.radarObjectNo, point.eventTime, point.primaryFlag,
      isFiniteNumber(point.referenceAltitude) ? point.referenceAltitude.toFixed(2) : "", point.correctionStatus]
      .join("\u0000").toLowerCase();
    return point;
  }

  function normalizeRfPoint(row, sourceIndex) {
    const position = row.position || {};
    const home = row.home || {};
    const rfScannerId = textValue(row.rfScannerId);
    const trackId = textValue(row.trackId);
    const globalObjectNo = textValue(row.objectNo);
    const eventId = textValue(row.eventId);
    const eventTime = eventTimeString(row.eventTime);
    const rawLongitude = finiteNumber(position.longitude);
    const rawLatitude = finiteNumber(position.latitude);
    const rawAltitude = finiteNumber(position.altitude);
    const objectNo = globalObjectNo || "RF:" + (rfScannerId || "?") + ":" + (trackId || eventId || sourceIndex);
    const point = {
      eventId,
      sourceEventId: "",
      eventTime,
      _timeMs: timeValue(eventTime),
      sourceType: "RFSCNR",
      deviceId: rfScannerId,
      sourceTrackId: trackId,
      radarId: "",
      radarObjectNo: "",
      rfScannerId,
      trackId,
      objectNo,
      globalObjectNo,
      matched: booleanValue(row.matched),
      primaryFlag: "",
      rawLongitude,
      rawLatitude,
      rawAltitude,
      correctedLongitude: null,
      correctedLatitude: null,
      correctedAltitude: null,
      referenceAltitude: null,
      rfAltitude: rawAltitude,
      homeLongitude: finiteNumber(home.longitude),
      homeLatitude: finiteNumber(home.latitude),
      homeAltitude: finiteNumber(home.altitude),
      altitudeReference: textValue(row.altitudeReference) || "RELATIVE_TO_HOME",
      timeSource: textValue(row.timeSource),
      horizontalCorrectionMeters: null,
      altitudeDeltaMeters: null,
      correctionStatus: "RF_OBSERVED",
      hasRawPosition: validCoordinate(rawLatitude, rawLongitude),
      hasCorrectedPosition: false
    };
    point._sourceIndex = Number.isInteger(sourceIndex) ? sourceIndex : -1;
    point._trackKey = [point.sourceType, point.rfScannerId, point.trackId || point.eventId].join("\u0000");
    point._pairKey = sampleKey(point);
    point._searchText = [point.sourceType, point.objectNo, point.globalObjectNo, point.rfScannerId, point.trackId,
      point.eventTime, point.timeSource, point.matched ? "matched" : "unmatched", point.altitudeReference]
      .join("\u0000").toLowerCase();
    return point;
  }

  function installPoints(points, options) {
    const settings = Object.assign({ assignPairOrdinals: true }, options);
    state.points = points || [];
    state.objectIndex = new Map();
    state.pairTrackIndex = new Map();
    state.crossSourceIndexes = new WeakMap();
    OBJECT_COLOR_CACHE.clear();
    let south = Infinity;
    let west = Infinity;
    let north = -Infinity;
    let east = -Infinity;
    state.points.forEach((point) => {
      if (!state.objectIndex.has(point.objectNo)) state.objectIndex.set(point.objectNo, []);
      state.objectIndex.get(point.objectNo).push(point);
      if (!point._trackKey) point._trackKey = [point.sourceType, point.objectNo, point.deviceId, point.sourceTrackId].join("\u0000");
      if (!point._pairKey) point._pairKey = sampleKey(point);
      if (!state.pairTrackIndex.has(point._trackKey)) state.pairTrackIndex.set(point._trackKey, []);
      state.pairTrackIndex.get(point._trackKey).push(point);
      if (point.hasRawPosition) {
        south = Math.min(south, point.rawLatitude);
        north = Math.max(north, point.rawLatitude);
        west = Math.min(west, point.rawLongitude);
        east = Math.max(east, point.rawLongitude);
      }
      if (point.hasCorrectedPosition) {
        south = Math.min(south, point.correctedLatitude);
        north = Math.max(north, point.correctedLatitude);
        west = Math.min(west, point.correctedLongitude);
        east = Math.max(east, point.correctedLongitude);
      }
    });
    state.objectIndex.forEach((rows) => rows.sort((a, b) => a._timeMs - b._timeMs || TABLE_COLLATOR.compare(a.eventId || "", b.eventId || "")));
    state.pairTrackIndex.forEach((rows) => {
      rows.sort(pointOrder);
      if (settings.assignPairOrdinals) preparePairOrdinals(rows);
      else rows.forEach((point) => {
        delete point._pairOrdinal;
        delete point._pairTotal;
      });
    });
    state.mapBounds = Number.isFinite(south) ? [[south, west], [north, east]] : null;
    state.mapPoints = state.points;
    state.dataVersion += 1;
    invalidateTableCache();
  }

  function pointOrder(left, right) {
    return left._timeMs - right._timeMs || TABLE_COLLATOR.compare(left.eventId || "", right.eventId || "") ||
      TABLE_COLLATOR.compare(left.sourceEventId || "", right.sourceEventId || "");
  }

  function preparePairOrdinals(rows) {
    rows.sort(pointOrder);
    rows.forEach((point, index) => {
      point._pairOrdinal = index + 1;
      point._pairTotal = rows.length;
    });
  }

  function nearestPointInRows(rows, at) {
    const target = timeValue(at);
    if (!rows.length) return null;
    if (!Number.isFinite(target)) return rows[0];
    let low = 0;
    let high = rows.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (rows[middle]._timeMs < target) low = middle + 1;
      else high = middle;
    }
    if (low <= 0) return rows[0];
    if (low >= rows.length) return rows[rows.length - 1];
    return target - rows[low - 1]._timeMs <= rows[low]._timeMs - target ? rows[low - 1] : rows[low];
  }

  function invalidateTableCache() {
    state.tableCache = null;
  }

  function normalizeSummary(summary, points) {
    let rawCount = 0;
    let correctedCount = 0;
    const horizontalValues = [];
    const altitudeValues = [];
    const objects = new Set();
    points.forEach((point) => {
      if (point.hasRawPosition) rawCount += 1;
      if (point.hasCorrectedPosition) correctedCount += 1;
      if (isFiniteNumber(point.horizontalCorrectionMeters)) horizontalValues.push(point.horizontalCorrectionMeters);
      if (isFiniteNumber(point.altitudeDeltaMeters)) altitudeValues.push(Math.abs(point.altitudeDeltaMeters));
      objects.add(point.objectNo);
    });
    const objectCount = objects.size;
    return {
      sourceRows: finiteNumber(pick(summary, ["sourceRows", "rowCount", "totalCount"])) ?? points.length,
      objectCount: finiteNumber(pick(summary, ["objectCount", "objects", "targetCount"])) ?? objectCount,
      rawPositionCount: finiteNumber(pick(summary, ["rawPositionCount", "rawCount", "originalCount"])) ?? rawCount,
      correctedPositionCount: finiteNumber(pick(summary, ["correctedPositionCount", "correctedCount"])) ?? correctedCount,
      uncorrectedCount: finiteNumber(summary.uncorrectedCount) ?? Math.max(rawCount - correctedCount, 0),
      averageHorizontalCorrectionMeters: finiteNumber(pick(summary, ["averageHorizontalCorrectionMeters", "avgHorizontalCorrectionMeters", "averageDistanceMeters"])) ?? average(horizontalValues),
      maxHorizontalCorrectionMeters: finiteNumber(pick(summary, ["maxHorizontalCorrectionMeters", "maximumDistanceMeters"])) ?? maximum(horizontalValues),
      p95HorizontalCorrectionMeters: finiteNumber(pick(summary, ["p95HorizontalCorrectionMeters", "horizontalP95Meters", "p95DistanceMeters"])) ?? percentile(horizontalValues, .95),
      averageAbsoluteAltitudeDeltaMeters: finiteNumber(pick(summary, ["averageAbsoluteAltitudeDeltaMeters", "avgAltitudeDeltaMeters"])) ?? average(altitudeValues),
      maxAbsoluteAltitudeDeltaMeters: finiteNumber(pick(summary, ["maxAbsoluteAltitudeDeltaMeters", "maxAltitudeDeltaMeters"])) ?? maximum(altitudeValues)
    };
  }

  function normalizeSourceSummary(summary, points) {
    const radarPoints = points.filter((point) => point.sourceType === "RADAR");
    const rfPoints = points.filter((point) => point.sourceType === "RFSCNR");
    const objectKeys = new Set(points.map((point) => point.objectNo));
    const globalObjects = new Set(points.map((point) => point.globalObjectNo).filter(Boolean));
    const matchedRf = rfPoints.filter((point) => point.matched);
    const radarRows = finiteNumber(summary.radarSourceRows) ?? radarPoints.length;
    const rfScannerRows = finiteNumber(summary.rfScannerSourceRows) ?? rfPoints.length;
    return {
      sourceRows: finiteNumber(summary.sourceRows) ?? radarRows + rfScannerRows,
      radarRows,
      rfScannerRows,
      returnedRadarRows: radarPoints.length,
      returnedRfScannerRows: rfPoints.length,
      objectCount: objectKeys.size,
      globalObjectCount: globalObjects.size,
      radarObjectCount: finiteNumber(summary.radarObjectCount) ?? new Set(radarPoints.map((point) => point.objectNo)).size,
      rfScannerTrackCount: finiteNumber(summary.rfScannerTrackCount) ?? new Set(rfPoints.map((point) => point._trackKey)).size,
      matchedRfScannerRows: finiteNumber(summary.matchedRfScannerPointCount) ?? matchedRf.length,
      unmatchedRfScannerRows: finiteNumber(summary.unmatchedRfScannerPointCount) ?? Math.max(rfScannerRows - matchedRf.length, 0)
    };
  }

  function renderMeta() {
    const meta = state.meta;
    const dbState = String(meta.database.status || "").toUpperCase();
    const connected = ["UP", "OK", "CONNECTED", "AVAILABLE", "READY"].some((token) => dbState.includes(token));
    const unknown = !dbState || dbState === "UNKNOWN";
    setStatusPill(elements.dbStatus, connected ? "DB 연결됨" : (unknown ? "DB 상태 미확인" : "DB 연결 오류"), connected ? "ok" : (unknown ? "checking" : "error"));
    const database = meta.database;
    elements.dbTarget.textContent = database.syntheticDemo
      ? "SYNTHETIC DEMO · " + database.connectionLabel
      : "DB · " + database.connectionLabel;
    elements.dbTarget.title = database.syntheticDemo
      ? "100% synthetic in-memory demonstration data"
      : "현재 연결 라벨: " + database.connectionLabel;
    const radarReady = Boolean(meta.capabilities.radarReady || meta.capabilities.radarEvents);
    const rfReady = Boolean(meta.rfScannerCapabilities.ready);
    elements.schemaStatus.textContent = database.syntheticDemo
      ? "DEMO DATA"
      : (radarReady && rfReady ? "RADAR + RF READY" : (radarReady ? "RADAR READY" : (rfReady ? "RF READY" : "SOURCE NOT READY")));

    const calc = meta.capabilities;
    const hasXY = calc.correctedLongitude && calc.correctedLatitude;
    const hasZ = calc.correctedAltitude;
    elements.correctionStatus.textContent = hasXY ? (hasZ ? "보정 XY · Z 사용 가능" : "보정 XY 사용 가능 · Z 없음") : "보정 컬럼 없음 · 원본 모드";
    elements.correctionStatus.className = "status-pill " + (hasXY ? "status-ok" : "status-muted");

    const min = meta.timeRange.min;
    const max = meta.timeRange.max;
    const sourceRanges = [
      formatTimeRangeLabel("Radar", meta.radarTimeRange),
      formatTimeRangeLabel("RF", meta.rfScannerTimeRange)
    ].filter(Boolean);
    elements.timeRange.textContent = min || max
      ? "DB RANGE  " + (min ? formatEventTime(min, true) : "—") + "  →  " + (max ? formatEventTime(max, true) : "—") +
        (sourceRanges.length ? " · " + sourceRanges.join(" · ") : "")
      : "데이터 시간 범위가 없거나 테이블이 비어 있습니다.";

    const maxRangeSeconds = meta.limits.maxRangeSeconds;
    const maxQueryRows = meta.limits.maxQueryRows;
    const maxOverviewPoints = meta.limits.maxOverviewPoints;
    const rangePolicy = maxRangeSeconds === 0
      ? "시간 범위 고정 제한 없음"
      : (maxRangeSeconds > 0 ? "최대 조회 시간 " + formatDuration(maxRangeSeconds) : "시간 범위 제한 정보 없음");
    elements.queryLimitHelp.textContent = "· " + rangePolicy +
      (maxQueryRows === 0 ? " · 원본 행 제한 없음" : (maxQueryRows > 0 ? " · 정밀 조회 최대 " + formatInteger(maxQueryRows) + "행" : "")) +
      (maxOverviewPoints > 0 ? " · 지도 대표점 최대 " + formatInteger(maxOverviewPoints) + "개" : "") +
      " · 트랙 선택 시 전체 샘플 정밀 조회";

    const noteParts = [];
    if (!hasXY) noteParts.push("현재 DB에는 보정 좌표 컬럼이 없어 원본 위치만 표시합니다.");
    else if (!hasZ) noteParts.push("보정 고도 컬럼이 없어 파란 고도선은 표시되지 않습니다.");
    if (!calc.primaryFlag) {
      elements.primaryInput.checked = false;
      elements.primaryInput.disabled = true;
      noteParts.push("대표 관측 플래그가 없어 전체 관측치를 조회합니다.");
    }
    elements.sourceRadar.disabled = !radarReady;
    elements.sourceRf.disabled = !rfReady;
    if (!radarReady) {
      elements.sourceRadar.checked = false;
      noteParts.push("Radar 관측 소스가 준비되지 않았습니다.");
    }
    if (!rfReady) {
      elements.sourceRf.checked = false;
      noteParts.push("RF 관측 소스가 준비되지 않았습니다.");
    } else if (!meta.rfScannerCapabilities.objectMatch) {
      elements.matchModeInput.value = "ALL";
      elements.matchModeInput.disabled = true;
      noteParts.push("RF Global Object 매칭 필드를 사용할 수 없어 매칭 필터를 비활성화했습니다.");
    }
    if (meta.warnings.length) noteParts.push(meta.warnings.join(" "));
    elements.capabilityNote.textContent = noteParts.join(" ");
    elements.capabilityNote.hidden = noteParts.length === 0;
    renderDevicePickers();
    updateSourceControls();
  }

  function renderInitialIdle() {
    elements.queryMode.textContent = "RANGE QUERY";
    elements.mapTime.textContent = "조회 버튼을 눌러주세요";
    elements.mapCount.textContent = "0 SAMPLES · 0 OBJECTS";
    elements.resultBadge.textContent = "조회 대기";
    elements.samplingBadge.hidden = true;
    elements.samplingNote.hidden = true;
    elements.metricObjects.textContent = "—";
    elements.metricSources.textContent = "—";
    elements.metricSourcesSub.textContent = "조회 전";
    elements.metricCoverage.textContent = "—";
    elements.metricCoverageSub.textContent = "조회 전";
    elements.metricAverage.textContent = "—";
    elements.metricP95.textContent = "—";
    elements.metricAltitude.textContent = "—";
    elements.metricRfMatch.textContent = "—";
    elements.metricRfMatchSub.textContent = "조회 전";
    elements.analysisBody.replaceChildren();
    elements.analysisTable.style.display = "none";
    elements.tableEmpty.hidden = false;
    elements.tableEmpty.textContent = "조회 버튼을 누르면 선택한 범위의 관측치를 표시합니다.";
    elements.tableCaption.textContent = "조회할 시간 범위와 필터를 입력해 주세요.";
    elements.pageStatus.textContent = "0 / 0";
    elements.pagePrev.disabled = true;
    elements.pageNext.disabled = true;
    elements.mapEmpty.hidden = false;
    const mapTitle = elements.mapEmpty.querySelector("strong");
    const mapDescription = elements.mapEmpty.querySelector("span:last-child");
    if (mapTitle) mapTitle.textContent = "조회 버튼을 눌러주세요.";
    if (mapDescription) mapDescription.textContent = "원하는 시간 범위와 필터를 입력한 뒤 범위 불러오기를 실행하세요.";
    setQueryMessage("DB 최신 시각을 입력해 두었습니다. 원하는 범위를 정한 뒤 ‘범위 불러오기’를 눌러주세요.");
    renderDetailEmpty();
  }

  function renderTracks(snapshot, fitMap) {
    const from = snapshot.rangeStart || state.rangeStart || currentQuery().from;
    const to = snapshot.rangeEnd || state.rangeEnd || currentQuery().to;
    const mode = snapshot.mode || state.mode;
    elements.queryMode.textContent = mode + " QUERY";
    elements.mapTime.textContent = mode === "RANGE"
      ? formatEventTime(from, true) + "  →  " + formatEventTime(to, true)
      : formatEventTime(from, true);
    const sourceRows = sourceRowCount();
    const returnedRows = returnedRowCount();
    const snapshotCollapsed = Boolean(state.sampling.snapshotCollapsed);
    const compacted = state.sampling.applied || snapshotCollapsed;
    renderMapCount();
    elements.resultBadge.textContent = compacted
      ? formatInteger(returnedRows) + " / " + formatInteger(sourceRows) + " ROWS"
      : formatInteger(returnedRows) + " ROW" + (returnedRows === 1 ? "" : "S");
    elements.resultBadge.title = compacted
      ? (snapshotCollapsed
        ? "±" + formatInteger(state.toleranceMs) + " ms 후보 " + formatInteger(sourceRows) + "행 중 트랙별 최근접 " + formatInteger(returnedRows) + "개 표시"
        : "DB 전체 " + formatInteger(sourceRows) + "행 중 지도·표에 " + formatInteger(returnedRows) + "개 대표점 표시")
      : "조회된 전체 샘플 " + formatInteger(returnedRows) + "행";
    elements.samplingBadge.hidden = !compacted;
    elements.samplingBadge.textContent = state.sampling.applied ? "REPRESENTATIVE" : (snapshotCollapsed ? "NEAREST / TRACK" : "");
    elements.samplingNote.hidden = !compacted;
    if (state.sampling.applied) {
      const trackCoverage = state.sampling.trackCount > 0
        ? (state.sampling.allTracksRepresented
          ? "전체 " + formatInteger(state.sampling.trackCount) + "개 트랙 포함"
          : formatInteger(state.sampling.representedTracks) + " / " + formatInteger(state.sampling.trackCount) + "개 트랙 포함")
        : "시간축 전체를 분산 표본화";
      const metricScope = String(state.sampling.metricsScope || "").toUpperCase() === "FULL_RANGE"
        ? "요약 지표는 DB 전체 행 기준"
        : "요약 지표는 대표점 기준";
      const p95Scope = state.sampling.p95Approximate ? " · P95 근사값" : "";
      elements.samplingNote.textContent = "DB 전체 " + formatInteger(sourceRows) + "행을 분석해 " + formatInteger(returnedRows) +
        "개 대표점으로 표시합니다. " + trackCoverage + " · " + metricScope + p95Scope +
        ". 트랙을 선택하면 해당 트랙의 전체 샘플을 정밀 조회합니다.";
    } else if (snapshotCollapsed) {
      elements.samplingNote.textContent = "±" + formatInteger(state.toleranceMs) + " ms 후보 " + formatInteger(sourceRows) +
        "행에서 트랙별 기준 시각 최근접 " + formatInteger(returnedRows) + "개를 표시합니다. 요약 지표는 후보창 전체 행 기준입니다.";
    } else {
      elements.samplingNote.textContent = "";
    }
    elements.detailWindowField.hidden = mode === "RANGE";
    elements.metricObjects.previousElementSibling.textContent = state.sampling.applied ? "표시 객체" : "관측 객체";
    elements.metricObjects.nextElementSibling.textContent = "Global Object + 미매칭 RF · " +
      (state.sampling.applied ? "표시 대표점" : (mode === "RANGE" ? "선택 구간" : "선택 시점"));
    renderSummary();
    renderAnalysisTable();
    renderMap(fitMap);
    updateSortHeaders();
  }

  function renderSummary() {
    const summary = state.summary;
    const sources = state.sourceSummary || {};
    const raw = summary.rawPositionCount || 0;
    const corrected = summary.correctedPositionCount || 0;
    const coverage = raw ? corrected / raw * 100 : null;
    elements.metricObjects.textContent = formatInteger(sources.objectCount || 0);
    elements.metricSources.textContent = formatInteger(sources.radarRows || 0) + " / " + formatInteger(sources.rfScannerRows || 0);
    elements.metricSourcesSub.textContent = "RADAR / RF · 후보 행";
    elements.metricCoverage.textContent = coverage == null ? "—" : formatNumber(coverage, 0) + "%";
    const metricsFullRange = String(state.sampling.metricsScope || "").toUpperCase() === "FULL_RANGE";
    elements.metricCoverageSub.textContent = formatInteger(corrected) + " / " + formatInteger(raw) +
      ((state.sampling.applied || state.sampling.snapshotCollapsed)
        ? (metricsFullRange ? (state.sampling.snapshotCollapsed ? " 후보창" : " 전체 행") : " 표시점")
        : " 원본");
    elements.metricAverage.textContent = formatMeters(summary.averageHorizontalCorrectionMeters);
    elements.metricP95.textContent = formatMeters(summary.p95HorizontalCorrectionMeters);
    elements.metricAltitude.textContent = formatMeters(summary.maxAbsoluteAltitudeDeltaMeters);
    const rfRows = sources.rfScannerRows || 0;
    const matched = sources.matchedRfScannerRows || 0;
    elements.metricRfMatch.textContent = rfRows ? formatNumber(matched / rfRows * 100, 0) + "%" : "—";
    elements.metricRfMatchSub.textContent = formatInteger(matched) + " / " + formatInteger(rfRows) + " RF 행";
  }

  function renderAnalysisTable() {
    const sorted = getTableRows();
    const totalPages = Math.max(1, Math.ceil(sorted.length / TABLE_PAGE_SIZE));
    state.tablePage = Math.max(1, Math.min(state.tablePage, totalPages));
    const start = (state.tablePage - 1) * TABLE_PAGE_SIZE;
    const visible = sorted.slice(start, start + TABLE_PAGE_SIZE);
    const fragment = document.createDocumentFragment();
    visible.forEach((point) => {
      const row = document.createElement("tr");
      row.tabIndex = 0;
      row.dataset.objectNo = point.objectNo;
      if (state.selectedPoint && point._trackKey === state.selectedPoint._trackKey) row.classList.add("is-track-selected");
      if (state.selectedPoint && state.selectedPoint.globalObjectNo && point.globalObjectNo === state.selectedPoint.globalObjectNo) {
        row.classList.add("is-global-selected");
      }
      if (sameSample(point, state.selectedPoint)) row.classList.add("is-selected");
      row.innerHTML = [
        "<td>" + sourceTag(point.sourceType) + "</td>",
        "<td class=\"object-cell\"><i class=\"object-swatch\" style=\"color:" + objectColor(point.objectNo) + ";background:" + objectColor(point.objectNo) + "\"></i>" +
          escapeHtml(point.globalObjectNo || "미매칭") + (point.globalObjectNo ? "" : "<small class=\"fallback-object-id\">" + escapeHtml(point.objectNo) + "</small>") + "</td>",
        cell(formatEventTime(point.eventTime, false)),
        "<td class=\"object-cell\">" + escapeHtml(point.deviceId || "—") + subIdentifier(point) + "</td>",
        "<td>" + matchTag(point) + "</td>",
        "<td class=\"primary-cell\">" + primaryTag(point.primaryFlag) + "</td>",
        numberCell(formatCoordinate(point.rawLatitude)),
        numberCell(formatCoordinate(point.rawLongitude)),
        numberCell(formatAltitude(point.rawAltitude), point.sourceType === "RFSCNR" ? "rf-relative-cell" : ""),
        numberCell(point.sourceType === "RFSCNR"
          ? (isFiniteNumber(point.homeAltitude) ? "Home MSL " + formatAltitude(point.homeAltitude) : "—")
          : formatAltitude(point.referenceAltitude), "reference-altitude-cell"),
        numberCell(formatCoordinate(point.correctedLatitude)),
        numberCell(formatCoordinate(point.correctedLongitude)),
        numberCell(formatAltitude(point.correctedAltitude)),
        numberCell(formatMeters(point.horizontalCorrectionMeters)),
        numberCell(formatSignedMeters(point.altitudeDeltaMeters)),
        "<td>" + statusTag(point) + "</td>"
      ].join("");
      row.addEventListener("click", () => selectObject(point));
      row.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          selectObject(point);
        }
      });
      fragment.appendChild(row);
    });
    elements.analysisBody.replaceChildren(fragment);
    elements.tableEmpty.hidden = sorted.length > 0;
    if (!sorted.length) {
      elements.tableEmpty.textContent = state.hasQueried
        ? (state.tableFilter ? "검색 조건에 맞는 결과가 없습니다." : "조회된 좌표가 없습니다.")
        : "조회 버튼을 누르면 선택한 범위의 관측치를 표시합니다.";
    }
    elements.analysisTable.style.display = sorted.length ? "table" : "none";
    elements.pageStatus.textContent = sorted.length ? state.tablePage + " / " + totalPages : "0 / 0";
    elements.pagePrev.disabled = !sorted.length || state.tablePage <= 1;
    elements.pageNext.disabled = !sorted.length || state.tablePage >= totalPages;
    const rowKind = state.sampling.applied ? " 대표점" : (state.sampling.snapshotCollapsed ? " 최근접점" : "행");
    const rowLabel = sorted.length ? formatInteger(start + 1) + "–" + formatInteger(Math.min(start + TABLE_PAGE_SIZE, sorted.length)) + " / " +
      formatInteger(sorted.length) + rowKind : "조회 결과 없음";
    const sourceLabel = state.sampling.applied
      ? " · DB 전체 " + formatInteger(sourceRowCount()) + "행"
      : (state.sampling.snapshotCollapsed ? " · ±" + formatInteger(state.toleranceMs) + " ms 후보 " + formatInteger(sourceRowCount()) + "행" : "");
    elements.tableCaption.textContent = (state.tableFilter ? "검색 결과 " : "") + rowLabel + sourceLabel +
      (state.sampling.applied ? " · 선택 트랙은 전체 샘플을 정밀 조회합니다." : " · 행을 선택하면 상세 궤적을 확인합니다.");
  }

  function getTableRows() {
    const cache = state.tableCache;
    if (cache && cache.version === state.dataVersion && cache.filter === state.tableFilter &&
        cache.sortKey === state.sortKey && cache.sortDirection === state.sortDirection) return cache.rows;
    const filtered = state.tableFilter
      ? state.points.filter((point) => point._searchText.includes(state.tableFilter))
      : state.points;
    const rows = filtered.slice().sort(comparePoints(state.sortKey, state.sortDirection));
    state.tableCache = {
      version: state.dataVersion,
      filter: state.tableFilter,
      sortKey: state.sortKey,
      sortDirection: state.sortDirection,
      rows
    };
    return rows;
  }

  function initializeMap() {
    if (!window.L) {
      elements.mapFallback.hidden = false;
      return;
    }
    try {
      state.map = L.map(elements.map, {
        zoomControl: true,
        attributionControl: true,
        preferCanvas: true,
        minZoom: 2,
        maxZoom: DEFAULT_MAP_MAX_ZOOM
      }).setView(DEFAULT_CENTER, 8);
      state.radarLayer = createRadarCanvasLayer().addTo(state.map);
      L.control.scale({ imperial: false, position: "bottomleft" }).addTo(state.map);
      state.map.on("moveend zoomend resize", syncSamplePopoverToMap);
    } catch (error) {
      elements.mapFallback.hidden = false;
      elements.mapFallback.querySelector("span").textContent = error.message;
    }
  }

  function configureBaseMap() {
    if (!state.map || !state.meta) return;
    const config = state.meta.map;
    const maxZoom = mapMaxZoom();
    state.map.setMaxZoom(maxZoom);
    const center = validCoordinate(config.initialLatitude, config.initialLongitude)
      ? [config.initialLatitude, config.initialLongitude] : DEFAULT_CENTER;
    state.map.setView(center, config.initialZoom || 8);
    if (state.tileLayer) {
      state.map.removeLayer(state.tileLayer);
      state.tileLayer = null;
    }
    const tileUrl = config.tileUrl;
    const usableTileTemplate = tileUrl && (tileUrl.includes("{z}") || tileUrl.includes("{x}") || tileUrl.includes("{y}"));
    if (!usableTileTemplate) {
      elements.tileStatus.textContent = "배경: 좌표 격자";
      return;
    }
    try {
      const tileLayer = L.tileLayer(tileUrl, {
        attribution: config.attribution || "",
        maxNativeZoom: Math.min(config.maxNativeZoom ?? 19, maxZoom),
        maxZoom,
        crossOrigin: true
      });
      state.tileLayer = tileLayer;
      let tileErrors = 0;
      let tileSuccesses = 0;
      tileLayer.on("tileerror", () => {
        tileErrors += 1;
        if (tileErrors >= 3) elements.tileStatus.textContent = "일부 타일 응답 없음 · 재시도 중";
      });
      tileLayer.on("tileload", () => { tileSuccesses += 1; });
      tileLayer.on("load", () => {
        if (tileErrors > 0) {
          elements.tileStatus.textContent = tileSuccesses > 0
            ? "일부 타일 응답 없음 · 좌표 격자 병행"
            : "타일 응답 없음 · 좌표 격자 병행";
        } else if (tileSuccesses > 0) {
          elements.tileStatus.textContent = "배경: 지도 타일";
        }
        tileErrors = 0;
        tileSuccesses = 0;
      });
      tileLayer.addTo(state.map);
      tileLayer.bringToBack();
      elements.tileStatus.textContent = "배경 지도 로딩 중";
    } catch (error) {
      elements.tileStatus.textContent = "타일 설정 오류 · 좌표 격자 사용";
    }
  }

  function mapMaxZoom() {
    const configured = state.meta && state.meta.map && finiteNumber(state.meta.map.maxZoom);
    return Math.max(2, Math.min(24, configured ?? DEFAULT_MAP_MAX_ZOOM));
  }

  function focusSelectedPair(point) {
    if (!state.map || !point) return;
    const raw = point.hasRawPosition ? [point.rawLatitude, point.rawLongitude] : null;
    const corrected = point.hasCorrectedPosition ? [point.correctedLatitude, point.correctedLongitude] : null;
    if (raw && corrected) {
      const samePosition = raw[0] === corrected[0] && raw[1] === corrected[1];
      if (samePosition) {
        state.map.setView(raw, mapMaxZoom(), { animate: false });
        schedulePairPanelClear(point);
        return;
      }
      const padding = pairFitPadding();
      state.map.fitBounds([raw, corrected], {
        paddingTopLeft: padding.topLeft,
        paddingBottomRight: padding.bottomRight,
        maxZoom: mapMaxZoom(),
        animate: false
      });
      schedulePairPanelClear(point);
      return;
    }
    const single = corrected || raw;
    if (single) {
      state.map.setView(single, Math.max(state.map.getZoom(), Math.min(mapMaxZoom(), 20)), { animate: false });
      schedulePairPanelClear(point);
    }
  }

  function pairFitPadding() {
    const base = 56;
    const topLeft = [base, base];
    const bottomRight = [base, base];
    if (!elements.pairFocus || elements.pairFocus.hidden || !elements.map) return { topLeft, bottomRight };
    const mapRect = elements.map.getBoundingClientRect();
    const cardRect = elements.pairFocus.getBoundingClientRect();
    if (!mapRect.width || !mapRect.height || !cardRect.width || !cardRect.height) return { topLeft, bottomRight };
    const maxHorizontal = Math.max(base, Math.floor(mapRect.width * .42));
    const maxVertical = Math.max(base, Math.floor(mapRect.height * .38));
    if (cardRect.left < mapRect.left + mapRect.width / 2) {
      topLeft[0] = Math.min(maxHorizontal, Math.max(base, cardRect.right - mapRect.left + 18));
    } else {
      bottomRight[0] = Math.min(maxHorizontal, Math.max(base, mapRect.right - cardRect.left + 18));
    }
    if (cardRect.top < mapRect.top + mapRect.height / 2) {
      topLeft[1] = Math.min(maxVertical, Math.max(base, cardRect.bottom - mapRect.top + 18));
    } else {
      bottomRight[1] = Math.min(maxVertical, Math.max(base, mapRect.bottom - cardRect.top + 18));
    }
    return { topLeft, bottomRight };
  }

  function schedulePairPanelClear(point) {
    requestAnimationFrame(() => {
      if (!point || !state.map) return;
      movePanelAwayFromPair(elements.pairFocus, state.pairPanelMover, point);
      if (state.samplePopover.open) {
        if (state.samplePopover.pinned) movePanelAwayFromPair(elements.samplePopover, state.samplePopoverMover, point);
        else placeSamplePopoverNearAnchor();
      }
    });
  }

  function movePanelAwayFromPair(panel, mover, point) {
    if (!panel || panel.hidden || !mover) return;
    const projected = projectPair(point);
    if (!projected.length || !panelContainsAnyPoint(panel, projected, 16)) return;
    const width = panel.offsetWidth;
    const height = panel.offsetHeight;
    const stageWidth = elements.mapStage.clientWidth;
    const stageHeight = elements.mapStage.clientHeight;
    const candidates = [
      { x: 8, y: 70 },
      { x: stageWidth - width - 8, y: 70 },
      { x: 8, y: stageHeight - height - 30 },
      { x: stageWidth - width - 8, y: stageHeight - height - 30 }
    ];
    const best = chooseClearPanelPosition(candidates, width, height, stageWidth, stageHeight, projected, panel === elements.samplePopover);
    mover.moveTo(best.x, best.y);
  }

  function projectPair(point) {
    if (!state.map || !point) return [];
    const projected = [];
    if (point.hasRawPosition) projected.push(state.map.latLngToContainerPoint([point.rawLatitude, point.rawLongitude]));
    if (point.hasCorrectedPosition) projected.push(state.map.latLngToContainerPoint([point.correctedLatitude, point.correctedLongitude]));
    return projected;
  }

  function panelContainsAnyPoint(panel, points, margin) {
    const stageRect = elements.mapStage.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    const left = panelRect.left - stageRect.left - margin;
    const top = panelRect.top - stageRect.top - margin;
    const right = panelRect.right - stageRect.left + margin;
    const bottom = panelRect.bottom - stageRect.top + margin;
    return points.some((point) => point.x >= left && point.x <= right && point.y >= top && point.y <= bottom);
  }

  function chooseClearPanelPosition(candidates, panelWidth, panelHeight, stageWidth, stageHeight, points, avoidPairCard) {
    let best = null;
    candidates.forEach((candidate) => {
      const position = clampPanelPosition(candidate.x, candidate.y, stageWidth, stageHeight, panelWidth, panelHeight, 8);
      const rect = { left: position.x, top: position.y, right: position.x + panelWidth, bottom: position.y + panelHeight };
      const coveredPoints = points.filter((point) => point.x >= rect.left - 16 && point.x <= rect.right + 16 && point.y >= rect.top - 16 && point.y <= rect.bottom + 16).length;
      let cardOverlap = 0;
      if (avoidPairCard && elements.pairFocus && !elements.pairFocus.hidden) {
        const stageRect = elements.mapStage.getBoundingClientRect();
        const card = elements.pairFocus.getBoundingClientRect();
        const cardRect = { left: card.left - stageRect.left, top: card.top - stageRect.top, right: card.right - stageRect.left, bottom: card.bottom - stageRect.top };
        cardOverlap = Math.max(0, Math.min(rect.right, cardRect.right) - Math.max(rect.left, cardRect.left)) *
          Math.max(0, Math.min(rect.bottom, cardRect.bottom) - Math.max(rect.top, cardRect.top));
      }
      const score = coveredPoints * 1_000_000 + cardOverlap;
      if (!best || score < best.score) best = { x: position.x, y: position.y, score };
    });
    return best || { x: 8, y: 8 };
  }

  function renderMap(fitMap) {
    renderPairFocus();
    renderMapCount();
    if (!state.map || !state.radarLayer) return;
    state.radarLayer.setData(state.mapPoints, state.selectedObjectNo, state.selectedPoint,
      state.selectedPoint && state.selectedPoint._trackKey, matchLinksEnabled());
    elements.mapEmpty.hidden = Boolean(state.mapBounds);
    if (!state.mapBounds && state.hasQueried) {
      const title = elements.mapEmpty.querySelector("strong");
      const description = elements.mapEmpty.querySelector("span:last-child");
      if (title) title.textContent = "표시할 좌표가 없습니다.";
      if (description) description.textContent = "시간 범위나 소스·장비·오브젝트 필터를 조정한 뒤 다시 조회해 보세요.";
    }
    if (state.mapBounds && (fitMap || !state.initialFitDone)) {
      state.map.fitBounds(state.mapBounds, { padding: [48, 48], maxZoom: 17, animate: false });
      state.initialFitDone = true;
    }
  }

  function renderMapCount() {
    if (!elements.mapCount) return;
    const sourceRows = sourceRowCount();
    const returnedRows = returnedRowCount();
    if (state.sampling.applied) {
      const exact = state.exactStatus === "ready" && state.detailPoints.length
        ? " · SELECTED " + formatInteger(state.detailPoints.length) + " EXACT"
        : "";
      elements.mapCount.textContent = "DB " + formatInteger(sourceRows) + " ROWS · OVERVIEW " +
        formatInteger(returnedRows) + " REPRESENTATIVE" + exact + " · " +
        formatInteger(state.sourceSummary.objectCount || 0) + " OBJECTS";
      return;
    }
    if (state.sampling.snapshotCollapsed) {
      elements.mapCount.textContent = "±" + formatInteger(state.toleranceMs) + " ms CANDIDATES " + formatInteger(sourceRows) +
        " · DISPLAYED " + formatInteger(returnedRows) + " · " + formatInteger(state.sourceSummary.objectCount || 0) + " OBJECTS";
      return;
    }
    elements.mapCount.textContent = formatInteger(returnedRows) + " SAMPLES · " +
      formatInteger(state.sourceSummary.objectCount || 0) + " OBJECTS";
  }

  function markerTooltip(point) {
    const pairLabel = point._isRepresentative
      ? " · 대표점 (선택 시 전체 트랙 정밀 조회)"
      : " · PAIR " + escapeHtml(point._pairOrdinal || "—") + " / " + escapeHtml(point._pairTotal || "—");
    const objectLabel = point.globalObjectNo ? "OBJECT " + point.globalObjectNo : "글로벌 오브젝트 미매칭";
    const match = nearestCrossSourceMatch(point, state.mapPoints);
    const matchLine = match
      ? "<br><span style=\"color:#ffd166\">↔ " + (match.point.sourceType === "RADAR" ? "RADAR " : "RF ") +
        escapeHtml(match.point.deviceId || "—") + (match.point.sourceTrackId ? " / " + escapeHtml(match.point.sourceTrackId) : "") +
        " · Δt " + escapeHtml(formatTimeDelta(match.deltaMs)) + "</span>"
      : "";
    if (point.sourceType === "RFSCNR") {
      return "<strong style=\"color:" + objectColor(point.objectNo) + "\">" + escapeHtml(objectLabel) + "</strong>" + pairLabel + "<br>" +
        "RF " + escapeHtml(point.rfScannerId || "—") + (point.trackId ? " · TRACK " + escapeHtml(point.trackId) : "") + "<br>" +
        "<span style=\"color:#e6a5ff\">◆ RF 관측</span> " + escapeHtml(formatCoordinate(point.rawLatitude)) + ", " +
        escapeHtml(formatCoordinate(point.rawLongitude)) + " · 상대고도 " + escapeHtml(formatAltitude(point.rfAltitude)) + "<br>" +
        "Home MSL " + escapeHtml(formatAltitude(point.homeAltitude)) + " · " + escapeHtml(formatEventTime(point.eventTime, false)) +
        (point.timeSource ? " (" + escapeHtml(point.timeSource) + ")" : "") + matchLine;
    }
    return "<strong style=\"color:" + objectColor(point.objectNo) + "\">" + escapeHtml(objectLabel) + "</strong>" + pairLabel + "<br>" +
      "RADAR " + escapeHtml(point.radarId || "—") + (point.radarObjectNo ? " · TRACK " + escapeHtml(point.radarObjectNo) : "") + "<br>" +
      "<span style=\"color:#ff645d\">● 원본</span> " + escapeHtml(formatCoordinate(point.rawLatitude)) + ", " +
      escapeHtml(formatCoordinate(point.rawLongitude)) + " · " + escapeHtml(formatAltitude(point.rawAltitude)) + "<br>" +
      "<span style=\"color:#4b9fff\">▲ 보정</span> " + escapeHtml(formatCoordinate(point.correctedLatitude)) + ", " +
      escapeHtml(formatCoordinate(point.correctedLongitude)) + " · " + escapeHtml(formatAltitude(point.correctedAltitude)) + "<br>" +
      "수평 " + escapeHtml(formatMeters(point.horizontalCorrectionMeters)) + " · 고도 " +
      escapeHtml(formatSignedMeters(point.altitudeDeltaMeters)) + " · " + escapeHtml(formatEventTime(point.eventTime, false)) + matchLine;
  }

  function matchLinksEnabled() {
    return Boolean(elements.matchLinksInput && elements.matchLinksInput.checked && !elements.matchLinksInput.disabled);
  }

  function preferredMapCoordinate(point) {
    if (!point) return null;
    if (point.sourceType === "RADAR" && point.hasCorrectedPosition) return [point.correctedLatitude, point.correctedLongitude];
    if (point.hasRawPosition) return [point.rawLatitude, point.rawLongitude];
    if (point.hasCorrectedPosition) return [point.correctedLatitude, point.correctedLongitude];
    return null;
  }

  function nearestCrossSourceMatch(point, points) {
    if (!point || !point.globalObjectNo || !Array.isArray(points)) return null;
    const bucket = crossSourceIndex(points).get(point.globalObjectNo);
    const candidates = bucket && bucket[point.sourceType === "RADAR" ? "RFSCNR" : "RADAR"];
    if (!candidates || !candidates.length) return null;
    const targetTime = point._timeMs;
    if (!Number.isFinite(targetTime)) return { point: candidates[0], deltaMs: Number.POSITIVE_INFINITY };
    let low = 0;
    let high = candidates.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (Number.isFinite(candidates[middle]._timeMs) && candidates[middle]._timeMs < targetTime) low = middle + 1;
      else high = middle;
    }
    let best = null;
    [low - 1, low].forEach((candidateIndex) => {
      if (candidateIndex < 0 || candidateIndex >= candidates.length) return;
      const candidate = candidates[candidateIndex];
      const deltaMs = Number.isFinite(candidate._timeMs) ? Math.abs(candidate._timeMs - targetTime) : Number.POSITIVE_INFINITY;
      if (!best || deltaMs < best.deltaMs) best = { point: candidate, deltaMs };
    });
    return best;
  }

  function crossSourceIndex(points) {
    const cached = state.crossSourceIndexes.get(points);
    if (cached) return cached;
    const result = new Map();
    points.forEach((point) => {
      if (!point || !point.globalObjectNo || !preferredMapCoordinate(point)) return;
      let bucket = result.get(point.globalObjectNo);
      if (!bucket) {
        bucket = { RADAR: [], RFSCNR: [] };
        result.set(point.globalObjectNo, bucket);
      }
      bucket[point.sourceType].push(point);
    });
    result.forEach((bucket) => {
      bucket.RADAR.sort(pointOrder);
      bucket.RFSCNR.sort(pointOrder);
    });
    state.crossSourceIndexes.set(points, result);
    return result;
  }

  function formatTimeDelta(value) {
    if (!Number.isFinite(value)) return "—";
    if (value < 1000) return Math.round(value) + " ms";
    if (value < 60000) return formatNumber(value / 1000, value < 10000 ? 2 : 1) + " s";
    return formatNumber(value / 60000, 1) + " min";
  }

  function initializeMovablePanels() {
    if (!elements.mapStage) return;
    state.pairPanelMover = installMovablePanel(elements.pairFocus, elements.pairDragHandle, elements.mapStage, {
      onHome: resetPairPanelPosition
    });
    state.samplePopoverMover = installMovablePanel(elements.samplePopover, elements.samplePopoverDragHandle, elements.mapStage, {
      onUserMove: () => {
        state.samplePopover.pinned = true;
        renderSamplePopoverMode();
      },
      onHome: resetSamplePopoverPosition
    });
    elements.pairFocus.addEventListener("pointerdown", () => bringPanelFront(elements.pairFocus));
    elements.samplePopover.addEventListener("pointerdown", () => bringPanelFront(elements.samplePopover));
    const clampPanels = () => {
      if (state.pairPanelMover) state.pairPanelMover.clamp();
      if (state.samplePopover.open) {
        if (state.samplePopover.pinned && state.samplePopoverMover) state.samplePopoverMover.clamp();
        else syncSamplePopoverToMap();
      }
    };
    if (window.ResizeObserver) {
      state.panelResizeObserver = new ResizeObserver(clampPanels);
      state.panelResizeObserver.observe(elements.mapStage);
    } else window.addEventListener("resize", clampPanels);
  }

  function installMovablePanel(panel, handle, stage, options) {
    const settings = options || {};
    let drag = null;
    const readPosition = () => {
      const stageRect = stage.getBoundingClientRect();
      const panelRect = panel.getBoundingClientRect();
      return { x: panelRect.left - stageRect.left, y: panelRect.top - stageRect.top };
    };
    const dimensions = () => ({
      stageWidth: stage.clientWidth,
      stageHeight: stage.clientHeight,
      panelWidth: panel.offsetWidth,
      panelHeight: panel.offsetHeight
    });
    const applyPosition = (x, y, measured) => {
      if (panel.hidden) return;
      const size = measured || dimensions();
      const position = clampPanelPosition(x, y, size.stageWidth, size.stageHeight, size.panelWidth, size.panelHeight, 8);
      panel.style.left = position.x + "px";
      panel.style.top = position.y + "px";
      panel.style.right = "auto";
      panel.style.bottom = "auto";
    };
    const finishDrag = (event, restore) => {
      if (!drag || (event && event.pointerId !== drag.pointerId)) return;
      if (restore) applyPosition(drag.origin.x, drag.origin.y, drag.size);
      if (handle.hasPointerCapture && handle.hasPointerCapture(drag.pointerId)) handle.releasePointerCapture(drag.pointerId);
      handle.classList.remove("is-dragging");
      drag = null;
    };
    handle.addEventListener("pointerdown", (event) => {
      if (event.isPrimary === false || (event.button != null && event.button !== 0)) return;
      bringPanelFront(panel);
      const origin = readPosition();
      drag = {
        pointerId: event.pointerId,
        clientX: event.clientX,
        clientY: event.clientY,
        origin,
        size: dimensions()
      };
      handle.classList.add("is-dragging");
      if (handle.setPointerCapture) handle.setPointerCapture(event.pointerId);
      if (handle.focus) handle.focus({ preventScroll: true });
      event.preventDefault();
      event.stopPropagation();
    });
    handle.addEventListener("pointermove", (event) => {
      if (!drag || event.pointerId !== drag.pointerId) return;
      applyPosition(drag.origin.x + event.clientX - drag.clientX, drag.origin.y + event.clientY - drag.clientY, drag.size);
      if (settings.onUserMove) settings.onUserMove();
      event.preventDefault();
      event.stopPropagation();
    });
    handle.addEventListener("pointerup", (event) => finishDrag(event, false));
    handle.addEventListener("pointercancel", (event) => finishDrag(event, false));
    handle.addEventListener("lostpointercapture", (event) => finishDrag(event, false));
    handle.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && drag) {
        finishDrag(null, true);
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      if (event.key === "Home") {
        if (settings.onHome) settings.onHome();
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      const movement = {
        ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]
      }[event.key];
      if (!movement) return;
      const position = readPosition();
      const distance = event.shiftKey ? 32 : 8;
      applyPosition(position.x + movement[0] * distance, position.y + movement[1] * distance);
      if (settings.onUserMove) settings.onUserMove();
      event.preventDefault();
      event.stopPropagation();
    });
    return {
      moveTo(x, y) { applyPosition(x, y); },
      clamp() {
        if (!panel.hidden) {
          const position = readPosition();
          applyPosition(position.x, position.y);
        }
      },
      resetCss(styles) {
        panel.style.left = "";
        panel.style.top = "";
        panel.style.right = "";
        panel.style.bottom = "";
        Object.assign(panel.style, styles || {});
      },
      isDragging() { return Boolean(drag); },
      cancelActiveDrag() {
        if (!drag) return false;
        finishDrag(null, true);
        return true;
      }
    };
  }

  function clampPanelPosition(x, y, stageWidth, stageHeight, panelWidth, panelHeight, inset) {
    const safeInset = Number.isFinite(inset) ? inset : 8;
    const safeX = Number.isFinite(x) ? x : safeInset;
    const safeY = Number.isFinite(y) ? y : safeInset;
    const maxX = Math.max(safeInset, (Number(stageWidth) || 0) - (Number(panelWidth) || 0) - safeInset);
    const maxY = Math.max(safeInset, (Number(stageHeight) || 0) - (Number(panelHeight) || 0) - safeInset);
    return {
      x: Math.max(safeInset, Math.min(maxX, safeX)),
      y: Math.max(safeInset, Math.min(maxY, safeY))
    };
  }

  function bringPanelFront(panel) {
    [elements.pairFocus, elements.samplePopover].forEach((candidate) => {
      if (candidate) candidate.classList.toggle("is-front", candidate === panel);
    });
  }

  function resetPairPanelPosition() {
    if (!state.pairPanelMover) return;
    state.pairPanelMover.resetCss({ right: "12px", bottom: "30px" });
    requestAnimationFrame(() => state.pairPanelMover && state.pairPanelMover.clamp());
  }

  function showSamplePopover(point, anchorKind) {
    if (!point) return;
    state.samplePopover.open = true;
    state.samplePopover.pinned = false;
    state.samplePopover.anchorKind = anchorKind === "raw" || anchorKind === "rf" ? anchorKind : "corrected";
    state.samplePopover.point = point;
    elements.samplePopoverContent.innerHTML = markerTooltip(point);
    elements.samplePopover.hidden = false;
    bringPanelFront(elements.samplePopover);
    renderSamplePopoverMode();
    syncSamplePopoverToMap();
  }

  function updateSamplePopover(point) {
    if (!state.samplePopover.open || !point) return;
    state.samplePopover.point = point;
    elements.samplePopoverContent.innerHTML = markerTooltip(point);
    renderSamplePopoverMode();
    if (!state.samplePopover.pinned) syncSamplePopoverToMap();
  }

  function hideSamplePopover() {
    state.samplePopover.open = false;
    state.samplePopover.pinned = false;
    state.samplePopover.point = null;
    elements.samplePopover.hidden = true;
    if (state.samplePopoverFrame) cancelAnimationFrame(state.samplePopoverFrame);
    state.samplePopoverFrame = null;
  }

  function resetSamplePopoverPosition() {
    if (!state.samplePopover.open) return;
    state.samplePopover.pinned = false;
    renderSamplePopoverMode();
    syncSamplePopoverToMap();
  }

  function renderSamplePopoverMode() {
    if (!elements.samplePopoverMode) return;
    elements.samplePopoverMode.textContent = state.samplePopover.pinned ? "화면 위치 고정됨" : "선택점 따라가기";
  }

  function syncSamplePopoverToMap() {
    if (!state.samplePopover.open || state.samplePopover.pinned || state.samplePopoverFrame) return;
    state.samplePopoverFrame = requestAnimationFrame(() => {
      state.samplePopoverFrame = null;
      placeSamplePopoverNearAnchor();
    });
  }

  function placeSamplePopoverNearAnchor() {
    if (!state.samplePopover.open || state.samplePopover.pinned || !state.map || !state.samplePopoverMover) return;
    const point = state.samplePopover.point;
    const preferRaw = state.samplePopover.anchorKind === "raw" || state.samplePopover.anchorKind === "rf";
    const coordinate = preferRaw && point.hasRawPosition
      ? [point.rawLatitude, point.rawLongitude]
      : (!preferRaw && point.hasCorrectedPosition
        ? [point.correctedLatitude, point.correctedLongitude]
        : (point.hasCorrectedPosition
          ? [point.correctedLatitude, point.correctedLongitude]
          : (point.hasRawPosition ? [point.rawLatitude, point.rawLongitude] : null)));
    if (!coordinate) return;
    const anchor = state.map.latLngToContainerPoint(coordinate);
    const panelWidth = elements.samplePopover.offsetWidth;
    const panelHeight = elements.samplePopover.offsetHeight;
    const stageWidth = elements.mapStage.clientWidth;
    const stageHeight = elements.mapStage.clientHeight;
    const candidates = [
      { x: anchor.x + 18, y: anchor.y - panelHeight - 18 },
      { x: anchor.x - panelWidth - 18, y: anchor.y - panelHeight - 18 },
      { x: anchor.x + 18, y: anchor.y + 18 },
      { x: anchor.x - panelWidth - 18, y: anchor.y + 18 }
    ];
    const best = chooseClearPanelPosition(candidates, panelWidth, panelHeight, stageWidth, stageHeight, projectPair(point), true);
    state.samplePopoverMover.moveTo(best.x, best.y);
  }

  function createRadarCanvasLayer() {
    return new (L.Layer.extend({
      initialize: function () {
        this._points = [];
        this._groups = [];
        this._selectedObjectNo = null;
        this._selectedTrackKey = null;
        this._selectedPoint = null;
        this._matchLinks = false;
        this._hitGrid = new Map();
        this._hitCellSize = 24;
        this._frame = null;
        this._projectionPlan = null;
      },
      onAdd: function (map) {
        this._map = map;
        this._canvas = L.DomUtil.create("canvas", "leaflet-radar-canvas leaflet-layer leaflet-zoom-animated");
        this._canvas.setAttribute("aria-hidden", "true");
        map.getPanes().overlayPane.appendChild(this._canvas);
        map.on("moveend zoomend resize", this._scheduleDraw, this);
        map.on("click", this._onClick, this);
        this._scheduleDraw();
      },
      onRemove: function (map) {
        map.off("moveend zoomend resize", this._scheduleDraw, this);
        map.off("click", this._onClick, this);
        if (this._frame) cancelAnimationFrame(this._frame);
        this._canvas.remove();
      },
      setData: function (points, selectedObjectNo, selectedPoint, selectedTrackKey, matchLinks) {
        const nextPoints = points || [];
        let dataChanged = false;
        const selectionChanged = this._selectedPoint !== (selectedPoint || null);
        if (this._points !== nextPoints) {
          this._points = nextPoints;
          this._preprocessGroups();
          this._projectionPlan = null;
          dataChanged = true;
        }
        this._selectedObjectNo = selectedObjectNo || null;
        this._selectedTrackKey = selectedTrackKey || null;
        this._selectedPoint = selectedPoint || null;
        this._matchLinks = Boolean(matchLinks);
        this._scheduleDraw(dataChanged || selectionChanged);
      },
      _preprocessGroups: function () {
        const physicalTracks = new Map();
        this._points.forEach((point, pointIndex) => {
          point._radarRenderIndex = pointIndex;
          const physicalKey = [point.sourceType, point.deviceId, point.sourceTrackId || point.objectNo].join("\u0000");
          if (!physicalTracks.has(physicalKey)) physicalTracks.set(physicalKey, []);
          physicalTracks.get(physicalKey).push(point);
        });
        const segments = [];
        physicalTracks.forEach((trackPoints, physicalKey) => {
          trackPoints.sort(pointOrder);
          let segment = null;
          let segmentOrdinal = 0;
          trackPoints.forEach((point) => {
            if (!segment || segment.objectNo !== point.objectNo) {
              segmentOrdinal += 1;
              segment = {
                segmentKey: [physicalKey, point.objectNo, segmentOrdinal].join("\u0000"),
                trackKey: point._trackKey,
                sourceType: point.sourceType,
                objectNo: point.objectNo,
                globalObjectNo: point.globalObjectNo,
                deviceId: point.deviceId || "",
                sourceTrackId: point.sourceTrackId || "",
                color: objectColor(point.objectNo),
                points: []
              };
              segments.push(segment);
            }
            segment.points.push(point);
          });
        });
        segments.forEach((segment) => {
          segment.sourcePointCount = segment.points.length;
          const rawRuns = contiguousRunIds(segment.points, (point) => point.hasRawPosition);
          const correctedRuns = contiguousRunIds(segment.points, (point) => point.hasCorrectedPosition);
          segment.points.forEach((point, index) => {
            point._mapRawRunId = rawRuns[index];
            point._mapCorrectedRunId = correctedRuns[index];
          });
          segment.points = this._boundedTrackPoints(segment.points, 4000);
        });
        this._groups = segments;
      },
      _boundedTrackPoints: function (points, maximum) {
        if (points.length <= maximum) return points;
        const preserved = new Set([0, points.length - 1]);
        const binCount = Math.max(1, Math.floor((maximum - 2) / 10));
        const timeStart = points[0]._timeMs;
        const timeEnd = points[points.length - 1]._timeMs;
        const timeSpan = timeEnd - timeStart;
        const bins = Array.from({ length: binCount }, () => ({ first: -1, last: -1, extrema: new Array(8).fill(-1) }));
        const values = (point) => [
          point.rawLatitude, point.rawLatitude, point.rawLongitude, point.rawLongitude,
          point.correctedLatitude, point.correctedLatitude, point.correctedLongitude, point.correctedLongitude
        ];
        const extremaValues = Array.from({ length: binCount }, () => [Infinity, -Infinity, Infinity, -Infinity, Infinity, -Infinity, Infinity, -Infinity]);
        points.forEach((point, index) => {
          const timeRatio = Number.isFinite(timeSpan) && timeSpan > 0 && Number.isFinite(point._timeMs)
            ? (point._timeMs - timeStart) / timeSpan
            : index / Math.max(1, points.length - 1);
          const binIndex = Math.max(0, Math.min(binCount - 1, Math.floor(timeRatio * binCount)));
          const bin = bins[binIndex];
          if (bin.first < 0) bin.first = index;
          bin.last = index;
          values(point).forEach((value, valueIndex) => {
            if (!isFiniteNumber(value)) return;
            const isMinimum = valueIndex % 2 === 0;
            if ((isMinimum && value < extremaValues[binIndex][valueIndex]) ||
                (!isMinimum && value > extremaValues[binIndex][valueIndex])) {
              extremaValues[binIndex][valueIndex] = value;
              bin.extrema[valueIndex] = index;
            }
          });
        });
        bins.forEach((bin) => {
          if (bin.first >= 0) preserved.add(bin.first);
          if (bin.last >= 0) preserved.add(bin.last);
          bin.extrema.forEach((index) => { if (index >= 0) preserved.add(index); });
        });
        return Array.from(preserved).sort((left, right) => left - right).map((index) => points[index]);
      },
      _scheduleDraw: function (invalidateProjection) {
        if (!this._map) return;
        if (invalidateProjection !== false) this._projectionPlan = null;
        if (this._frame) return;
        this._frame = requestAnimationFrame(() => {
          this._frame = null;
          this._draw();
        });
      },
      _draw: function () {
        if (!this._map || !this._canvas) return;
        const size = this._map.getSize();
        const ratio = Math.min(window.devicePixelRatio || 1, 2);
        const cssWidth = size.x + "px";
        const cssHeight = size.y + "px";
        const backingWidth = Math.max(1, Math.round(size.x * ratio));
        const backingHeight = Math.max(1, Math.round(size.y * ratio));
        if (this._canvas.style.width !== cssWidth) this._canvas.style.width = cssWidth;
        if (this._canvas.style.height !== cssHeight) this._canvas.style.height = cssHeight;
        if (this._canvas.width !== backingWidth) this._canvas.width = backingWidth;
        if (this._canvas.height !== backingHeight) this._canvas.height = backingHeight;
        L.DomUtil.setPosition(this._canvas, this._map.containerPointToLayerPoint([0, 0]));
        const context = this._canvas.getContext("2d");
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        context.clearRect(0, 0, size.x, size.y);
        this._hitGrid = new Map();
        const projectionPlan = this._projectionPlan || (this._projectionPlan = this._buildProjectionPlan());

        const ordinary = [];
        const selected = [];
        const markerSpacing = this._markerSpacing();
        const selectedGlobalObject = this._selectedPoint && this._selectedPoint.globalObjectNo;
        const hasSelection = Boolean(this._selectedTrackKey || this._selectedObjectNo);
        const isSelectedGroup = (group) => group.trackKey === this._selectedTrackKey && group.objectNo === this._selectedObjectNo ||
          Boolean(selectedGlobalObject && group.globalObjectNo === selectedGlobalObject);
        this._groups.forEach((group) => (isSelectedGroup(group) ? selected : ordinary).push(group));
        ordinary.concat(selected).forEach((group) => {
          const isSelected = isSelectedGroup(group);
          const dimmed = hasSelection && !isSelected;
          this._drawTrack(context, group, "raw", group.color, isSelected, dimmed, projectionPlan);
          if (group.sourceType === "RADAR") this._drawTrack(context, group, "corrected", group.color, isSelected, dimmed, projectionPlan);
        });

        const vectorCells = new Set();
        ordinary.forEach((group) => {
          const isSelected = isSelectedGroup(group);
          this._drawVectors(context, group, isSelected, hasSelection && !isSelected, size, vectorCells, markerSpacing, projectionPlan);
        });
        const selectedVectorCells = new Set();
        selected.forEach((group) => this._drawVectors(context, group, true, false, size, selectedVectorCells, markerSpacing, projectionPlan));
        this._drawMatchLink(context, size);

        const markerCells = new Set();
        ordinary.forEach((group) => {
          group.points.forEach((point) => this._drawPoint(context, point, group.color, false, hasSelection, size, markerCells, markerSpacing, projectionPlan));
        });
        const selectedMarkerCells = new Set();
        selected.forEach((group) => {
          group.points.forEach((point) => this._drawPoint(context, point, group.color, true, false, size, selectedMarkerCells, markerSpacing, projectionPlan));
        });
        this._drawSelectedPair(context, size, projectionPlan);
        context.globalAlpha = 1;
      },
      _buildProjectionPlan: function () {
        const raw = new Array(this._points.length);
        const corrected = new Array(this._points.length);
        const rawNeeded = new Set();
        const correctedNeeded = new Set();
        const bounds = this._map.getBounds ? this._map.getBounds().pad(.18) : null;
        const markVisibleWithNeighbors = (group, type, needed) => {
          group.points.forEach((point, index) => {
            const latitude = type === "raw" ? point.rawLatitude : point.correctedLatitude;
            const longitude = type === "raw" ? point.rawLongitude : point.correctedLongitude;
            if (!validCoordinate(latitude, longitude) || (bounds && !bounds.contains([latitude, longitude]))) return;
            needed.add(point._radarRenderIndex);
            if (index > 0) needed.add(group.points[index - 1]._radarRenderIndex);
            if (index + 1 < group.points.length) needed.add(group.points[index + 1]._radarRenderIndex);
          });
        };
        if (bounds) {
          this._groups.forEach((group) => {
            markVisibleWithNeighbors(group, "raw", rawNeeded);
            markVisibleWithNeighbors(group, "corrected", correctedNeeded);
          });
        } else {
          this._groups.forEach((group) => group.points.forEach((point) => {
            rawNeeded.add(point._radarRenderIndex);
            if (point.hasCorrectedPosition) correctedNeeded.add(point._radarRenderIndex);
          }));
        }
        if (this._selectedPoint && Number.isInteger(this._selectedPoint._radarRenderIndex)) {
          rawNeeded.add(this._selectedPoint._radarRenderIndex);
          correctedNeeded.add(this._selectedPoint._radarRenderIndex);
        }
        rawNeeded.forEach((index) => {
          const point = this._points[index];
          if (point && point.hasRawPosition) raw[index] = this._map.latLngToContainerPoint([point.rawLatitude, point.rawLongitude]);
        });
        correctedNeeded.forEach((index) => {
          const point = this._points[index];
          if (point && point.hasCorrectedPosition) corrected[index] = this._map.latLngToContainerPoint([point.correctedLatitude, point.correctedLongitude]);
        });
        return { raw, corrected };
      },
      _drawTrack: function (context, group, type, color, selected, dimmed, projectionPlan) {
        const corrected = type === "corrected";
        const rfTrack = group.sourceType === "RFSCNR";
        context.save();
        context.strokeStyle = color;
        context.globalAlpha = dimmed ? (corrected ? .08 : .14) : (selected ? .9 : (corrected ? .4 : .58));
        context.lineWidth = selected ? (corrected ? 2.6 : 3.1) : (corrected ? 1.4 : 1.9);
        context.lineJoin = "round";
        context.lineCap = "round";
        context.setLineDash(rfTrack ? [2, 6] : (corrected ? [7, 4] : []));
        let active = false;
        let activeRunId = null;
        group.points.forEach((point) => {
          const projected = corrected
            ? projectionPlan.corrected[point._radarRenderIndex]
            : projectionPlan.raw[point._radarRenderIndex];
          const runId = corrected ? point._mapCorrectedRunId : point._mapRawRunId;
          if (!projected || runId == null) {
            if (active) context.stroke();
            active = false;
            activeRunId = null;
            return;
          }
          if (!active || runId !== activeRunId) {
            if (active) context.stroke();
            context.beginPath();
            context.moveTo(projected.x, projected.y);
            active = true;
            activeRunId = runId;
          } else {
            context.lineTo(projected.x, projected.y);
          }
        });
        if (active) context.stroke();
        context.restore();
      },
      _drawVectors: function (context, group, selected, dimmed, size, occupiedCells, spacing, projectionPlan) {
        if (group.sourceType !== "RADAR") return;
        context.save();
        context.setLineDash([3, 3]);
        context.strokeStyle = group.color;
        context.lineWidth = selected ? 1.8 : 1;
        context.globalAlpha = dimmed ? .07 : (selected ? .34 : .18);
        context.beginPath();
        group.points.forEach((point) => {
          const raw = projectionPlan.raw[point._radarRenderIndex];
          const calc = projectionPlan.corrected[point._radarRenderIndex];
          if (!raw || !calc) return;
          if (!this._visible(raw, size) && !this._visible(calc, size)) return;
          const cell = ["V", this._screenCell(raw, spacing)].join("\u0000");
          if (occupiedCells.has(cell)) return;
          occupiedCells.add(cell);
          context.moveTo(raw.x, raw.y);
          context.lineTo(calc.x, calc.y);
        });
        context.stroke();
        context.restore();
      },
      _drawPoint: function (context, point, color, selected, dimmed, size, occupiedCells, spacing, projectionPlan) {
        const raw = projectionPlan.raw[point._radarRenderIndex];
        if (raw) {
          const rfPoint = point.sourceType === "RFSCNR";
          const cell = [rfPoint ? "F" : "R", this._screenCell(raw, spacing)].join("\u0000");
          if (this._visible(raw, size, 14) && !occupiedCells.has(cell)) {
            occupiedCells.add(cell);
            context.save();
            context.globalAlpha = dimmed ? .18 : (selected ? .9 : .82);
            context.fillStyle = color;
            context.strokeStyle = rfPoint ? "#e6a5ff" : (selected ? "rgba(255,255,255,.82)" : "rgba(3,16,21,.82)");
            context.lineWidth = rfPoint ? (selected ? 2.4 : 1.8) : (selected ? 2 : 1.3);
            context.beginPath();
            if (rfPoint) {
              const radius = selected ? 8.2 : 6.4;
              context.moveTo(raw.x, raw.y - radius);
              context.lineTo(raw.x + radius, raw.y);
              context.lineTo(raw.x, raw.y + radius);
              context.lineTo(raw.x - radius, raw.y);
              context.closePath();
            } else {
              context.arc(raw.x, raw.y, selected ? 7.2 : 5.5, 0, Math.PI * 2);
            }
            context.fill();
            context.stroke();
            context.restore();
            this._registerHit({ x: raw.x, y: raw.y, point, kind: rfPoint ? "rf" : "raw", type: rfPoint ? "RF 관측" : "원본 좌표", latitude: point.rawLatitude, longitude: point.rawLongitude });
          }
        }
        const calc = projectionPlan.corrected[point._radarRenderIndex];
        if (calc) {
          const cell = ["C", this._screenCell(calc, spacing)].join("\u0000");
          if (this._visible(calc, size, 14) && !occupiedCells.has(cell)) {
            occupiedCells.add(cell);
            const radius = selected ? 8 : 6.5;
            context.save();
            context.globalAlpha = dimmed ? .18 : (selected ? .92 : .84);
            context.fillStyle = color;
            context.strokeStyle = selected ? "rgba(255,255,255,.72)" : "rgba(3,16,21,.72)";
            context.lineWidth = selected ? 1.7 : 1.1;
            context.beginPath();
            context.moveTo(calc.x, calc.y - radius);
            context.lineTo(calc.x + radius * .9, calc.y + radius * .72);
            context.lineTo(calc.x - radius * .9, calc.y + radius * .72);
            context.closePath();
            context.fill();
            context.stroke();
            context.restore();
            this._registerHit({ x: calc.x, y: calc.y, point, kind: "corrected", type: "보정 좌표", latitude: point.correctedLatitude, longitude: point.correctedLongitude });
          }
        }
      },
      _drawMatchLink: function (context, size) {
        if (!this._matchLinks || !this._selectedPoint || !this._selectedPoint.globalObjectNo) return;
        const match = nearestCrossSourceMatch(this._selectedPoint, this._points);
        if (!match) return;
        const selectedCoordinate = preferredMapCoordinate(this._selectedPoint);
        const matchedCoordinate = preferredMapCoordinate(match.point);
        if (!selectedCoordinate || !matchedCoordinate) return;
        const from = this._map.latLngToContainerPoint(selectedCoordinate);
        const to = this._map.latLngToContainerPoint(matchedCoordinate);
        if (!this._visible(from, size, 40) && !this._visible(to, size, 40)) return;
        context.save();
        context.lineCap = "round";
        context.setLineDash([8, 5]);
        context.strokeStyle = "rgba(255,209,102,.98)";
        context.shadowColor = "rgba(0,0,0,.7)";
        context.shadowBlur = 4;
        context.lineWidth = 3;
        context.beginPath();
        context.moveTo(from.x, from.y);
        context.lineTo(to.x, to.y);
        context.stroke();
        context.setLineDash([]);
        [from, to].forEach((anchor) => {
          context.fillStyle = "#ffd166";
          context.strokeStyle = "#08151b";
          context.lineWidth = 2;
          context.beginPath();
          context.arc(anchor.x, anchor.y, 4.5, 0, Math.PI * 2);
          context.fill();
          context.stroke();
        });
        const label = "GLOBAL Δt " + formatTimeDelta(match.deltaMs);
        const middleX = (from.x + to.x) / 2;
        const middleY = (from.y + to.y) / 2;
        context.font = "600 10px 'Cascadia Code', Consolas, monospace";
        const width = context.measureText(label).width + 12;
        context.fillStyle = "rgba(5,13,17,.94)";
        context.fillRect(middleX - width / 2, middleY - 22, width, 18);
        context.fillStyle = "#ffd166";
        context.fillText(label, middleX - width / 2 + 6, middleY - 9);
        context.restore();
      },
      _drawSelectedPair: function (context, size, projectionPlan) {
        const point = this._selectedPoint;
        if (!point) return;
        const raw = projectionPlan.raw[point._radarRenderIndex];
        const calc = projectionPlan.corrected[point._radarRenderIndex];
        if ((!raw || !this._visible(raw, size, 24)) && (!calc || !this._visible(calc, size, 24))) return;

        context.save();
        context.globalAlpha = 1;
        context.lineCap = "round";
        context.lineJoin = "round";
        if (raw && calc) {
          const dx = calc.x - raw.x;
          const dy = calc.y - raw.y;
          const length = Math.sqrt(dx * dx + dy * dy);
          context.strokeStyle = "rgba(232,241,243,.95)";
          context.fillStyle = "#e8f1f3";
          context.lineWidth = 3;
          context.setLineDash([]);
          context.beginPath();
          context.moveTo(raw.x, raw.y);
          context.lineTo(calc.x, calc.y);
          context.stroke();
          if (length > 9) {
            const unitX = dx / length;
            const unitY = dy / length;
            const tipX = calc.x - unitX * 10;
            const tipY = calc.y - unitY * 10;
            context.beginPath();
            context.moveTo(tipX, tipY);
            context.lineTo(tipX - unitX * 8 + unitY * 5, tipY - unitY * 8 - unitX * 5);
            context.lineTo(tipX - unitX * 8 - unitY * 5, tipY - unitY * 8 + unitX * 5);
            context.closePath();
            context.fill();
          }
        }
        if (raw) {
          const rfPoint = point.sourceType === "RFSCNR";
          context.fillStyle = rfPoint ? "#d98cff" : "#ff645d";
          context.strokeStyle = "#ffffff";
          context.lineWidth = 2.2;
          context.beginPath();
          if (rfPoint) {
            const radius = 10;
            context.moveTo(raw.x, raw.y - radius);
            context.lineTo(raw.x + radius, raw.y);
            context.lineTo(raw.x, raw.y + radius);
            context.lineTo(raw.x - radius, raw.y);
            context.closePath();
          } else {
            context.arc(raw.x, raw.y, 8, 0, Math.PI * 2);
          }
          context.fill();
          context.stroke();
          this._registerHit({ x: raw.x, y: raw.y, point, kind: rfPoint ? "rf" : "raw", type: rfPoint ? "RF 관측" : "원본 좌표", latitude: point.rawLatitude, longitude: point.rawLongitude, priority: 2 });
        }
        if (calc) {
          const radius = 9.5;
          context.fillStyle = "#4b9fff";
          context.strokeStyle = "#ffffff";
          context.lineWidth = 2.2;
          context.beginPath();
          context.moveTo(calc.x, calc.y - radius);
          context.lineTo(calc.x + radius * .9, calc.y + radius * .72);
          context.lineTo(calc.x - radius * .9, calc.y + radius * .72);
          context.closePath();
          context.fill();
          context.stroke();
          this._registerHit({ x: calc.x, y: calc.y, point, kind: "corrected", type: "보정 좌표", latitude: point.correctedLatitude, longitude: point.correctedLongitude, priority: 3 });
        }
        const labelPoint = calc || raw;
        if (labelPoint) {
          const label = point._isRepresentative ? "REP" : (point.sourceType === "RFSCNR" ? "RF " : "PAIR ") + (point._pairOrdinal || "—");
          context.font = "600 11px 'Cascadia Code', Consolas, monospace";
          const width = context.measureText(label).width + 12;
          const labelX = labelPoint.x + 12;
          const labelY = labelPoint.y - 24;
          context.fillStyle = "rgba(5,13,17,.93)";
          context.strokeStyle = "rgba(101,216,255,.68)";
          context.lineWidth = 1;
          context.fillRect(labelX, labelY, width, 21);
          context.strokeRect(labelX, labelY, width, 21);
          context.fillStyle = "#e8f1f3";
          context.fillText(label, labelX + 6, labelY + 14);
        }
        context.restore();
      },
      _markerSpacing: function () {
        const zoom = this._map.getZoom();
        if (zoom <= 8) return 24;
        if (zoom <= 10) return 18;
        if (zoom <= 12) return 13;
        if (zoom <= 14) return 9;
        if (zoom <= 16) return 6;
        return 3;
      },
      _screenCell: function (point, size) {
        return Math.floor(point.x / size) + ":" + Math.floor(point.y / size);
      },
      _registerHit: function (hit) {
        const key = this._screenCell(hit, this._hitCellSize);
        if (!this._hitGrid.has(key)) this._hitGrid.set(key, []);
        this._hitGrid.get(key).push(hit);
      },
      _visible: function (point, size, margin) {
        const edge = margin || 25;
        return point.x >= -edge && point.y >= -edge && point.x <= size.x + edge && point.y <= size.y + edge;
      },
      _onClick: function (event) {
        const target = event.containerPoint;
        let nearest = null;
        let distance = 13 * 13;
        const cellX = Math.floor(target.x / this._hitCellSize);
        const cellY = Math.floor(target.y / this._hitCellSize);
        for (let x = cellX - 1; x <= cellX + 1; x += 1) {
          for (let y = cellY - 1; y <= cellY + 1; y += 1) {
            const hits = this._hitGrid.get(x + ":" + y) || [];
            for (let index = hits.length - 1; index >= 0; index -= 1) {
              const hit = hits[index];
              const squared = (hit.x - target.x) ** 2 + (hit.y - target.y) ** 2;
              if (squared < distance || (squared === distance && (!nearest || (hit.priority || 0) > (nearest.priority || 0)))) {
                distance = squared;
                nearest = hit;
              }
            }
          }
        }
        if (!nearest) return;
        selectObject(nearest.point);
        showSamplePopover(state.selectedPoint || nearest.point, nearest.kind);
      }
    }))();
  }

  function selectObject(point) {
    state.selectedObjectNo = point.objectNo;
    state.selectedPoint = point;
    updateSamplePopover(point);
    revealSelectedPointInTable(point);
    renderAnalysisTable();
    renderDetailShell();
    loadDetail(point.objectNo);
    renderMap(false);
    focusSelectedPair(state.selectedPoint || point);
    if (window.innerWidth < 900) elements.detailPanel.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function selectAdjacentPair(direction) {
    if (!state.selectedPoint) return;
    const exactRangeReady = state.mode !== "RANGE" || state.exactStatus === "ready";
    if (!exactRangeReady) return;
    const rows = state.mode === "RANGE"
      ? state.detailPoints
      : (state.pairTrackIndex.get(state.selectedPoint._trackKey) || []);
    if (!rows.length) return;
    let index = rows.findIndex((point) => sameSample(point, state.selectedPoint));
    if (index < 0) index = Math.max(0, Number(state.selectedPoint._pairOrdinal || 1) - 1);
    const nextIndex = Math.max(0, Math.min(rows.length - 1, index + direction));
    if (nextIndex === index) return;
    selectObject(rows[nextIndex]);
  }

  function revealSelectedPointInTable(point) {
    const rows = getTableRows();
    const index = rows.findIndex((candidate) => sameSample(candidate, point));
    if (index >= 0) {
      state.tablePage = Math.floor(index / TABLE_PAGE_SIZE) + 1;
      requestAnimationFrame(() => {
        const selectedRow = elements.analysisBody.querySelector("tr.is-selected");
        if (selectedRow) selectedRow.scrollIntoView({ block: "nearest", inline: "nearest" });
      });
    }
  }

  function renderPairFocus() {
    const point = state.selectedPoint;
    const wasHidden = elements.pairFocus.hidden;
    elements.pairFocus.hidden = !point;
    if (!point) return;
    if (wasHidden) requestAnimationFrame(() => state.pairPanelMover && state.pairPanelMover.clamp());
    const exactRangeReady = state.mode !== "RANGE" || state.exactStatus === "ready";
    const rows = state.mode === "RANGE" && exactRangeReady
      ? state.detailPoints
      : (state.pairTrackIndex.get(point._trackKey) || []);
    const ordinal = Number(point._pairOrdinal || 1);
    const total = Number(point._pairTotal || rows.length || 1);
    const samplePrefix = point.sourceType === "RFSCNR" ? "RF SAMPLE" : "PAIR";
    elements.pairPosition.textContent = state.mode === "RANGE" && !exactRangeReady
      ? (state.exactStatus === "loading" ? samplePrefix + " · 정밀 조회 중" : samplePrefix + " · 대표점")
      : samplePrefix + " " + ordinal + " / " + total + (state.mode === "RANGE" ? " · EXACT" : "");
    elements.pairObject.textContent = point.globalObjectNo ? "OBJECT " + point.globalObjectNo : "GLOBAL OBJECT 미매칭";
    elements.pairTime.textContent = formatEventTime(point.eventTime, false);
    const crossSource = nearestCrossSourceMatch(point, state.mapPoints);
    if (point.sourceType === "RFSCNR") {
      elements.pairSourceRoute.innerHTML = "<span class=\"pair-rf\">◆ RF 관측</span><i>·</i><span>홈 기준 상대고도</span>";
      elements.pairHorizontal.textContent = crossSource ? "Radar 연결 Δt " + formatTimeDelta(crossSource.deltaMs) : "Radar 연결 없음";
      elements.pairAltitude.textContent = "RF 상대고도 " + formatAltitude(point.rfAltitude) + " · Home MSL " + formatAltitude(point.homeAltitude);
      elements.pairEventId.textContent = "RF " + (point.rfScannerId || "—") + " · TRACK " + (point.trackId || "—") +
        " · EVENT ID " + (point.eventId || "—") + (point.timeSource ? " · " + point.timeSource : "");
    } else {
      elements.pairSourceRoute.innerHTML = "<span class=\"pair-raw\">● 원본</span><i>→</i><span class=\"pair-calc\">▲ 보정</span>";
      elements.pairHorizontal.textContent = "수평 " + formatMeters(point.horizontalCorrectionMeters);
      elements.pairAltitude.textContent = "고도 " + formatSignedMeters(point.altitudeDeltaMeters);
      elements.pairEventId.textContent = "RADAR " + (point.radarId || "—") + " · EVENT ID " + (point.eventId || "—") +
        (point.sourceEventId ? " · SOURCE EVENT " + point.sourceEventId : "") +
        (point.radarObjectNo ? " · TRACK " + point.radarObjectNo : "") +
        (crossSource ? " · RF " + (crossSource.point.rfScannerId || "—") + " Δt " + formatTimeDelta(crossSource.deltaMs) : "");
    }
    elements.pairPrev.disabled = !exactRangeReady || ordinal <= 1;
    elements.pairNext.disabled = !exactRangeReady || ordinal >= total;
  }

  function clearSelection() {
    state.selectedObjectNo = null;
    state.selectedPoint = null;
    state.detailPoints = [];
    if (state.detailController) state.detailController.abort();
    state.detailRequestVersion += 1;
    resetExactTrackState();
    setDetailLoading(false);
    hideSamplePopover();
    renderAnalysisTable();
    renderMap(false);
    renderDetailEmpty();
  }

  function renderDetailShell() {
    const point = state.selectedPoint;
    if (!point) return renderDetailEmpty();
    elements.detailEmpty.hidden = true;
    elements.detailContent.hidden = false;
    elements.selectedObjectBadge.hidden = false;
    elements.selectedObjectBadge.textContent = point.globalObjectNo ? "OBJECT " + point.globalObjectNo : "GLOBAL OBJECT 미매칭";
    const deviceLabel = point.sourceType === "RFSCNR"
      ? [point.rfScannerId, point.trackId].filter(Boolean).join(" / ")
      : [point.radarId, point.radarObjectNo].filter(Boolean).join(" / ");
    elements.selectedRadarBadge.hidden = !deviceLabel;
    elements.selectedRadarBadge.textContent = deviceLabel ? (point.sourceType === "RFSCNR" ? "RF " : "RADAR ") + deviceLabel : "";
    renderDetailResolutionBadge();
    setDetailValues(point);
  }

  function renderDetail() {
    renderDetailShell();
    if (!state.selectedPoint) return;
    const selectedTrackKey = state.selectedPoint && state.selectedPoint._trackKey;
    const chartPoints = state.detailPoints.filter((p) =>
      p.eventTime && (p.rawAltitude != null || p.correctedAltitude != null || p.rfAltitude != null) &&
      (!selectedTrackKey || p._trackKey === selectedTrackKey)
    );
    const requestedTime = state.selectedPoint && state.selectedPoint.eventTime || currentQuery().from;
    const chartResult = state.chart.setData(chartPoints, requestedTime);
    elements.chartEmpty.textContent = state.exactStatus === "loading"
      ? "선택 트랙의 전체 샘플을 정밀 조회하고 있습니다."
      : (state.exactStatus === "error" ? "정밀 조회에 실패해 사용 가능한 대표점만 표시합니다." : "이 구간에는 표시할 고도 데이터가 없습니다.");
    elements.chartEmpty.hidden = chartPoints.length > 0;
    elements.seriesCount.textContent = detailSeriesCount(chartPoints.length, chartResult.displayCount);
    elements.seriesRange.textContent = chartPoints.length
      ? formatEventTime(chartPoints[0].eventTime, true) + "  →  " + formatEventTime(chartPoints[chartPoints.length - 1].eventTime, true)
      : "선택 구간에 고도 데이터 없음";
  }

  function renderDetailEmpty() {
    elements.detailEmpty.hidden = false;
    elements.detailContent.hidden = true;
    elements.selectedObjectBadge.hidden = true;
    elements.selectedRadarBadge.hidden = true;
    elements.detailResolutionBadge.hidden = true;
    const title = elements.detailEmpty.querySelector("strong");
    const description = elements.detailEmpty.querySelector("span");
    if (title) title.textContent = state.hasQueried ? "분석할 오브젝트를 선택하세요." : "조회 버튼을 눌러 데이터를 불러와 주세요.";
    if (description) description.textContent = state.hasQueried
      ? "지도 마커 또는 Analyze 표의 행을 클릭하면 해당 센서 트랙의 고도 변화를 확인합니다."
      : "조회 후 Radar 원본·보정 고도 또는 RF 상대고도를 확인할 수 있습니다.";
    state.chart.setData([], currentQuery().from);
  }

  function setDetailValues(point) {
    const isRf = point.sourceType === "RFSCNR";
    elements.detailObservedTitle.textContent = isRf ? "RF 관측 · 홈 기준 상대고도" : "Radar 원본 좌표";
    elements.detailCalcBlock.hidden = isRf;
    elements.detailDeltaBlock.hidden = false;
    elements.detailDeltaBlock.classList.toggle("is-rf", isRf);
    elements.detailHomeAltRow.hidden = !isRf;
    const homeLabel = elements.detailHomeAltRow.querySelector("dt");
    if (homeLabel) homeLabel.textContent = "홈 고도 (MSL)";
    elements.detailHomeAlt.textContent = formatAltitude(point.homeAltitude);
    const observedAltitudeLabel = elements.detailRawAlt.closest("div").querySelector("dt");
    if (observedAltitudeLabel) observedAltitudeLabel.textContent = isRf ? "홈 기준 상대고도" : "Altitude";
    elements.detailRawLat.textContent = formatCoordinate(point.rawLatitude);
    elements.detailRawLon.textContent = formatCoordinate(point.rawLongitude);
    elements.detailRawAlt.textContent = formatAltitude(point.rawAltitude);
    elements.detailCalcLat.textContent = formatCoordinate(point.correctedLatitude);
    elements.detailCalcLon.textContent = formatCoordinate(point.correctedLongitude);
    elements.detailCalcAlt.textContent = formatAltitude(point.correctedAltitude);
    const deltaTitle = elements.detailDeltaBlock.querySelector(":scope > span");
    const deltaLabels = elements.detailDeltaBlock.querySelectorAll(":scope > div:not(.detail-time) small");
    if (deltaTitle) deltaTitle.textContent = isRf ? "RF 고도 기준" : "선택 시점 변화량";
    if (deltaLabels[0]) deltaLabels[0].textContent = isRf ? "관측 고도 기준" : "수평 이동";
    if (deltaLabels[1]) deltaLabels[1].textContent = isRf ? "홈 고도 (MSL)" : "고도 변화";
    elements.detailHorizontal.textContent = isRf ? (point.altitudeReference || "RELATIVE_TO_HOME") : formatMeters(point.horizontalCorrectionMeters);
    elements.detailAltDelta.textContent = isRf ? formatAltitude(point.homeAltitude) : formatSignedMeters(point.altitudeDeltaMeters);
    elements.detailEventTime.textContent = formatEventTime(point.eventTime, true);
  }

  function setDetailLoading(loading) {
    elements.detailPanel.setAttribute("aria-busy", String(loading));
    if (loading) elements.seriesCount.textContent = "정밀 조회 중…";
    renderPairFocus();
    renderDetailResolutionBadge();
  }

  function renderDetailResolutionBadge() {
    if (!elements.detailResolutionBadge || !state.selectedPoint || state.mode !== "RANGE") {
      if (elements.detailResolutionBadge) elements.detailResolutionBadge.hidden = true;
      return;
    }
    const labels = {
      loading: "정밀 조회 중",
      ready: "EXACT · " + formatInteger(state.detailPoints.length) + "행",
      error: "대표점 · 정밀 실패",
      idle: state.sampling.applied ? "대표점" : "EXACT"
    };
    elements.detailResolutionBadge.hidden = false;
    elements.detailResolutionBadge.textContent = labels[state.exactStatus] || labels.idle;
    elements.detailResolutionBadge.className = "resolution-badge resolution-" + state.exactStatus;
    elements.detailResolutionBadge.title = state.exactStatus === "error" ? state.exactError :
      (state.exactStatus === "ready" ? "선택 트랙의 전체 샘플을 불러왔습니다." : "선택 트랙의 전체 샘플을 조회합니다.");
  }

  function detailSeriesCount(count, displayCount) {
    const displayLabel = displayCount < count ? " · 차트 표시 " + formatInteger(displayCount) : "";
    if (state.mode !== "RANGE") return formatInteger(count) + " samples" + displayLabel;
    if (state.exactStatus === "loading") return formatInteger(count) + " 대표점 · 정밀 조회 중" + displayLabel;
    if (state.exactStatus === "error") return formatInteger(count) + " 대표점 · 정밀 조회 실패" + displayLabel;
    if (state.exactStatus === "ready") return formatInteger(count) + " 전체 샘플 · EXACT" + displayLabel;
    return formatInteger(count) + (state.sampling.applied ? " 대표점" : " 전체 샘플") + displayLabel;
  }

  function sortTable(key) {
    if (state.sortKey === key) state.sortDirection = state.sortDirection === "asc" ? "desc" : "asc";
    else {
      state.sortKey = key;
      state.sortDirection = "asc";
    }
    state.tablePage = 1;
    invalidateTableCache();
    updateSortHeaders();
    renderAnalysisTable();
  }

  function updateSortHeaders() {
    elements.analysisTable.querySelectorAll("th[data-sort]").forEach((header) => {
      const active = header.dataset.sort === state.sortKey;
      if (active) header.setAttribute("aria-sort", state.sortDirection === "asc" ? "ascending" : "descending");
      else header.removeAttribute("aria-sort");
      header.querySelector("span").textContent = active ? (state.sortDirection === "asc" ? "↑" : "↓") : "↕";
    });
  }

  function comparePoints(key, direction) {
    const factor = direction === "asc" ? 1 : -1;
    return (a, b) => {
      let left = tableValue(a, key);
      let right = tableValue(b, key);
      if (left == null && right == null) return 0;
      if (left == null) return 1;
      if (right == null) return -1;
      if (typeof left === "number" && typeof right === "number") return (left - right) * factor;
      return TABLE_COLLATOR.compare(String(left), String(right)) * factor;
    };
  }

  function tableValue(point, key) {
    if (key === "eventTime") return point._timeMs;
    if (key === "primaryFlag" && !point.primaryFlag) return null;
    if (key === "referenceAltitude" && point.sourceType === "RFSCNR") return point.homeAltitude;
    return point[key];
  }

  function currentQuery() {
    const from = parseTimeInput(elements.fromInput.value);
    const to = parseTimeInput(elements.toInput.value);
    const sources = [];
    if (elements.sourceRadar.checked && !elements.sourceRadar.disabled) sources.push("RADAR");
    if (elements.sourceRf.checked && !elements.sourceRf.disabled) sources.push("RFSCNR");
    return {
      from,
      to,
      mode: from && to && from !== to ? "RANGE" : "SNAPSHOT",
      sources,
      radarIds: effectiveDeviceSelection(state.radars, state.selectedRadarIds),
      rfScannerIds: effectiveDeviceSelection(state.rfScanners, state.selectedRfScannerIds),
      objectNo: elements.objectInput.value.trim(),
      matchMode: sources.includes("RFSCNR") ? elements.matchModeInput.value : "ALL",
      primaryOnly: elements.primaryInput.checked
    };
  }

  function effectiveDeviceSelection(rows, selected) {
    if (!selected.size) return [];
    if (rows.length && selected.size >= rows.length && rows.every((row) => selected.has(row.id))) return [];
    return Array.from(selected);
  }

  function observationSearchParams(query, overview) {
    const params = toSearchParams({
      from: query.from,
      to: query.to,
      objectNo: query.objectNo,
      matchMode: query.matchMode,
      primaryOnly: query.primaryOnly,
      overview
    });
    query.sources.forEach((source) => params.append("source", source));
    query.radarIds.forEach((id) => params.append("radarId", id));
    query.rfScannerIds.forEach((id) => params.append("rfScannerId", id));
    return params;
  }

  function updateSourceControls(changedSource) {
    const radar = elements.sourceRadar.checked && !elements.sourceRadar.disabled;
    const rf = elements.sourceRf.checked && !elements.sourceRf.disabled;
    if (!radar && !rf && changedSource) {
      const fallback = changedSource === "RADAR" ? elements.sourceRadar : elements.sourceRf;
      if (!fallback.disabled) fallback.checked = true;
      showToast("데이터 소스는 하나 이상 선택해야 합니다.", true);
      return updateSourceControls();
    }
    elements.radarPickerField.classList.toggle("is-disabled", !radar);
    elements.rfPickerField.classList.toggle("is-disabled", !rf);
    if (!radar) elements.radarPicker.open = false;
    if (!rf) elements.rfPicker.open = false;
    elements.primaryInput.disabled = !radar || Boolean(state.meta && !state.meta.capabilities.primaryFlag);
    const objectMatchUnavailable = Boolean(state.meta && !state.meta.rfScannerCapabilities.objectMatch);
    elements.matchModeInput.disabled = !rf || objectMatchUnavailable;
    elements.matchLinksInput.disabled = !radar || !rf || objectMatchUnavailable;
    if (changedSource && state.hasQueried) {
      void loadTracks({ preserveSelection: false, fitMap: true, refreshSensors: false });
    }
  }

  function setDefaultRange(date) {
    const eventTime = dateToEventTime(date);
    setRangeInputs(eventTime, eventTime);
  }

  function setRangeInputs(from, to) {
    const normalizedFrom = eventTimeString(from);
    const normalizedTo = eventTimeString(to);
    if (/^\d{17}$/.test(normalizedFrom)) elements.fromInput.value = normalizedFrom;
    if (/^\d{17}$/.test(normalizedTo)) elements.toInput.value = normalizedTo;
  }

  function parseTimeInput(value) {
    const digits = String(value || "").replace(/\D/g, "");
    if (![8, 10, 12, 14, 17].includes(digits.length)) return "";
    const normalized = digits.padEnd(17, "0");
    const year = Number(normalized.slice(0, 4));
    const month = Number(normalized.slice(4, 6));
    const day = Number(normalized.slice(6, 8));
    const hour = Number(normalized.slice(8, 10));
    const minute = Number(normalized.slice(10, 12));
    const second = Number(normalized.slice(12, 14));
    const millisecond = Number(normalized.slice(14, 17));
    const date = new Date(year, month - 1, day, hour, minute, second, millisecond);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day ||
        date.getHours() !== hour || date.getMinutes() !== minute || date.getSeconds() !== second || date.getMilliseconds() !== millisecond) return "";
    return normalized;
  }

  function eventTimeString(value) {
    if (value == null) return "";
    if (value instanceof Date && !isNaN(value)) return dateToEventTime(value);
    const string = String(value).trim();
    if (/^\d{17}$/.test(string)) return string;
    if (/^\d{14}$/.test(string)) return string + "000";
    if (/^\d{8,17}$/.test(string)) return string.padEnd(17, "0").slice(0, 17);
    const parsed = new Date(string);
    return isNaN(parsed) ? string : dateToEventTime(parsed);
  }

  function dateToEventTime(date) {
    const pad = (value, size) => String(value).padStart(size, "0");
    return date.getFullYear() + pad(date.getMonth() + 1, 2) + pad(date.getDate(), 2) + pad(date.getHours(), 2) +
      pad(date.getMinutes(), 2) + pad(date.getSeconds(), 2) + pad(date.getMilliseconds(), 3);
  }

  function timeValue(eventTime) {
    const digits = eventTimeString(eventTime);
    if (!/^\d{17}$/.test(digits)) return Number.NaN;
    return new Date(
      Number(digits.slice(0, 4)), Number(digits.slice(4, 6)) - 1, Number(digits.slice(6, 8)),
      Number(digits.slice(8, 10)), Number(digits.slice(10, 12)), Number(digits.slice(12, 14)),
      Number(digits.slice(14, 17))
    ).getTime();
  }

  function formatEventTime(value, includeDate) {
    const digits = eventTimeString(value);
    if (!/^\d{17}$/.test(digits)) return value ? String(value) : "—";
    const time = digits.slice(8, 10) + ":" + digits.slice(10, 12) + ":" + digits.slice(12, 14) + "." + digits.slice(14, 17);
    return includeDate ? digits.slice(0, 4) + "-" + digits.slice(4, 6) + "-" + digits.slice(6, 8) + " " + time : time;
  }

  function formatTimeRangeLabel(label, range) {
    if (!range || (!range.min && !range.max)) return "";
    return label + " " + (range.min ? formatEventTime(range.min, true) : "—") + " → " +
      (range.max ? formatEventTime(range.max, true) : "—");
  }

  function sourceRowCount() {
    return finiteNumber(state.sampling && state.sampling.sourceRows) ?? state.sourceSummary.sourceRows ?? state.points.length;
  }

  function returnedRowCount() {
    return finiteNumber(state.sampling && state.sampling.returnedRows) ?? state.points.length;
  }

  function formatQueryResult(snapshot) {
    if (!snapshot.points.length) return "조회는 완료됐지만 선택한 범위에 표시 가능한 Radar/RF 좌표가 없습니다. 시간·소스·장비·오브젝트 필터를 확인해 주세요.";
    const corrected = snapshot.summary.correctedPositionCount || 0;
    const raw = snapshot.summary.rawPositionCount || 0;
    const sources = snapshot.sourceSummary || {};
    if (snapshot.sampling && snapshot.sampling.applied) {
      const metricsFullRange = String(snapshot.sampling.metricsScope || "").toUpperCase() === "FULL_RANGE";
      return formatInteger(sources.objectCount || 0) + "개 표시 객체 · DB 전체 " + formatInteger(snapshot.sampling.sourceRows) +
        "행 중 대표점 " + formatInteger(snapshot.sampling.returnedRows) + "개를 표시했습니다. " +
        "Radar 원본 " + formatInteger(raw) + "건 · 보정 " + formatInteger(corrected) + "건은 " +
        (metricsFullRange ? "전체 행" : "대표점") + " 기준이며, 트랙 선택 시 전체 샘플을 정밀 조회합니다.";
    }
    if (snapshot.sampling && snapshot.sampling.snapshotCollapsed) {
      return "±" + formatInteger(snapshot.toleranceMs) + " ms 후보 " + formatInteger(snapshot.sampling.sourceRows) + "행에서 트랙별 최근접 " +
        formatInteger(snapshot.sampling.returnedRows) + "개를 표시했습니다.";
    }
    return formatInteger(sources.objectCount || 0) + "개 표시 객체 · " +
      formatInteger(snapshot.sampling && snapshot.sampling.returnedRows || snapshot.points.length) + "개 샘플 · Radar 원본 " +
      formatInteger(raw) + "건 · 보정 " + formatInteger(corrected) + "건 · RF " + formatInteger(sources.returnedRfScannerRows || 0) + "건을 표시했습니다.";
  }

  function normalizeCorrectionStatus(status, hasPosition, correctedAltitude) {
    const normalized = String(status || "").trim().toUpperCase();
    if (normalized) return normalized;
    if (hasPosition && correctedAltitude != null) return "COMPLETE";
    if (hasPosition || correctedAltitude != null) return "PARTIAL";
    return "UNCORRECTED";
  }

  function statusTag(point) {
    const status = String(point.correctionStatus || "UNCORRECTED").toUpperCase();
    let label = status;
    let className = "status-none";
    if (point.sourceType === "RFSCNR" || status === "RF_OBSERVED") {
      label = "RF 관측";
      className = "status-rf";
    } else if (["COMPLETE", "CORRECTED", "CALCULATED", "OK"].some((token) => status.includes(token))) {
      label = "보정 완료";
      className = "status-complete";
    } else if (status.includes("PARTIAL")) {
      label = "일부 보정";
      className = "status-partial";
    } else if (status.includes("ALTITUDE_ONLY")) {
      label = "고도만 보정";
      className = "status-partial";
    } else if (status.includes("NO_RAW")) {
      label = "원본 좌표 없음";
      className = "status-partial";
    } else if (status.includes("RAW_ONLY")) {
      label = "원본만";
    } else if (["UNCORRECTED", "NOT_CALCULATED", "MISSING", "NONE"].some((token) => status.includes(token))) {
      label = "미보정";
    }
    return "<span class=\"status-tag " + className + "\">" + escapeHtml(label) + "</span>";
  }

  function normalizePrimaryFlag(value) {
    const normalized = textValue(value).trim().toUpperCase();
    if (["Y", "YES", "TRUE", "1"].includes(normalized)) return "Y";
    if (["N", "NO", "FALSE", "0"].includes(normalized)) return "N";
    return normalized;
  }

  function primaryTag(value) {
    if (!value) return "<span class=\"missing\">—</span>";
    const className = value === "Y" ? "primary-yes" : (value === "N" ? "primary-no" : "primary-other");
    const meaning = value === "Y" ? "대표 관측" : (value === "N" ? "비대표 관측" : "알 수 없는 값");
    return "<span class=\"primary-tag " + className + "\" title=\"" + meaning +
      " · 보정 적용 여부와는 별개\">" + escapeHtml(value) + "</span>";
  }

  function sourceTag(sourceType) {
    const rf = sourceType === "RFSCNR";
    return "<span class=\"source-tag " + (rf ? "source-rf" : "source-radar") + "\">" + (rf ? "RF" : "RADAR") + "</span>";
  }

  function matchTag(point) {
    if (point.sourceType !== "RFSCNR") {
      return point.globalObjectNo ? "<span class=\"match-tag match-linked\">GLOBAL</span>" : "<span class=\"missing\">—</span>";
    }
    return point.matched
      ? "<span class=\"match-tag match-linked\">MATCHED</span>"
      : "<span class=\"match-tag match-unlinked\">UNMATCHED</span>";
  }

  function sampleKey(point) {
    if (!point) return "";
    if (point.eventId) return point.sourceType + "\u0000E:" + point.eventId + "\u0000R:" + (point.sourceEventId || "");
    return ["F", point.sourceType, point.objectNo, point.deviceId, point.sourceTrackId, point.eventTime, point.sourceEventId, point._sourceIndex]
      .map((value) => String(value || ""))
      .join("\u0000");
  }

  function sameSample(left, right) {
    return Boolean(left && right && (left === right ||
      left._trackKey === right._trackKey && left._pairKey && right._pairKey && left._pairKey === right._pairKey));
  }

  function nearestPointForObject(objectNo, at) {
    const target = timeValue(at);
    const points = state.objectIndex.get(objectNo) || [];
    if (!points.length) return null;
    if (!Number.isFinite(target)) return points[0];
    let low = 0;
    let high = points.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (points[middle]._timeMs < target) low = middle + 1;
      else high = middle;
    }
    if (low <= 0) return points[0];
    if (low >= points.length) return points[points.length - 1];
    return target - points[low - 1]._timeMs <= points[low]._timeMs - target ? points[low - 1] : points[low];
  }

  function nearestPointForSelection(previous, fallbackTime) {
    if (!previous) return null;
    const exact = state.points.find((point) => sameSample(point, previous));
    if (exact) return exact;
    return nearestPointForObject(previous.objectNo, previous.eventTime || fallbackTime);
  }

  function objectColor(objectNo) {
    const cacheKey = String(objectNo || "UNKNOWN");
    if (OBJECT_COLOR_CACHE.has(cacheKey)) return OBJECT_COLOR_CACHE.get(cacheKey);
    let hash = 2166136261;
    const value = cacheKey;
    for (let index = 0; index < value.length; index += 1) {
      hash ^= value.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    const hue = Math.abs(hash) % 360;
    const saturation = 72 + Math.abs(hash >>> 8) % 18;
    const lightness = 58 + Math.abs(hash >>> 16) % 10;
    const color = "hsl(" + hue + " " + saturation + "% " + lightness + "%)";
    OBJECT_COLOR_CACHE.set(cacheKey, color);
    return color;
  }

  function subIdentifier(point) {
    const track = point.sourceTrackId ? "TRACK " + point.sourceTrackId : "";
    const pair = point._isRepresentative ? "대표점" : (point._pairOrdinal ? "PAIR " + point._pairOrdinal + "/" + point._pairTotal : "");
    const secondary = [track, pair].filter(Boolean).join(" · ");
    return secondary ? "<small class=\"device-sub-id\">" + escapeHtml(secondary) + "</small>" : "";
  }

  function cell(value) {
    return "<td" + (value === "—" ? " class=\"missing\"" : "") + ">" + escapeHtml(value) + "</td>";
  }

  function numberCell(value, extraClass) {
    const classes = ["number-cell", extraClass || "", value === "—" ? "missing" : ""].filter(Boolean).join(" ");
    return "<td class=\"" + classes + "\">" + escapeHtml(value) + "</td>";
  }

  function formatCoordinate(value) { return isFiniteNumber(value) ? value.toFixed(8) : "—"; }
  function formatAltitude(value) { return isFiniteNumber(value) ? formatNumber(value, 2) + " m" : "—"; }
  function formatMeters(value) { return isFiniteNumber(value) ? formatNumber(value, Math.abs(value) < 10 ? 2 : 1) + " m" : "—"; }
  function formatSignedMeters(value) { return isFiniteNumber(value) ? (value > 0 ? "+" : "") + formatNumber(value, 2) + " m" : "—"; }
  function formatInteger(value) { return isFiniteNumber(value) ? INTEGER_FORMATTER.format(value) : "—"; }
  function formatNumber(value, digits) {
    if (!NUMBER_FORMATTERS.has(digits)) {
      NUMBER_FORMATTERS.set(digits, new Intl.NumberFormat("ko-KR", { minimumFractionDigits: digits, maximumFractionDigits: digits }));
    }
    return NUMBER_FORMATTERS.get(digits).format(value);
  }

  function setLoading(loading) {
    elements.loadButton.disabled = loading;
    elements.loadButton.classList.toggle("is-loading", loading);
    elements.loadButton.querySelector("span:last-child").textContent = loading ? "조회 중" : "범위 불러오기";
    elements.queryForm.setAttribute("aria-busy", String(loading));
    elements.sourceRadar.disabled = loading || Boolean(state.meta && !state.meta.capabilities.radarReady && !state.meta.capabilities.radarEvents);
    elements.sourceRf.disabled = loading || Boolean(state.meta && !state.meta.rfScannerCapabilities.ready);
    elements.radarPicker.classList.toggle("is-loading", loading);
    elements.rfPicker.classList.toggle("is-loading", loading);
    if (!loading) updateSourceControls();
  }

  function setQueryMessage(message, error, warning) {
    elements.queryMessage.textContent = message || "";
    elements.queryMessage.classList.toggle("error", Boolean(error));
    elements.queryMessage.classList.toggle("warning", Boolean(warning));
  }

  function setStatusPill(element, message, stateName) {
    element.className = "status-pill status-" + stateName;
    element.innerHTML = "<span class=\"status-dot\" aria-hidden=\"true\"></span><span>" + escapeHtml(message) + "</span>";
  }

  function showToast(message, error) {
    clearTimeout(state.toastTimer);
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", Boolean(error));
    elements.toast.hidden = false;
    state.toastTimer = setTimeout(() => { elements.toast.hidden = true; }, 5500);
  }

  async function fetchJson(url, options) {
    const requestOptions = Object.assign({ cache: "no-store" }, options || {});
    requestOptions.headers = Object.assign({ Accept: "application/json" }, (options && options.headers) || {});
    const response = await fetch(url, requestOptions);
    let body = null;
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("json")) body = await response.json();
    else {
      const text = await response.text();
      body = text ? { message: text } : {};
    }
    if (!response.ok) {
      const error = new Error(body.message || body.error || "HTTP " + response.status);
      error.status = response.status;
      error.code = body.code;
      throw error;
    }
    return body;
  }

  function describeError(error, fallback) {
    if (!error) return fallback;
    if (error.status === 503) return "DB에 연결할 수 없습니다. SSH 터널과 접속 설정을 확인해 주세요.";
    if (error.code === "TIME_RANGE_TOO_LARGE" || /requested range must not exceed/i.test(error.message || "")) {
      const seconds = Number((String(error.message || "").match(/(\d+)\s*seconds?/i) || [])[1]);
      const limit = Number.isFinite(seconds) && seconds > 0 ? formatDuration(seconds) : "설정된 시간";
      return "현재 실행 중인 서버는 한 번에 최대 " + limit + "까지만 조회할 수 있습니다. 서버 설정을 확인하거나 범위를 줄여 주세요.";
    }
    if (error.code === "QUERY_ROW_LIMIT_EXCEEDED" || error.status === 422) {
      return "대용량 범위 개요를 생성하지 못했습니다. 대표점 조회를 지원하는 최신 서버인지 확인해 주세요.";
    }
    if (error.code === "INVALID_TIME_RANGE" || /from must be earlier than or equal to to/i.test(error.message || "")) {
      return "From은 To보다 늦을 수 없습니다. 조회 시각을 확인해 주세요.";
    }
    if (error.status === 400) return "조회 조건이 올바르지 않습니다. 시간 형식과 필터 값을 확인해 주세요.";
    return error.message ? fallback + " (" + error.message + ")" : fallback;
  }

  function formatDuration(seconds) {
    if (seconds % 3600 === 0) return formatInteger(seconds / 3600) + "시간";
    if (seconds % 60 === 0) return formatInteger(seconds / 60) + "분";
    return formatInteger(seconds) + "초";
  }

  function unwrap(value) {
    let source = value || {};
    for (let count = 0; count < 3; count += 1) {
      if (source && !Array.isArray(source) && typeof source === "object") {
        const keys = Object.keys(source);
        if (keys.length === 1 && ["data", "result", "body", "response"].includes(keys[0])) source = source[keys[0]];
        else break;
      } else break;
    }
    return source || {};
  }

  function pick(object, keys) {
    if (!object || typeof object !== "object") return undefined;
    for (const key of keys) {
      if (Object.prototype.hasOwnProperty.call(object, key) && object[key] !== undefined) return object[key];
    }
    const lookup = new Map(Object.keys(object).map((key) => [key.replace(/_/g, "").toLowerCase(), key]));
    for (const key of keys) {
      const match = lookup.get(key.replace(/_/g, "").toLowerCase());
      if (match && object[match] !== undefined) return object[match];
    }
    return undefined;
  }

  function firstNumber(primary, primaryKeys, secondary, secondaryKeys) {
    const first = finiteNumber(pick(primary, primaryKeys));
    return first != null ? first : finiteNumber(pick(secondary, secondaryKeys));
  }

  function finiteNumber(value) {
    if (value == null || value === "") return null;
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function booleanValue(value) {
    if (typeof value === "boolean") return value;
    if (typeof value === "number") return value !== 0;
    return ["TRUE", "Y", "YES", "1", "AVAILABLE", "SUPPORTED"].includes(String(value || "").trim().toUpperCase());
  }

  function textValue(value) { return value == null ? "" : String(value); }
  function arrayValue(value) { return Array.isArray(value) ? value : null; }
  function isFiniteNumber(value) { return typeof value === "number" && Number.isFinite(value); }
  function validCoordinate(latitude, longitude) {
    return isFiniteNumber(latitude) && isFiniteNumber(longitude) && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
  }

  function contiguousRunIds(rows, isValid) {
    let runId = 0;
    let active = false;
    return rows.map((row) => {
      if (!isValid(row)) {
        active = false;
        return null;
      }
      if (!active) {
        runId += 1;
        active = true;
      }
      return runId;
    });
  }

  function haversineMeters(lat1, lon1, lat2, lon2) {
    const radians = (degrees) => degrees * Math.PI / 180;
    const deltaLat = radians(lat2 - lat1);
    const deltaLon = radians(lon2 - lon1);
    const a = Math.sin(deltaLat / 2) ** 2 + Math.cos(radians(lat1)) * Math.cos(radians(lat2)) * Math.sin(deltaLon / 2) ** 2;
    return 6371008.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  function average(values) { return values.length ? values.reduce((total, value) => total + value, 0) / values.length : null; }
  function maximum(values) { return values.length ? Math.max.apply(null, values) : null; }
  function percentile(values, ratio) {
    if (!values.length) return null;
    const sorted = values.slice().sort((a, b) => a - b);
    return sorted[Math.max(0, Math.ceil(ratio * sorted.length) - 1)];
  }

  function toSearchParams(values) {
    const params = new URLSearchParams();
    Object.entries(values).forEach(([key, value]) => {
      if (value !== "" && value != null) params.set(key, String(value));
    });
    return params;
  }

  function toCamel(value) { return value.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()); }

  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/[&<>'"]/g, (character) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", "\"": "&quot;"
    })[character]);
  }

  function createAltitudeChart(canvas, tooltip) {
    const context = canvas.getContext("2d");
    let sourceData = [];
    let data = [];
    let currentTime = Number.NaN;
    let geometry = null;
    let hoverIndex = -1;
    let dataStats = null;
    let seriesRunIds = new WeakMap();
    let pointerFrame = null;
    let pendingPointer = null;

    const colors = {
      grid: "rgba(72, 102, 113, .24)",
      axis: "#60757f",
      text: "#81969f",
      raw: "#ff645d",
      calc: "#4b9fff",
      rf: "#e6a5ff",
      cursor: "#57edb5",
      hover: "rgba(232, 241, 243, .28)"
    };

    function setData(points, requestedAt) {
      sourceData = points || [];
      currentTime = timeValue(requestedAt);
      const runKeys = ["rawAltitude", "correctedAltitude", "rfAltitude"];
      const runs = {};
      runKeys.forEach((key) => {
        runs[key] = contiguousRunIds(sourceData, (point) => isFiniteNumber(seriesValue(point, key)));
      });
      seriesRunIds = new WeakMap();
      sourceData.forEach((point, index) => {
        seriesRunIds.set(point, {
          rawAltitude: runs.rawAltitude[index],
          correctedAltitude: runs.correctedAltitude[index],
          rfAltitude: runs.rfAltitude[index]
        });
      });
      const chartWidth = Math.max(320, canvas.getBoundingClientRect().width || 800);
      data = downsampleAltitudePoints(sourceData, currentTime, chartWidth);
      hoverIndex = -1;
      tooltip.hidden = true;
      let minAltitude = Infinity;
      let maxAltitude = -Infinity;
      let minTime = Infinity;
      let maxTime = -Infinity;
      let rawCount = 0;
      let correctedCount = 0;
      let rfCount = 0;
      let firstRaw = null;
      let firstCalc = null;
      let firstRf = null;
      data.forEach((point) => {
        if (Number.isFinite(point._timeMs)) {
          minTime = Math.min(minTime, point._timeMs);
          maxTime = Math.max(maxTime, point._timeMs);
        }
        const rawAltitude = seriesValue(point, "rawAltitude");
        const correctedAltitude = seriesValue(point, "correctedAltitude");
        const rfAltitude = seriesValue(point, "rfAltitude");
        if (isFiniteNumber(rawAltitude)) {
          minAltitude = Math.min(minAltitude, rawAltitude);
          maxAltitude = Math.max(maxAltitude, rawAltitude);
          rawCount += 1;
          if (!firstRaw) firstRaw = point;
        }
        if (isFiniteNumber(correctedAltitude)) {
          minAltitude = Math.min(minAltitude, correctedAltitude);
          maxAltitude = Math.max(maxAltitude, correctedAltitude);
          correctedCount += 1;
          if (!firstCalc) firstCalc = point;
        }
        if (isFiniteNumber(rfAltitude)) {
          minAltitude = Math.min(minAltitude, rfAltitude);
          maxAltitude = Math.max(maxAltitude, rfAltitude);
          rfCount += 1;
          if (!firstRf) firstRf = point;
        }
      });
      dataStats = Number.isFinite(minAltitude) && Number.isFinite(minTime)
        ? { minAltitude, maxAltitude, minTime, maxTime, rawCount, correctedCount, rfCount, firstRaw, firstCalc, firstRf }
        : null;
      draw();
      return { sourceCount: sourceData.length, displayCount: data.length };
    }

    function downsampleAltitudePoints(points, selectedTime, pixelWidth) {
      const maximum = Math.max(512, Math.min(3000, Math.ceil(pixelWidth * 3)));
      if (points.length <= maximum) return points.slice();
      const binCount = Math.max(1, Math.floor((maximum - 3) / 8));
      const firstTime = points[0]._timeMs;
      const lastTime = points[points.length - 1]._timeMs;
      const timeSpan = lastTime - firstTime;
      const bins = Array.from({ length: binCount }, () => ({
        first: -1,
        last: -1,
        minimum: { rawAltitude: Infinity, correctedAltitude: Infinity, rfAltitude: Infinity },
        maximum: { rawAltitude: -Infinity, correctedAltitude: -Infinity, rfAltitude: -Infinity },
        minimumIndex: { rawAltitude: -1, correctedAltitude: -1, rfAltitude: -1 },
        maximumIndex: { rawAltitude: -1, correctedAltitude: -1, rfAltitude: -1 }
      }));
      points.forEach((point, index) => {
        const ratio = Number.isFinite(timeSpan) && timeSpan > 0 && Number.isFinite(point._timeMs)
          ? (point._timeMs - firstTime) / timeSpan
          : index / Math.max(1, points.length - 1);
        const bin = bins[Math.max(0, Math.min(binCount - 1, Math.floor(ratio * binCount)))];
        if (bin.first < 0) bin.first = index;
        bin.last = index;
        ["rawAltitude", "correctedAltitude", "rfAltitude"].forEach((key) => {
          const value = seriesValue(point, key);
          if (!isFiniteNumber(value)) return;
          if (value < bin.minimum[key]) {
            bin.minimum[key] = value;
            bin.minimumIndex[key] = index;
          }
          if (value > bin.maximum[key]) {
            bin.maximum[key] = value;
            bin.maximumIndex[key] = index;
          }
        });
      });
      const preserved = new Set([0, points.length - 1]);
      bins.forEach((bin) => {
        if (bin.first >= 0) preserved.add(bin.first);
        if (bin.last >= 0) preserved.add(bin.last);
        ["rawAltitude", "correctedAltitude", "rfAltitude"].forEach((key) => {
          if (bin.minimumIndex[key] >= 0) preserved.add(bin.minimumIndex[key]);
          if (bin.maximumIndex[key] >= 0) preserved.add(bin.maximumIndex[key]);
        });
      });
      if (Number.isFinite(selectedTime)) {
        let low = 0;
        let high = points.length;
        while (low < high) {
          const middle = (low + high) >>> 1;
          if (points[middle]._timeMs < selectedTime) low = middle + 1;
          else high = middle;
        }
        const nearest = low <= 0 ? 0 : (low >= points.length ? points.length - 1 :
          (selectedTime - points[low - 1]._timeMs <= points[low]._timeMs - selectedTime ? low - 1 : low));
        preserved.add(nearest);
      }
      return Array.from(preserved).sort((left, right) => left - right).map((index) => points[index]);
    }

    function draw() {
      const rect = canvas.getBoundingClientRect();
      const width = Math.max(1, rect.width);
      const height = Math.max(1, rect.height);
      const ratio = Math.min(window.devicePixelRatio || 1, 2);
      if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
      }
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      context.clearRect(0, 0, width, height);

      const padding = { top: 15, right: 18, bottom: 29, left: 58 };
      const plotWidth = Math.max(1, width - padding.left - padding.right);
      const plotHeight = Math.max(1, height - padding.top - padding.bottom);
      if (!dataStats) {
        geometry = null;
        return;
      }

      let minAltitude = dataStats.minAltitude;
      let maxAltitude = dataStats.maxAltitude;
      const altitudePadding = Math.max((maxAltitude - minAltitude) * .12, 1);
      minAltitude -= altitudePadding;
      maxAltitude += altitudePadding;
      let minTime = dataStats.minTime;
      let maxTime = dataStats.maxTime;
      if (minTime === maxTime) { minTime -= 500; maxTime += 500; }
      geometry = { width, height, padding, plotWidth, plotHeight, minAltitude, maxAltitude, minTime, maxTime };
      const x = (time) => padding.left + (time - minTime) / (maxTime - minTime) * plotWidth;
      const y = (altitude) => padding.top + (maxAltitude - altitude) / (maxAltitude - minAltitude) * plotHeight;

      context.lineWidth = 1;
      context.font = "9px Cascadia Code, Consolas, monospace";
      context.textBaseline = "middle";
      for (let index = 0; index <= 4; index += 1) {
        const lineY = padding.top + plotHeight * index / 4;
        const altitude = maxAltitude - (maxAltitude - minAltitude) * index / 4;
        context.strokeStyle = colors.grid;
        context.beginPath(); context.moveTo(padding.left, lineY); context.lineTo(width - padding.right, lineY); context.stroke();
        context.fillStyle = colors.text;
        context.textAlign = "right";
        context.fillText(formatNumber(altitude, Math.abs(maxAltitude - minAltitude) < 10 ? 1 : 0) + " m", padding.left - 8, lineY);
      }
      for (let index = 0; index <= 4; index += 1) {
        const tickTime = minTime + (maxTime - minTime) * index / 4;
        const lineX = padding.left + plotWidth * index / 4;
        context.strokeStyle = colors.grid;
        context.beginPath(); context.moveTo(lineX, padding.top); context.lineTo(lineX, height - padding.bottom); context.stroke();
        context.fillStyle = colors.text;
        context.textAlign = index === 0 ? "left" : (index === 4 ? "right" : "center");
        context.fillText(formatClock(tickTime), lineX, height - 12);
      }

      drawSeries("rawAltitude", colors.raw, x, y);
      drawSeries("correctedAltitude", colors.calc, x, y);
      drawSeries("rfAltitude", colors.rf, x, y);

      if (Number.isFinite(currentTime) && currentTime >= minTime && currentTime <= maxTime) {
        const cursorX = x(currentTime);
        context.save();
        context.setLineDash([4, 4]);
        context.strokeStyle = colors.cursor;
        context.lineWidth = 1;
        context.beginPath(); context.moveTo(cursorX, padding.top); context.lineTo(cursorX, height - padding.bottom); context.stroke();
        context.restore();
      }

      if (hoverIndex >= 0 && data[hoverIndex]) {
        const hoverX = x(data[hoverIndex]._timeMs);
        context.strokeStyle = colors.hover;
        context.beginPath(); context.moveTo(hoverX, padding.top); context.lineTo(hoverX, height - padding.bottom); context.stroke();
        [[seriesValue(data[hoverIndex], "rawAltitude"), colors.raw],
          [seriesValue(data[hoverIndex], "correctedAltitude"), colors.calc],
          [seriesValue(data[hoverIndex], "rfAltitude"), colors.rf]].forEach(([value, color]) => {
          if (!isFiniteNumber(value)) return;
          context.fillStyle = color;
          context.strokeStyle = "#071116";
          context.lineWidth = 2;
          context.beginPath(); context.arc(hoverX, y(value), 4, 0, Math.PI * 2); context.fill(); context.stroke();
        });
      }
    }

    function drawSeries(key, color, x, y) {
      context.strokeStyle = color;
      context.fillStyle = color;
      context.lineWidth = 2;
      context.lineJoin = "round";
      context.lineCap = "round";
      let active = false;
      let activeRunId = null;
      let activeCount = 0;
      let singleX = 0;
      let singleY = 0;
      const finishRun = () => {
        if (!active) return;
        if (activeCount > 1) {
          context.strokeStyle = color;
          context.stroke();
        } else {
          context.fillStyle = color;
          context.beginPath();
          context.arc(singleX, singleY, 3.2, 0, Math.PI * 2);
          context.fill();
        }
        active = false;
        activeRunId = null;
        activeCount = 0;
      };
      data.forEach((point) => {
        const value = seriesValue(point, key);
        const time = point._timeMs;
        const runIds = seriesRunIds.get(point);
        const runId = runIds && runIds[key];
        if (!isFiniteNumber(value) || !Number.isFinite(time) || runId == null) {
          finishRun();
          return;
        }
        const pointX = x(time);
        const pointY = y(value);
        if (!active || runId !== activeRunId) {
          finishRun();
          context.beginPath();
          context.moveTo(pointX, pointY);
          active = true;
          activeRunId = runId;
          activeCount = 1;
          singleX = pointX;
          singleY = pointY;
        } else {
          context.lineTo(pointX, pointY);
          activeCount += 1;
        }
      });
      finishRun();
    }

    function seriesValue(point, key) {
      if (!point) return null;
      if (key === "rawAltitude" && point.sourceType === "RFSCNR") return null;
      if (key === "rfAltitude" && point.sourceType !== "RFSCNR") return null;
      return point[key];
    }

    function nearestIndex(clientX) {
      if (!geometry || !data.length) return -1;
      const rect = canvas.getBoundingClientRect();
      const localX = clientX - rect.left;
      const targetTime = geometry.minTime + (localX - geometry.padding.left) / geometry.plotWidth * (geometry.maxTime - geometry.minTime);
      let low = 0;
      let high = data.length;
      while (low < high) {
        const middle = (low + high) >>> 1;
        if (data[middle]._timeMs < targetTime) low = middle + 1;
        else high = middle;
      }
      if (low <= 0) return 0;
      if (low >= data.length) return data.length - 1;
      return targetTime - data[low - 1]._timeMs <= data[low]._timeMs - targetTime ? low - 1 : low;
    }

    function showTooltip(index, clientX, clientY) {
      const point = data[index];
      if (!point) { tooltip.hidden = true; return; }
      tooltip.innerHTML = point.sourceType === "RFSCNR"
        ? "<strong>" + escapeHtml(formatEventTime(point.eventTime, true)) + "</strong>" +
          "<span class=\"tooltip-rf\">RF 상대고도&nbsp; " + escapeHtml(formatAltitude(point.rfAltitude)) + "</span><br>" +
          "Home MSL " + escapeHtml(formatAltitude(point.homeAltitude))
        : "<strong>" + escapeHtml(formatEventTime(point.eventTime, true)) + "</strong>" +
          "<span class=\"tooltip-raw\">원본&nbsp; " + escapeHtml(formatAltitude(point.rawAltitude)) + "</span><br>" +
          "<span class=\"tooltip-calc\">보정&nbsp; " + escapeHtml(formatAltitude(point.correctedAltitude)) + "</span><br>" +
          "고도차 " + escapeHtml(formatSignedMeters(point.altitudeDeltaMeters));
      tooltip.hidden = false;
      const wrap = elements.chartWrap.getBoundingClientRect();
      const width = tooltip.offsetWidth;
      const height = tooltip.offsetHeight;
      tooltip.style.left = Math.max(5, Math.min(clientX - wrap.left + 12, wrap.width - width - 5)) + "px";
      tooltip.style.top = Math.max(5, Math.min(clientY - wrap.top - height / 2, wrap.height - height - 5)) + "px";
    }

    canvas.addEventListener("pointermove", (event) => {
      pendingPointer = { clientX: event.clientX, clientY: event.clientY };
      if (pointerFrame) return;
      pointerFrame = requestAnimationFrame(() => {
        pointerFrame = null;
        const pointer = pendingPointer;
        pendingPointer = null;
        if (!pointer) return;
        const index = nearestIndex(pointer.clientX);
        if (index !== hoverIndex) { hoverIndex = index; draw(); }
        showTooltip(index, pointer.clientX, pointer.clientY);
      });
    });
    canvas.addEventListener("pointerleave", () => {
      if (pointerFrame) cancelAnimationFrame(pointerFrame);
      pointerFrame = null;
      pendingPointer = null;
      hoverIndex = -1;
      tooltip.hidden = true;
      draw();
    });
    canvas.addEventListener("click", (event) => {
      const index = nearestIndex(event.clientX);
      syncToPoint(index);
    });
    canvas.addEventListener("keydown", (event) => {
      if (!data.length) return;
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        hoverIndex = hoverIndex < 0 ? 0 : Math.max(0, Math.min(data.length - 1, hoverIndex + (event.key === "ArrowRight" ? 1 : -1)));
        draw();
      }
      if (event.key === "Enter" && hoverIndex >= 0) syncToPoint(hoverIndex);
    });

    function syncToPoint(index) {
      const point = data[index];
      if (!point) return;
      if (state.mode === "RANGE") {
        selectObject(point);
        showToast(formatEventTime(point.eventTime, true) + " 샘플을 선택했습니다. 조회 범위는 유지됩니다.");
      } else {
        setRangeInputs(point.eventTime, point.eventTime);
        showToast(formatEventTime(point.eventTime, true) + " 시점을 입력했습니다. ‘범위 불러오기’를 누르면 조회합니다.");
      }
    }

    if (window.ResizeObserver) {
      state.resizeObserver = new ResizeObserver(draw);
      state.resizeObserver.observe(canvas);
    } else window.addEventListener("resize", draw);

    function setCurrentTime(value) {
      currentTime = timeValue(value);
      draw();
    }

    return { setData, setCurrentTime, draw };
  }

  function formatClock(timestamp) {
    const date = new Date(timestamp);
    const pad = (value, size) => String(value).padStart(size, "0");
    return pad(date.getHours(), 2) + ":" + pad(date.getMinutes(), 2) + ":" + pad(date.getSeconds(), 2);
  }
})();
