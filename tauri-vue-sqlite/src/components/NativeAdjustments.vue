<script setup lang="ts">
import { onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";

type ExclusionRuleRow = {
  id: number;
  participant_id: string | null;
  from_cp: number;
  to_cp: number;
  direction: "forward" | "reverse" | "both";
  apply_mode: "once" | "always";
  max_leg_seconds: number | null;
  created_at: string;
};

type CorrectionRow = {
  id: number;
  participant_id: string | null;
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

const rules = ref<ExclusionRuleRow[]>([]);
const globalCorrections = ref<CorrectionRow[]>([]);
const editRuleId = ref<number | null>(null);
const fromCp = ref<number | null>(null);
const toCp = ref<number | null>(null);
const direction = ref<"forward" | "reverse" | "both">("forward");
const applyMode = ref<"once" | "always">("once");
const maxLegSeconds = ref<number | null>(null);

onMounted(() => {
  void refreshRules();
  void refreshGlobalCorrections();
});

function correctionTypeText(correctionType: string) {
  if (correctionType === "add_cp") return "Добавление КП";
  if (correctionType === "remove_cp") return "Удаление КП";
  if (correctionType === "exclude_leg_time") return "Исключение перегона";
  if (correctionType === "anomaly_day_shift_24h") return "Коррекция аномалии: -24ч";
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
    const modeText = removeMode === "points_only" ? "только очки" : "очки + перегоны";
    const cpText = Number.isFinite(cp) ? cp : "-";
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

  return JSON.stringify(row.payload);
}

function directionText(directionValue: ExclusionRuleRow["direction"]) {
  if (directionValue === "forward") return "Прямое (от -> до)";
  if (directionValue === "reverse") return "Обратное (до -> от)";
  return "Оба направления";
}

function applyModeText(applyModeValue: ExclusionRuleRow["apply_mode"]) {
  if (applyModeValue === "once") return "Один раз";
  return "Всегда";
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
    emit("status", "Общая корректировка удаления КП добавлена. Нажмите Пересчитать.");
    await refreshGlobalCorrections();
  } catch (error) {
    emit("status", `Ошибка общей корректировки: ${String(error)}`);
  }
}

async function saveRule() {
  const payload = {
    participantScope: "all",
    participantId: null,
    fromCp: Number(fromCp.value),
    toCp: Number(toCp.value),
    direction: direction.value,
    applyMode: applyMode.value,
    maxLegSeconds: maxLegSeconds.value,
  };
  if (!payload.fromCp || !payload.toCp) {
    emit("status", "Для правила исключения заполните От/До КП.");
    return;
  }
  try {
    if (editRuleId.value) {
      await invoke("update_exclusion_rule", {
        ruleId: editRuleId.value,
        ...payload,
      });
      emit("status", `Правило #${editRuleId.value} обновлено. Нажмите Пересчитать.`);
    } else {
      await invoke("add_exclusion_rule", payload);
      emit("status", "Правило исключения добавлено. Нажмите Пересчитать.");
    }
    resetRuleForm();
    await refreshRules();
  } catch (error) {
    emit("status", `Ошибка сохранения правила: ${String(error)}`);
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

async function refreshRules() {
  try {
    const rows = await invoke<ExclusionRuleRow[]>("get_exclusion_rules", {
      participantId: null,
    });
    rules.value = rows.filter((x) => x.participant_id === null);
  } catch (error) {
    emit("status", `Ошибка загрузки правил: ${String(error)}`);
  }
}

function beginEditRule(rule: ExclusionRuleRow) {
  editRuleId.value = rule.id;
  fromCp.value = rule.from_cp;
  toCp.value = rule.to_cp;
  direction.value = rule.direction;
  applyMode.value = rule.apply_mode;
  maxLegSeconds.value = rule.max_leg_seconds;
}

async function deleteRule(ruleId: number) {
  try {
    await invoke("delete_exclusion_rule", { ruleId });
    emit("status", `Правило #${ruleId} удалено. Нажмите Пересчитать.`);
    await refreshRules();
  } catch (error) {
    emit("status", `Ошибка удаления правила: ${String(error)}`);
  }
}

async function undoCorrection(row: CorrectionRow) {
  try {
    await invoke("delete_correction_entry", {
      sourceTable: row.source_table,
      correctionId: row.id,
    });
    emit("status", `Корректировка #${row.id} отменена. Нажмите Пересчитать.`);
    await refreshGlobalCorrections();
  } catch (error) {
    emit("status", `Ошибка отмены корректировки: ${String(error)}`);
  }
}

function resetRuleForm() {
  editRuleId.value = null;
  fromCp.value = null;
  toCp.value = null;
  direction.value = "forward";
  applyMode.value = "once";
  maxLegSeconds.value = null;
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
                <button :disabled="props.busy" @click="undoCorrection(c)">Отменить</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="native-tools nested-card">
      <h2>Исключить перегон</h2>
      <div class="native-settings">
        <label>
          От КП
          <input v-model.number="fromCp" type="number" min="1" />
        </label>
        <label>
          До КП
          <input v-model.number="toCp" type="number" min="1" />
        </label>
        <span class="subtitle">Для всех участников</span>
      </div>
      <div class="native-settings">
        <label>
          Направление
          <select v-model="direction">
            <option value="forward">Прямое (from -> to)</option>
            <option value="reverse">Обратное (to -> from)</option>
            <option value="both">Оба направления</option>
          </select>
        </label>
        <label>
          Режим
          <select v-model="applyMode">
            <option value="once">Исключить 1 раз</option>
            <option value="always">Исключать всегда</option>
          </select>
        </label>
        <label>
          Макс. время перегона (сек, пусто = без лимита)
          <input v-model.number="maxLegSeconds" type="number" min="0" placeholder="например 600" />
        </label>
      </div>
      <div class="native-settings">
        <button :disabled="props.busy" @click="saveRule">
          {{ editRuleId ? `Сохранить правило #${editRuleId}` : "Добавить правило" }}
        </button>
        <button :disabled="props.busy" @click="resetRuleForm">Сбросить форму</button>
      </div>
    </section>

    <section class="native-tools nested-card">
      <h2>Существующие правила исключения</h2>
      <button :disabled="props.busy" @click="refreshRules">Обновить список правил</button>
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr>
              <th>ID</th>
              <th>Область</th>
              <th>От</th>
              <th>До</th>
              <th>Направление</th>
              <th>Режим</th>
              <th>Макс (сек)</th>
              <th>Действие</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in rules" :key="rule.id">
              <td>{{ rule.id }}</td>
              <td>все</td>
              <td>{{ rule.from_cp }}</td>
              <td>{{ rule.to_cp }}</td>
              <td>{{ directionText(rule.direction) }}</td>
              <td>{{ applyModeText(rule.apply_mode) }}</td>
              <td>{{ rule.max_leg_seconds ?? "" }}</td>
              <td>
                <button :disabled="props.busy" @click="beginEditRule(rule)">Изменить</button>
                <button :disabled="props.busy" @click="deleteRule(rule.id)">Удалить</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>
