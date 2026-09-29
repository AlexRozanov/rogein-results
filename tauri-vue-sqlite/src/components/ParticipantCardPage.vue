<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import {
  closeAuxiliaryWindowOrClearHash,
  markReturnToResultsTab,
  openAuxWebviewWindow,
} from "../workspaceUiState";
import AddToStartProtocolDialog, {
  type AddToStartProtocolDraft,
} from "./AddToStartProtocolDialog.vue";
import IconActionButton from "./IconActionButton.vue";

const props = defineProps<{
  resultId: number | null;
}>();

type NativeParticipantDetails = {
  participant: {
    id: number | null;
    chip_raw_id: string | null;
    participant_id: string;
    name: string;
    start_station_id: number;
    start_time: string;
    source_row: number | null;
  };
  result: {
    id: number;
    finish_participant_id: number | null;
    chip_raw_id: string | null;
    participant_id: string;
    name: string;
    status: "OK" | "Дисквалификация" | "Ошибка" | "Не стартовал" | "Нет в протоколе";
    format_name?: string;
    team_id?: number | null;
    team_size?: number;
    gender?: string | null;
    age?: number | null;
    has_anomalies: boolean;
    anomaly_count: number;
    points_raw: number;
    penalty_points: number;
    points_final: number;
    elapsed_seconds: number;
    delay_seconds: number;
    penalty_minutes: number;
    diagnostics_json: string;
    computed_at: string;
    place?: number | null;
  } | null;
  anomalies: {
    anomaly_type: string;
    title: string;
    details: string;
    payload: Record<string, unknown>;
    resolved: boolean;
    resolved_correction_id: number | null;
    resolved_at: string | null;
  }[];
  anomalies_history: {
    anomaly_type: string;
    title: string;
    details: string;
    payload: Record<string, unknown>;
    resolved: boolean;
    resolved_correction_id: number | null;
    resolved_at: string | null;
  }[];
  raw_marks: { seq: number; cp_number: number; mark_time: string }[];
  corrected_marks: { seq: number; cp_number: number; mark_time: string }[];
  excluded_legs: {
    from_cp: number;
    to_cp: number;
    direction: string;
    apply_mode: string;
    max_leg_seconds: number | null;
    source: string;
  }[];
  corrections: {
    id: number;
    finish_participant_id: number | null;
    scope: "global" | "personal";
    source_table: "manual_corrections" | "legacy_corrections";
    correction_type: string;
    payload: Record<string, unknown>;
    created_at: string;
  }[];
};

const details = ref<NativeParticipantDetails | null>(null);
const status = ref("Загрузка карточки...");
const busy = ref(false);
const addCpNumber = ref<number | null>(null);
const removeCpNumber = ref<number | null>(null);
const removeMode = ref<"remove_legs" | "points_only">("remove_legs");
const addToProtocolOpen = ref(false);

type PathDistanceInfo = {
  ready: boolean;
  status: string;
  distance_m: number | null;
  legs_counted: number;
  legs_missing: number;
  missing_cps: number[];
};

const pathDistance = ref<PathDistanceInfo | null>(null);
const sportKind = ref<"rogaine" | "orient">("rogaine");
const startCp = ref<number | null>(null);
const finishCp = ref<number>(0);
const courseControls = ref<number[]>([]);

const isOrient = computed(() => sportKind.value === "orient");

const resultIdClean = computed(() => {
  const id = Number(props.resultId);
  return Number.isFinite(id) && id > 0 ? id : null;
});
/** Surrogate finish-dump id used for personal corrections / anomalies. */
const finishKeyClean = computed(() => {
  const fromParticipant = details.value?.participant.id;
  if (fromParticipant != null && Number.isFinite(fromParticipant) && fromParticipant > 0) {
    return String(fromParticipant);
  }
  const fromResult = details.value?.result?.finish_participant_id;
  if (fromResult != null && Number.isFinite(fromResult) && fromResult > 0) {
    return String(fromResult);
  }
  return "";
});
const anomalyDayShiftCorrection = computed(() =>
  details.value?.corrections.find(
    (c) => c.scope === "personal" && c.correction_type === "anomaly_day_shift_24h",
  ) ?? null,
);
const startMarkCorrection = computed(() =>
  details.value?.corrections.find(
    (c) => c.scope === "personal" && c.correction_type === "set_start_mark",
  ) ?? null,
);
const startPunchAnomaly = computed(() => {
  const list = [
    ...(details.value?.anomalies ?? []),
    ...(details.value?.anomalies_history ?? []),
  ];
  return (
    list.find(
      (a) =>
        a.anomaly_type === "start_punch_missing" ||
        a.anomaly_type === "start_punch_after_finish",
    ) ?? null
  );
});
const startMarkDraft = ref("");

