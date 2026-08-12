<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";

type ExclusionRuleRow = {
  id: number;
  finish_participant_id: number | null;
  format_id: number | null;
  format_name: string | null;
  from_cp: number;
  to_cp: number;
  direction: "forward" | "reverse" | "both";
  apply_mode: "once" | "always";
  max_leg_seconds: number | null;
  created_at: string;
  scope: string;
};

type FormatOption = {
  format_id: number;
  format_name: string;
};

const props = defineProps<{
  busy: boolean;
  formats: FormatOption[];
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const rules = ref<ExclusionRuleRow[]>([]);
const localBusy = ref(false);
const formOpen = ref(false);
const formMode = ref<"create" | "edit" | "copy">("create");
const editRuleId = ref<number | null>(null);

/** "all" = для всех, иначе id формата строкой */
const scopeTarget = ref<string>("all");
const fromCp = ref<number | null>(null);
const toCp = ref<number | null>(null);
const direction = ref<"forward" | "reverse" | "both">("forward");
const applyMode = ref<"once" | "always">("once");
const maxLegSeconds = ref<number | null>(null);

const formTitle = computed(() => {
  if (formMode.value === "edit" && editRuleId.value != null) {
    return `Изменить исключение #${editRuleId.value}`;
  }
  if (formMode.value === "copy") {
    return "Скопировать исключение";
  }
  return "Добавить исключение";
});

onMounted(() => {
  void refreshRules();
});

watch(
  () => props.formats.map((f) => f.format_id).join(","),
  () => {
    // keep selected format if still exists
    if (scopeTarget.value !== "all") {
      const id = Number(scopeTarget.value);
      if (!props.formats.some((f) => f.format_id === id)) {
        scopeTarget.value = "all";
      }
    }
  },
);

function directionText(directionValue: ExclusionRuleRow["direction"]) {
  if (directionValue === "forward") return "Прямое";
  if (directionValue === "reverse") return "Обратное";
  return "Оба";
}

function applyModeText(applyModeValue: ExclusionRuleRow["apply_mode"]) {
  if (applyModeValue === "once") return "1 раз";
  return "Всегда";
}

function scopeText(rule: ExclusionRuleRow) {
  if (rule.format_id != null) {
    return rule.format_name || `Формат #${rule.format_id}`;
  }
  return "Для всех";
}

function scopePayload() {
  if (scopeTarget.value === "all") {
    return {
      participantScope: "all",
      participantId: null as string | null,
      formatId: null as number | null,
    };
  }
  return {
    participantScope: "format",
    participantId: null as string | null,
    formatId: Number(scopeTarget.value),
  };
}

function resetFormFields() {
  editRuleId.value = null;
  scopeTarget.value = "all";
  fromCp.value = null;
  toCp.value = null;
  direction.value = "forward";
  applyMode.value = "once";
  maxLegSeconds.value = null;
}

function fillFormFromRule(rule: ExclusionRuleRow) {
  scopeTarget.value = rule.format_id == null ? "all" : String(rule.format_id);
  fromCp.value = rule.from_cp;
  toCp.value = rule.to_cp;
  direction.value = rule.direction;
  applyMode.value = rule.apply_mode;
  maxLegSeconds.value = rule.max_leg_seconds;
}

function openCreateForm() {
  formMode.value = "create";
  resetFormFields();
  formOpen.value = true;
}

function openEditForm(rule: ExclusionRuleRow) {
  formMode.value = "edit";
  editRuleId.value = rule.id;
  fillFormFromRule(rule);
  formOpen.value = true;
}

function openCopyForm(rule: ExclusionRuleRow) {
  formMode.value = "copy";
  editRuleId.value = null;
  fillFormFromRule(rule);
  formOpen.value = true;
}

function closeForm() {
  formOpen.value = false;
  resetFormFields();
  formMode.value = "create";
}

async function refreshRules() {
  try {
    rules.value = await invoke<ExclusionRuleRow[]>("get_exclusion_rules", {
      participantId: null,
      formatId: null,
    });
  } catch (error) {
    emit("status", `Ошибка загрузки правил исключения: ${String(error)}`);
  }
}

async function saveRule() {
  const from = Number(fromCp.value);
  const to = Number(toCp.value);
  if (!from || !to) {
    emit("status", "Для правила исключения заполните От/До КП.");
    return;
  }
  if (scopeTarget.value !== "all") {
    const formatId = Number(scopeTarget.value);
    if (!Number.isFinite(formatId) || formatId <= 0) {
      emit("status", "Выберите формат или «Для всех».");
      return;
    }
  }

  localBusy.value = true;
  try {
    const payload = {
      ...scopePayload(),
      fromCp: from,
      toCp: to,
      direction: direction.value,
      applyMode: applyMode.value,
      maxLegSeconds: maxLegSeconds.value,
    };
    const isEdit = formMode.value === "edit" && editRuleId.value != null;
    if (isEdit) {
      await invoke("update_exclusion_rule", {
        ruleId: editRuleId.value,
        ...payload,
      });
    } else {
      await invoke("add_exclusion_rule", payload);
    }
    await invoke("recalculate_results");
    emit(
      "status",
      isEdit
        ? `Правило #${editRuleId.value} обновлено, выполнен пересчет.`
        : "Правило исключения добавлено, выполнен пересчет.",
    );
    closeForm();
    await refreshRules();
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения правила: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function deleteRule(ruleId: number) {
  if (!window.confirm(`Удалить правило исключения #${ruleId}?`)) {
    return;
  }
  localBusy.value = true;
  try {
    await invoke("delete_exclusion_rule", { ruleId });
    await invoke("recalculate_results");
    emit("status", `Правило #${ruleId} удалено, выполнен пересчет.`);
    await refreshRules();
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка удаления правила: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refreshRules });
</script>

<template>
  <section class="native-tools nested-card">
    <div class="modal-header" style="margin-bottom: 8px">
      <h3 style="margin: 0">Исключение перегонов</h3>
      <button :disabled="props.busy || localBusy" @click="openCreateForm">
        Добавить исключение
      </button>
    </div>
    <p class="subtitle">
      «Для всех» — ко всем участникам. Правило формата дополняет общие при расчёте.
    </p>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
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
          <tr v-if="!rules.length">
            <td colspan="7">Правил нет</td>
          </tr>
          <tr v-for="rule in rules" :key="rule.id">
            <td>{{ scopeText(rule) }}</td>
            <td>{{ rule.from_cp }}</td>
            <td>{{ rule.to_cp }}</td>
            <td>{{ directionText(rule.direction) }}</td>
            <td>{{ applyModeText(rule.apply_mode) }}</td>
            <td>{{ rule.max_leg_seconds ?? "" }}</td>
            <td>
              <IconActionButton
                variant="edit"
                label="Изменить"
                :disabled="props.busy || localBusy"
                @click="openEditForm(rule)"
              />
              <IconActionButton
                variant="copy"
                label="Скопировать для изменения"
                :disabled="props.busy || localBusy"
                @click="openCopyForm(rule)"
              />
              <IconActionButton
                variant="delete"
                label="Удалить"
                :disabled="props.busy || localBusy"
                @click="deleteRule(rule.id)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="formOpen"
      class="modal-backdrop"
      @click.self="closeForm"
    >
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
            Область
            <select v-model="scopeTarget">
              <option value="all">Для всех</option>
              <option
                v-for="fmt in props.formats"
                :key="fmt.format_id"
                :value="String(fmt.format_id)"
              >
                {{ fmt.format_name }}
              </option>
            </select>
          </label>
          <label>
            От КП
            <input v-model.number="fromCp" type="number" min="1" />
          </label>
          <label>
            До КП
            <input v-model.number="toCp" type="number" min="1" />
          </label>
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
            <input
              v-model.number="maxLegSeconds"
              type="number"
              min="0"
              placeholder="например 600"
            />
          </label>
        </div>
        <div class="native-settings">
          <IconActionButton
            variant="save"
            :label="formMode === 'edit' ? 'Сохранить' : 'Добавить'"
            :disabled="props.busy || localBusy"
            @click="saveRule"
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
