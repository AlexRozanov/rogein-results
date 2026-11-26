<script setup lang="ts">
import { nextTick, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";
import IconActionButton from "./IconActionButton.vue";
import ImportExistingDataDialog from "./ImportExistingDataDialog.vue";
import {
  mergeClusterWithNext,
  parseClusterGroups,
  splitClusterAfter,
  toggleClusterGroup,
} from "../courseOrder";

type CourseRow = {
  id: number;
  name: string;
  controls: number[];
  /** 0 = fixed, >0 = cluster group id */
  randomOrder?: Array<number | boolean>;
};

type CourseDraft = {
  key: string;
  id: number | null;
  name: string;
  controls: number[];
  randomOrder: number[];
  addText: string;
  editingIndex: number | null;
  editText: string;
};

type CoursesImportSummary = {
  imported_rows: number;
  total_rows: number;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const drafts = ref<CourseDraft[]>([]);
const removedCps = ref<Set<number>>(new Set());
const localBusy = ref(false);
const coursesFile = ref<File | null>(null);
const importDialogOpen = ref(false);
const importExistingCount = ref(0);
const mismatchOpen = ref(false);
const dragFrom = ref<{ key: string; index: number } | null>(null);
const dropAt = ref<{ key: string; index: number } | null>(null);
const skipClick = ref(false);
const saveTimers = new Map<string, ReturnType<typeof setTimeout>>();
let newDraftSeq = 0;

const isBusy = () => props.busy || localBusy.value;

onMounted(() => {
  void refresh();
});

function normalizeGroups(
  controls: number[],
  flags?: Array<number | boolean>,
): number[] {
  return parseClusterGroups(flags, controls.length);
}

function toDraft(row: CourseRow): CourseDraft {
  return {
    key: `id-${row.id}`,
    id: row.id,
    name: row.name,
    controls: [...row.controls],
    randomOrder: normalizeGroups(row.controls, row.randomOrder),
    addText: "",
    editingIndex: null,
    editText: "",
  };
}

async function refresh() {
  try {
    const [rows, corrections] = await Promise.all([
      invoke<CourseRow[]>("get_courses"),
      invoke<{ correction_type: string; payload: Record<string, unknown> }[]>(
        "get_manual_corrections",
        { participantId: null },
      ),
    ]);
    drafts.value = rows.map(toDraft);
    removedCps.value = new Set(
      corrections
        .filter((row) => row.correction_type === "remove_cp")
        .map((row) => Number(row.payload?.cp_number))
        .filter((cp) => Number.isFinite(cp)),
    );
  } catch (error) {
    emit("status", `Ошибка загрузки дистанций: ${String(error)}`);
  }
}

function isRemovedCp(cp: number) {
  return removedCps.value.has(cp);
}

type CourseDisplaySeg =
  | { kind: "fixed"; index: number; cp: number }
  | { kind: "cluster"; start: number; cps: number[]; indices: number[] };

/** Группы для отображения: 71 → 72 → [74 75] → [77 78] → 81 */
function courseDisplaySegments(draft: CourseDraft): CourseDisplaySeg[] {
  const out: CourseDisplaySeg[] = [];
  let i = 0;
  while (i < draft.controls.length) {
    const g = draft.randomOrder[i] ?? 0;
    if (g > 0) {
      const cps: number[] = [];
      const indices: number[] = [];
      while (i < draft.controls.length && draft.randomOrder[i] === g) {
        cps.push(draft.controls[i]!);
        indices.push(i);
        i += 1;
      }
      out.push({ kind: "cluster", start: indices[0]!, cps, indices });
    } else {
      out.push({ kind: "fixed", index: i, cp: draft.controls[i]! });
      i += 1;
    }
  }
  return out;
}

function parseCpList(raw: string): number[] {
  return raw
    .split(/[\s,;→\-–]+/u)
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => Number(part))
    .filter((n) => Number.isInteger(n) && n > 0);
}

function addCourse() {
  newDraftSeq += 1;
  drafts.value.push({
    key: `new-${newDraftSeq}`,
    id: null,
    name: "",
    controls: [],
    randomOrder: [],
    addText: "",
    editingIndex: null,
    editText: "",
  });
}

function scheduleSave(draft: CourseDraft) {
  const prev = saveTimers.get(draft.key);
  if (prev) clearTimeout(prev);
  saveTimers.set(
    draft.key,
    setTimeout(() => {
      saveTimers.delete(draft.key);
      void persist(draft);
    }, 280),
  );
}

async function persist(draft: CourseDraft) {
  const name = draft.name.trim();
  if (!name || draft.controls.length === 0) return;
  if (localBusy.value) {
    scheduleSave(draft);
    return;
  }
  localBusy.value = true;
  try {
    const saved = await invoke<CourseRow>("save_course", {
      courseId: draft.id,
      name,
      controls: draft.controls,
      randomOrder: normalizeGroups(draft.controls, draft.randomOrder),
    });
    draft.id = saved.id;
    draft.name = saved.name;
    draft.controls = [...saved.controls];
    draft.randomOrder = normalizeGroups(saved.controls, saved.randomOrder);
    draft.key = `id-${saved.id}`;
    emit("status", `Дистанция «${saved.name}» сохранена, результаты пересчитаны.`);
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения дистанции: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function removeCourse(draft: CourseDraft) {
  if (isBusy()) return;
  const label = draft.name.trim() || "без названия";
  if (!window.confirm(`Удалить дистанцию «${label}»? Результаты будут пересчитаны.`)) return;
  if (draft.id == null) {
    drafts.value = drafts.value.filter((d) => d.key !== draft.key);
    return;
  }
  localBusy.value = true;
  try {
    await invoke("delete_course", { courseId: draft.id });
    drafts.value = drafts.value.filter((d) => d.key !== draft.key);
    emit("status", `Дистанция «${label}» удалена, результаты пересчитаны.`);
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка удаления дистанции: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

function addFromInput(draft: CourseDraft) {
  const nums = parseCpList(draft.addText);
  if (!nums.length) return;
  draft.controls.push(...nums);
  draft.randomOrder.push(...nums.map(() => 0));
  draft.addText = "";
  scheduleSave(draft);
}

function onAddKeydown(draft: CourseDraft, event: KeyboardEvent) {
  if (event.key === "Enter" || event.key === " " || event.key === ",") {
    event.preventDefault();
    addFromInput(draft);
    return;
  }
  if (event.key === "Backspace" && !draft.addText && draft.controls.length) {
    event.preventDefault();
    draft.controls.pop();
    draft.randomOrder.pop();
    scheduleSave(draft);
  }
}

function onAddPaste(draft: CourseDraft, event: ClipboardEvent) {
  const text = event.clipboardData?.getData("text") ?? "";
  const nums = parseCpList(text);
  if (nums.length <= 1) return;
  event.preventDefault();
  draft.controls.push(...nums);
  draft.randomOrder.push(...nums.map(() => 0));
  draft.addText = "";
  scheduleSave(draft);
}

function removeCp(draft: CourseDraft, index: number) {
  draft.controls.splice(index, 1);
  draft.randomOrder.splice(index, 1);
  if (draft.editingIndex === index) {
    draft.editingIndex = null;
    draft.editText = "";
  }
  scheduleSave(draft);
}

function toggleRandom(draft: CourseDraft, index: number) {
  if (index < 0 || index >= draft.randomOrder.length) return;
  // Alt+клик: в гроздь / из грозди; к предыдущей грозди присоединяется.
  draft.randomOrder = toggleClusterGroup(draft.randomOrder, index, true);
  scheduleSave(draft);
}

/** Клик по пробелу внутри [a b c] — разделить на две смежные грозди. */
function splitClusterAt(draft: CourseDraft, index: number) {
  const next = splitClusterAfter(draft.randomOrder, index);
  if (!next) return;
  draft.randomOrder = next;
  scheduleSave(draft);
}

/** Клик по → между двумя гроздями — объединить. */
function mergeClustersAt(draft: CourseDraft, index: number) {
  const next = mergeClusterWithNext(draft.randomOrder, index);
  if (!next) return;
  draft.randomOrder = next;
  scheduleSave(draft);
}

function canSplitAfter(draft: CourseDraft, index: number) {
  const id = draft.randomOrder[index] ?? 0;
  return id > 0 && draft.randomOrder[index + 1] === id;
}

function canMergeAfterSegment(draft: CourseDraft, lastIndex: number) {
  const left = draft.randomOrder[lastIndex] ?? 0;
  const right = draft.randomOrder[lastIndex + 1] ?? 0;
  return left > 0 && right > 0 && left !== right;
}

function onChipClick(draft: CourseDraft, index: number, event: MouseEvent) {
  if (event.altKey) {
    toggleRandom(draft, index);
    return;
  }
  startEditChip(draft, index);
}

function moveCpToSlot(draft: CourseDraft, from: number, slot: number) {
  let to = slot;
  if (from < to) to -= 1;
  if (from === to || to < 0 || to > draft.controls.length - 1) return;
  const [cp] = draft.controls.splice(from, 1);
  const [flag] = draft.randomOrder.splice(from, 1);
  draft.controls.splice(to, 0, cp);
  draft.randomOrder.splice(to, 0, flag);
  scheduleSave(draft);
}

function isDropCaret(draft: CourseDraft, slot: number) {
  if (!dragFrom.value || !dropAt.value) return false;
  if (dragFrom.value.key !== draft.key || dropAt.value.key !== draft.key) return false;
  if (dropAt.value.index !== slot) return false;
  return slot !== dragFrom.value.index && slot !== dragFrom.value.index + 1;
}

function setDropSlot(draft: CourseDraft, slot: number, event: DragEvent) {
  event.preventDefault();
  if (!dragFrom.value || dragFrom.value.key !== draft.key) return;
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
  if (dropAt.value?.key !== draft.key || dropAt.value.index !== slot) {
    dropAt.value = { key: draft.key, index: slot };
  }
}

function startEditChip(draft: CourseDraft, index: number) {
  if (skipClick.value) {
    skipClick.value = false;
    return;
  }
  draft.editingIndex = index;
  draft.editText = String(draft.controls[index] ?? "");
  void nextTick(() => {
    const el = document.querySelector<HTMLInputElement>(
      `input[data-course-edit="${draft.key}-${index}"]`,
    );
    el?.focus();
    el?.select();
  });
}

function commitEditChip(draft: CourseDraft) {
  if (draft.editingIndex == null) return;
  const nums = parseCpList(draft.editText);
  const index = draft.editingIndex;
  draft.editingIndex = null;
  draft.editText = "";
  if (!nums.length) {
    removeCp(draft, index);
    return;
  }
  const keepGroup = draft.randomOrder[index] ?? 0;
  const flags = nums.map((_, i) => (i === 0 ? keepGroup : 0));
  draft.controls.splice(index, 1, ...nums);
  draft.randomOrder.splice(index, 1, ...flags);
  scheduleSave(draft);
}

function onDragStart(draft: CourseDraft, index: number, event: DragEvent) {
  dragFrom.value = { key: draft.key, index };
  dropAt.value = null;
  skipClick.value = true;
  event.dataTransfer?.setData("text/plain", String(index));
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
}

function onChipDragOver(draft: CourseDraft, index: number, event: DragEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  const slot = event.clientX < rect.left + rect.width / 2 ? index : index + 1;
  setDropSlot(draft, slot, event);
}

function onChipDrop(draft: CourseDraft, index: number, event: DragEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  const fallback = event.clientX < rect.left + rect.width / 2 ? index : index + 1;
  const slot = dropAt.value?.key === draft.key ? dropAt.value.index : fallback;
  onDropAt(draft, slot);
}

function onDropAt(draft: CourseDraft, slot: number) {
  const from = dragFrom.value;
  dropAt.value = null;
  dragFrom.value = null;
  if (!from || from.key !== draft.key) return;
  moveCpToSlot(draft, from.index, slot);
}

function onRowDragLeave(draft: CourseDraft, event: DragEvent) {
  const next = event.relatedTarget as Node | null;
  if (next && (event.currentTarget as HTMLElement).contains(next)) return;
  if (dropAt.value?.key === draft.key) dropAt.value = null;
}

function onDragEnd() {
  dragFrom.value = null;
  dropAt.value = null;
  setTimeout(() => {
    skipClick.value = false;
  }, 0);
}

async function onFileSelected(file: File | null) {
  coursesFile.value = file;
  if (!coursesFile.value) return;
  await beginImportFlow();
}

async function beginImportFlow() {
  try {
    const counts = await invoke<{ courses: number }>("get_data_presence_counts");
    if (counts.courses > 0) {
      importExistingCount.value = counts.courses;
      importDialogOpen.value = true;
      return;
    }
    await runImport(true);
  } catch (error) {
    emit("status", `Ошибка проверки дистанций: ${String(error)}`);
  }
}

function onImportDialogCancel() {
  importDialogOpen.value = false;
}

async function onImportDialogMerge() {
  importDialogOpen.value = false;
  await runImport(false);
}

async function onImportDialogReplace() {
  importDialogOpen.value = false;
  await runImport(true);
}

async function onMismatchReplace() {
  mismatchOpen.value = false;
  await runImport(true);
}

async function runImport(reset: boolean) {
  if (!coursesFile.value) {
    emit("status", "Выберите CSV с дистанциями.");
    return;
  }
  localBusy.value = true;
  try {
    const csvContent = await coursesFile.value.text();
    const summary = await invoke<CoursesImportSummary>("import_courses_content", {
      csvContent,
      reset,
    });
    await invoke("recalculate_results");
    emit(
      "status",
      reset
        ? `Дистанции загружены заново: ${summary.imported_rows}, всего ${summary.total_rows}. Результаты пересчитаны.`
        : `Дистанции обновлены: ${summary.imported_rows}, всего ${summary.total_rows}. Результаты пересчитаны.`,
    );
    await refresh();
    emit("saved");
  } catch (error) {
    if (!reset && String(error).includes("IOF_COURSE_MISMATCH")) {
      mismatchOpen.value = true;
      emit("status", "Порядок КП в XML не совпадает с загруженными дистанциями.");
      return;
    }
    emit("status", `Ошибка импорта дистанций: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <section class="native-tools nested-card">
    <h2>Дистанции</h2>
    <p class="subtitle">
      Порядок КП: <code>1 → 2 → 3</code>. Клик по номеру — изменить, × — удалить из этой дистанции,
      перетаскивание — порядок (полоска показывает, куда встанет КП).
      ПКМ / Alt+клик по КП — гроздь.
      Клик по <code>·</code> внутри <code>[74 · 75 · 76]</code> — разделить на смежные грозди
      <code>[74 75] → [76]</code> (сначала первая, потом вторая).
      Клик по <code>→</code> между двумя гроздями — объединить.
      В поле справа введите номер и Enter / пробел; можно вставить список
      <code>32 51 34</code>. Старт и финиш задаются в настройках.
      CSV: строка на дистанцию, <code>D1;31;32;33</code> или <code>D1;31 32 33</code>.
      Также файл WinOrient <code>.Courses.txt</code>:
      <code>D1 3,8 30 S1-36-31-…-F1</code> — S1 старт, F1 финиш, между ними порядок КП.
      IOF XML 3.0 <code>CourseData</code> добавляет длину, набор высоты, перегоны и
      <code>randomOrder</code> для гроздей.
    </p>
    <p v-if="removedCps.size" class="subtitle">
      Зачёркнутый КП снят в настройках для всех участников — как будто его не было на дистанции.
    </p>
    <div class="native-row">
      <FilePickerButton
        button-label="Выбрать CSV/XML дистанций"
        empty-label="Файл не выбран"
        accept=".csv,.txt,.xml,text/csv,text/plain,application/xml,text/xml"
        :disabled="isBusy()"
        @file-selected="onFileSelected"
      />
      <button type="button" :disabled="isBusy()" @click="addCourse">Добавить дистанцию</button>
      <button type="button" :disabled="isBusy()" @click="refresh">Обновить</button>
    </div>
    <div class="native-results-wrap">
      <table class="native-results course-table">
        <thead>
          <tr>
            <th>Дистанция</th>
            <th>КП</th>
            <th>Порядок</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!drafts.length">
            <td colspan="4">Дистанции ещё не загружены</td>
          </tr>
          <tr v-for="draft in drafts" :key="draft.key">
          <td>
            <input
              v-model="draft.name"
              class="course-name-input"
              placeholder="D1"
              :disabled="isBusy()"
              @change="scheduleSave(draft)"
              @keydown.enter.prevent="persist(draft)"
            />
          </td>
          <td>{{ draft.controls.length }}</td>
          <td class="course-seq-cell">
            <div
              class="course-chip-row"
              @dragleave="onRowDragLeave(draft, $event)"
            >
              <template
                v-for="(seg, segIdx) in courseDisplaySegments(draft)"
                :key="`${draft.key}-seg-${segIdx}-${seg.kind === 'fixed' ? seg.cp : seg.cps.join('-')}`"
              >
                <template v-if="seg.kind === 'fixed'">
                  <span
                    class="course-drop-caret"
                    :class="{ active: isDropCaret(draft, seg.index) }"
                    aria-hidden="true"
                  />
                  <span
                    class="course-chip"
                    :class="{
                      editing: draft.editingIndex === seg.index,
                      dragging: dragFrom?.key === draft.key && dragFrom.index === seg.index,
                      voided: isRemovedCp(seg.cp),
                    }"
                    :title="
                      isRemovedCp(seg.cp)
                        ? 'Снят в настройках — как будто не было на дистанции'
                        : 'Alt+клик или ПКМ — гроздь (свободный порядок)'
                    "
                    draggable="true"
                    @dragstart="onDragStart(draft, seg.index, $event)"
                    @dragover="onChipDragOver(draft, seg.index, $event)"
                    @drop.prevent="onChipDrop(draft, seg.index, $event)"
                    @dragend="onDragEnd"
                    @contextmenu.prevent="toggleRandom(draft, seg.index)"
                  >
                    <input
                      v-if="draft.editingIndex === seg.index"
                      :data-course-edit="`${draft.key}-${seg.index}`"
                      v-model="draft.editText"
                      class="course-chip-edit"
                      :disabled="isBusy()"
                      @keydown.enter.prevent="commitEditChip(draft)"
                      @keydown.escape="draft.editingIndex = null"
                      @blur="commitEditChip(draft)"
                    />
                    <template v-else>
                      <button
                        type="button"
                        class="course-chip-num"
                        :disabled="isBusy()"
                        @click="onChipClick(draft, seg.index, $event)"
                      >
                        {{ seg.cp }}
                      </button>
                      <button
                        type="button"
                        class="course-chip-x"
                        title="Удалить КП"
                        :disabled="isBusy()"
                        @click.stop="removeCp(draft, seg.index)"
                      >
                        ×
                      </button>
                    </template>
                  </span>
                </template>
                <span v-else class="course-cluster-group">
                  <span class="course-cluster-bracket" aria-hidden="true">[</span>
                  <template v-for="(cp, j) in seg.cps" :key="`${draft.key}-c-${seg.indices[j]}-${cp}`">
                    <span
                      class="course-drop-caret"
                      :class="{ active: isDropCaret(draft, seg.indices[j]!) }"
                      aria-hidden="true"
                    />
                    <span
                      class="course-chip cluster"
                      :class="{
                        editing: draft.editingIndex === seg.indices[j],
                        dragging:
                          dragFrom?.key === draft.key && dragFrom.index === seg.indices[j],
                        voided: isRemovedCp(cp),
                      }"
                      title="Гроздь: свободный порядок. Alt+клик или ПКМ — убрать"
                      draggable="true"
                      @dragstart="onDragStart(draft, seg.indices[j]!, $event)"
                      @dragover="onChipDragOver(draft, seg.indices[j]!, $event)"
                      @drop.prevent="onChipDrop(draft, seg.indices[j]!, $event)"
                      @dragend="onDragEnd"
                      @contextmenu.prevent="toggleRandom(draft, seg.indices[j]!)"
                    >
                      <input
                        v-if="draft.editingIndex === seg.indices[j]"
                        :data-course-edit="`${draft.key}-${seg.indices[j]}`"
                        v-model="draft.editText"
                        class="course-chip-edit"
                        :disabled="isBusy()"
                        @keydown.enter.prevent="commitEditChip(draft)"
                        @keydown.escape="draft.editingIndex = null"
                        @blur="commitEditChip(draft)"
                      />
                      <template v-else>
                        <button
                          type="button"
                          class="course-chip-num"
                          :disabled="isBusy()"
                          @click="onChipClick(draft, seg.indices[j]!, $event)"
                        >
                          {{ cp }}
                        </button>
                        <button
                          type="button"
                          class="course-chip-x"
                          title="Удалить КП"
                          :disabled="isBusy()"
                          @click.stop="removeCp(draft, seg.indices[j]!)"
                        >
                          ×
                        </button>
                      </template>
                    </span>
                    <button
                      v-if="j < seg.cps.length - 1"
                      type="button"
                      class="course-cluster-gap"
                      :class="{
                        active: isDropCaret(draft, seg.indices[j]! + 1),
                        split: canSplitAfter(draft, seg.indices[j]!),
                      }"
                      :title="
                        canSplitAfter(draft, seg.indices[j]!)
                          ? 'Разделить гроздь здесь'
                          : undefined
                      "
                      :disabled="isBusy() || !canSplitAfter(draft, seg.indices[j]!)"
                      @click.stop.prevent="splitClusterAt(draft, seg.indices[j]!)"
                      @dragover="setDropSlot(draft, seg.indices[j]! + 1, $event)"
                      @drop.prevent="onDropAt(draft, seg.indices[j]! + 1)"
                    >
                      ·
                    </button>
                  </template>
                  <span class="course-cluster-bracket" aria-hidden="true">]</span>
                </span>
                <span
                  v-if="
                    (seg.kind === 'fixed' ? seg.index + 1 : seg.start + seg.cps.length) <
                    draft.controls.length
                  "
                  class="course-seq-arrow"
                  :class="{
                    active: isDropCaret(
                      draft,
                      seg.kind === 'fixed' ? seg.index + 1 : seg.start + seg.cps.length,
                    ),
                    merge: canMergeAfterSegment(
                      draft,
                      seg.kind === 'fixed' ? seg.index : seg.start + seg.cps.length - 1,
                    ),
                  }"
                  :title="
                    canMergeAfterSegment(
                      draft,
                      seg.kind === 'fixed' ? seg.index : seg.start + seg.cps.length - 1,
                    )
                      ? 'Объединить грозди'
                      : undefined
                  "
                  @click.stop="
                    canMergeAfterSegment(
                      draft,
                      seg.kind === 'fixed' ? seg.index : seg.start + seg.cps.length - 1,
                    ) &&
                      mergeClustersAt(
                        draft,
                        seg.kind === 'fixed' ? seg.index : seg.start + seg.cps.length - 1,
                      )
                  "
                  @dragover="
                    setDropSlot(
                      draft,
                      seg.kind === 'fixed' ? seg.index + 1 : seg.start + seg.cps.length,
                      $event,
                    )
                  "
                  @drop.prevent="
                    onDropAt(
                      draft,
                      seg.kind === 'fixed' ? seg.index + 1 : seg.start + seg.cps.length,
                    )
                  "
                >
                  →
                </span>
              </template>
              <span
                class="course-drop-caret"
                :class="{ active: isDropCaret(draft, draft.controls.length) }"
                aria-hidden="true"
              />
              <input
                v-model="draft.addText"
                class="course-add-input"
                placeholder="+ КП"
                :disabled="isBusy()"
                @dragover="setDropSlot(draft, draft.controls.length, $event)"
                @drop.prevent="onDropAt(draft, draft.controls.length)"
                @keydown="onAddKeydown(draft, $event)"
                @paste="onAddPaste(draft, $event)"
                @blur="addFromInput(draft)"
              />
            </div>
          </td>
          <td>
            <IconActionButton
              variant="delete"
              label="Удалить дистанцию"
              :disabled="isBusy()"
              @click="removeCourse(draft)"
            />
          </td>
        </tr>
        </tbody>
      </table>
    </div>
  </section>

  <ImportExistingDataDialog
    :open="importDialogOpen"
    kind="courses"
    :existing-count="importExistingCount"
    :file-name="coursesFile?.name"
    :busy="isBusy()"
    @cancel="onImportDialogCancel"
    @merge="onImportDialogMerge"
    @replace="onImportDialogReplace"
  />
  <div v-if="mismatchOpen" class="modal-backdrop" @click.self="!isBusy() && (mismatchOpen = false)">
    <div class="modal-card" role="dialog" aria-modal="true" aria-label="Заменить дистанции?">
      <div class="modal-header">
        <h3>Заменить дистанции?</h3>
      </div>
      <p>Порядок КП в XML не совпадает с загруженными дистанциями. Заменить дистанции из XML?</p>
      <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
        <button type="button" :disabled="isBusy()" @click="mismatchOpen = false">Отменить</button>
        <button
          type="button"
          :disabled="isBusy()"
          @click="onMismatchReplace"
        >
          Заменить
        </button>
      </div>
    </div>
  </div>
</template>
