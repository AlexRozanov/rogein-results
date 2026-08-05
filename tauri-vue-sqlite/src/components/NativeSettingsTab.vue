<script setup lang="ts">
import { onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import NativeSettingsForm from "./NativeSettingsForm.vue";

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
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
};

type FormatDraft = {
  format_id: number;
  format_name: string;
  control_minutes: string | number;
  penalty_per_minute: string | number;
  dq_minutes: string | number;
  finish_cp: string | number;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const globalSettings = ref<NativeSettings | null>(null);
const formatDrafts = ref<FormatDraft[]>([]);
const localBusy = ref(false);

onMounted(() => {
  void refresh();
});

function toDraft(row: FormatSettings): FormatDraft {
  return {
    format_id: row.format_id,
    format_name: row.format_name,
    control_minutes: row.control_minutes == null ? "" : String(row.control_minutes),
    penalty_per_minute:
      row.penalty_per_minute == null ? "" : String(row.penalty_per_minute),
    dq_minutes: row.dq_minutes == null ? "" : String(row.dq_minutes),
    finish_cp: row.finish_cp == null ? "" : String(row.finish_cp),
  };
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

async function refresh() {
  try {
    globalSettings.value = await invoke<NativeSettings>("get_settings");
    const rows = await invoke<FormatSettings[]>("get_format_settings");
    formatDrafts.value = rows.map(toDraft);
  } catch (error) {
    emit("status", `Ошибка загрузки настроек: ${String(error)}`);
  }
}

async function saveGlobal(form: NativeSettings) {
  localBusy.value = true;
  try {
    const updated = await invoke<NativeSettings>("set_settings", {
      controlMinutes: Number(form.control_minutes),
      penaltyPerMinute: Number(form.penalty_per_minute),
      dqMinutes: Number(form.dq_minutes),
      finishCp: Number(form.finish_cp),
      competitionDate: String(form.competition_date || "").trim(),
      competitionStartTime: String(form.competition_start_time || "").trim(),
    });
    globalSettings.value = updated;
    await invoke("recalculate_results");
    emit("status", "Общие настройки сохранены, выполнен пересчет.");
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения общих настроек: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function saveFormat(draft: FormatDraft) {
  localBusy.value = true;
  try {
    await invoke<FormatSettings>("set_format_settings", {
      formatId: draft.format_id,
      controlMinutes: parseOptionalInt(draft.control_minutes),
      penaltyPerMinute: parseOptionalInt(draft.penalty_per_minute),
      dqMinutes: parseOptionalInt(draft.dq_minutes),
      finishCp: parseOptionalInt(draft.finish_cp),
    });
    await invoke("recalculate_results");
    await refresh();
    emit(
      "status",
      `Настройки формата «${draft.format_name}» сохранены, выполнен пересчет.`,
    );
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения настроек формата: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <section class="native-tools nested-card">
    <h2>Настройки</h2>
    <p class="subtitle">
      Общие значения используются по умолчанию. Для формата пустое поле = взять из общих.
    </p>

    <h3>Общие настройки</h3>
    <NativeSettingsForm
      :settings="globalSettings"
      :busy="props.busy || localBusy"
      @save="saveGlobal"
    />

    <h3>Настройки форматов</h3>
    <p class="subtitle">
      Дата соревнования и время общего старта задаются только в общих настройках.
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
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!formatDrafts.length">
            <td colspan="6">Справочник форматов пуст — загрузите стартовый протокол</td>
          </tr>
          <tr v-for="draft in formatDrafts" :key="draft.format_id">
            <td>{{ draft.format_name }}</td>
            <td>
              <input
                v-model="draft.control_minutes"
                class="row-edit-input"
                type="number"
                min="0"
                :placeholder="globalSettings ? String(globalSettings.control_minutes) : ''"
              />
            </td>
            <td>
              <input
                v-model="draft.penalty_per_minute"
                class="row-edit-input"
                type="number"
                min="0"
                :placeholder="globalSettings ? String(globalSettings.penalty_per_minute) : ''"
              />
            </td>
            <td>
              <input
                v-model="draft.dq_minutes"
                class="row-edit-input"
                type="number"
                min="0"
                :placeholder="globalSettings ? String(globalSettings.dq_minutes) : ''"
              />
            </td>
            <td>
              <input
                v-model="draft.finish_cp"
                class="row-edit-input"
                type="number"
                min="0"
                :placeholder="globalSettings ? String(globalSettings.finish_cp) : ''"
              />
            </td>
            <td>
              <button
                :disabled="props.busy || localBusy"
                @click="saveFormat(draft)"
              >
                Сохранить
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
