<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { closeAuxiliaryWindowOrClearHash } from "../workspaceUiState";
import IconActionButton from "./IconActionButton.vue";

type CourseMapPayload = {
  file_name: string;
  mime_type: string;
  data_base64: string;
};

type CpLegendRow = {
  id: number;
  cp_number: number;
  name: string;
  cp_type_id: number | null;
  cp_type_name: string;
  map_x: number | null;
  map_y: number | null;
};

type CourseMapSpecialPoints = {
  start_cp: number | null;
  finish_cp: number;
  start_map_x: number | null;
  start_map_y: number | null;
  finish_map_x: number | null;
  finish_map_y: number | null;
};

type SpecialKind = "start" | "finish";
type ActiveTarget =
  | { kind: "legend"; id: number }
  | { kind: SpecialKind }
  | null;

type MapGpsAnchor = {
  id: number;
  name: string;
  map_x: number;
  map_y: number;
  lat: number | null;
  lon: number | null;
  link_kind: "free" | "legend" | "start" | "finish" | string;
  legend_id: number | null;
  cp_number: number | null;
  map_locked: boolean;
  placement_ok: boolean;
};

type MapGeorefInfo = {
  scale_denominator: number | null;
  ready: boolean;
  status: string;
  anchors: MapGpsAnchor[];
};

type UndoEntry =
  | { kind: "legend"; legendId: number; map_x: number | null; map_y: number | null }
  | { kind: SpecialKind; map_x: number | null; map_y: number | null };

const SCALE_PRESETS = [
  { value: "fit", label: "Вписать в окно" },
  { value: "0.125", label: "12.5%" },
  { value: "0.25", label: "25%" },
  { value: "0.5", label: "50%" },
  { value: "0.75", label: "75%" },
  { value: "1", label: "100%" },
  { value: "1.25", label: "125%" },
  { value: "1.5", label: "150%" },
  { value: "2", label: "200%" },
  { value: "3", label: "300%" },
  { value: "4", label: "400%" },
] as const;

const NUMERIC_PRESETS = SCALE_PRESETS.filter((p) => p.value !== "fit").map((p) =>
  Number(p.value),
);
const MIN_SCALE = 0.125;
const MAX_SCALE = 4;
const WHEEL_ZOOM_FACTOR = 1.08;
const CLICK_MOVE_THRESHOLD_PX = 5;

const status = ref("Загрузка карты...");
const busy = ref(false);
const imageUrl = ref<string | null>(null);
const fileName = ref("");
const scaleChoice = ref<string>("fit");
const fitScale = ref(1);
const naturalWidth = ref(0);
const naturalHeight = ref(0);

const legends = ref<CpLegendRow[]>([]);
const special = ref<CourseMapSpecialPoints>({
  start_cp: null,
  finish_cp: 240,
  start_map_x: null,
  start_map_y: null,
  finish_map_x: null,
  finish_map_y: null,
});
const markupMode = ref(false);
const gpsMode = ref(false);
const autoNext = ref(true);
const hidePlaced = ref(false);
const activeTarget = ref<ActiveTarget>(null);
const selectedTarget = ref<ActiveTarget>(null);
const undoStack = ref<UndoEntry[]>([]);
const localBusy = ref(false);
const gpsAnchors = ref<MapGpsAnchor[]>([]);
const selectedGpsId = ref<number | null>(null);
const georefStatus = ref("");
const gpsLatDraft = ref<string>("");
const gpsLonDraft = ref<string>("");
const gpsNameDraft = ref("");
/** Draft GPS binding before coordinates are saved (not stored in DB). */
const pendingGps = ref<{
  link_kind: "free" | "legend" | "start" | "finish";
  legend_id: number | null;
  map_x: number | null;
  map_y: number | null;
  name: string;
  cp_number: number | null;
} | null>(null);

const viewportRef = ref<HTMLElement | null>(null);
const stageRef = ref<HTMLElement | null>(null);
const imageRef = ref<HTMLImageElement | null>(null);
const isPanning = ref(false);
const isDraggingMarker = ref(false);

let resizeObserver: ResizeObserver | null = null;
let panLastX = 0;
let panLastY = 0;
let panPointerId: number | null = null;
let panMoved = false;
let panStartX = 0;
let panStartY = 0;
let suppressNextClick = false;

let markerDragTarget: ActiveTarget = null;
let markerDragPointerId: number | null = null;
let markerOrig: UndoEntry | null = null;

const effectiveScale = computed(() => {
  if (scaleChoice.value === "fit") return fitScale.value;
  const n = Number(scaleChoice.value);
  return Number.isFinite(n) && n > 0 ? n : 1;
});

const scalePercentLabel = computed(() => {
  const pct = effectiveScale.value * 100;
  const rounded = Math.round(pct * 10) / 10;
  return `${rounded}%`;
});

const isCustomScale = computed(() => {
  if (scaleChoice.value === "fit") return false;
  return !SCALE_PRESETS.some((p) => p.value === scaleChoice.value);
});

const stageStyle = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value) {
    return { width: "100%", height: "100%" };
  }
  const s = effectiveScale.value;
  return {
    width: `${Math.max(1, Math.round(naturalWidth.value * s))}px`,
    height: `${Math.max(1, Math.round(naturalHeight.value * s))}px`,
  };
});

const placedCount = computed(
  () => legends.value.filter((l) => l.map_x != null && l.map_y != null).length,
);

const unplacedLegends = computed(() =>
  legends.value.filter((l) => l.map_x == null || l.map_y == null),
);

const visibleLegends = computed(() =>
  hidePlaced.value ? unplacedLegends.value : legends.value,
);

const placedMarkers = computed(() =>
  legends.value.filter((l) => l.map_x != null && l.map_y != null),
);

const startPlaced = computed(
  () => special.value.start_map_x != null && special.value.start_map_y != null,
);
const finishPlaced = computed(
  () => special.value.finish_map_x != null && special.value.finish_map_y != null,
);

const activeLabel = computed(() => {
  const t = activeTarget.value;
  if (!t) return null;
  if (t.kind === "start") {
    return special.value.start_cp != null
      ? `Старт (КП ${special.value.start_cp})`
      : "Старт (номер не задан в настройках)";
  }
  if (t.kind === "finish") return `Финиш (КП ${special.value.finish_cp})`;
  const row = legends.value.find((l) => l.id === t.id);
  return row ? `${row.cp_number} — ${row.name}` : null;
});

function sameTarget(a: ActiveTarget, b: ActiveTarget) {
  if (!a || !b) return a === b;
  if (a.kind !== b.kind) return false;
  if (a.kind === "legend" && b.kind === "legend") return a.id === b.id;
  return true;
}

const showGpsBlock = ref(true);
const showPointsBlock = ref(true);

const selectedGps = computed(
  () => gpsAnchors.value.find((a) => a.id === selectedGpsId.value) ?? null,
);

const editingGpsForm = computed(() => selectedGps.value != null || pendingGps.value != null);

const pendingGpsLocked = computed(
  () => pendingGps.value != null && pendingGps.value.link_kind !== "free",
);