function toDatetimeLocal(sql: string) {
  const raw = String(sql || "").trim();
  const m = raw.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}(?::\d{2})?)/);
  if (!m) return "";
  const time = m[2].length === 5 ? `${m[2]}:00` : m[2];
  return `${m[1]}T${time}`;
}

function fromDatetimeLocal(value: string) {
  const raw = value.trim().replace("T", " ");
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(raw)) return `${raw}:00`;
  return raw;
}

const pathMetrics = computed(() => {
  const distM = pathDistance.value?.distance_m;
  const result = details.value?.result;
  if (
    !pathDistance.value?.ready ||
    distM == null ||
    !Number.isFinite(distM) ||
    distM <= 0 ||
    !result
  ) {
    return null;
  }
  const km = distM / 1000;
  const elapsed = Math.max(0, Number(result.elapsed_seconds || 0));
  const hours = elapsed / 3600;
  const points = Number(result.points_final || 0);

  const speedKmh = hours > 0 ? km / hours : null;
  const paceSecPerKm = km > 0 && elapsed > 0 ? elapsed / km : null;
  const pointsPerKm = isOrient.value ? null : km > 0 ? points / km : null;
  const pointsPerHour = isOrient.value ? null : hours > 0 ? points / hours : null;

  return {
    distanceKm: km,
    speedKmh,
    paceSecPerKm,
    pointsPerKm,
    pointsPerHour,
    legsMissing: pathDistance.value.legs_missing,
    status: pathDistance.value.status,
  };
});

const canAddToProtocol = computed(() => {
  if (isOrient.value) return false;
  const result = details.value?.result;
  if (!result) return false;
  if (result.status === "Нет в протоколе") return true;
  return String(result.diagnostics_json || "").includes("not_in_start_protocol");
});

const courseProgress = computed(() => {
  const required = courseControls.value;
  if (!required.length) return [];
  const marks = details.value?.corrected_marks ?? [];
  const start = startCp.value;
  const finish = finishCp.value;
  let startIdx = start != null ? marks.findIndex((m) => m.cp_number === start) : 0;
  if (startIdx < 0) startIdx = 0;
  let finishIdx = -1;
  for (let i = marks.length - 1; i > startIdx; i -= 1) {
    if (marks[i].cp_number === finish) {
      finishIdx = i;
      break;
    }
  }
  const end = finishIdx >= 0 ? finishIdx : marks.length - 1;
  const punches = marks.slice(startIdx, end + 1).map((m) => m.cp_number);
  const punched = new Set(punches);
  let i = 0;
  let firstMiss = true;
  return required.map((cp) => {
    while (i < punches.length && punches[i] !== cp) i += 1;
    const taken = i < punches.length && punches[i] === cp;
    if (taken) i += 1;
    const blocker = !taken && firstMiss;
    if (!taken) firstMiss = false;
    return { cp, taken, punched: punched.has(cp), blocker };
  });
});

const missingCourseCps = computed(() => courseProgress.value.filter((item) => !item.taken));
const addableCourseCps = computed(() =>
  courseProgress.value.filter((item) => !item.taken && !item.punched),
);
const firstMissedCp = computed(() => courseProgress.value.find((item) => item.blocker)?.cp ?? null);

function courseCpTitle(item: { taken: boolean; punched: boolean; blocker: boolean }) {
  if (item.taken) return "взято в порядке дистанции";
  if (item.punched) return "есть отметка, но не в зачёт: предыдущий КП не взят";
  if (item.blocker) return "не взято — с этого КП дистанция не засчитана";
  return "не взято";
}

const addToProtocolDraft = computed<AddToStartProtocolDraft | null>(() => {
  if (!details.value) return null;
  return {
    participant_id: details.value.participant.participant_id,
    name: details.value.participant.name,
  };
});

