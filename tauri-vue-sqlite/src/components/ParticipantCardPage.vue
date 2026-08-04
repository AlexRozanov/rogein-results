<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";

const props = defineProps<{
  participantId: string;
}>();

type NativeParticipantDetails = {
  participant: {
    participant_id: string;
    name: string;
    start_station_id: number;
    start_time: string;
    source_row: number | null;
  };
  result: {
    participant_id: string;
    name: string;
    status: "OK" | "DQ" | "ERR";
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
    participant_id: string | null;
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

const participantIdClean = computed(() => decodeURIComponent(props.participantId || "").trim());
const anomalyDayShiftCorrection = computed(() =>
  details.value?.corrections.find(
    (c) => c.scope === "personal" && c.correction_type === "anomaly_day_shift_24h",
  ) ?? null,
);

function fmtHms(totalSeconds: number) {
  const s = Math.max(0, Number(totalSeconds || 0));
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function diffSeconds(prevMarkTime: string, currentMarkTime: string) {
  const prev = new Date(prevMarkTime.replace(" ", "T"));
  const cur = new Date(currentMarkTime.replace(" ", "T"));
  return Math.max(0, Math.round((cur.getTime() - prev.getTime()) / 1000));
}

function diagnosticsText() {
  const diagRaw = details.value?.result?.diagnostics_json;
  if (!diagRaw) return "-";
  try {
    const parsed = JSON.parse(diagRaw);
    return Array.isArray(parsed) && parsed.length ? parsed.join(", ") : "-";
  } catch {
    return diagRaw;
  }
}

function correctionTypeText(correctionType: string) {
  if (correctionType === "add_cp") return "Добавление КП";
  if (correctionType === "remove_cp") return "Удаление КП";
  if (correctionType === "exclude_leg_time") return "Исключение перегона";
  if (correctionType === "anomaly_day_shift_24h") return "Коррекция аномалии: -24ч";
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
    const modeText = removeMode === "points_only" ? "только очки" : "очки + перегоны";
    const cpText = Number.isFinite(cp) ? cp : "-";
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

  return JSON.stringify(c.payload);
}

async function loadDetails() {
  const pid = participantIdClean.value;
  if (!pid) {
    status.value = "Не указан ID участника";
    details.value = null;
    return;
  }
  status.value = "Загрузка карточки...";
  try {
    const response = await invoke<NativeParticipantDetails>("get_participant_details", {
      participantId: pid,
    });
    details.value = response;
    status.value = `Загружен участник ${response.participant.participant_id}: ${response.participant.name}`;
  } catch (error) {
    details.value = null;
    status.value = `Ошибка: ${String(error)}`;
  }
}

async function recalculateAndReload() {
  await invoke("recalculate_results");
  await loadDetails();
}

async function addPersonalCp() {
  const pid = participantIdClean.value;
  if (!pid || !addCpNumber.value) {
    status.value = "Укажите ID участника и номер КП.";
    return;
  }
  busy.value = true;
  try {
    await invoke("add_cp_correction", {
      participantId: pid,
      cpNumber: Number(addCpNumber.value),
    });
    status.value = "Персональная корректировка добавлена. Пересчитываю...";
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка add-cp: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function removePersonalCp() {
  const pid = participantIdClean.value;
  if (!pid || !removeCpNumber.value) {
    status.value = "Укажите ID участника и номер КП.";
    return;
  }
  busy.value = true;
  try {
    await invoke("remove_cp_correction", {
      participantId: pid,
      cpNumber: Number(removeCpNumber.value),
      removeMode: removeMode.value,
      participantScope: "one",
    });
    status.value = "Персональная корректировка удаления добавлена. Пересчитываю...";
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
    status.value = `Корректировка #${correctionId} отменена. Пересчитываю...`;
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка отмены корректировки: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function applyAnomalyDayShiftCorrection() {
  const pid = participantIdClean.value;
  if (!pid) return;
  busy.value = true;
  try {
    await invoke("add_anomaly_day_shift_correction", { participantId: pid });
    status.value = "Корректировка аномалии (-24ч) добавлена. Пересчитываю...";
    await recalculateAndReload();
  } catch (error) {
    status.value = `Ошибка корректировки аномалии: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

watch(participantIdClean, () => {
  void loadDetails();
});

onMounted(() => {
  void loadDetails();
});

function goBackToResults() {
  window.location.hash = "";
}
</script>

<template>
  <main class="container">
    <section class="native-tools">
      <div class="native-row">
        <button type="button" @click="goBackToResults">← Назад к результатам</button>
      </div>
      <h2>Карточка участника</h2>
      <p class="status">{{ status }}</p>
    </section>

    <section v-if="details" class="native-tools">
      <h2>Персональные корректировки</h2>
      <div class="native-settings">
        <label>
          Добавить КП (номер)
          <input v-model.number="addCpNumber" type="number" min="1" />
        </label>
        <button :disabled="busy" @click="addPersonalCp">Добавить корректировку</button>
      </div>
      <div class="native-settings">
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
        <button :disabled="busy" @click="removePersonalCp">Добавить корректировку</button>
      </div>
    </section>

    <section v-if="details" class="native-tools nested-card">
      <h3>Сводка</h3>
      <table class="native-results">
        <tbody>
          <tr><th>ID</th><td>{{ details.participant.participant_id }}</td></tr>
          <tr><th>Имя</th><td>{{ details.participant.name }}</td></tr>
          <tr><th>Старт КП</th><td>{{ details.participant.start_station_id }}</td></tr>
          <tr><th>Старт</th><td>{{ details.participant.start_time }}</td></tr>
          <tr><th>Статус</th><td>{{ details.result?.status ?? "-" }}</td></tr>
          <tr><th>Сырые очки</th><td>{{ details.result?.points_raw ?? "-" }}</td></tr>
          <tr><th>Штраф</th><td>{{ details.result?.penalty_points ?? "-" }}</td></tr>
          <tr><th>Итог</th><td>{{ details.result?.points_final ?? "-" }}</td></tr>
          <tr>
            <th>Время</th>
            <td>{{ details.result ? fmtHms(details.result.elapsed_seconds) : "-" }}</td>
          </tr>
          <tr><th>Диагностика</th><td>{{ diagnosticsText() }}</td></tr>
        </tbody>
      </table>
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
                <button
                  v-else-if="a.anomaly_type === 'start_time_day_shift' && anomalyDayShiftCorrection"
                  :disabled="busy"
                  @click="undoCorrection(anomalyDayShiftCorrection.id, anomalyDayShiftCorrection.source_table)"
                >
                  Отменить -24ч
                </button>
                <span v-else>-</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
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

    <div v-if="details" class="participant-grid two-col">
      <section class="native-tools nested-card">
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

      <section class="native-tools nested-card">
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
                <button
                  v-if="c.scope === 'personal'"
                  :disabled="busy"
                  @click="undoCorrection(c.id, c.source_table)"
                >
                  Отменить
                </button>
                <span v-else class="subtitle">только на главной</span>
              </td>
            </tr>
          </tbody>
          </table>
        </div>
      </section>
    </div>
  </main>
</template>