const gpsCompleteCount = computed(
  () =>
    gpsAnchors.value.filter((a) => a.lat != null && a.lon != null && a.placement_ok).length,
);

const freeGpsAnchors = computed(() =>
  gpsAnchors.value.filter((a) => a.link_kind === "free"),
);

function typeColor(typeName: string) {
  const n = String(typeName || "").toLowerCase();
  if (n.includes("вод")) return "#38bdf8";
  if (n.includes("смеш")) return "#c084fc";
  if (n.includes("сух") || n.includes("земля") || n.includes("пеш")) return "#fb923c";
  if (!n.trim()) return "#94a3b8";
  let hash = 0;
  for (let i = 0; i < n.length; i += 1) hash = (hash * 31 + n.charCodeAt(i)) >>> 0;
  const hue = hash % 360;
  return `hsl(${hue} 65% 55%)`;
}

function recomputeFitScale() {
  const viewport = viewportRef.value;
  if (!viewport || !naturalWidth.value || !naturalHeight.value) {
    fitScale.value = 1;
    return;
  }
  const pad = 24;
  const availW = Math.max(1, viewport.clientWidth - pad);
  const availH = Math.max(1, viewport.clientHeight - pad);
  const next = Math.min(availW / naturalWidth.value, availH / naturalHeight.value);
  fitScale.value = Math.max(0.05, Math.min(8, next));
}

function onImageLoad() {
  const img = imageRef.value;
  if (!img) return;
  naturalWidth.value = img.naturalWidth || 0;
  naturalHeight.value = img.naturalHeight || 0;
  nextTick(() => recomputeFitScale());
  if (naturalWidth.value > 0 && naturalHeight.value > 0) {
    void invoke("set_course_map_image_size", {
      width: naturalWidth.value,
      height: naturalHeight.value,
    })
      .then(() => refreshGeoref())
      .catch(() => {
        /* ignore */
      });
  }
}

async function refreshGeoref() {
  try {
    const info = await invoke<MapGeorefInfo>("get_map_georef_info");
    gpsAnchors.value = info.anchors || [];
    georefStatus.value = info.status;
    if (
      selectedGpsId.value != null &&
      !gpsAnchors.value.some((a) => a.id === selectedGpsId.value)
    ) {
      selectedGpsId.value = null;
    }
    syncGpsDrafts();
  } catch (error) {
    georefStatus.value = `Ошибка GPS-привязки: ${String(error)}`;
  }
}

function syncGpsDrafts() {
  const a = selectedGps.value;
  if (!a) {
    if (!pendingGps.value) {
      gpsLatDraft.value = "";
      gpsLonDraft.value = "";
      gpsNameDraft.value = "";
    }
    return;
  }
  pendingGps.value = null;
  gpsLatDraft.value = a.lat == null ? "" : String(a.lat);
  gpsLonDraft.value = a.lon == null ? "" : String(a.lon);
  gpsNameDraft.value = a.name || "";
}

function clearPendingGps() {
  pendingGps.value = null;
}

function gpsAnchorLabel(a: MapGpsAnchor) {
  if (a.link_kind === "start") return "Старт";
  if (a.link_kind === "finish") return "Финиш";
  if (a.link_kind === "legend" && a.cp_number != null) return `КП ${a.cp_number}`;
  return `G${a.id}`;
}

function findGpsForLegend(legendId: number) {
  return gpsAnchors.value.find((a) => a.link_kind === "legend" && a.legend_id === legendId);
}

function findGpsForSpecial(kind: SpecialKind) {
  return gpsAnchors.value.find((a) => a.link_kind === kind);
}

function centerMapOnNorm(x: number, y: number) {
  const viewport = viewportRef.value;
  if (!viewport || !naturalWidth.value || !naturalHeight.value) return;
  const s = effectiveScale.value;
  const px = x * naturalWidth.value * s;
  const py = y * naturalHeight.value * s;
  viewport.scrollLeft = Math.max(0, px - viewport.clientWidth / 2);
  viewport.scrollTop = Math.max(0, py - viewport.clientHeight / 2);
}

async function scrollSidebarTo(selector: string) {
  await nextTick();
  const root = document.querySelector(".course-map-sidebar");
  const el = root?.querySelector(selector) as HTMLElement | null;
  el?.scrollIntoView({ block: "nearest", behavior: "smooth" });
}

async function revealInSidebar(
  block: "gps" | "points",
  itemSelector: string,
  opts?: { expand?: boolean },
) {
  if (opts?.expand) {
    if (block === "gps") showGpsBlock.value = true;
    if (block === "points") showPointsBlock.value = true;
  }
  const isOpen = block === "gps" ? showGpsBlock.value : showPointsBlock.value;
  if (!isOpen) return;
  await scrollSidebarTo(itemSelector);
}

/** Show CP/start/finish in points list + sync GPS selection (without forcing the other panel open). */
async function focusFeatureWithGps(
  target: ActiveTarget,
  opts?: { fromMap?: boolean; preferGpsEdit?: boolean },
) {
  if (!target) return;
  const fromMap = Boolean(opts?.fromMap);
  // Map click / explicit GPS edit may open GPS; sidebar CP pick must not reopen a collapsed GPS panel.
  const expandGps = fromMap || Boolean(opts?.preferGpsEdit);

  activeTarget.value = target;
  selectedTarget.value = target;

  if (target.kind === "legend") {
    await revealInSidebar("points", `[data-side-cp="${target.id}"]`, { expand: true });
    const row = legends.value.find((l) => l.id === target.id);
    const gps = findGpsForLegend(target.id);
    if (gps) {
      clearPendingGps();
      selectedGpsId.value = gps.id;
      syncGpsDrafts();
      await revealInSidebar("gps", `[data-side-gps="${gps.id}"]`, { expand: expandGps });
      status.value = `КП ${row?.cp_number ?? ""}: GPS ${gps.lat}, ${gps.lon}`;
    } else if (row && row.map_x != null && row.map_y != null) {
      selectedGpsId.value = null;
      beginPendingGps(
        {
          link_kind: "legend",
          legend_id: row.id,
          map_x: row.map_x,
          map_y: row.map_y,
          name: `КП ${row.cp_number} — ${row.name}`,
          cp_number: row.cp_number,
        },
        { expandGps },
      );
      await revealInSidebar("gps", `[data-side-gps="pending"]`, { expand: expandGps });
      status.value = `КП ${row.cp_number}: GPS нет — введите координаты и сохраните.`;
    } else {
      clearPendingGps();
      selectedGpsId.value = null;
      status.value = `КП ${row?.cp_number ?? ""} выбран.`;
    }
    if (!fromMap && row?.map_x != null && row.map_y != null) {
      centerMapOnNorm(row.map_x, row.map_y);
    }
    return;
  }

  await revealInSidebar("points", `[data-side-special="${target.kind}"]`, { expand: true });
  const gps = findGpsForSpecial(target.kind);
  const x = target.kind === "start" ? special.value.start_map_x : special.value.finish_map_x;
  const y = target.kind === "start" ? special.value.start_map_y : special.value.finish_map_y;
  const label = target.kind === "start" ? "Старт" : "Финиш";
  if (gps) {
    clearPendingGps();
    selectedGpsId.value = gps.id;
    syncGpsDrafts();
    await revealInSidebar("gps", `[data-side-gps="${gps.id}"]`, { expand: expandGps });
    status.value = `${label}: GPS ${gps.lat}, ${gps.lon}`;
  } else if (x != null && y != null) {
    selectedGpsId.value = null;
    const name =
      target.kind === "start"
        ? special.value.start_cp != null
          ? `Старт (КП ${special.value.start_cp})`
          : "Старт"
        : `Финиш (КП ${special.value.finish_cp})`;
    beginPendingGps(
      {
        link_kind: target.kind,
        legend_id: null,
        map_x: x,
        map_y: y,
        name,
        cp_number:
          target.kind === "start" ? special.value.start_cp : special.value.finish_cp,
      },
      { expandGps },
    );
    await revealInSidebar("gps", `[data-side-gps="pending"]`, { expand: expandGps });
    status.value = `${label}: GPS нет — введите координаты и сохраните.`;
  } else {
    clearPendingGps();
    selectedGpsId.value = null;
    status.value = `${label} выбран.`;
  }
  if (!fromMap && x != null && y != null) centerMapOnNorm(x, y);
}

