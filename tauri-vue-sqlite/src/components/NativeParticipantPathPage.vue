<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { closeAuxiliaryWindowOrClearHash } from "../workspaceUiState";

const props = defineProps<{
  resultId: number | null;
}>();

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

type MarkRow = { seq: number; cp_number: number; mark_time: string };

type ParticipantPathDetails = {
  participant: { id: number | null; participant_id: string; name: string };
  result: { id: number; finish_participant_id: number | null } | null;
  corrected_marks: MarkRow[];
};

type PathPoint = {
  key: string;
  label: string;
  title: string;
  x: number;
  y: number;
  kind: "start" | "finish" | "cp";
  cp_number: number | null;
  cp_type_name: string;
  mark: MarkRow | null;
};

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

const status = ref("Загрузка пути...");
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
const participantName = ref("");
const participantId = ref("");
const finishParticipantId = ref<number | null>(null);
const correctedMarks = ref<MarkRow[]>([]);
const remapMode = ref(false);
const selectedMarkKey = ref<string | null>(null);
const manualToCp = ref<number | null>(null);
const pathDistanceM = ref<number | null>(null);
const pathDistanceStatus = ref("");

const viewportRef = ref<HTMLElement | null>(null);
const imageRef = ref<HTMLImageElement | null>(null);
const isPanning = ref(false);

let resizeObserver: ResizeObserver | null = null;
let panLastX = 0;
let panLastY = 0;
let panPointerId: number | null = null;

const resultIdClean = computed(() => {
  const id = Number(props.resultId);
  return Number.isFinite(id) && id > 0 ? id : null;
});

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

function resolveCpPosition(cpNumber: number): {
  x: number;
  y: number;
  kind: "start" | "finish" | "cp";
  name: string;
  cp_type_name: string;
} | null {
  if (
    special.value.start_cp != null &&
    cpNumber === special.value.start_cp &&
    special.value.start_map_x != null &&
    special.value.start_map_y != null
  ) {
    return {
      x: special.value.start_map_x,
      y: special.value.start_map_y,
      kind: "start",
      name: "Старт",
      cp_type_name: "",
    };
  }
  if (
    cpNumber === special.value.finish_cp &&
    special.value.finish_map_x != null &&
    special.value.finish_map_y != null
  ) {
    return {
      x: special.value.finish_map_x,
      y: special.value.finish_map_y,
      kind: "finish",
      name: "Финиш",
      cp_type_name: "",
    };
  }
  const row = legends.value.find((l) => l.cp_number === cpNumber);
  if (!row || row.map_x == null || row.map_y == null) return null;
  return {
    x: row.map_x,
    y: row.map_y,
    kind: "cp",
    name: row.name,
    cp_type_name: row.cp_type_name,
  };
}

const pathBuild = computed(() => {
  const points: PathPoint[] = [];
  const missing: number[] = [];

  if (special.value.start_map_x != null && special.value.start_map_y != null) {
    points.push({
      key: "start",
      label: "S",
      title:
        special.value.start_cp != null
          ? `Старт (КП ${special.value.start_cp})`
          : "Старт",
      x: special.value.start_map_x,
      y: special.value.start_map_y,
      kind: "start",
      cp_number: special.value.start_cp,
      cp_type_name: "",
      mark: null,
    });
  }

  for (const mark of correctedMarks.value) {
    const pos = resolveCpPosition(mark.cp_number);
    if (!pos) {
      if (!missing.includes(mark.cp_number)) missing.push(mark.cp_number);
      continue;
    }
    const last = points[points.length - 1];
    if (
      last &&
      Math.abs(last.x - pos.x) < 1e-9 &&
      Math.abs(last.y - pos.y) < 1e-9
    ) {
      continue;
    }
    points.push({
      key: `m-${mark.seq}-${mark.cp_number}-${mark.mark_time}`,
      label:
        pos.kind === "start" ? "S" : pos.kind === "finish" ? "F" : String(mark.cp_number),
      title:
        pos.kind === "cp"
          ? `${mark.cp_number} — ${pos.name}`
          : `${pos.name} (КП ${mark.cp_number})`,
      x: pos.x,
      y: pos.y,
      kind: pos.kind,
      cp_number: mark.cp_number,
      cp_type_name: pos.cp_type_name,
      mark,
    });
  }

  return { points, missing };
});

