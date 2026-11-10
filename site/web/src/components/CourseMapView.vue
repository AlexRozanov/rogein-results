<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";

export type PathPoint = {
  x: number;
  y: number;
  cp_number?: number | null;
  kind?: string | null;
};

const props = defineProps<{
  mapUrl: string;
  path: PathPoint[];
  downloadName?: string;
}>();

const emit = defineEmits<{
  size: [size: { width: number; height: number }];
}>();

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

const NUMERIC_PRESETS = SCALE_PRESETS.filter((p) => p.value !== "fit").map((p) => Number(p.value));
const MIN_SCALE = 0.125;
const MAX_SCALE = 4;
const WHEEL_ZOOM_FACTOR = 1.08;

const rootRef = ref<HTMLElement | null>(null);
const viewportRef = ref<HTMLElement | null>(null);
const imageRef = ref<HTMLImageElement | null>(null);
const scaleChoice = ref("fit");
const fitScale = ref(1);
const naturalWidth = ref(0);
const naturalHeight = ref(0);
const isPanning = ref(false);
const isFullscreen = ref(false);
let panLastX = 0;
let panLastY = 0;
let panPointerId: number | null = null;
let resizeObserver: ResizeObserver | null = null;

const effectiveScale = computed(() => {
  if (scaleChoice.value === "fit") return fitScale.value;
  const n = Number(scaleChoice.value);
  return Number.isFinite(n) && n > 0 ? n : fitScale.value;
});

const isCustomScale = computed(() => {
  if (scaleChoice.value === "fit") return false;
  const n = Number(scaleChoice.value);
  return !NUMERIC_PRESETS.some((p) => Math.abs(p - n) < 0.0005);
});

const scalePercentLabel = computed(() => `${Math.round(effectiveScale.value * 1000) / 10}%`);

const stageStyle = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value) return {};
  const s = effectiveScale.value;
  const round = scaleChoice.value === "fit" ? Math.floor : Math.round;
  return {
    width: `${Math.max(1, round(naturalWidth.value * s))}px`,
    height: `${Math.max(1, round(naturalHeight.value * s))}px`,
  };
});

const pathSvgViewBox = computed(() => {
  const w = Math.max(1, naturalWidth.value || 100);
  const h = Math.max(1, naturalHeight.value || 100);
  return `0 0 ${w} ${h}`;
});

const pathStrokeWidth = computed(() => Math.max(1.2, (naturalWidth.value || 1000) * 0.0025));
const pathOutlineWidth = computed(() => pathStrokeWidth.value * 1.7);
const pathDotRadius = computed(() => pathStrokeWidth.value * 0.85);
const pathArrowSize = computed(() => Math.max(6, (naturalWidth.value || 1000) * 0.008));
const pathArrowOutlineSize = computed(() => pathArrowSize.value * 1.35);