function selectGpsAnchor(id: number, opts?: { fromMap?: boolean }) {
  clearPendingGps();
  selectedGpsId.value = id;
  syncGpsDrafts();
  const fromMap = Boolean(opts?.fromMap);
  const anchor = gpsAnchors.value.find((a) => a.id === id);
  void revealInSidebar("gps", `[data-side-gps="${id}"]`, { expand: true });

  // Linked GPS: highlight related CP/start/finish, but do not reopen a collapsed points panel from the GPS list.
  if (anchor?.link_kind === "legend" && anchor.legend_id != null) {
    activeTarget.value = { kind: "legend", id: anchor.legend_id };
    selectedTarget.value = { kind: "legend", id: anchor.legend_id };
    void revealInSidebar("points", `[data-side-cp="${anchor.legend_id}"]`, {
      expand: fromMap,
    });
  } else if (anchor?.link_kind === "start" || anchor?.link_kind === "finish") {
    activeTarget.value = { kind: anchor.link_kind };
    selectedTarget.value = { kind: anchor.link_kind };
    void revealInSidebar("points", `[data-side-special="${anchor.link_kind}"]`, {
      expand: fromMap,
    });
  } else {
    activeTarget.value = null;
    selectedTarget.value = null;
  }

  if (!fromMap && anchor && anchor.placement_ok !== false) {
    centerMapOnNorm(anchor.map_x, anchor.map_y);
  }
}

function beginPendingGps(
  draft: NonNullable<typeof pendingGps.value>,
  opts?: { expandGps?: boolean },
) {
  selectedGpsId.value = null;
  pendingGps.value = draft;
  gpsLatDraft.value = "";
  gpsLonDraft.value = "";
  gpsNameDraft.value = draft.name;
  if (opts?.expandGps !== false) {
    showGpsBlock.value = true;
  }
}

function startPlaceNewGps() {
  beginPendingGps({
    link_kind: "free",
    legend_id: null,
    map_x: null,
    map_y: null,
    name: `GPS ${gpsAnchors.value.length + 1}`,
    cp_number: null,
  });
  status.value = "Кликните на карте, затем введите широту и долготу.";
}

async function attachGpsToSpecial(kind: SpecialKind) {
  await focusFeatureWithGps({ kind }, { preferGpsEdit: true });
}

async function attachGpsToLegend(row: CpLegendRow) {
  await focusFeatureWithGps({ kind: "legend", id: row.id }, { preferGpsEdit: true });
}

function onMarkupModeToggle() {
  if (markupMode.value) {
    gpsMode.value = false;
    clearPendingGps();
  }
}

function onGpsModeToggle() {
  if (gpsMode.value) markupMode.value = false;
  else clearPendingGps();
}

async function placeOrMoveGpsAt(clientX: number, clientY: number) {
  const norm = clientToMapNorm(clientX, clientY);
  if (!norm) return;
  if (selectedGps.value?.map_locked || pendingGpsLocked.value) {
    status.value =
      "Эта GPS-точка привязана к КП/старту/финишу — позиция на карте берётся от них. Можно менять только lat/lon.";
    return;
  }

  // New free draft or pending free without coords yet — only keep draft in UI.
  if (
    pendingGps.value?.link_kind === "free" ||
    (pendingGps.value == null && selectedGpsId.value == null)
  ) {
    beginPendingGps({
      link_kind: "free",
      legend_id: null,
      map_x: norm.x,
      map_y: norm.y,
      name: gpsNameDraft.value.trim() || `GPS ${gpsAnchors.value.length + 1}`,
      cp_number: null,
    });
    status.value = "Точка на карте выбрана. Введите широту и долготу, затем сохраните.";
    return;
  }

  // Move existing free GPS (already has coordinates).
  if (selectedGps.value && selectedGps.value.link_kind === "free") {
    localBusy.value = true;
    try {
      await invoke<MapGpsAnchor>("upsert_map_gps_anchor", {
        item: {
          id: selectedGpsId.value,
          linkKind: "free",
          name: selectedGps.value.name,
          mapX: norm.x,
          mapY: norm.y,
          lat: selectedGps.value.lat,
          lon: selectedGps.value.lon,
        },
      });
      status.value = `GPS-точка #${selectedGpsId.value} перемещена.`;
      await refreshGeoref();
    } catch (error) {
      status.value = `Ошибка GPS-точки: ${String(error)}`;
    } finally {
      localBusy.value = false;
    }
  }
}

function parseLatLonDraft(): { lat: number; lon: number } | null {
  const latRaw = gpsLatDraft.value.trim();
  const lonRaw = gpsLonDraft.value.trim();
  if (!latRaw || !lonRaw) {
    status.value = "Укажите и широту, и долготу.";
    return null;
  }
  const lat = Number(latRaw.replace(",", "."));
  const lon = Number(lonRaw.replace(",", "."));
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    status.value = "Широта и долгота должны быть числами.";
    return null;
  }
  return { lat, lon };
}