const pathPoints = computed(() => pathBuild.value.points);
const missingOnMap = computed(() => pathBuild.value.missing);

const pathSegments = computed(() => {
  const pts = pathPoints.value;
  const w = naturalWidth.value || 100;
  const h = naturalHeight.value || 100;
  const segs: {
    key: string;
    x1: number;
    y1: number;
    x2: number;
    y2: number;
    withArrow: boolean;
  }[] = [];
  for (let i = 0; i < pts.length - 1; i += 1) {
    segs.push({
      key: `seg-${i}`,
      x1: pts[i].x * w,
      y1: pts[i].y * h,
      x2: pts[i + 1].x * w,
      y2: pts[i + 1].y * h,
      withArrow: i === 0,
    });
  }
  return segs;
});

const pathSvgViewBox = computed(() => {
  const w = Math.max(1, naturalWidth.value || 100);
  const h = Math.max(1, naturalHeight.value || 100);
  return `0 0 ${w} ${h}`;
});

const pathStrokeWidth = computed(() => {
  const w = naturalWidth.value || 1000;
  return Math.max(1.2, w * 0.0025);
});

/** Black outline around the green stroke. */
const pathOutlineWidth = computed(() => pathStrokeWidth.value * 1.7);

const pathDotRadius = computed(() => pathStrokeWidth.value * 0.85);

const pathArrowSize = computed(() => {
  const w = naturalWidth.value || 1000;
  return Math.max(6, w * 0.008);
});

const pathArrowOutlineSize = computed(() => pathArrowSize.value * 1.35);

const pathCpDots = computed(() =>
  pathPoints.value
    .filter((pt) => pt.mark != null)
    .map((pt) => ({
      key: pt.key,
      cx: pt.x * (naturalWidth.value || 100),
      cy: pt.y * (naturalHeight.value || 100),
      mark: pt.mark!,
      selected: selectedMarkKey.value === pt.key,
    })),
);

const placedTargets = computed(() =>
  legends.value.filter((l) => l.map_x != null && l.map_y != null),
);

const selectedMark = computed(() => {
  if (!selectedMarkKey.value) return null;
  return pathCpDots.value.find((d) => d.key === selectedMarkKey.value)?.mark ?? null;
});

function markKey(m: MarkRow) {
  return `m-${m.seq}-${m.cp_number}-${m.mark_time}`;
}

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

function toggleRemapMode() {
  remapMode.value = !remapMode.value;
  selectedMarkKey.value = null;
  manualToCp.value = null;
  if (remapMode.value) {
    status.value =
      `${participantId.value} — ${participantName.value}: режим замены. Выберите отметку на пути (чёрная точка).`;
  } else {
    status.value = `${participantId.value} — ${participantName.value}`;
  }
}

function selectMarkFromPath(dot: { key: string; mark: MarkRow }, event?: Event) {
  event?.stopPropagation();
  event?.preventDefault();
  if (!remapMode.value) return;
  selectedMarkKey.value = dot.key;
  manualToCp.value = null;
  status.value = `Выбрана отметка КП ${dot.mark.cp_number} (${dot.mark.mark_time}). Кликните целевой КП на карте или введите номер.`;
}

function onPanStart(event: PointerEvent) {
  if (event.button !== 0 || !viewportRef.value) return;
  const target = event.target as Element | null;
  if (target?.closest?.(".course-path-cp-hit, .course-map-marker, .course-path-remap-hit")) {
    return;
  }
  isPanning.value = true;
  panLastX = event.clientX;
  panLastY = event.clientY;
  panPointerId = event.pointerId;
  viewportRef.value.setPointerCapture(event.pointerId);
  event.preventDefault();
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
  fitScale.value = Math.min(MAX_SCALE, Math.max(MIN_SCALE, next));
}

