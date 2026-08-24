<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";
import CollapsiblePanel from "./CollapsiblePanel.vue";

type ExclusionRuleRow = {
  id: number;
  finish_participant_id: number | null;
  format_id: number | null;
  course_id: number | null;
  course_name: string | null;
  from_cp: number;
  to_cp: number;
  max_leg_seconds: number | null;
  created_at: string;
};

type CourseRow = {
  id: number;
  name: string;
  controls: number[];
};

type NativeSettings = {
  start_cp: number | null;
  finish_cp: number;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const localBusy = ref(false);
const rules = ref<ExclusionRuleRow[]>([]);
const courses = ref<CourseRow[]>([]);
const removedCps = ref<Set<number>>(new Set());
const startCp = ref<number | null>(null);
const finishCp = ref<number>(0);
const fromCp = ref<number | null>(null);
const toCp = ref<number | null>(null);
const selectedCourseId = ref<number | null>(null);
const maxLegMinutes = ref<number | null>(null);
const formOpen = ref(false);
const formMode = ref<"create" | "edit">("create");
const editRuleId = ref<number | null>(null);

const isBusy = computed(() => props.busy || localBusy.value);

const selectedCourse = computed(
  () => courses.value.find((course) => course.id === selectedCourseId.value) ?? null,
);

const usedLegs = computed(() => {
  const out = new Set<string>();
  const courseId = selectedCourseId.value;
  for (const rule of rules.value) {
    if (formMode.value === "edit" && rule.id === editRuleId.value) continue;
    if (rule.course_id !== courseId) continue;
    out.add(legKey(rule.from_cp, rule.to_cp));
  }
  return out;
});

/** Все соседние перегоны выбранной дистанции в порядке прохождения, включая повторы КП. */
const courseLegOptions = computed(() => {
  const course = selectedCourse.value;
  if (!course) return [];
  return courseLegs(course).map((leg) => ({
    from: leg.from,
    to: leg.to,
    used: usedLegs.value.has(legKey(leg.from, leg.to)),
    label: `${formatCp(leg.from)} → ${formatCp(leg.to)}`,
  }));
});

const selectedLegKey = computed(() =>
  fromCp.value != null && toCp.value != null ? legKey(fromCp.value, toCp.value) : "",
);

const selectableLegKeys = computed(() =>
  courseLegOptions.value.filter((item) => !item.used).map((item) => legKey(item.from, item.to)),
);

const formTitle = computed(() =>
  formMode.value === "edit" && editRuleId.value != null
    ? `Изменить исключение #${editRuleId.value}`
    : "Добавить исключение перегона",
);

function legKey(from: number, to: number): string {
  return `${from}:${to}`;
}

function parseLegKey(value: string): { from: number; to: number } | null {
  const [fromRaw, toRaw] = value.split(":");
  const from = Number(fromRaw);
  const to = Number(toRaw);
  if (!Number.isFinite(from) || !Number.isFinite(to) || from <= 0 || to <= 0) return null;
  return { from, to };
}

function formatCp(cp: number): string {
  if (startCp.value != null && cp === startCp.value) return `${cp} (старт)`;
  if (cp === finishCp.value) return `${cp} (финиш)`;
  return String(cp);
}

function courseLegs(course: CourseRow): { from: number; to: number }[] {
  const controls = filteredControls(course);
  const seq: number[] = [];
  if (startCp.value != null && controls[0] !== startCp.value) seq.push(startCp.value);
  seq.push(...controls);
  if (finishCp.value && seq[seq.length - 1] !== finishCp.value) seq.push(finishCp.value);
  const legs: { from: number; to: number }[] = [];
  for (let i = 0; i < seq.length - 1; i += 1) {
    if (seq[i] === seq[i + 1]) continue;
    legs.push({ from: seq[i], to: seq[i + 1] });
  }
  return legs;
}

function selectFirstFreeLeg() {
  const key = selectableLegKeys.value[0];
  const parsed = key ? parseLegKey(key) : null;
  fromCp.value = parsed?.from ?? null;
  toCp.value = parsed?.to ?? null;
}

onMounted(() => {
  void refresh();
});

function filteredControls(course: CourseRow): number[] {
  return course.controls.filter((cp) => !removedCps.value.has(cp));
}

function toCpText(rule: ExclusionRuleRow): string {
  if (rule.to_cp > 0) return formatCp(rule.to_cp);
  const course =
    courses.value.find((item) => item.id === rule.course_id) ??
    (rule.course_name
      ? courses.value.find((item) => item.name === rule.course_name)
      : undefined);
  if (!course) return "—";
  const match = courseLegs(course).find((leg) => leg.from === rule.from_cp);
  return match ? formatCp(match.to) : "—";
}

function courseText(rule: ExclusionRuleRow): string {
  return rule.course_name?.trim() || (rule.course_id != null ? `#${rule.course_id}` : "Все");
}

function maxTimeText(seconds: number | null): string {
  if (seconds == null) return "без лимита";
  if (seconds % 60 === 0) return `${seconds / 60} мин`;
  return `${seconds} сек`;
}

function maxSecondsFromForm(): number | null {
  const mins = maxLegMinutes.value;
  if (mins == null || !Number.isFinite(mins) || mins <= 0) return null;
  return Math.round(mins * 60);
}

function resetForm() {
  editRuleId.value = null;
  fromCp.value = null;
  toCp.value = null;
  selectedCourseId.value = courses.value[0]?.id ?? null;
  maxLegMinutes.value = null;
  formMode.value = "create";
}

function openCreateForm() {
  formMode.value = "create";
  editRuleId.value = null;
  selectedCourseId.value = courses.value[0]?.id ?? null;
  selectFirstFreeLeg();
  maxLegMinutes.value = null;
  formOpen.value = true;
}

function openEditForm(rule: ExclusionRuleRow) {
  formMode.value = "edit";
  editRuleId.value = rule.id;
  selectedCourseId.value = rule.course_id;
  fromCp.value = rule.from_cp;
  toCp.value = rule.to_cp > 0 ? rule.to_cp : null;
  if (toCp.value == null && selectedCourse.value) {
    const match = courseLegs(selectedCourse.value).find((leg) => leg.from === rule.from_cp);
    toCp.value = match?.to ?? null;
  }
  maxLegMinutes.value =
    rule.max_leg_seconds != null && rule.max_leg_seconds % 60 === 0
      ? rule.max_leg_seconds / 60
      : rule.max_leg_seconds != null
        ? Math.round((rule.max_leg_seconds / 60) * 10) / 10
        : null;
  formOpen.value = true;
}

function closeForm() {
  formOpen.value = false;
  resetForm();
}

function onCourseChange(value: string) {
  selectedCourseId.value = value ? Number(value) : null;
  if (!selectedLegKey.value || !selectableLegKeys.value.includes(selectedLegKey.value)) {
    selectFirstFreeLeg();
  }
}

function onLegChange(value: string) {
  const parsed = parseLegKey(value);
  fromCp.value = parsed?.from ?? null;
  toCp.value = parsed?.to ?? null;
}

async function refresh() {
  try {
    const [settings, courseRows, corrections, exclusionRows] = await Promise.all([
      invoke<NativeSettings>("get_settings"),
      invoke<CourseRow[]>("get_courses"),
      invoke<Array<{ correction_type: string; payload: Record<string, unknown> }>>(
        "get_manual_corrections",
        { participantId: null },
      ),
      invoke<ExclusionRuleRow[]>("get_exclusion_rules", {
        participantId: null,
        formatId: null,
      }),
    ]);
    startCp.value = settings.start_cp;
    finishCp.value = settings.finish_cp;
    courses.value = courseRows;
    const removed = new Set<number>();
    for (const row of corrections) {
      if (row.correction_type !== "remove_cp") continue;
      const cp = Number(row.payload?.cp_number);
      if (Number.isFinite(cp)) removed.add(cp);
    }
    removedCps.value = removed;
    rules.value = exclusionRows.filter(
      (row) => row.finish_participant_id == null && row.format_id == null,
    );
    if (
      selectedCourseId.value != null &&
      !courses.value.some((course) => course.id === selectedCourseId.value)
    ) {
      selectedCourseId.value = courses.value[0]?.id ?? null;
    }
    if (fromCp.value != null && toCp.value != null && !selectableLegKeys.value.includes(selectedLegKey.value) && formMode.value !== "edit") {
      fromCp.value = null;
      toCp.value = null;
    }
  } catch (error) {
    emit("status", `Ошибка загрузки исключений перегонов: ${String(error)}`);
  }
}

async function saveRule() {
  if (selectedCourseId.value == null) {
    emit("status", "Выберите дистанцию.");
    return;
  }
  if (fromCp.value == null || toCp.value == null) {
    emit("status", "Выберите перегон.");
    return;
  }
  localBusy.value = true;
  try {
    const payload = {
      participantScope: "all",
      participantId: null as string | null,
      formatId: null as number | null,
      courseId: selectedCourseId.value,
      fromCp: fromCp.value,
      toCp: toCp.value,
      direction: "forward",
      applyMode: "always",
      maxLegSeconds: maxSecondsFromForm(),
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
        ? `Перегон ${fromCp.value} → ${toCp.value} обновлён, выполнен пересчёт.`
        : `Перегон ${fromCp.value} → ${toCp.value} исключён, выполнен пересчёт.`,
    );
    closeForm();
    await refresh();
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения исключения: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function deleteRule(rule: ExclusionRuleRow) {
  if (!window.confirm(`Удалить исключение перегона ${rule.from_cp} → ${rule.to_cp}?`)) {
    return;
  }
  localBusy.value = true;
  try {
    await invoke("delete_exclusion_rule", { ruleId: rule.id });
    await invoke("recalculate_results");
    emit("status", `Исключение ${rule.from_cp} → ${rule.to_cp} удалено, выполнен пересчёт.`);
    await refresh();
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка удаления исключения: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <CollapsiblePanel panel-id="orient-exclusion-rules" title="Исключение перегонов">
    <template #actions>
      <button :disabled="isBusy || !courses.length" @click="openCreateForm">
        Добавить исключение
      </button>
    </template>
    <p class="subtitle">
      Исключение задаётся отдельно для каждой дистанции. В списке все перегоны выбранной
      дистанции в порядке прохождения. Один и тот же КП может встречаться несколько раз.
    </p>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th>Дистанция</th>
            <th>От КП</th>
            <th>До КП</th>
            <th>Макс. время</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rules.length">
            <td colspan="5">Исключённых перегонов нет</td>
          </tr>
          <tr v-for="rule in rules" :key="rule.id">
            <td>{{ courseText(rule) }}</td>
            <td>{{ rule.from_cp }}</td>
            <td>{{ toCpText(rule) }}</td>
            <td>{{ maxTimeText(rule.max_leg_seconds) }}</td>
            <td>
              <IconActionButton
                variant="edit"
                label="Изменить"
                :disabled="isBusy"
                @click="openEditForm(rule)"
              />
              <IconActionButton
                variant="delete"
                label="Удалить"
                :disabled="isBusy"
                @click="deleteRule(rule)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!courses.length" class="subtitle">
      Сначала загрузите дистанции.
    </p>

    <template #overlay>
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
              Дистанция
              <select
                :value="selectedCourseId == null ? '' : String(selectedCourseId)"
                :disabled="localBusy || !courses.length"
                @change="onCourseChange(($event.target as HTMLSelectElement).value)"
              >
                <option value="">Выберите дистанцию</option>
                <option v-for="course in courses" :key="course.id" :value="String(course.id)">
                  {{ course.name }}
                </option>
              </select>
            </label>
            <label>
              Перегон
              <select
                :key="`leg-${selectedCourseId ?? 'none'}`"
                :value="selectedLegKey"
                :disabled="localBusy || !courseLegOptions.length"
                @change="onLegChange(($event.target as HTMLSelectElement).value)"
              >
                <option value="">Выберите перегон</option>
                <option
                  v-for="(item, idx) in courseLegOptions"
                  :key="`${idx}-${item.from}-${item.to}`"
                  :value="`${item.from}:${item.to}`"
                  :disabled="item.used"
                >
                  {{ item.label }}
                </option>
              </select>
            </label>
            <label>
              Макс. время (мин, пусто = без лимита)
              <input
                v-model.number="maxLegMinutes"
                type="number"
                min="1"
                step="1"
                placeholder="без лимита"
              />
            </label>
          </div>
          <div class="native-settings">
            <IconActionButton
              variant="save"
              :label="formMode === 'edit' ? 'Сохранить' : 'Добавить'"
              :disabled="isBusy || selectedCourseId == null || fromCp == null || toCp == null"
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
    </template>
  </CollapsiblePanel>
</template>