async function saveSelectedGpsCoords() {
  const coords = parseLatLonDraft();
  if (!coords) return;

  localBusy.value = true;
  try {
    if (pendingGps.value) {
      const draft = pendingGps.value;
      if (draft.link_kind === "free" && (draft.map_x == null || draft.map_y == null)) {
        status.value = "Сначала кликните точку на карте.";
        return;
      }
      const created = await invoke<MapGpsAnchor>("upsert_map_gps_anchor", {
        item: {
          id: null,
          linkKind: draft.link_kind,
          legendId: draft.legend_id,
          name: gpsNameDraft.value.trim() || draft.name,
          mapX: draft.map_x,
          mapY: draft.map_y,
          lat: coords.lat,
          lon: coords.lon,
        },
      });
      clearPendingGps();
      selectedGpsId.value = created.id;
      await refreshGeoref();
      status.value = `GPS сохранена: ${coords.lat}, ${coords.lon}.`;
      await revealInSidebar("gps", `[data-side-gps="${created.id}"]`, { expand: true });
      return;
    }

    if (selectedGpsId.value == null || !selectedGps.value) {
      status.value = "Сначала выберите КП/старт/финиш или свободную точку.";
      return;
    }

    await invoke("upsert_map_gps_anchor", {
      item: {
        id: selectedGpsId.value,
        linkKind: selectedGps.value.link_kind,
        legendId: selectedGps.value.legend_id,
        name: gpsNameDraft.value.trim() || selectedGps.value.name,
        mapX: selectedGps.value.map_locked ? null : selectedGps.value.map_x,
        mapY: selectedGps.value.map_locked ? null : selectedGps.value.map_y,
        lat: coords.lat,
        lon: coords.lon,
      },
    });
    await refreshGeoref();
    status.value = `GPS #${selectedGpsId.value}: ${coords.lat}, ${coords.lon} сохранены.`;
  } catch (error) {
    status.value = `Ошибка сохранения GPS: ${String(error)}`;
  } finally {
    localBusy.value = false;
  }
}

async function deleteSelectedGps() {
  if (pendingGps.value) {
    clearPendingGps();
    gpsLatDraft.value = "";
    gpsLonDraft.value = "";
    gpsNameDraft.value = "";
    status.value = "Черновик GPS отменён.";
    return;
  }
  if (selectedGpsId.value == null) return;
  if (!window.confirm(`Удалить GPS-точку #${selectedGpsId.value}?`)) return;
  localBusy.value = true;
  try {
    await invoke("delete_map_gps_anchor", { anchorId: selectedGpsId.value });
    selectedGpsId.value = null;
    clearPendingGps();
    await refreshGeoref();
    status.value = "GPS-точка удалена.";
  } catch (error) {
    status.value = `Ошибка удаления GPS: ${String(error)}`;
  } finally {
    localBusy.value = false;
  }
}

function replaceLegend(row: CpLegendRow) {
  const idx = legends.value.findIndex((l) => l.id === row.id);
  if (idx >= 0) legends.value.splice(idx, 1, row);
  else {
    legends.value.push(row);
    legends.value.sort((a, b) => a.cp_number - b.cp_number || a.id - b.id);
  }
}

function selectNextUnplaced(afterId: number | null = null) {
  const list = unplacedLegends.value;
  if (!list.length) {
    activeTarget.value = null;
    return;
  }
  if (afterId == null) {
    activeTarget.value = { kind: "legend", id: list[0].id };
    return;
  }
  const afterIdx = legends.value.findIndex((l) => l.id === afterId);
  const next = legends.value
    .slice(afterIdx + 1)
    .find((l) => l.map_x == null || l.map_y == null);
  activeTarget.value = { kind: "legend", id: (next ?? list[0]).id };
}

async function loadSpecialPoints() {
  special.value = await invoke<CourseMapSpecialPoints>("get_course_map_special_points");
}

async function loadLegends() {
  try {
    legends.value = await invoke<CpLegendRow[]>("get_cp_legends");
    if (
      markupMode.value &&
      autoNext.value &&
      (activeTarget.value == null || activeTarget.value.kind !== "legend")
    ) {
      selectNextUnplaced();
    }
  } catch (error) {
    status.value = `Ошибка загрузки легенд: ${String(error)}`;
  }
}