const pathSegments = computed(() => {
  const pts = props.path;
  const w = naturalWidth.value || 100;
  const h = naturalHeight.value || 100;
  const segs: { key: string; x1: number; y1: number; x2: number; y2: number; withArrow: boolean }[] = [];
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

const pathDots = computed(() => {
  const w = naturalWidth.value || 100;
  const h = naturalHeight.value || 100;
  return props.path.map((pt, i) => ({
    key: `dot-${i}-${pt.cp_number ?? "x"}`,
    cx: pt.x * w,
    cy: pt.y * h,
  }));
});

function recomputeFitScale() {
  const viewport = viewportRef.value;
  if (!viewport || !naturalWidth.value || !naturalHeight.value) {
    fitScale.value = 1;
    return;
  }
  const availW = Math.max(1, viewport.clientWidth);
  const availH = Math.max(1, viewport.clientHeight);
  const next = Math.min(availW / naturalWidth.value, availH / naturalHeight.value);
  fitScale.value = Math.max(0.01, next);
}

function onImageLoad() {
  const img = imageRef.value;
  if (!img) return;
  naturalWidth.value = img.naturalWidth;
  naturalHeight.value = img.naturalHeight;
  recomputeFitScale();
  if (naturalWidth.value > 0 && naturalHeight.value > 0) {
    emit("size", { width: naturalWidth.value, height: naturalHeight.value });
  }
}

function nearestPreset(scale: number, direction: "up" | "down") {
  const clamped = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
  if (direction === "up") {
    return NUMERIC_PRESETS.find((p) => p > clamped + 0.001) ?? MAX_SCALE;
  }
  return [...NUMERIC_PRESETS].reverse().find((p) => p < clamped - 0.001) ?? MIN_SCALE;
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

function fullscreenElement(): Element | null {
  const doc = document as Document & { webkitFullscreenElement?: Element | null };
  return document.fullscreenElement ?? doc.webkitFullscreenElement ?? null;
}

function requestNativeFullscreen(el: HTMLElement): Promise<void> {
  const anyEl = el as HTMLElement & {
    webkitRequestFullscreen?: () => Promise<void> | void;
  };
  const fn = el.requestFullscreen?.bind(el) ?? anyEl.webkitRequestFullscreen?.bind(el);
  if (!fn) return Promise.reject(new Error("fullscreen unavailable"));
  return Promise.resolve(fn());
}

function exitNativeFullscreen(): Promise<void> {
  const doc = document as Document & { webkitExitFullscreen?: () => Promise<void> | void };
  const fn = document.exitFullscreen?.bind(document) ?? doc.webkitExitFullscreen?.bind(document);
  if (!fn) return Promise.resolve();
  return Promise.resolve(fn());
}

function syncFullscreenFlag() {
  isFullscreen.value = fullscreenElement() === rootRef.value;
}

async function toggleFullscreen() {
  const el = rootRef.value;
  if (!el) return;
  if (fullscreenElement() === el) {
    await exitNativeFullscreen().catch(() => {
      isFullscreen.value = false;
    });
    return;
  }
  if (isFullscreen.value) {
    isFullscreen.value = false;
    return;
  }
  try {
    await requestNativeFullscreen(el);
    isFullscreen.value = true;
  } catch {
    isFullscreen.value = true;
  }
}

function safeDownloadStem(raw: string) {
  const cleaned = raw
    .replace(/[<>:"/\\|?*\u0000-\u001f]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  return (cleaned || "karta").slice(0, 80);
}

function drawArrow(
  ctx: CanvasRenderingContext2D,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  size: number,
  fill: string,
) {
  const angle = Math.atan2(y2 - y1, x2 - x1);
  ctx.save();
  ctx.translate(x2, y2);
  ctx.rotate(angle);
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.lineTo(-size, size / 2);
  ctx.lineTo(-size, -size / 2);
  ctx.closePath();
  ctx.fillStyle = fill;
  ctx.fill();
  ctx.restore();
}

function drawPathOnCanvas(ctx: CanvasRenderingContext2D, width: number, height: number) {
  const pts = props.path;
  if (pts.length === 0) return;
  const mapped = pts.map((pt) => ({ x: pt.x * width, y: pt.y * height }));
  const stroke = Math.max(1.2, width * 0.0025);
  const outline = stroke * 1.7;
  const dot = stroke * 0.85;
  const arrow = Math.max(6, width * 0.008);
  const arrowOutline = arrow * 1.35;

  const strokeLine = (widthPx: number, color: string) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = widthPx;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    mapped.forEach((pt, i) => {
      if (i === 0) ctx.moveTo(pt.x, pt.y);
      else ctx.lineTo(pt.x, pt.y);
    });
    ctx.stroke();
  };

  if (mapped.length > 1) {
    strokeLine(outline, "#000");
    drawArrow(ctx, mapped[0].x, mapped[0].y, mapped[1].x, mapped[1].y, arrowOutline, "#000");
    strokeLine(stroke, "rgba(3, 255, 0, 1)");
    drawArrow(ctx, mapped[0].x, mapped[0].y, mapped[1].x, mapped[1].y, arrow, "rgba(3, 255, 0, 1)");
  }
  for (const pt of mapped) {
    ctx.beginPath();
    ctx.arc(pt.x, pt.y, dot, 0, Math.PI * 2);
    ctx.fillStyle = "#111";
    ctx.fill();
    ctx.lineWidth = Math.max(1, stroke * 0.35);
    ctx.strokeStyle = "#fff";
    ctx.stroke();
  }
}

async function loadMapBitmap(): Promise<CanvasImageSource> {
  const img = imageRef.value;
  if (img && img.naturalWidth > 0) return img;
  const response = await fetch(props.mapUrl);
  if (!response.ok) throw new Error("Не удалось загрузить карту");
  return createImageBitmap(await response.blob());
}

function canvasToPngBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error("tainted"));
    }, "image/png");
  });
}