function onImageLoad() {
  const img = imageRef.value;
  if (!img) return;
  naturalWidth.value = img.naturalWidth;
  naturalHeight.value = img.naturalHeight;
  recomputeFitScale();
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

function onPanMove(event: PointerEvent) {
  if (!isPanning.value || !viewportRef.value) return;
  if (panPointerId != null && event.pointerId !== panPointerId) return;
  const dx = event.clientX - panLastX;
  const dy = event.clientY - panLastY;
  panLastX = event.clientX;
  panLastY = event.clientY;
  viewportRef.value.scrollLeft -= dx;
  viewportRef.value.scrollTop -= dy;
}

function onPanEnd(event: PointerEvent) {
  if (!isPanning.value) return;
  if (panPointerId != null && event.pointerId !== panPointerId) return;
  const viewport = viewportRef.value;
  if (viewport && panPointerId != null && viewport.hasPointerCapture(panPointerId)) {
    viewport.releasePointerCapture(panPointerId);
  }
  isPanning.value = false;
  panPointerId = null;
}

async function loadAll() {
  const resultId = resultIdClean.value;
  if (!resultId) {
    status.value = "Не указан ID результата.";
    return;
  }

  busy.value = true;
  status.value = "Загрузка пути...";
  try {
    const [details, payload, legendsData, specialData] = await Promise.all([
      invoke<ParticipantPathDetails>("get_participant_details", { resultId }),
      invoke<CourseMapPayload | null>("get_course_map_payload"),
      invoke<CpLegendRow[]>("get_cp_legends"),
      invoke<CourseMapSpecialPoints>("get_course_map_special_points"),
    ]);

    participantName.value = details.participant.name;
    participantId.value = details.participant.participant_id;
    finishParticipantId.value =
      details.result?.finish_participant_id ?? details.participant.id ?? null;
    correctedMarks.value = details.corrected_marks || [];
    legends.value = legendsData;
    special.value = specialData;
    if (selectedMarkKey.value) {
      const still = correctedMarks.value.some((m) => markKey(m) === selectedMarkKey.value);
      if (!still) selectedMarkKey.value = null;
    }

    if (!payload) {
      imageUrl.value = null;
      fileName.value = "";
      naturalWidth.value = 0;
      naturalHeight.value = 0;
      status.value = "Карта не загружена.";
      return;
    }

    fileName.value = payload.file_name;
    const keepView = Boolean(imageUrl.value);
    if (!keepView) {
      scaleChoice.value = "fit";
      naturalWidth.value = 0;
      naturalHeight.value = 0;
    }
    imageUrl.value = `data:${payload.mime_type};base64,${payload.data_base64}`;
    if (remapMode.value && selectedMark.value) {
      status.value = `Выбрана отметка КП ${selectedMark.value.cp_number}. Кликните целевой КП или введите номер.`;
    } else if (remapMode.value) {
      status.value =
        `${details.participant.participant_id} — ${details.participant.name}: режим замены. Выберите отметку на пути.`;
    } else {
      status.value = `${details.participant.participant_id} — ${details.participant.name}`;
    }

    try {
      const dist = await invoke<{
        ready: boolean;
        status: string;
        distance_m: number | null;
      }>("get_participant_path_distance", { resultId });
      pathDistanceM.value = dist.distance_m;
      pathDistanceStatus.value = dist.status;
    } catch (error) {
      pathDistanceM.value = null;
      pathDistanceStatus.value = `Расстояние: ${String(error)}`;
    }
  } catch (error) {
    imageUrl.value = null;
    status.value = `Ошибка загрузки пути: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function applyRemapToCp(toCp: number) {
  const mark = selectedMark.value;
  const finishId = finishParticipantId.value;
  if (!mark) {
    status.value = "Сначала выберите отметку на пути.";
    return;
  }
  if (finishId == null || finishId <= 0) {
    status.value = "Нет finish_participant_id для корректировки.";
    return;
  }
  const target = Number(toCp);
  if (!Number.isFinite(target) || target <= 0) {
    status.value = "Укажите корректный номер целевого КП.";
    return;
  }
  if (target === mark.cp_number) {
    status.value = "Целевой КП совпадает с текущим.";
    return;
  }

  busy.value = true;
  try {
    const result = await invoke<{
      inserted: number;
      skipped_existing: number;
      failed: number;
    }>("apply_cp_remap_corrections", {
      items: [
        {
          finishParticipantId: finishId,
          fromCp: mark.cp_number,
          toCp: target,
          markTime: mark.mark_time,
          seq: mark.seq,
        },
      ],
    });
    await invoke("recalculate_results");
    selectedMarkKey.value = null;
    manualToCp.value = null;
    const msg =
      `Замена ${mark.cp_number}→${target}: добавлено ${result.inserted}, уже было ${result.skipped_existing}, ошибок ${result.failed}.` +
      (remapMode.value ? " Выберите следующую отметку или выйдите из режима." : "");
    busy.value = false;
    await loadAll();
    status.value = msg;
  } catch (error) {
    status.value = `Ошибка замены КП: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function applyManualToCp() {
  if (manualToCp.value == null) return;
  await applyRemapToCp(manualToCp.value);
}

function onTargetCpClick(cpNumber: number, event: Event) {
  event.stopPropagation();
  event.preventDefault();
  if (!remapMode.value || !selectedMark.value) return;
  void applyRemapToCp(cpNumber);
}

async function closeWindow() {
  await closeAuxiliaryWindowOrClearHash(["course-path", "course-map"]);
}

watch(resultIdClean, () => {
  void loadAll();
});

watch(viewportRef, (el, prev) => {
  if (prev && resizeObserver) resizeObserver.unobserve(prev);
  if (el && resizeObserver) {
    resizeObserver.observe(el);
    recomputeFitScale();
  }
});

onMounted(() => {
  resizeObserver = new ResizeObserver(() => recomputeFitScale());
  if (viewportRef.value) resizeObserver.observe(viewportRef.value);
  void loadAll();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  isPanning.value = false;
  panPointerId = null;
});
</script>

<template>
  <main class="container course-map-page course-path-page">
    <section class="native-tools">
      <div class="native-row">
        <button type="button" @click="closeWindow">Закрыть</button>
        <button type="button" :disabled="busy" @click="loadAll">Обновить</button>
        <button
          type="button"
          :disabled="busy || !imageUrl"
          :class="{ active: remapMode }"
          title="Ручная замена КП для этой отметки участника"
          @click="toggleRemapMode"
        >
          {{ remapMode ? "Выйти из замены" : "Замена КП" }}
        </button>
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

      <div v-if="remapMode" class="native-row course-path-remap-bar">
        <span class="subtitle">
          <template v-if="selectedMark">
            Отметка: КП {{ selectedMark.cp_number }} · {{ selectedMark.mark_time }}
            → целевой КП
          </template>
          <template v-else>Сначала кликните чёрную точку на пути</template>
        </span>
        <label>
          Номер КП
          <input
            v-model.number="manualToCp"
            type="number"
            min="1"
            :disabled="busy || !selectedMark"
            style="width: 5.5rem"
            @keydown.enter.prevent="applyManualToCp"
          />
        </label>
        <button
          type="button"
          :disabled="busy || !selectedMark || manualToCp == null"
          @click="applyManualToCp"
        >
          Применить
        </button>
      </div>

      <p class="status">{{ status }}</p>
      <p v-if="pathDistanceStatus" class="subtitle">
        <template v-if="pathDistanceM != null">
          Длина пути ≈ {{ (pathDistanceM / 1000).toFixed(2) }} км ({{ Math.round(pathDistanceM) }} м)
        </template>
        <template v-else>{{ pathDistanceStatus }}</template>
      </p>
      <p v-if="imageUrl" class="subtitle">
        Путь: {{ Math.max(0, pathPoints.length - (pathPoints[0]?.kind === 'start' ? 1 : 0)) }} КП
        на карте
        <template v-if="missingOnMap.length">
          · без координат: {{ missingOnMap.join(", ") }}
        </template>
        · Ctrl+колёсико — масштаб · ЛКМ — сдвиг
        <template v-if="remapMode">
          · замена: точка пути → КП на карте
        </template>
      </p>
    </section>

    <div v-if="imageUrl" class="course-path-viewport-wrap">
      <section
        ref="viewportRef"
        class="course-map-viewport"
        :class="{ 'is-panning': isPanning, 'is-remap-mode': remapMode }"
        @wheel="onViewportWheel"
        @pointerdown="onPanStart"
        @pointermove="onPanMove"
        @pointerup="onPanEnd"
        @pointercancel="onPanEnd"
      >
        <div class="course-map-stage" :style="stageStyle">
          <img
            ref="imageRef"
            class="course-map-image"
            :src="imageUrl"
            :alt="fileName || 'Карта'"
            draggable="false"
            @dragstart.prevent
            @load="onImageLoad"
          />

          <svg
            v-if="naturalWidth && naturalHeight"
            class="course-path-svg"
            :viewBox="pathSvgViewBox"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <defs>
              <marker
                id="course-path-arrow-outline"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                :markerWidth="pathArrowOutlineSize"
                :markerHeight="pathArrowOutlineSize"
                markerUnits="userSpaceOnUse"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#000" />
              </marker>
              <marker
                id="course-path-arrow"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                :markerWidth="pathArrowSize"
                :markerHeight="pathArrowSize"
                markerUnits="userSpaceOnUse"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="rgba(3, 255, 0, 1)" />
              </marker>
            </defs>

            <line
              v-for="seg in pathSegments"
              :key="`${seg.key}-outline`"
              class="course-path-leg outline"
              :x1="seg.x1"
              :y1="seg.y1"
              :x2="seg.x2"
              :y2="seg.y2"
              :stroke-width="pathOutlineWidth"
              :marker-end="seg.withArrow ? 'url(#course-path-arrow-outline)' : undefined"
            />
            <line
              v-for="seg in pathSegments"
              :key="seg.key"
              class="course-path-leg"
              :x1="seg.x1"
              :y1="seg.y1"
              :x2="seg.x2"
              :y2="seg.y2"
              :stroke-width="pathStrokeWidth"
              :marker-end="seg.withArrow ? 'url(#course-path-arrow)' : undefined"
            />

            <g
              v-for="dot in pathCpDots"
              :key="`dot-${dot.key}`"
              class="course-path-cp-hit"
              :class="{ selected: dot.selected, interactive: remapMode }"
              @pointerdown="selectMarkFromPath(dot, $event)"
            >
              <circle
                class="course-path-cp-hit-area"
                :cx="dot.cx"
                :cy="dot.cy"
                :r="Math.max(pathDotRadius * 3.2, pathStrokeWidth * 2.5)"
              />
              <circle
                class="course-path-cp-dot"
                :class="{ selected: dot.selected }"
                :cx="dot.cx"
                :cy="dot.cy"
                :r="pathDotRadius * (dot.selected ? 1.45 : 1)"
              />
            </g>
          </svg>

          <template v-if="remapMode && selectedMark">
            <button
              v-for="row in placedTargets"
              :key="`target-${row.id}`"
              type="button"
              class="course-map-marker course-path-remap-hit"
              :class="{
                'is-same-cp': row.cp_number === selectedMark.cp_number,
              }"
              :style="{
                left: `${(row.map_x || 0) * 100}%`,
                top: `${(row.map_y || 0) * 100}%`,
                '--marker-color': typeColor(row.cp_type_name),
              }"
              :title="`Заменить на ${row.cp_number} — ${row.name}`"
              :disabled="busy || row.cp_number === selectedMark.cp_number"
              @pointerdown="onTargetCpClick(row.cp_number, $event)"
            >
              {{ row.cp_number }}
            </button>
          </template>
        </div>
      </section>
    </div>
    <p v-else class="subtitle">Нет карты для отображения пути.</p>
  </main>
</template>