async function loadMap() {
  busy.value = true;
  status.value = "Загрузка карты...";
  try {
    const payload = await invoke<CourseMapPayload | null>("get_course_map_payload");
    if (!payload) {
      imageUrl.value = null;
      fileName.value = "";
      naturalWidth.value = 0;
      naturalHeight.value = 0;
      status.value = "Карта не загружена.";
      return;
    }
    fileName.value = payload.file_name;
    scaleChoice.value = "fit";
    naturalWidth.value = 0;
    naturalHeight.value = 0;
    imageUrl.value = `data:${payload.mime_type};base64,${payload.data_base64}`;
    status.value = payload.file_name;
    await Promise.all([loadLegends(), loadSpecialPoints(), refreshGeoref()]);
  } catch (error) {
    imageUrl.value = null;
    fileName.value = "";
    naturalWidth.value = 0;
    naturalHeight.value = 0;
    status.value = `Ошибка загрузки карты: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

function nearestPreset(scale: number, direction: "up" | "down") {
  const clamped = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
  if (direction === "up") {
    const next = NUMERIC_PRESETS.find((p) => p > clamped + 0.001);
    return next ?? MAX_SCALE;
  }
  const prev = [...NUMERIC_PRESETS].reverse().find((p) => p < clamped - 0.001);
  return prev ?? MIN_SCALE;
}

function setNumericScale(scale: number) {
  const clamped = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
  const rounded = Math.round(clamped * 1000) / 1000;
  const match = NUMERIC_PRESETS.find((p) => Math.abs(p - rounded) < 0.0005);
  scaleChoice.value = String(match ?? rounded);
}

function zoomIn() {
  scaleChoice.value = String(nearestPreset(effectiveScale.value, "up"));
}

function zoomOut() {
  scaleChoice.value = String(nearestPreset(effectiveScale.value, "down"));
}

function onViewportWheel(event: WheelEvent) {
  if (!event.ctrlKey || !imageUrl.value) return;
  event.preventDefault();
  const direction = event.deltaY < 0 ? 1 : -1;
  const factor = direction > 0 ? WHEEL_ZOOM_FACTOR : 1 / WHEEL_ZOOM_FACTOR;
  setNumericScale(effectiveScale.value * factor);
}

function clientToMapNorm(clientX: number, clientY: number): { x: number; y: number } | null {
  const stage = stageRef.value;
  if (!stage) return null;
  const rect = stage.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return null;
  const x = (clientX - rect.left) / rect.width;
  const y = (clientY - rect.top) / rect.height;
  return {
    x: Math.min(1, Math.max(0, x)),
    y: Math.min(1, Math.max(0, y)),
  };
}

function pushUndo(entry: UndoEntry) {
  undoStack.value.push(entry);
  if (undoStack.value.length > 100) undoStack.value.shift();
}

async function saveLegendPosition(
  legendId: number,
  mapX: number | null,
  mapY: number | null,
  pushUndoFlag = true,
) {
  const current = legends.value.find((l) => l.id === legendId);
  if (!current) return;
  if (pushUndoFlag) {
    pushUndo({ kind: "legend", legendId, map_x: current.map_x, map_y: current.map_y });
  }
  localBusy.value = true;
  try {
    const updated = await invoke<CpLegendRow>("set_cp_legend_map_position", {
      legendId,
      mapX,
      mapY,
    });
    replaceLegend(updated);
  } catch (error) {
    status.value = `Ошибка сохранения точки КП: ${String(error)}`;
  } finally {
    localBusy.value = false;
  }
}

async function saveSpecialPosition(
  kind: SpecialKind,
  mapX: number | null,
  mapY: number | null,
  pushUndoFlag = true,
) {
  if (pushUndoFlag) {
    pushUndo({
      kind,
      map_x: kind === "start" ? special.value.start_map_x : special.value.finish_map_x,
      map_y: kind === "start" ? special.value.start_map_y : special.value.finish_map_y,
    });
  }
  localBusy.value = true;
  try {
    special.value = await invoke<CourseMapSpecialPoints>("set_course_map_special_position", {
      point: kind,
      mapX,
      mapY,
    });
  } catch (error) {
    status.value = `Ошибка сохранения ${kind === "start" ? "старта" : "финиша"}: ${String(error)}`;
  } finally {
    localBusy.value = false;
  }
}

async function placeActiveAt(clientX: number, clientY: number) {
  if (gpsMode.value) {
    await placeOrMoveGpsAt(clientX, clientY);
    return;
  }
  if (!markupMode.value) return;
  const target = activeTarget.value;
  if (!target) {
    status.value = "Выберите старт, финиш или КП в списке.";
    return;
  }
  const norm = clientToMapNorm(clientX, clientY);
  if (!norm) return;

  if (target.kind === "start" || target.kind === "finish") {
    await saveSpecialPosition(target.kind, norm.x, norm.y, true);
    selectedTarget.value = target;
    status.value =
      target.kind === "start"
        ? `Старт отмечен на карте (КП ${special.value.start_cp ?? "—"}).`
        : `Финиш отмечен на карте (КП ${special.value.finish_cp}).`;
    return;
  }

  await saveLegendPosition(target.id, norm.x, norm.y, true);
  selectedTarget.value = target;
  if (autoNext.value) selectNextUnplaced(target.id);
  status.value = `КП размещён (${placedCount.value}/${legends.value.length}).`;
}

async function clearLegendPlacement(legendId: number) {
  await saveLegendPosition(legendId, null, null, true);
  if (autoNext.value && markupMode.value) {
    activeTarget.value = { kind: "legend", id: legendId };
  }
  status.value = "Точка КП снята с карты.";
}

async function clearSpecialPlacement(kind: SpecialKind) {
  await saveSpecialPosition(kind, null, null, true);
  activeTarget.value = { kind };
  selectedTarget.value = { kind };
  status.value = kind === "start" ? "Старт снят с карты." : "Финиш снят с карты.";
}

async function undoLast() {
  const entry = undoStack.value.pop();
  if (!entry) return;
  if (entry.kind === "legend") {
    await saveLegendPosition(entry.legendId, entry.map_x, entry.map_y, false);
    activeTarget.value = { kind: "legend", id: entry.legendId };
    selectedTarget.value = { kind: "legend", id: entry.legendId };
  } else {
    await saveSpecialPosition(entry.kind, entry.map_x, entry.map_y, false);
    activeTarget.value = { kind: entry.kind };
    selectedTarget.value = { kind: entry.kind };
  }
  status.value = "Отменено последнее изменение точки.";
}

function activateLegend(row: CpLegendRow, opts?: { fromMap?: boolean }) {
  void focusFeatureWithGps({ kind: "legend", id: row.id }, { fromMap: opts?.fromMap });
  if (!opts?.fromMap && !gpsMode.value && !markupMode.value) {
    markupMode.value = true;
  }
}

function activateSpecial(kind: SpecialKind, opts?: { fromMap?: boolean }) {
  void focusFeatureWithGps({ kind }, { fromMap: opts?.fromMap });
  if (!opts?.fromMap && !gpsMode.value && !markupMode.value) {
    markupMode.value = true;
  }
}

function onPanStart(event: PointerEvent) {
  if (event.button !== 0 || !viewportRef.value) return;
  if (isDraggingMarker.value) return;
  isPanning.value = true;
  panMoved = false;
  panStartX = event.clientX;
  panStartY = event.clientY;
  panLastX = event.clientX;
  panLastY = event.clientY;
  panPointerId = event.pointerId;
  viewportRef.value.setPointerCapture(event.pointerId);
  event.preventDefault();
}

function onPanMove(event: PointerEvent) {
  if (!isPanning.value || !viewportRef.value) return;
  if (panPointerId != null && event.pointerId !== panPointerId) return;
  const dx = event.clientX - panLastX;
  const dy = event.clientY - panLastY;
  if (
    Math.hypot(event.clientX - panStartX, event.clientY - panStartY) > CLICK_MOVE_THRESHOLD_PX
  ) {
    panMoved = true;
  }
  panLastX = event.clientX;
  panLastY = event.clientY;
  viewportRef.value.scrollLeft -= dx;
  viewportRef.value.scrollTop -= dy;
}

function onPanEnd(event: PointerEvent) {
  if (!isPanning.value) return;
  if (panPointerId != null && event.pointerId !== panPointerId) return;
  const viewport = viewportRef.value;
  const wasClick = !panMoved;
  const clickX = event.clientX;
  const clickY = event.clientY;
  if (viewport && panPointerId != null && viewport.hasPointerCapture(panPointerId)) {
    viewport.releasePointerCapture(panPointerId);
  }
  isPanning.value = false;
  panPointerId = null;

  if (wasClick && !suppressNextClick && (markupMode.value || gpsMode.value)) {
    void placeActiveAt(clickX, clickY);
  }
  suppressNextClick = false;
}

function onLegendMarkerDown(event: PointerEvent, row: CpLegendRow) {
  if (event.button !== 0) return;
  event.stopPropagation();
  event.preventDefault();
  activateLegend(row, { fromMap: true });
  if (!markupMode.value || gpsMode.value) return;
  const target: ActiveTarget = { kind: "legend", id: row.id };
  isDraggingMarker.value = true;
  markerDragTarget = target;
  markerDragPointerId = event.pointerId;
  markerOrig = { kind: "legend", legendId: row.id, map_x: row.map_x, map_y: row.map_y };
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}

function onSpecialMarkerDown(event: PointerEvent, kind: SpecialKind) {
  if (event.button !== 0) return;
  event.stopPropagation();
  event.preventDefault();
  activateSpecial(kind, { fromMap: true });
  if (!markupMode.value || gpsMode.value) return;
  const target: ActiveTarget = { kind };
  isDraggingMarker.value = true;
  markerDragTarget = target;
  markerDragPointerId = event.pointerId;
  markerOrig = {
    kind,
    map_x: kind === "start" ? special.value.start_map_x : special.value.finish_map_x,
    map_y: kind === "start" ? special.value.start_map_y : special.value.finish_map_y,
  };
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}

function onMarkerPointerMove(event: PointerEvent) {
  if (!isDraggingMarker.value || !markerDragTarget) return;
  if (markerDragPointerId != null && event.pointerId !== markerDragPointerId) return;
  const norm = clientToMapNorm(event.clientX, event.clientY);
  if (!norm) return;
  if (markerDragTarget.kind === "legend") {
    const idx = legends.value.findIndex((l) => l.id === markerDragTarget!.id);
    if (idx < 0) return;
    legends.value[idx] = { ...legends.value[idx], map_x: norm.x, map_y: norm.y };
  } else if (markerDragTarget.kind === "start") {
    special.value = { ...special.value, start_map_x: norm.x, start_map_y: norm.y };
  } else {
    special.value = { ...special.value, finish_map_x: norm.x, finish_map_y: norm.y };
  }
}

async function onMarkerPointerUp(event: PointerEvent) {
  if (!isDraggingMarker.value || !markerDragTarget) return;
  if (markerDragPointerId != null && event.pointerId !== markerDragPointerId) return;
  const el = event.currentTarget as HTMLElement;
  if (el.hasPointerCapture(event.pointerId)) el.releasePointerCapture(event.pointerId);

  const target = markerDragTarget;
  isDraggingMarker.value = false;
  markerDragTarget = null;
  markerDragPointerId = null;
  suppressNextClick = true;

  if (markerOrig) {
    pushUndo(markerOrig);
    markerOrig = null;
  }

  localBusy.value = true;
  try {
    if (target.kind === "legend") {
      const row = legends.value.find((l) => l.id === target.id);
      if (!row || row.map_x == null || row.map_y == null) return;
      const updated = await invoke<CpLegendRow>("set_cp_legend_map_position", {
        legendId: target.id,
        mapX: row.map_x,
        mapY: row.map_y,
      });
      replaceLegend(updated);
      status.value = `КП ${updated.cp_number} перемещён.`;
    } else {
      const x = target.kind === "start" ? special.value.start_map_x : special.value.finish_map_x;
      const y = target.kind === "start" ? special.value.start_map_y : special.value.finish_map_y;
      if (x == null || y == null) return;
      special.value = await invoke<CourseMapSpecialPoints>("set_course_map_special_position", {
        point: target.kind,
        mapX: x,
        mapY: y,
      });
      status.value = target.kind === "start" ? "Старт перемещён." : "Финиш перемещён.";
    }
  } catch (error) {
    status.value = `Ошибка перемещения: ${String(error)}`;
    await Promise.all([loadLegends(), loadSpecialPoints()]);
  } finally {
    localBusy.value = false;
  }
}

function onKeyDown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    if (isDraggingMarker.value) return;
    markupMode.value = false;
    return;
  }
  if ((event.ctrlKey || event.metaKey) && event.code === "KeyZ") {
    event.preventDefault();
    void undoLast();
    return;
  }
  if (event.key === "Delete" || event.key === "Backspace") {
    const tag = (event.target as HTMLElement | null)?.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
    const t = selectedTarget.value;
    if (!t) return;
    event.preventDefault();
    if (t.kind === "legend") {
      const row = legends.value.find((l) => l.id === t.id);
      if (!row || row.map_x == null) return;
      void clearLegendPlacement(t.id);
    } else if (t.kind === "start" && startPlaced.value) {
      void clearSpecialPlacement("start");
    } else if (t.kind === "finish" && finishPlaced.value) {
      void clearSpecialPlacement("finish");
    }
  }
}

watch(markupMode, (enabled) => {
  if (enabled) {
    if (autoNext.value) selectNextUnplaced(
      activeTarget.value?.kind === "legend" ? activeTarget.value.id : null,
    );
    else if (!activeTarget.value && legends.value.length) {
      activeTarget.value = { kind: "legend", id: legends.value[0].id };
    }
    status.value = "Разметка: старт/финиш — спецзнаки; КП — авто или вручную.";
  } else {
    status.value = fileName.value || status.value;
  }
});

watch(autoNext, (enabled) => {
  if (enabled && markupMode.value) {
    selectNextUnplaced(activeTarget.value?.kind === "legend" ? activeTarget.value.id : null);
  }
});

watch(viewportRef, (el, prev) => {
  if (prev && resizeObserver) resizeObserver.unobserve(prev);
  if (el && resizeObserver) {
    resizeObserver.observe(el);
    recomputeFitScale();
  }
});

async function closeWindow() {
  await closeAuxiliaryWindowOrClearHash(["course-map"]);
}

onMounted(() => {
  resizeObserver = new ResizeObserver(() => recomputeFitScale());
  if (viewportRef.value) resizeObserver.observe(viewportRef.value);
  window.addEventListener("keydown", onKeyDown);
  void loadMap();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  isPanning.value = false;
  panPointerId = null;
  window.removeEventListener("keydown", onKeyDown);
});
</script>

<template>
  <main class="container course-map-page">
    <section class="native-tools">
      <div class="native-row">
        <button type="button" @click="closeWindow">Закрыть</button>
        <button type="button" :disabled="busy" @click="loadMap">Обновить</button>
        <label class="checkbox-label">
          <input v-model="markupMode" type="checkbox" @change="onMarkupModeToggle" />
          Разметка КП
        </label>
        <label class="checkbox-label">
          <input v-model="gpsMode" type="checkbox" @change="onGpsModeToggle" />
          GPS-привязка
        </label>
        <label class="checkbox-label" :class="{ muted: !markupMode }">
          <input v-model="autoNext" type="checkbox" :disabled="!markupMode" />
          Авто: следующий
        </label>
        <label class="checkbox-label">
          <input v-model="hidePlaced" type="checkbox" />
          Скрыть размещённые
        </label>
        <IconActionButton
          variant="undo"
          label="Отменить (Ctrl+Z)"
          :disabled="!undoStack.length || localBusy"
          @click="undoLast"
        />
        <label class="course-map-scale-label">
          Масштаб:
          <select v-model="scaleChoice" :disabled="!imageUrl">
            <option value="fit">Вписать в окно</option>
            <option v-if="isCustomScale" :value="scaleChoice">
              {{ scalePercentLabel }}
            </option>
            <option
              v-for="opt in SCALE_PRESETS.filter((p) => p.value !== 'fit')"
              :key="opt.value"
              :value="opt.value"
            >
              {{ opt.label }}
            </option>
          </select>
        </label>
        <button type="button" :disabled="!imageUrl" title="Уменьшить" @click="zoomOut">−</button>
        <span class="subtitle">{{ imageUrl ? scalePercentLabel : "—" }}</span>
        <button type="button" :disabled="!imageUrl" title="Увеличить" @click="zoomIn">+</button>
      </div>
      <p class="status">{{ status }}</p>
      <p v-if="georefStatus" class="subtitle">{{ georefStatus }}</p>
      <p v-if="gpsMode" class="subtitle">
        GPS с координатами: {{ gpsCompleteCount }}/{{ gpsAnchors.length }} (нужно ≥ 2) ·
        клик по КП/старту/финишу — ввести GPS · сохранение только с широтой и долготой
      </p>
      <p v-if="markupMode" class="subtitle">
        Активно:
        <strong v-if="activeLabel">{{ activeLabel }}</strong>
        <span v-else>ничего не выбрано</span>
        · КП {{ placedCount }}/{{ legends.length }}
        · Del — снять · Ctrl+Z — отмена
      </p>
    </section>

    <div v-if="imageUrl" class="course-map-layout">
      <aside class="course-map-sidebar">
        <section
          class="course-map-side-block course-map-side-block-gps"
          :class="{ collapsed: !showGpsBlock }"
        >
          <button
            type="button"
            class="course-map-side-toggle"
            @click="showGpsBlock = !showGpsBlock"
          >
            <span>{{ showGpsBlock ? "▾" : "▸" }} GPS-привязка</span>
            <span class="subtitle">{{ gpsCompleteCount }}/{{ gpsAnchors.length }}</span>
          </button>
          <div v-show="showGpsBlock" class="course-map-side-body course-map-gps-body">
            <div class="native-row" style="margin-bottom: 6px; flex: 0 0 auto">
              <button type="button" :disabled="localBusy" @click="startPlaceNewGps">
                + Свободная GPS
              </button>
            </div>
            <div class="course-map-cp-list course-map-gps-list">
              <div
                v-for="a in gpsAnchors"
                :key="`gps-${a.id}`"
                :data-side-gps="a.id"
                class="course-map-cp-item"
                :class="{
                  active: selectedGpsId === a.id,
                  selected: selectedGpsId === a.id,
                  placed: a.lat != null && a.lon != null && a.placement_ok,
                }"
                role="button"
                tabindex="0"
                @click="selectGpsAnchor(a.id)"
                @keydown.enter.prevent="selectGpsAnchor(a.id)"
              >
                <span class="course-map-cp-dot" style="background: #fbbf24"></span>
                <span class="course-map-cp-num">{{ gpsAnchorLabel(a) }}</span>
                <span class="course-map-cp-name" :title="a.name">{{ a.name }}</span>
                <span class="course-map-cp-mark">
                  {{ a.lat != null && a.lon != null && a.placement_ok ? "✓" : "·" }}
                </span>
              </div>
              <p v-if="!gpsAnchors.length && !pendingGps" class="subtitle" style="margin: 0">
                Нет GPS-точек — кликните КП/старт/финиш или добавьте свободную
              </p>
              <div
                v-if="pendingGps"
                class="course-map-cp-item active selected"
                data-side-gps="pending"
              >
                <span class="course-map-cp-dot" style="background: #fde68a"></span>
                <span class="course-map-cp-num">…</span>
                <span class="course-map-cp-name" :title="pendingGps.name">
                  {{ pendingGps.name }} (черновик)
                </span>
                <span class="course-map-cp-mark">·</span>
              </div>
            </div>

            <div v-if="editingGpsForm" class="native-settings course-map-gps-form">
              <p
                v-if="selectedGps?.map_locked || pendingGpsLocked"
                class="subtitle"
                style="margin: 0 0 6px"
              >
                Позиция на карте = размещение
                {{
                  (selectedGps?.link_kind || pendingGps?.link_kind) === "start"
                    ? "старта"
                    : (selectedGps?.link_kind || pendingGps?.link_kind) === "finish"
                      ? "финиша"
                      : "КП"
                }}
              </p>
              <p
                v-else-if="pendingGps?.link_kind === 'free' && pendingGps.map_x == null"
                class="subtitle"
                style="margin: 0 0 6px"
              >
                Кликните на карте, чтобы выбрать место точки
              </p>
              <label>
                Имя
                <input
                  v-model="gpsNameDraft"
                  type="text"
                  :disabled="localBusy || !!selectedGps?.map_locked || pendingGpsLocked"
                />
              </label>
              <label>
                Широта
                <input
                  v-model="gpsLatDraft"
                  type="text"
                  placeholder="55.123456"
                  :disabled="localBusy"
                />
              </label>
              <label>
                Долгота
                <input
                  v-model="gpsLonDraft"
                  type="text"
                  placeholder="37.123456"
                  :disabled="localBusy"
                />
              </label>
              <div class="native-row">
                <IconActionButton
                  variant="save"
                  label="Сохранить GPS"
                  :disabled="localBusy"
                  @click="saveSelectedGpsCoords"
                />
                <IconActionButton
                  :variant="pendingGps ? 'cancel' : 'delete'"
                  :label="pendingGps ? 'Отмена' : 'Удалить'"
                  :disabled="localBusy"
                  @click="deleteSelectedGps"
                />
              </div>
            </div>
          </div>
        </section>

        <section
          class="course-map-side-block course-map-side-block-points"
          :class="{ collapsed: !showPointsBlock }"
        >
          <button
            type="button"
            class="course-map-side-toggle"
            @click="showPointsBlock = !showPointsBlock"
          >
            <span>
              {{ showPointsBlock ? "▾" : "▸" }} Старт / Финиш / КП
              <template v-if="hidePlaced">
                ({{ unplacedLegends.length }}/{{ legends.length }})
              </template>
              <template v-else>
                ({{ unplacedLegends.length }} без точки)
              </template>
            </span>
          </button>
          <div v-show="showPointsBlock" class="course-map-side-body course-map-points-body">
            <div class="course-map-cp-list course-map-points-list">
              <div
                v-if="!hidePlaced || !startPlaced"
                data-side-special="start"
                class="course-map-cp-item special-start"
                :class="{
                  active: sameTarget(activeTarget, { kind: 'start' }),
                  selected: sameTarget(selectedTarget, { kind: 'start' }),
                  placed: startPlaced,
                }"
                role="button"
                tabindex="0"
                @click="activateSpecial('start')"
                @keydown.enter.prevent="activateSpecial('start')"
              >
                <span
                  class="course-map-cp-dot"
                  style="background: #4ade80"
                  title="Старт"
                ></span>
                <span class="course-map-cp-num">
                  {{ special.start_cp != null ? special.start_cp : "—" }}
                </span>
                <span class="course-map-cp-name">Старт</span>
                <span class="course-map-cp-mark">{{ startPlaced ? "✓" : "·" }}</span>
                <span
                  v-if="findGpsForSpecial('start')?.lat != null"
                  class="course-map-cp-mark"
                  title="Есть GPS"
                  style="color: #b45309"
                >⌖</span>
                <button
                  v-if="startPlaced"
                  type="button"
                  class="course-map-cp-clear"
                  title="Снять старт с карты"
                  @click.stop="clearSpecialPlacement('start')"
                >
                  ×
                </button>
              </div>
              <div
                v-if="!hidePlaced || !finishPlaced"
                data-side-special="finish"
                class="course-map-cp-item special-finish"
                :class="{
                  active: sameTarget(activeTarget, { kind: 'finish' }),
                  selected: sameTarget(selectedTarget, { kind: 'finish' }),
                  placed: finishPlaced,
                }"
                role="button"
                tabindex="0"
                @click="activateSpecial('finish')"
                @keydown.enter.prevent="activateSpecial('finish')"
              >
                <span
                  class="course-map-cp-dot"
                  style="background: #f87171"
                  title="Финиш"
                ></span>
                <span class="course-map-cp-num">{{ special.finish_cp }}</span>
                <span class="course-map-cp-name">Финиш</span>
                <span class="course-map-cp-mark">{{ finishPlaced ? "✓" : "·" }}</span>
                <span
                  v-if="findGpsForSpecial('finish')?.lat != null"
                  class="course-map-cp-mark"
                  title="Есть GPS"
                  style="color: #b45309"
                >⌖</span>
                <button
                  v-if="finishPlaced"
                  type="button"
                  class="course-map-cp-clear"
                  title="Снять финиш с карты"
                  @click.stop="clearSpecialPlacement('finish')"
                >
                  ×
                </button>
              </div>
              <div
                v-for="row in visibleLegends"
                :key="row.id"
                :data-side-cp="row.id"
                class="course-map-cp-item"
                :class="{
                  active: sameTarget(activeTarget, { kind: 'legend', id: row.id }),
                  selected: sameTarget(selectedTarget, { kind: 'legend', id: row.id }),
                  placed: row.map_x != null && row.map_y != null,
                }"
                role="button"
                tabindex="0"
                @click="activateLegend(row)"
                @keydown.enter.prevent="activateLegend(row)"
              >
                <span
                  class="course-map-cp-dot"
                  :style="{ background: typeColor(row.cp_type_name) }"
                ></span>
                <span class="course-map-cp-num">{{ row.cp_number }}</span>
                <span class="course-map-cp-name" :title="row.name">{{ row.name }}</span>
                <span class="course-map-cp-mark">
                  {{ row.map_x != null && row.map_y != null ? "✓" : "·" }}
                </span>
                <span
                  v-if="findGpsForLegend(row.id)?.lat != null"
                  class="course-map-cp-mark"
                  title="Есть GPS"
                  style="color: #b45309"
                >⌖</span>
                <button
                  v-if="row.map_x != null && row.map_y != null"
                  type="button"
                  class="course-map-cp-clear"
                  title="Снять с карты"
                  @click.stop="clearLegendPlacement(row.id)"
                >
                  ×
                </button>
              </div>
              <p
                v-if="hidePlaced && startPlaced && finishPlaced && !visibleLegends.length"
                class="subtitle"
                style="margin: 0"
              >
                Все точки размещены
              </p>
            </div>
          </div>
        </section>
      </aside>

      <section
        ref="viewportRef"
        class="course-map-viewport"
        :class="{
          'is-panning': isPanning,
          'is-markup': markupMode || gpsMode,
          'is-dragging-marker': isDraggingMarker,
        }"
        @wheel="onViewportWheel"
        @pointerdown="onPanStart"
        @pointermove="onPanMove"
        @pointerup="onPanEnd"
        @pointercancel="onPanEnd"
        @lostpointercapture="onPanEnd"
      >
        <div ref="stageRef" class="course-map-stage" :style="stageStyle">
          <img
            ref="imageRef"
            class="course-map-image"
            :src="imageUrl"
            :alt="fileName || 'Карта'"
            draggable="false"
            @dragstart.prevent
            @load="onImageLoad"
          />

          <button
            v-if="startPlaced"
            type="button"
            class="course-map-marker special start"
            :class="{
              active: sameTarget(activeTarget, { kind: 'start' }),
              selected: sameTarget(selectedTarget, { kind: 'start' }) || !!findGpsForSpecial('start'),
              'has-gps': !!findGpsForSpecial('start')?.lat,
            }"
            :style="{
              left: `${(special.start_map_x || 0) * 100}%`,
              top: `${(special.start_map_y || 0) * 100}%`,
            }"
            :title="
              findGpsForSpecial('start')?.lat != null
                ? `Старт — GPS ${findGpsForSpecial('start')?.lat}, ${findGpsForSpecial('start')?.lon}`
                : `Старт — КП ${special.start_cp ?? '—'}`
            "
            @pointerdown="onSpecialMarkerDown($event, 'start')"
            @pointermove="onMarkerPointerMove"
            @pointerup="onMarkerPointerUp"
            @pointercancel="onMarkerPointerUp"
          >
            <span class="marker-glyph" aria-hidden="true">▶</span>
          </button>

          <button
            v-if="finishPlaced"
            type="button"
            class="course-map-marker special finish"
            :class="{
              active: sameTarget(activeTarget, { kind: 'finish' }),
              selected:
                sameTarget(selectedTarget, { kind: 'finish' }) || !!findGpsForSpecial('finish'),
              'has-gps': !!findGpsForSpecial('finish')?.lat,
            }"
            :style="{
              left: `${(special.finish_map_x || 0) * 100}%`,
              top: `${(special.finish_map_y || 0) * 100}%`,
            }"
            :title="
              findGpsForSpecial('finish')?.lat != null
                ? `Финиш — GPS ${findGpsForSpecial('finish')?.lat}, ${findGpsForSpecial('finish')?.lon}`
                : `Финиш — КП ${special.finish_cp}`
            "
            @pointerdown="onSpecialMarkerDown($event, 'finish')"
            @pointermove="onMarkerPointerMove"
            @pointerup="onMarkerPointerUp"
            @pointercancel="onMarkerPointerUp"
          >
            <span class="marker-glyph" aria-hidden="true">⚑</span>
          </button>

          <button
            v-if="pendingGps?.link_kind === 'free' && pendingGps.map_x != null && pendingGps.map_y != null"
            type="button"
            class="course-map-marker gps pending"
            :style="{
              left: `${pendingGps.map_x * 100}%`,
              top: `${pendingGps.map_y * 100}%`,
            }"
            title="Черновик GPS — введите координаты и сохраните"
          >
            …
          </button>

          <button
            v-for="a in freeGpsAnchors"
            :key="`gps-m-${a.id}`"
            type="button"
            class="course-map-marker gps"
            :class="{
              selected: selectedGpsId === a.id,
              'has-coords': a.lat != null && a.lon != null,
            }"
            :style="{
              left: `${a.map_x * 100}%`,
              top: `${a.map_y * 100}%`,
            }"
            :title="
              a.lat != null && a.lon != null
                ? `${a.name}: ${a.lat}, ${a.lon}`
                : `${a.name}: нет GPS`
            "
            @pointerdown.stop.prevent="selectGpsAnchor(a.id, { fromMap: true })"
          >
            G{{ a.id }}
          </button>

          <button
            v-for="row in placedMarkers"
            :key="`m-${row.id}`"
            type="button"
            class="course-map-marker"
            :class="{
              active: sameTarget(activeTarget, { kind: 'legend', id: row.id }),
              selected:
                sameTarget(selectedTarget, { kind: 'legend', id: row.id }) ||
                selectedGpsId === findGpsForLegend(row.id)?.id,
              'has-gps': !!findGpsForLegend(row.id)?.lat,
            }"
            :style="{
              left: `${(row.map_x || 0) * 100}%`,
              top: `${(row.map_y || 0) * 100}%`,
              '--marker-color': typeColor(row.cp_type_name),
            }"
            :title="
              findGpsForLegend(row.id)?.lat != null
                ? `${row.cp_number} — GPS ${findGpsForLegend(row.id)?.lat}, ${findGpsForLegend(row.id)?.lon}`
                : `${row.cp_number} — ${row.name}`
            "
            @pointerdown="onLegendMarkerDown($event, row)"
            @pointermove="onMarkerPointerMove"
            @pointerup="onMarkerPointerUp"
            @pointercancel="onMarkerPointerUp"
          >
            {{ row.cp_number }}
          </button>
        </div>
      </section>
    </div>
    <p v-else class="subtitle">Загрузите карту на вкладке «Легенды КП».</p>
  </main>
</template>