async function downloadMapWithPath() {
  const width = naturalWidth.value;
  const height = naturalHeight.value;
  if (!width || !height) return;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const paint = async (source: CanvasImageSource) => {
    ctx.clearRect(0, 0, width, height);
    ctx.drawImage(source, 0, 0, width, height);
    drawPathOnCanvas(ctx, width, height);
    return canvasToPngBlob(canvas);
  };
  let blob: Blob;
  try {
    blob = await paint(await loadMapBitmap());
  } catch {
    try {
      const response = await fetch(props.mapUrl);
      if (!response.ok) throw new Error("fetch");
      blob = await paint(await createImageBitmap(await response.blob()));
    } catch {
      window.alert("Не удалось подготовить карту для скачивания.");
      return;
    }
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${safeDownloadStem(props.downloadName || "karta")}.png`;
  link.click();
  URL.revokeObjectURL(url);
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape" && isFullscreen.value && !fullscreenElement()) {
    isFullscreen.value = false;
  }
}

function onFullscreenChange() {
  syncFullscreenFlag();
}

function onViewportWheel(event: WheelEvent) {
  if (!event.ctrlKey) return;
  event.preventDefault();
  const factor = event.deltaY < 0 ? WHEEL_ZOOM_FACTOR : 1 / WHEEL_ZOOM_FACTOR;
  setNumericScale(effectiveScale.value * factor);
}

function onPanStart(event: PointerEvent) {
  if (event.button !== 0 || !viewportRef.value) return;
  isPanning.value = true;
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

onMounted(() => {
  resizeObserver = new ResizeObserver(() => recomputeFitScale());
  if (viewportRef.value) resizeObserver.observe(viewportRef.value);
  window.addEventListener("keydown", onKeydown);
  document.addEventListener("fullscreenchange", onFullscreenChange);
  document.addEventListener("webkitfullscreenchange" as "fullscreenchange", onFullscreenChange);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  window.removeEventListener("keydown", onKeydown);
  document.removeEventListener("fullscreenchange", onFullscreenChange);
  document.removeEventListener("webkitfullscreenchange" as "fullscreenchange", onFullscreenChange);
  if (fullscreenElement() === rootRef.value) {
    void exitNativeFullscreen();
  }
  document.body.style.overflow = "";
});

watch(isFullscreen, async (open) => {
  document.body.style.overflow = open ? "hidden" : "";
  await nextTick();
  recomputeFitScale();
});

watch(
  () => props.mapUrl,
  async () => {
    scaleChoice.value = "fit";
    naturalWidth.value = 0;
    naturalHeight.value = 0;
    await Promise.resolve();
    const img = imageRef.value;
    if (img && img.complete && img.naturalWidth) onImageLoad();
  },
);
</script>

<template>
  <div ref="rootRef" class="course-map" :class="{ 'is-fullscreen': isFullscreen }">
    <div class="course-map-toolbar">
      <label>
        Масштаб
        <select v-model="scaleChoice">
          <option value="fit">Вписать в окно</option>
          <option v-if="isCustomScale" :value="scaleChoice">{{ scalePercentLabel }}</option>
          <option v-for="opt in SCALE_PRESETS.filter((p) => p.value !== 'fit')" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </label>
      <button type="button" title="Уменьшить" @click="zoomOut">−</button>
      <span class="muted">{{ scalePercentLabel }}</span>
      <button type="button" title="Увеличить" @click="zoomIn">+</button>
      <div class="course-map-toolbar-actions">
        <button
          type="button"
          class="course-map-icon-btn"
          title="Скачать карту с путём"
          aria-label="Скачать карту с путём"
          :disabled="!naturalWidth || !naturalHeight"
          @click="downloadMapWithPath"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path
              d="M12 4v11m0 0-4-4m4 4 4-4M5 20h14"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </button>
        <button type="button" class="course-map-fullscreen-btn" @click="toggleFullscreen">
          {{ isFullscreen ? "Свернуть" : "На весь экран" }}
        </button>
      </div>
    </div>

    <div
      ref="viewportRef"
      class="course-map-viewport"
      :class="{ 'is-panning': isPanning, 'is-fitted': scaleChoice === 'fit' }"
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
          :src="mapUrl"
          alt="Карта"
          draggable="false"
          @dragstart.prevent
          @load="onImageLoad"
        />
        <svg
          v-if="naturalWidth && naturalHeight"
          class="course-map-svg"
          :viewBox="pathSvgViewBox"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <defs>
            <marker
              id="site-path-arrow-outline"
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
              id="site-path-arrow"
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
            class="course-map-leg outline"
            :x1="seg.x1"
            :y1="seg.y1"
            :x2="seg.x2"
            :y2="seg.y2"
            :stroke-width="pathOutlineWidth"
            :marker-end="seg.withArrow ? 'url(#site-path-arrow-outline)' : undefined"
          />
          <line
            v-for="seg in pathSegments"
            :key="seg.key"
            class="course-map-leg"
            :x1="seg.x1"
            :y1="seg.y1"
            :x2="seg.x2"
            :y2="seg.y2"
            :stroke-width="pathStrokeWidth"
            :marker-end="seg.withArrow ? 'url(#site-path-arrow)' : undefined"
          />
          <circle
            v-for="dot in pathDots"
            :key="dot.key"
            class="course-map-dot"
            :cx="dot.cx"
            :cy="dot.cy"
            :r="pathDotRadius"
          />
        </svg>
      </div>
    </div>
  </div>
</template>