function fmtHms(totalSeconds: number) {
  const s = Math.max(0, Number(totalSeconds || 0));
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function fmtNum(value: number | null | undefined, digits = 2) {
  if (value == null || !Number.isFinite(value)) return "—";
  return value.toFixed(digits);
}

function fmtPace(secPerKm: number | null | undefined) {
  if (secPerKm == null || !Number.isFinite(secPerKm) || secPerKm <= 0) return "—";
  const total = Math.round(secPerKm);
  const mm = Math.floor(total / 60);
  const ss = String(total % 60).padStart(2, "0");
  return `${mm}:${ss} /км`;
}

function diffSeconds(prevMarkTime: string, currentMarkTime: string) {
  const prev = new Date(prevMarkTime.replace(" ", "T"));
  const cur = new Date(currentMarkTime.replace(" ", "T"));
  return Math.max(0, Math.round((cur.getTime() - prev.getTime()) / 1000));
}

function diagnosticsText() {
  const diagRaw = details.value?.result?.diagnostics_json;
  if (!diagRaw) return "-";
  let parsed: unknown;
  try {
    parsed = JSON.parse(diagRaw);
  } catch {
    return diagRaw;
  }
  if (!Array.isArray(parsed) || !parsed.length) return "-";
  return parsed.map((item) => diagnosticLabel(String(item))).join(", ");
}

function diagnosticLabel(code: string) {
  if (code === "missing_course") return "Нет дистанции";
  if (code === "course_empty") return "Дистанция без КП";
  if (code === "course_unknown") return "Дистанция не найдена";
  if (code === "start_cp_not_configured") return "Не задана стартовая станция";
  if (code === "no_marks") return "Нет отметок";
  if (code === "start_missing") return "Нет отметки старта";
  if (code === "finish_missing") return "Нет отметки финиша";
  if (code === "course_incomplete") return "Дистанция пройдена не полностью";
  if (code === "overtime") return "Превышение контрольного времени";
  return code;
}

function correctionTypeText(correctionType: string) {
  if (correctionType === "add_cp") return "Добавление КП";
  if (correctionType === "remove_cp") return "Удаление КП";
  if (correctionType === "exclude_leg_time") return "Исключение перегона";
  if (correctionType === "anomaly_day_shift_24h") return "Коррекция аномалии: -24ч";
  if (correctionType === "set_start_mark") return "Время стартовой отметки";
  if (correctionType === "remap_cp") return "Замена КП (станция)";
  return correctionType;
}

function directionText(direction: string) {
  if (direction === "forward") return "Прямое (от -> до)";
  if (direction === "reverse") return "Обратное (до -> от)";
  if (direction === "both") return "Оба направления";
  return direction || "-";
}

function applyModeText(applyMode: string) {
  if (applyMode === "once") return "Один раз";
  if (applyMode === "always") return "Всегда";
  return applyMode || "-";
}

function legSourceText(source: string) {
  if (source === "global_rule") return "Общее правило";
  if (source === "personal_rule") return "Персональное правило";
  return source || "-";
}

function anomalyTypeText(anomalyType: string) {
  if (anomalyType === "start_time_day_shift") return "Сдвиг даты старта";
  if (anomalyType === "start_punch_missing") return "Нет отметки старта";
  if (anomalyType === "start_punch_after_finish") return "Старт позже финиша";
  return anomalyType;
}

function correctionPayloadText(c: NativeParticipantDetails["corrections"][number]) {
  const cp = Number(c.payload?.cp_number);
  const fromCp = Number(c.payload?.from_cp);
  const toCp = Number(c.payload?.to_cp);
  const removeMode = c.payload?.remove_mode;

  if (c.correction_type === "add_cp") {
    return Number.isFinite(cp) ? `Номер КП: ${cp}` : "Номер КП: -";
  }

  if (c.correction_type === "remove_cp") {
    const cpText = Number.isFinite(cp) ? cp : "-";
    if (removeMode === "from_course") {
      return `КП ${cpText}: снят с дистанции для всех`;
    }
    const modeText = removeMode === "points_only" ? "только очки" : "очки + перегоны";
    return `Номер КП: ${cpText}; действие: ${modeText}`;
  }

  if (c.correction_type === "exclude_leg_time") {
    const fromText = Number.isFinite(fromCp) ? fromCp : "-";
    const toText = Number.isFinite(toCp) ? toCp : "-";
    return `Перегон: ${fromText} -> ${toText}`;
  }

  if (c.correction_type === "anomaly_day_shift_24h") {
    const seconds = Number(c.payload?.seconds);
    return `Коррекция времени: -${Number.isFinite(seconds) ? seconds : 86400} сек (-24ч)`;
  }

  if (c.correction_type === "set_start_mark") {
    return `Время старта: ${String(c.payload?.mark_time || "-")}`;
  }

  if (c.correction_type === "remap_cp") {
    const fromText = Number.isFinite(fromCp) ? fromCp : "-";
    const toText = Number.isFinite(toCp) ? toCp : "-";
    const when = String(c.payload?.mark_time || "-");
    return `Замена: ${fromText} → ${toText} @ ${when}`;
  }

  return JSON.stringify(c.payload);
}

async function loadDetails() {
  const resultId = resultIdClean.value;
  if (!resultId) {
    status.value = "Не указан ID результата";
    details.value = null;
    pathDistance.value = null;
    return;
  }
  status.value = "Загрузка карточки...";
  try {
    const response = await invoke<NativeParticipantDetails>("get_participant_details", {
      resultId,
    });
    details.value = response;
    status.value = `Загружен результат #${resultId}: ${response.participant.name} (id ${response.participant.participant_id})`;
    try {
      const settings = await invoke<{
        sport_kind?: string;
        start_cp?: number | null;
        finish_cp?: number;
      }>("get_settings");
      sportKind.value = settings.sport_kind === "orient" ? "orient" : "rogaine";
      startCp.value = settings.start_cp ?? null;
      finishCp.value = Number(settings.finish_cp || 0);
    } catch {
      sportKind.value = "rogaine";
    }
    courseControls.value = [];
    if (sportKind.value === "orient") {
      try {
        const [courses, corrections] = await Promise.all([
          invoke<{ id: number; name: string; controls: number[] }[]>("get_courses"),
          invoke<{ correction_type: string; payload: Record<string, unknown> }[]>(
            "get_manual_corrections",
            { participantId: null },
          ),
        ]);
        const removed = new Set(
          corrections
            .filter((row) => row.correction_type === "remove_cp")
            .map((row) => Number(row.payload?.cp_number))
            .filter((cp) => Number.isFinite(cp)),
        );
        const name = String(response.result?.format_name || "").trim();
        courseControls.value = (courses.find((c) => c.name === name)?.controls ?? []).filter(
          (cp) => !removed.has(cp),
        );
      } catch {
        courseControls.value = [];
      }
    }
    try {
      pathDistance.value = await invoke<PathDistanceInfo>("get_participant_path_distance", {
        resultId,
      });
    } catch {
      pathDistance.value = null;
    }
  } catch (error) {
    details.value = null;
    pathDistance.value = null;
    status.value = `Ошибка: ${String(error)}`;
  }
}

async function recalculateAndReload() {
  const bib = details.value?.participant.participant_id?.trim() || "";
  const name = details.value?.result?.name || details.value?.participant.name || "";
  if (bib && name) {
    const newId = await invoke<number | null>("find_result_id", {
      participantId: bib,
      name,
    });
    if (newId && newId !== resultIdClean.value) {
      window.location.hash = `#result/${newId}`;
      return;
    }
  }
  await loadDetails();
}

async function addPersonalCp() {
  const pid = finishKeyClean.value;
  if (!pid || !addCpNumber.value) {
    status.value = isOrient.value
      ? "Выберите КП из дистанции участника."
      : "Нет связи с финишным дампом или не указан номер КП.";
    return;
  }
  if (isOrient.value) {
    const allowed = addableCourseCps.value.some((item) => item.cp === Number(addCpNumber.value));
    if (!allowed) {
      status.value = "Можно добавить только КП дистанции, которого нет в отметках.";
      return;
    }
  }
  busy.value = true;
  try {
    await invoke("add_cp_correction", {
      participantId: pid,
      cpNumber: Number(addCpNumber.value),
    });
    status.value = "Персональная корректировка добавлена, результат пересчитан.";
    addCpNumber.value = null;
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка add-cp: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function removePersonalCp() {
  const pid = finishKeyClean.value;
  if (!pid || !removeCpNumber.value) {
    status.value = "Нет связи с финишным дампом или не указан номер КП.";
    return;
  }
  busy.value = true;
  try {
    await invoke("remove_cp_correction", {
      participantId: pid,
      cpNumber: Number(removeCpNumber.value),
      removeMode: isOrient.value ? "remove_legs" : removeMode.value,
      participantScope: "one",
    });
    status.value = "Персональная корректировка удаления добавлена, результат пересчитан.";
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка remove-cp: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function undoCorrection(correctionId: number, sourceTable: string) {
  busy.value = true;
  try {
    await invoke("delete_correction_entry", {
      sourceTable,
      correctionId,
    });
    status.value = `Корректировка #${correctionId} отменена, результат пересчитан.`;
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка отмены корректировки: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function applyAnomalyDayShiftCorrection() {
  const pid = finishKeyClean.value;
  if (!pid) return;
  busy.value = true;
  try {
    await invoke("add_anomaly_day_shift_correction", { participantId: pid });
    status.value = "Корректировка аномалии (-24ч) добавлена, результат пересчитан.";
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка корректировки аномалии: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function applyStartMarkCorrection() {
  const pid = finishKeyClean.value;
  if (!pid) return;
  const markTime = fromDatetimeLocal(startMarkDraft.value);
  if (!/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(markTime)) {
    status.value = "Укажите время стартовой отметки.";
    return;
  }
  busy.value = true;
  try {
    await invoke("set_start_mark_correction", {
      participantId: pid,
      markTime,
    });
    status.value = "Время стартовой отметки сохранено, результат пересчитан.";
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка времени старта: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

watch(resultIdClean, () => {
  void loadDetails();
});

watch([startPunchAnomaly, startMarkCorrection], ([anomaly, correction]) => {
  const fromCorrection = String(correction?.payload?.mark_time || "");
  const fromAnomaly = String(anomaly?.payload?.suggested_start_time || "");
  startMarkDraft.value = toDatetimeLocal(fromCorrection || fromAnomaly);
});

onMounted(() => {
  void loadDetails();
});

async function goBackToResults() {
  markReturnToResultsTab();
  await closeAuxiliaryWindowOrClearHash(["participant-card"]);
}

function openAddToProtocol() {
  addToProtocolOpen.value = true;
}

function closeAddToProtocol() {
  addToProtocolOpen.value = false;
}

async function onAddedToProtocol() {
  status.value = "Участник добавлен в стартовый протокол. Обновляю карточку...";
  await loadDetails();
}

async function openPathWindow() {
  const resultId = resultIdClean.value;
  if (!resultId || !details.value?.result) {
    status.value = "Нет результата для отображения пути.";
    return;
  }
  if (!details.value.corrected_marks.length) {
    status.value = "Нет отметок КП для построения пути.";
    return;
  }

  busy.value = true;
  try {
    const [mapInfo, legends] = await Promise.all([
      invoke<{ has_map: boolean }>("get_course_map_info"),
      invoke<{ map_x: number | null; map_y: number | null }[]>("get_cp_legends"),
    ]);
    if (!mapInfo.has_map) {
      status.value = "Сначала загрузите карту на вкладке «Легенды КП».";
      return;
    }
    const placed = legends.some((l) => l.map_x != null && l.map_y != null);
    let specialPlaced = false;
    try {
      const special = await invoke<{
        start_map_x: number | null;
        start_map_y: number | null;
        finish_map_x: number | null;
        finish_map_y: number | null;
      }>("get_course_map_special_points");
      specialPlaced =
        (special.start_map_x != null && special.start_map_y != null) ||
        (special.finish_map_x != null && special.finish_map_y != null);
    } catch {
      // ignore
    }
    if (!placed && !specialPlaced) {
      status.value = "На карте ещё нет размещённых КП. Отметьте точки в окне карты.";
      return;
    }

    const targetHash = `#course-path/${resultId}`;
    const name = details.value.participant.name;
    const bib = details.value.participant.participant_id;
    const opened = await openAuxWebviewWindow({
      label: "course-path",
      title: `Путь — ${bib} ${name}`,
      width: 1280,
      height: 900,
      url: targetHash,
    });
    if (opened.ok) {
      status.value = "Путь открыт в отдельном окне.";
    } else {
      status.value = "Не удалось открыть отдельное окно пути, открыл в текущем окне.";
      window.location.hash = targetHash;
      console.error(opened.error);
    }
  } catch (error) {
    status.value = `Ошибка открытия пути: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="container">
    <section class="native-tools">
      <div class="native-row">
        <button type="button" @click="goBackToResults">← Назад к результатам</button>
        <button
          v-if="canAddToProtocol"
          type="button"
          :disabled="busy"
          @click="openAddToProtocol"
        >
          Добавить в протокол
        </button>
        <button
          v-if="details?.result && !isOrient"
          type="button"
          :disabled="busy || !details.corrected_marks.length"
          title="Показать путь на карте по взятым КП"
          @click="openPathWindow"
        >
          Показать путь
        </button>
      </div>
      <h2>Карточка участника</h2>
      <p class="status">{{ status }}</p>
    </section>

    <section v-if="details" class="native-tools">
      <h2>Персональные корректировки</h2>
      <div class="native-settings">
        <label v-if="isOrient">
          Добавить КП
          <select
            :value="addCpNumber == null ? '' : String(addCpNumber)"
            :disabled="busy || !finishKeyClean || !addableCourseCps.length"
            @change="
              addCpNumber = ($event.target as HTMLSelectElement).value
                ? Number(($event.target as HTMLSelectElement).value)
                : null
            "
          >
            <option value="">КП без отметки</option>
            <option
              v-for="item in addableCourseCps"
              :key="`add-cp-${item.cp}`"
              :value="String(item.cp)"
            >
              {{ item.cp }}
            </option>
          </select>
        </label>
        <label v-else>
          Добавить КП (номер)
          <input v-model.number="addCpNumber" type="number" min="1" />
        </label>
        <button
          :disabled="busy || !finishKeyClean || (isOrient && (addCpNumber == null || !addableCourseCps.length))"
          :title="
            isOrient
              ? 'Если станция не сработала, но участник доказал отметку'
              : undefined
          "
          @click="addPersonalCp"
        >
          Добавить корректировку
        </button>
      </div>
      <p v-if="isOrient" class="subtitle">
        Добавление КП — если станция не сработала, но участник доказал, что отмечался.
        В списке только КП этой дистанции без отметки. Снять КП со всех дистанций можно в настройках.
      </p>
      <div v-if="!isOrient" class="native-settings">
        <label>
          Удалить КП (номер)
          <input v-model.number="removeCpNumber" type="number" min="1" />
        </label>
        <label>
          Действие
          <select v-model="removeMode">
            <option value="remove_legs">Удалить перегоны</option>
            <option value="points_only">Удалить только очки</option>
          </select>
        </label>
        <button :disabled="busy || !finishKeyClean" @click="removePersonalCp">Добавить корректировку</button>
      </div>
    </section>

    <section v-if="details" class="native-tools nested-card">
      <h3>Сводка</h3>
      <table class="native-results">
        <tbody>
          <tr><th>ID</th><td>{{ details.participant.participant_id }}</td></tr>
          <tr><th>Chip raw id</th><td>{{ details.participant.chip_raw_id || "—" }}</td></tr>
          <tr><th>Имя</th><td>{{ details.participant.name }}</td></tr>
          <tr v-if="isOrient">
            <th>Дистанция</th>
            <td>{{ details.result?.format_name || "—" }}</td>
          </tr>
          <tr v-if="isOrient">
            <th>Место</th>
            <td>{{ details.result?.place ?? "—" }}</td>
          </tr>
          <tr v-if="!isOrient"><th>Пол</th><td>{{ details.result?.gender?.trim() || "—" }}</td></tr>
          <tr v-if="!isOrient">
            <th>Возраст</th>
            <td>
              {{ details.result?.age != null ? details.result.age : "—" }}
              <span
                v-if="(details.result?.team_size ?? 0) > 1"
                class="subtitle"
              >
                (для награждения в команде — возраст самого младшего)
              </span>
            </td>
          </tr>
          <tr><th>Старт КП</th><td>{{ details.participant.start_station_id }}</td></tr>
          <tr><th>Старт</th><td>{{ details.participant.start_time }}</td></tr>
          <tr><th>Статус</th><td>{{ details.result?.status ?? "-" }}</td></tr>
          <tr v-if="isOrient">
            <th>КП</th>
            <td>
              {{ details.result?.points_raw ?? 0 }}
              <template v-if="courseControls.length">
                / {{ courseControls.length }}
              </template>
            </td>
          </tr>
          <tr v-if="isOrient && courseProgress.length">
            <th>Порядок дистанции</th>
            <td class="course-seq-cell">
              <div class="course-chip-row">
                <template v-for="(item, idx) in courseProgress" :key="`cp-${idx}-${item.cp}`">
                  <span
                    class="course-chip"
                    :class="{
                      muted: !item.taken && !item.punched,
                      'out-of-order': !item.taken && item.punched,
                      blocker: item.blocker,
                    }"
                    :title="courseCpTitle(item)"
                  >
                    <span class="course-chip-num">{{ item.cp }}</span>
                  </span>
                  <span v-if="idx < courseProgress.length - 1" class="course-seq-arrow">→</span>
                </template>
              </div>
              <p v-if="firstMissedCp != null" class="subtitle">
                Дистанция оборвалась на КП {{ firstMissedCp }}
                <template v-if="missingCourseCps.length > 1">
                  — следующие КП не в зачёт, пока не взят этот.
                </template>
              </p>
            </td>
          </tr>
          <tr v-if="!isOrient"><th>Сырые очки</th><td>{{ details.result?.points_raw ?? "-" }}</td></tr>
          <tr v-if="!isOrient"><th>Штраф</th><td>{{ details.result?.penalty_points ?? "-" }}</td></tr>
          <tr v-if="!isOrient"><th>Итог</th><td>{{ details.result?.points_final ?? "-" }}</td></tr>
          <tr>
            <th>Время</th>
            <td>{{ details.result ? fmtHms(details.result.elapsed_seconds) : "-" }}</td>
          </tr>
          <template v-if="pathMetrics">
            <tr>
              <th>Километраж</th>
              <td>
                {{ fmtNum(pathMetrics.distanceKm) }} км
                <span
                  v-if="pathMetrics.legsMissing > 0"
                  class="subtitle"
                  :title="pathMetrics.status"
                >
                  (часть КП без координат)
                </span>
              </td>
            </tr>
            <tr>
              <th>Скорость</th>
              <td>{{ pathMetrics.speedKmh != null ? `${fmtNum(pathMetrics.speedKmh)} км/ч` : "—" }}</td>
            </tr>
            <tr>
              <th>Темп</th>
              <td>{{ fmtPace(pathMetrics.paceSecPerKm) }}</td>
            </tr>
            <tr v-if="pathMetrics && !isOrient">
              <th>Очков на 1 км</th>
              <td>{{ fmtNum(pathMetrics.pointsPerKm, 1) }}</td>
            </tr>
            <tr v-if="pathMetrics && !isOrient">
              <th>Очков в час</th>
              <td>{{ fmtNum(pathMetrics.pointsPerHour, 1) }}</td>
            </tr>
          </template>
          <tr><th>Диагностика</th><td>{{ diagnosticsText() }}</td></tr>
        </tbody>
      </table>
    </section>

    <section v-if="details" class="native-tools nested-card">
      <h3>Корректировки</h3>
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr><th>ID</th><th>Область</th><th>Тип</th><th>Параметры</th><th>Создано</th><th>Действие</th></tr>
          </thead>
          <tbody>
            <tr v-if="!details.corrections.length">
              <td colspan="6">Корректировок нет</td>
            </tr>
            <tr v-for="c in details.corrections" :key="`${c.source_table}-${c.id}`">
              <td>{{ c.id }}</td>
              <td>{{ c.scope === "global" ? "общая" : "персональная" }}</td>
              <td>{{ correctionTypeText(c.correction_type) }}</td>
              <td class="correction-text-cell">{{ correctionPayloadText(c) }}</td>
              <td>{{ c.created_at }}</td>
              <td>
                <IconActionButton
                  v-if="c.scope === 'personal'"
                  variant="undo"
                  label="Отменить"
                  :disabled="busy"
                  @click="undoCorrection(c.id, c.source_table)"
                />
                <span v-else class="subtitle">{{ isOrient ? "в настройках" : "только на главной" }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="details" class="native-tools nested-card">
      <h3>Аномалии</h3>
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr><th>Тип</th><th>Название</th><th>Подробности</th><th>Коррекция</th></tr>
          </thead>
          <tbody>
            <tr v-if="!details.anomalies.length">
              <td colspan="4">Аномалий не найдено</td>
            </tr>
            <tr v-for="(a, idx) in details.anomalies" :key="`${a.anomaly_type}-${idx}`">
              <td>{{ anomalyTypeText(a.anomaly_type) }}</td>
              <td>{{ a.title }}</td>
              <td class="correction-text-cell">{{ a.details }}</td>
              <td>
                <button
                  v-if="a.anomaly_type === 'start_time_day_shift' && !anomalyDayShiftCorrection"
                  :disabled="busy"
                  @click="applyAnomalyDayShiftCorrection"
                >
                  Применить -24ч
                </button>
                <IconActionButton
                  v-else-if="a.anomaly_type === 'start_time_day_shift' && anomalyDayShiftCorrection"
                  variant="undo"
                  label="Отменить -24ч"
                  :disabled="busy"
                  @click="undoCorrection(anomalyDayShiftCorrection.id, anomalyDayShiftCorrection.source_table)"
                />
                <span
                  v-else-if="
                    a.anomaly_type === 'start_punch_missing' ||
                    a.anomaly_type === 'start_punch_after_finish'
                  "
                  class="subtitle"
                >
                  время ниже
                </span>
                <span v-else>-</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="isOrient && startPunchAnomaly" class="native-settings" style="margin-top: 12px">
        <label>
          Время стартовой отметки
          <input
            v-model="startMarkDraft"
            type="datetime-local"
            step="1"
            :disabled="busy"
          />
        </label>
        <button
          type="button"
          :disabled="busy || !finishKeyClean || !startMarkDraft"
          @click="applyStartMarkCorrection"
        >
          {{ startMarkCorrection ? "Сохранить время старта" : "Поставить отметку старта" }}
        </button>
        <IconActionButton
          v-if="startMarkCorrection"
          variant="undo"
          label="Отменить время старта"
          :disabled="busy"
          @click="undoCorrection(startMarkCorrection.id, startMarkCorrection.source_table)"
        />
      </div>
      <p v-if="isOrient && startPunchAnomaly" class="subtitle">
        По умолчанию: время первого КП минус худший перегон «старт → этот КП» среди остальных
        участников. Можно поправить вручную.
      </p>
    </section>

    <section v-if="details" class="native-tools nested-card">
      <h3>История аномалий (исправленные)</h3>
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr><th>Тип</th><th>Название</th><th>Подробности</th><th>Исправлено</th></tr>
          </thead>
          <tbody>
            <tr v-if="!details.anomalies_history.length">
              <td colspan="4">Исправленных аномалий нет</td>
            </tr>
            <tr v-for="(a, idx) in details.anomalies_history" :key="`history-${a.anomaly_type}-${idx}`">
              <td>{{ anomalyTypeText(a.anomaly_type) }}</td>
              <td>{{ a.title }}</td>
              <td class="correction-text-cell">{{ a.details }}</td>
              <td>
                {{
                  a.resolved_at
                    ? `корректировкой #${a.resolved_correction_id ?? "-"} (${a.resolved_at})`
                    : `корректировкой #${a.resolved_correction_id ?? "-"}`
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="details" class="participant-grid two-col">
      <section class="native-tools nested-card">
        <h3>Использованные отметки (после корректировок)</h3>
        <div class="native-results-wrap">
          <table class="native-results">
          <thead>
            <tr><th>#</th><th>КП</th><th>Время отметки</th><th>Перегон</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="(m, idx) in details.corrected_marks"
              :key="`${m.seq}-${m.cp_number}-${m.mark_time}`"
            >
              <td>{{ idx + 1 }}</td>
              <td>{{ m.cp_number }}</td>
              <td>{{ m.mark_time }}</td>
              <td>
                {{
                  idx === 0
                    ? "-"
                    : fmtHms(
                        diffSeconds(details.corrected_marks[idx - 1].mark_time, m.mark_time),
                      )
                }}
              </td>
            </tr>
          </tbody>
          </table>
        </div>
      </section>

      <section class="native-tools nested-card">
        <h3>Сырые отметки из CSV</h3>
        <div class="native-results-wrap">
          <table class="native-results">
          <thead>
            <tr><th>seq</th><th>КП</th><th>Время</th></tr>
          </thead>
          <tbody>
            <tr v-for="m in details.raw_marks" :key="`${m.seq}-${m.cp_number}-${m.mark_time}`">
              <td>{{ m.seq }}</td>
              <td>{{ m.cp_number }}</td>
              <td>{{ m.mark_time }}</td>
            </tr>
          </tbody>
          </table>
        </div>
      </section>
    </div>

    <section v-if="details && !isOrient" class="native-tools nested-card">
      <h3>Исключенные перегоны</h3>
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr><th>От</th><th>До</th><th>Направление</th><th>Режим</th><th>Макс (сек)</th><th>Источник</th></tr>
          </thead>
          <tbody>
            <tr v-if="!details.excluded_legs.length">
              <td colspan="6">Нет исключенных перегонов</td>
            </tr>
            <tr v-for="(x, i) in details.excluded_legs" :key="`${x.source}-${i}`">
              <td>{{ x.from_cp }}</td>
              <td>{{ x.to_cp }}</td>
              <td>{{ directionText(x.direction) }}</td>
              <td>{{ applyModeText(x.apply_mode) }}</td>
              <td>{{ x.max_leg_seconds ?? "" }}</td>
              <td>{{ legSourceText(x.source) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <AddToStartProtocolDialog
      :open="addToProtocolOpen"
      :busy="busy"
      :initial="addToProtocolDraft"
      title="Добавить в протокол"
      @close="closeAddToProtocol"
      @saved="onAddedToProtocol"
      @status="status = $event"
    />
  </main>
</template>
