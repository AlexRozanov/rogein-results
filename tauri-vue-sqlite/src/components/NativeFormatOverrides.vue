<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
  start_mode: "station" | "time";
  start_cp: number | null;
  competition_date: string;
  competition_start_time: string;
};

type FormatSettings = {
  format_id: number;
  format_name: string;
  control_minutes: number | null;
  penalty_per_minute: number | null;
  dq_minutes: number | null;
  finish_cp: number | null;
  competition_start_time: string | null;
};

type FormatOption = {
  format_id: number;
  format_name: string;
};

const props = defineProps<{
  busy: boolean;
  globalSettings: NativeSettings | null;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
  formatsLoaded: [formats: FormatOption[]];
}>();

const allRows = ref<FormatSettings[]>([]);
const localBusy = ref(false);
const formOpen = ref(false);
const formMode = ref<"create" | "edit" | "copy">("create");
const editingFormatId = ref<number | null>(null);

const selectedFormatId = ref<string>("");
const controlMinutes = ref<string>("");
const penaltyPerMinute = ref<string>("");
const dqMinutes = ref<string>("");
const finishCp = ref<string>("");
const competitionStartTime = ref<string>("");

function hasOverride(row: FormatSettings) {
  return (
    row.control_minutes != null ||
    row.penalty_per_minute != null ||
    row.dq_minutes != null ||
    row.finish_cp != null ||
    (row.competition_start_time != null && String(row.competition_start_time).trim() !== "")
  );
}

const overriddenRows = computed(() => allRows.value.filter(hasOverride));

const allFormatOptions = computed<FormatOption[]>(() =>
  allRows.value.map((r) => ({
    format_id: r.format_id,
    format_name: r.format_name,
  })),
);

const selectableFormats = computed(() => {
  if (formMode.value === "edit" && editingFormatId.value != null) {
    return allRows.value.filter((r) => r.format_id === editingFormatId.value);
  }
  // create/copy: formats without overrides, or for copy allow all except source if already overridden?
  // Prefer formats that don't yet have overrides.
  const free = allRows.value.filter((r) => !hasOverride(r));
  if (formMode.value === "copy" && free.length === 0) {
    return allRows.value;
  }
  return free;
});

const formTitle = computed(() => {
  if (formMode.value === "edit") return "Изменить настройки формата";
  if (formMode.value === "copy") return "Скопировать настройки формата";
  return "Добавить настройки формата";
});

onMounted(() => {
  void refresh();
});

watch(allFormatOptions, (formats) => {
  emit("formatsLoaded", formats);
}, { immediate: true });

function displayValue(value: number | null) {
  return value == null ? "—" : String(value);
}

function displayTime(value: string | null) {
  const trimmed = String(value || "").trim();
  return trimmed ? trimmed.slice(0, 8) : "—";
}

function toTimeInputValue(raw: string | null | undefined) {
  const value = String(raw || "").trim();
  if (!value) return "";
  if (/^\d{2}:\d{2}(:\d{2})?$/.test(value)) return value.slice(0, 8);
  return value;
}

