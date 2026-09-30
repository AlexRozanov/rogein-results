<script setup lang="ts">
import { onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";

type CorrectionRow = {
  id: number;
  finish_participant_id: number | null;
  scope: "global" | "personal";
  source_table: "manual_corrections" | "legacy_corrections";
  correction_type: string;
  payload: Record<string, unknown>;
  created_at: string;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
}>();

const globalRemoveCpNumber = ref<number | null>(null);
const globalRemoveMode = ref<"remove_legs" | "points_only">("remove_legs");
const globalCorrections = ref<CorrectionRow[]>([]);

onMounted(() => {
  void refreshGlobalCorrections();
});

function correctionTypeText(correctionType: string) {
  if (correctionType === "add_cp") return "Добавление КП";
  if (correctionType === "remove_cp") return "Удаление КП";
  if (correctionType === "exclude_leg_time") return "Исключение перегона";
  if (correctionType === "anomaly_day_shift_24h") return "Коррекция аномалии: -24ч";
  if (correctionType === "remap_cp") return "Замена КП (станция)";
  if (correctionType === "assign_course") return "Смена дистанции";
  return correctionType;
}

function correctionPayloadText(row: CorrectionRow) {
  const cp = Number(row.payload?.cp_number);
  const fromCp = Number(row.payload?.from_cp);
  const toCp = Number(row.payload?.to_cp);
  const removeMode = row.payload?.remove_mode;

  if (row.correction_type === "add_cp") {
    return Number.isFinite(cp) ? `Номер КП: ${cp}` : "Номер КП: -";
  }

  if (row.correction_type === "remove_cp") {
    const cpText = Number.isFinite(cp) ? cp : "-";
    if (removeMode === "from_course") {
      return `КП ${cpText}: снят с дистанции для всех`;
    }
    const modeText = removeMode === "points_only" ? "только очки" : "очки + перегоны";
    return `Номер КП: ${cpText}; действие: ${modeText}`;
  }

  if (row.correction_type === "exclude_leg_time") {
    const fromText = Number.isFinite(fromCp) ? fromCp : "-";
    const toText = Number.isFinite(toCp) ? toCp : "-";
    return `Перегон: ${fromText} -> ${toText}`;
  }

  if (row.correction_type === "anomaly_day_shift_24h") {
    const seconds = Number(row.payload?.seconds);
    return `Коррекция времени: -${Number.isFinite(seconds) ? seconds : 86400} сек (-24ч)`;
  }

  if (row.correction_type === "remap_cp") {
    const fromText = Number.isFinite(fromCp) ? fromCp : "-";
    const toText = Number.isFinite(toCp) ? toCp : "-";
    const when = String(row.payload?.mark_time || "-");
    return `Замена: ${fromText} → ${toText} @ ${when}`;
  }

  if (row.correction_type === "assign_course") {
    const fromCourse = String(row.payload?.from || "").trim() || "—";
    const toCourse = String(row.payload?.to || "").trim() || "—";
    return `${fromCourse} → ${toCourse}`;
  }

  return JSON.stringify(row.payload);
}

async function addGlobalRemoveCp() {
  if (!globalRemoveCpNumber.value) {
    emit("status", "Укажите номер КП для общей корректировки.");
    return;
  }
  try {
    await invoke("remove_cp_correction", {
      participantId: null,
      cpNumber: Number(globalRemoveCpNumber.value),
      removeMode: globalRemoveMode.value,
      participantScope: "all",
    });
    emit("status", "Общая корректировка удаления КП добавлена, выполнен пересчёт.");
    await refreshGlobalCorrections();
  } catch (error) {
    emit("status", `Ошибка общей корректировки: ${String(error)}`);
  }
}

async function refreshGlobalCorrections() {
  try {
    globalCorrections.value = await invoke<CorrectionRow[]>("get_manual_corrections", {
      participantId: null,
    });
  } catch (error) {
    emit("status", `Ошибка загрузки общих корректировок: ${String(error)}`);
  }
}

async function undoCorrection(row: CorrectionRow) {
  try {
    await invoke("delete_correction_entry", {
      sourceTable: row.source_table,
      correctionId: row.id,
    });
    emit("status", `Корректировка #${row.id} отменена, выполнен пересчёт.`);
    await refreshGlobalCorrections();
  } catch (error) {
    emit("status", `Ошибка отмены корректировки: ${String(error)}`);
  }
}
</script>

<template>
  <section class="native-tools">
    <h2>Общие корректировки</h2>
    <section class="native-tools nested-card">
      <h2>Удалить КП</h2>
      <div class="native-settings">
        <label>
          Номер КП
          <input v-model.number="globalRemoveCpNumber" type="number" min="1" />
        </label>
        <label>
          Действие
          <select v-model="globalRemoveMode">
            <option value="remove_legs">Удалить перегоны</option>
            <option value="points_only">Удалить только очки</option>
          </select>
        </label>
      </div>
      <button :disabled="props.busy" @click="addGlobalRemoveCp">Добавить корректировку</button>
    </section>

    <section class="native-tools nested-card">
      <h2>Существующие общие корректировки</h2>
      <button :disabled="props.busy" @click="refreshGlobalCorrections">
        Обновить список корректировок
      </button>
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr>
              <th>ID</th>
              <th>Тип</th>
              <th>Параметры</th>
              <th>Создано</th>
              <th>Действие</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!globalCorrections.length">
              <td colspan="5">Корректировок нет</td>
            </tr>
            <tr v-for="c in globalCorrections" :key="`${c.source_table}-${c.id}`">
              <td>{{ c.id }}</td>
              <td>{{ correctionTypeText(c.correction_type) }}</td>
              <td>{{ correctionPayloadText(c) }}</td>
              <td>{{ c.created_at }}</td>
              <td>
                <IconActionButton
                  variant="undo"
                  label="Отменить"
                  :disabled="props.busy"
                  @click="undoCorrection(c)"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>
