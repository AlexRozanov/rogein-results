<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";

type FormatOption = {
  format_id: number;
  format_name: string;
};

type CpLegendTypeRow = {
  id: number;
  type_name: string;
  usage_count: number;
};

type FormatCpTypeRuleItem = {
  cp_type_id: number;
  cp_type_name: string;
  max_count: number | null;
};

type FormatCpTypeRulesBundle = {
  format_id: number;
  format_name: string;
  rules: FormatCpTypeRuleItem[];
};

type DraftTypeRule = {
  enabled: boolean;
  maxCount: string;
};

const props = defineProps<{
  busy: boolean;
  formats: FormatOption[];
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const bundles = ref<FormatCpTypeRulesBundle[]>([]);
const typeRows = ref<CpLegendTypeRow[]>([]);
const localBusy = ref(false);
const formOpen = ref(false);
const formMode = ref<"create" | "edit">("create");
const editingFormatId = ref<number | null>(null);
const selectedFormatId = ref<string>("");
const drafts = ref<Record<number, DraftTypeRule>>({});

const formTitle = computed(() =>
  formMode.value === "edit" ? "Изменить типы КП формата" : "Задать типы КП для формата",
);

const configuredFormatIds = computed(
  () => new Set(bundles.value.map((b) => b.format_id)),
);

const selectableFormats = computed(() => {
  if (formMode.value === "edit" && editingFormatId.value != null) {
    return props.formats.filter((f) => f.format_id === editingFormatId.value);
  }
  return props.formats.filter((f) => !configuredFormatIds.value.has(f.format_id));
});

onMounted(() => {
  void refresh();
});

watch(
  () => props.formats.map((f) => f.format_id).join(","),
  () => {
    if (
      selectedFormatId.value &&
      !props.formats.some((f) => String(f.format_id) === selectedFormatId.value)
    ) {
      selectedFormatId.value = "";
    }
  },
);

async function refresh() {
  try {
    const [rulesData, typesData] = await Promise.all([
      invoke<FormatCpTypeRulesBundle[]>("get_format_cp_type_rules"),
      invoke<CpLegendTypeRow[]>("get_cp_legend_type_rows"),
    ]);
    bundles.value = rulesData;
    typeRows.value = typesData;
  } catch (error) {
    emit("status", `Ошибка загрузки правил типов КП: ${String(error)}`);
  }
}

function rulesSummary(bundle: FormatCpTypeRulesBundle) {
  if (!bundle.rules.length) return "Все типы";
  return bundle.rules
    .map((r) =>
      r.max_count == null ? r.cp_type_name : `${r.cp_type_name} (≤${r.max_count})`,
    )
    .join(", ");
}

function resetDrafts(seed?: FormatCpTypeRulesBundle | null) {
  const next: Record<number, DraftTypeRule> = {};
  const byType = new Map((seed?.rules || []).map((r) => [r.cp_type_id, r]));
  for (const t of typeRows.value) {
    const existing = byType.get(t.id);
    next[t.id] = {
      enabled: !!existing,
      maxCount: existing?.max_count == null ? "" : String(existing.max_count),
    };
  }
  drafts.value = next;
}

function openCreate() {
  formMode.value = "create";
  editingFormatId.value = null;
  selectedFormatId.value = selectableFormats.value[0]
    ? String(selectableFormats.value[0].format_id)
    : "";
  resetDrafts(null);
  formOpen.value = true;
}

function openEdit(bundle: FormatCpTypeRulesBundle) {
  formMode.value = "edit";
  editingFormatId.value = bundle.format_id;
  selectedFormatId.value = String(bundle.format_id);
  resetDrafts(bundle);
  formOpen.value = true;
}

function closeForm() {
  formOpen.value = false;
  editingFormatId.value = null;
  selectedFormatId.value = "";
  drafts.value = {};
}

function parseMaxCount(raw: string): number | null | "invalid" {
  const trimmed = String(raw || "").trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  if (!Number.isInteger(n) || n < 1) return "invalid";
  return n;
}

async function saveForm() {
  const formatId = Number(selectedFormatId.value);
  if (!Number.isFinite(formatId) || formatId <= 0) {
    emit("status", "Выберите формат.");
    return;
  }

  const rules: { cpTypeId: number; maxCount: number | null }[] = [];
  for (const t of typeRows.value) {
    const draft = drafts.value[t.id];
    if (!draft?.enabled) continue;
    const maxCount = parseMaxCount(draft.maxCount);
    if (maxCount === "invalid") {
      emit(
        "status",
        `Для типа «${t.type_name}» укажите пустое значение или целое число ≥ 1.`,
      );
      return;
    }
    rules.push({ cpTypeId: t.id, maxCount });
  }

  localBusy.value = true;
  try {
    if (!rules.length) {
      bundles.value = await invoke<FormatCpTypeRulesBundle[]>("delete_format_cp_type_rules", {
        formatId,
      });
      emit(
        "status",
        "Правила типов для формата сняты: засчитываются все КП. Выполняется пересчёт…",
      );
    } else {
      bundles.value = await invoke<FormatCpTypeRulesBundle[]>("set_format_cp_type_rules", {
        formatId,
        rules,
      });
      emit("status", "Правила типов КП для формата сохранены. Выполняется пересчёт…");
    }
    await invoke("recalculate_results");
    emit("status", "Правила типов КП сохранены, выполнен пересчёт.");
    emit("saved");
    closeForm();
  } catch (error) {
    emit("status", `Ошибка сохранения правил типов КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function removeRules(bundle: FormatCpTypeRulesBundle) {
  if (
    !window.confirm(
      `Снять ограничения типов для формата «${bundle.format_name}»? Будут засчитываться все КП.`,
    )
  ) {
    return;
  }
  localBusy.value = true;
  try {
    bundles.value = await invoke<FormatCpTypeRulesBundle[]>("delete_format_cp_type_rules", {
      formatId: bundle.format_id,
    });
    await invoke("recalculate_results");
    emit("status", `Ограничения для «${bundle.format_name}» сняты, выполнен пересчёт.`);
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка удаления правил типов КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <section class="native-tools nested-card">
    <div class="modal-header" style="margin-bottom: 8px">
      <h3 style="margin: 0">Типы КП по форматам</h3>
      <button :disabled="props.busy || localBusy" @click="openCreate">Задать для формата</button>
    </div>
    <p class="subtitle">
      Если типы не заданы — засчитываются все КП. Если заданы — только выбранные типы;
      для типа можно ограничить максимум (считаются первые взятые КП этого типа).
    </p>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th>Формат</th>
            <th>Разрешённые типы</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!bundles.length">
            <td colspan="3">Ограничений нет — для всех форматов засчитываются все КП</td>
          </tr>
          <tr v-for="bundle in bundles" :key="bundle.format_id">
            <td>{{ bundle.format_name }}</td>
            <td class="cell-wrap">{{ rulesSummary(bundle) }}</td>
            <td>
              <IconActionButton
                variant="edit"
                label="Изменить"
                :disabled="props.busy || localBusy"
                @click="openEdit(bundle)"
              />
              <IconActionButton
                variant="delete"
                label="Снять"
                :disabled="props.busy || localBusy"
                @click="removeRules(bundle)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="formOpen" class="modal-backdrop" @click.self="closeForm">
      <div class="modal-card" role="dialog" aria-modal="true" :aria-label="formTitle">
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
              <option value="">Выберите формат</option>
              <option
                v-for="fmt in selectableFormats"
                :key="fmt.format_id"
                :value="String(fmt.format_id)"
              >
                {{ fmt.format_name }}
              </option>
            </select>
          </label>
        </div>

        <p class="subtitle">Отметьте типы, которые засчитываются. Максимум — необязателен.</p>
        <div v-if="!typeRows.length" class="subtitle">
          Сначала добавьте типы в справочнике на вкладке «Легенды КП».
        </div>
        <div v-else class="native-results-wrap">
          <table class="native-results">
            <thead>
              <tr>
                <th></th>
                <th>Тип</th>
                <th>Макс. КП</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in typeRows" :key="t.id">
                <td>
                  <input v-model="drafts[t.id].enabled" type="checkbox" />
                </td>
                <td>{{ t.type_name }}</td>
                <td>
                  <input
                    v-model="drafts[t.id].maxCount"
                    class="row-edit-input"
                    type="number"
                    min="1"
                    step="1"
                    placeholder="без лимита"
                    :disabled="!drafts[t.id]?.enabled"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="native-row" style="margin-top: 12px">
          <IconActionButton
            variant="save"
            label="Сохранить"
            :disabled="localBusy || props.busy"
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