function parseOptionalInt(raw: string | number | null | undefined): number | null {
  if (raw === null || raw === undefined) return null;
  if (typeof raw === "number") {
    if (!Number.isFinite(raw)) {
      throw new Error(`Некорректное число: ${raw}`);
    }
    return Math.trunc(raw);
  }
  const value = String(raw).trim();
  if (!value) return null;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Некорректное число: ${raw}`);
  }
  return Math.trunc(parsed);
}

function resetFormFields() {
  editingFormatId.value = null;
  selectedFormatId.value = "";
  controlMinutes.value = "";
  penaltyPerMinute.value = "";
  dqMinutes.value = "";
  finishCp.value = "";
  competitionStartTime.value = "";
}

function fillFormFromRow(row: FormatSettings) {
  selectedFormatId.value = String(row.format_id);
  controlMinutes.value = row.control_minutes == null ? "" : String(row.control_minutes);
  penaltyPerMinute.value =
    row.penalty_per_minute == null ? "" : String(row.penalty_per_minute);
  dqMinutes.value = row.dq_minutes == null ? "" : String(row.dq_minutes);
  finishCp.value = row.finish_cp == null ? "" : String(row.finish_cp);
  competitionStartTime.value = toTimeInputValue(row.competition_start_time);
}

function openCreateForm() {
  formMode.value = "create";
  resetFormFields();
  const free = allRows.value.filter((r) => !hasOverride(r));
  if (!free.length) {
    emit(
      "status",
      allRows.value.length
        ? "У всех форматов уже есть переопределения. Измените существующие."
        : "Справочник форматов пуст — загрузите стартовый протокол.",
    );
    return;
  }
  selectedFormatId.value = String(free[0].format_id);
  formOpen.value = true;
}

function openEditForm(row: FormatSettings) {
  formMode.value = "edit";
  editingFormatId.value = row.format_id;
  fillFormFromRow(row);
  formOpen.value = true;
}

function openCopyForm(row: FormatSettings) {
  formMode.value = "copy";
  editingFormatId.value = null;
  fillFormFromRow(row);
  const free = allRows.value.filter((r) => !hasOverride(r));
  if (!free.length) {
    emit(
      "status",
      "Нет формата без переопределений — сначала удалите лишние или создайте новый формат.",
    );
    return;
  }
  selectedFormatId.value = String(free[0].format_id);
  formOpen.value = true;
}

function closeForm() {
  formOpen.value = false;
  resetFormFields();
  formMode.value = "create";
}

async function refresh() {
  try {
    allRows.value = await invoke<FormatSettings[]>("get_format_settings");
  } catch (error) {
    emit("status", `Ошибка загрузки настроек форматов: ${String(error)}`);
  }
}

async function saveForm() {
  const formatId = Number(selectedFormatId.value);
  if (!Number.isFinite(formatId) || formatId <= 0) {
    emit("status", "Выберите формат.");
    return;
  }
  let control: number | null;
  let penalty: number | null;
  let dq: number | null;
  let finish: number | null;
  const startTime = String(competitionStartTime.value || "").trim() || null;
  try {
    control = parseOptionalInt(controlMinutes.value);
    penalty = parseOptionalInt(penaltyPerMinute.value);
    dq = parseOptionalInt(dqMinutes.value);
    finish = parseOptionalInt(finishCp.value);
  } catch (error) {
    emit("status", String(error));
    return;
  }
  if (
    control == null &&
    penalty == null &&
    dq == null &&
    finish == null &&
    startTime == null
  ) {
    emit("status", "Укажите хотя бы одно значение, иначе это не переопределение.");
    return;
  }

  const rowName =
    allRows.value.find((r) => r.format_id === formatId)?.format_name || String(formatId);

  localBusy.value = true;
  try {
    await invoke<FormatSettings>("set_format_settings", {
      formatId,
      controlMinutes: control,
      penaltyPerMinute: penalty,
      dqMinutes: dq,
      finishCp: finish,
      competitionStartTime: startTime,
    });
    await invoke("recalculate_results");
    emit(
      "status",
      `Настройки формата «${rowName}» сохранены, выполнен пересчет.`,
    );
    closeForm();
    await refresh();
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения настроек формата: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function deleteOverride(row: FormatSettings) {
  if (
    !window.confirm(
      `Удалить переопределения для формата «${row.format_name}»? Будут снова использоваться общие настройки.`,
    )
  ) {
    return;
  }
  localBusy.value = true;
  try {
    await invoke("set_format_settings", {
      formatId: row.format_id,
      controlMinutes: null,
      penaltyPerMinute: null,
      dqMinutes: null,
      finishCp: null,
      competitionStartTime: null,
    });
    await invoke("recalculate_results");
    emit(
      "status",
      `Переопределения формата «${row.format_name}» удалены, выполнен пересчет.`,
    );
    await refresh();
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка удаления переопределений: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh, allFormatOptions });
</script>

<template>
  <section class="native-tools nested-card">
    <div class="modal-header" style="margin-bottom: 8px">
      <h3 style="margin: 0">Настройки форматов</h3>
      <button :disabled="props.busy || localBusy" @click="openCreateForm">
        Добавить для формата
      </button>
    </div>
    <p class="subtitle">
      Показываются только форматы с переопределениями. Пустое поле = взять из общих.
      Тип старта и стартовая станция — только в общих. Стартовое время можно задать для формата.
    </p>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th>Формат</th>
            <th>Контроль (мин)</th>
            <th>Штраф/мин</th>
            <th>DQ после (мин)</th>
            <th>Финиш КП</th>
            <th>Старт (время)</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!overriddenRows.length">
            <td colspan="7">Переопределений нет — используются только общие настройки</td>
          </tr>
          <tr v-for="row in overriddenRows" :key="row.format_id">
            <td>{{ row.format_name }}</td>
            <td>{{ displayValue(row.control_minutes) }}</td>
            <td>{{ displayValue(row.penalty_per_minute) }}</td>
            <td>{{ displayValue(row.dq_minutes) }}</td>
            <td>{{ displayValue(row.finish_cp) }}</td>
            <td>{{ displayTime(row.competition_start_time) }}</td>
            <td>
              <IconActionButton
                variant="edit"
                label="Изменить"
                :disabled="props.busy || localBusy"
                @click="openEditForm(row)"
              />
              <IconActionButton
                variant="copy"
                label="Копировать"
                :disabled="props.busy || localBusy"
                @click="openCopyForm(row)"
              />
              <IconActionButton
                variant="delete"
                label="Удалить"
                :disabled="props.busy || localBusy"
                @click="deleteOverride(row)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="formOpen" class="modal-backdrop" @click.self="closeForm">
      <div
        class="modal-card"
        role="dialog"
        aria-modal="true"
        :aria-label="formTitle"
      >
        <div class="modal-header">
          <h3>{{ formTitle }}</h3>
          <button
            class="modal-close-btn"
            type="button"
            title="Закрыть"
            aria-label="Закрыть"
            :disabled="localBusy"
            @click="closeForm"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path
                d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
                fill="currentColor"
              />
            </svg>
          </button>
        </div>

        <div class="native-settings">
          <label>
            Формат
            <select v-model="selectedFormatId" :disabled="formMode === 'edit'">
              <option disabled value="">Выберите формат</option>
              <option
                v-for="fmt in selectableFormats"
                :key="fmt.format_id"
                :value="String(fmt.format_id)"
              >
                {{ fmt.format_name }}
              </option>
            </select>
          </label>
          <label>
            Контроль (мин)
            <input
              v-model="controlMinutes"
              type="number"
              min="0"
              :placeholder="
                props.globalSettings
                  ? String(props.globalSettings.control_minutes)
                  : ''
              "
            />
          </label>
          <label>
            Штраф/мин
            <input
              v-model="penaltyPerMinute"
              type="number"
              min="0"
              :placeholder="
                props.globalSettings
                  ? String(props.globalSettings.penalty_per_minute)
                  : ''
              "
            />
          </label>
          <label>
            DQ после (мин)
            <input
              v-model="dqMinutes"
              type="number"
              min="0"
              :placeholder="
                props.globalSettings ? String(props.globalSettings.dq_minutes) : ''
              "
            />
          </label>
          <label>
            Финиш КП
            <input
              v-model="finishCp"
              type="number"
              min="0"
              :placeholder="
                props.globalSettings ? String(props.globalSettings.finish_cp) : ''
              "
            />
          </label>
          <label>
            Стартовое время
            <input
              v-model="competitionStartTime"
              type="time"
              step="1"
              :placeholder="
                props.globalSettings?.competition_start_time
                  ? props.globalSettings.competition_start_time.slice(0, 8)
                  : ''
              "
            />
          </label>
        </div>
        <p class="subtitle">Пустое поле = взять из общих настроек.</p>
        <div class="native-settings">
          <IconActionButton
            variant="save"
            :label="formMode === 'edit' ? 'Сохранить' : 'Добавить'"
            :disabled="props.busy || localBusy"
            @click="saveForm"
          />
          <IconActionButton
            variant="cancel"
            label="Отмена"
            :disabled="localBusy"
            @click="closeForm"
          />
        </div>
      </div>
    </div>
  </section>
</template>
