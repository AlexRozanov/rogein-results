<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";
import AddToStartProtocolDialog from "./AddToStartProtocolDialog.vue";
import IconActionButton from "./IconActionButton.vue";
import ImportExistingDataDialog from "./ImportExistingDataDialog.vue";
import DateInput from "./DateInput.vue";

type StartProtocolRow = {
  id: number;
  participant_id: string;
  name: string;
  format_id: number | null;
  format_name: string;
  gender: string | null;
  birth_date_raw: string | null;
  birth_date_iso: string | null;
  source_row: number | null;
  team_id: number | null;
  team_size: number;
  teammates: string;
  has_result: boolean;
  is_incomplete: boolean;
  missing_format: boolean;
};

type StartProtocolResponse = {
  rows: StartProtocolRow[];
  total_count: number;
  offset: number;
  limit: number;
};

type StartProtocolImportSummary = {
  imported_rows: number;
  total_rows: number;
};

type StartProtocolFormatRow = {
  id: number;
  format_name: string;
  usage_count: number;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  teamsChanged: [];
}>();

const rows = ref<StartProtocolRow[]>([]);
const totalCount = ref(0);
const search = ref("");
const formatFilterId = ref<string>("");
const onlyIncomplete = ref(false);
const onlyMissingFormat = ref(false);
const protocolFile = ref<File | null>(null);
const importDialogOpen = ref(false);
const importExistingCount = ref(0);
const pageSize = ref(50);
const pageOffset = ref(0);
const formatRows = ref<StartProtocolFormatRow[]>([]);

const editId = ref<number | null>(null);
const editChipId = ref<string>("");
const editName = ref("");
const editFormatId = ref<string>("");
const editGender = ref<"мужской" | "женский">("мужской");
const editBirthDateIso = ref("");

const formatsModalOpen = ref(false);
const formatsBusy = ref(false);
const formatsError = ref("");
const newFormatName = ref("");
const renameDrafts = ref<Record<number, string>>({});

const addModalOpen = ref(false);
const selectedIds = ref<number[]>([]);
const teamBusy = ref(false);
let searchTimer: ReturnType<typeof setTimeout> | null = null;

onMounted(() => {
  void loadFormats();
  void refresh();
  window.addEventListener("keydown", onTeamHotkey);
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onTeamHotkey);
  if (searchTimer) clearTimeout(searchTimer);
});

function isTypingTarget(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tag = target.tagName;
  if (tag === "TEXTAREA" || tag === "SELECT") return true;
  if (tag === "INPUT") {
    const type = (target as HTMLInputElement).type || "text";
    return type !== "checkbox" && type !== "radio" && type !== "button";
  }
  return false;
}

function onTeamHotkey(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return;
  // Physical key (layout-independent): KeyG is the same in RU/EN.
  if (event.code !== "KeyG") return;
  if (isTypingTarget(event.target)) return;
  if (formatsModalOpen.value || addModalOpen.value) return;
  if (props.busy || teamBusy.value || !canMergeSelection.value) return;
  event.preventDefault();
  void mergeSelectedIntoTeam();
}

function ruDateToIso(value: string | null | undefined): string {
  const raw = String(value || "").trim();
  const parts = raw.split(".");
  if (parts.length !== 3) return "";
  const [d, m, y] = parts;
  if (!d || !m || !y) return "";
  return `${y.padStart(4, "0")}-${m.padStart(2, "0")}-${d.padStart(2, "0")}`;
}

function isoDateToRu(value: string | null | undefined): string {
  const raw = String(value || "").trim();
  const parts = raw.split("-");
  if (parts.length !== 3) return "";
  const [y, m, d] = parts;
  if (!d || !m || !y) return "";
  return `${d.padStart(2, "0")}.${m.padStart(2, "0")}.${y.padStart(4, "0")}`;
}

function applyFormatRows(rowsData: StartProtocolFormatRow[]) {
  formatRows.value = rowsData;
  const drafts: Record<number, string> = {};
  for (const row of rowsData) {
    drafts[row.id] = row.format_name;
  }
  renameDrafts.value = drafts;
  if (formatFilterId.value && !rowsData.some((r) => String(r.id) === formatFilterId.value)) {
    formatFilterId.value = "";
  }
}

async function loadFormats() {
  try {
    const rowsData = await invoke<StartProtocolFormatRow[]>("get_start_protocol_format_rows");
    applyFormatRows(rowsData);
    if (formatFilterId.value && !formatRows.value.some((r) => String(r.id) === formatFilterId.value)) {
      formatFilterId.value = "";
      pageOffset.value = 0;
      await refresh();
    }
  } catch (error) {
    emit("status", `Ошибка загрузки справочника форматов: ${String(error)}`);
  }
}

async function refresh() {
  try {
    const response = await invoke<StartProtocolResponse>("get_start_protocol", {
      limit: pageSize.value,
      offset: pageOffset.value,
      search: search.value.trim() || null,
      formatId: formatFilterId.value ? Number(formatFilterId.value) : null,
      onlyIncomplete: onlyIncomplete.value,
      onlyMissingFormat: onlyMissingFormat.value,
    });
    rows.value = response.rows;
    totalCount.value = response.total_count;
    pageOffset.value = response.offset;
    if (!rows.value.length && totalCount.value > 0 && pageOffset.value > 0) {
      const lastPageOffset = Math.max(0, (Math.ceil(totalCount.value / pageSize.value) - 1) * pageSize.value);
      if (lastPageOffset !== pageOffset.value) {
        pageOffset.value = lastPageOffset;
        const retry = await invoke<StartProtocolResponse>("get_start_protocol", {
          limit: pageSize.value,
          offset: pageOffset.value,
          search: search.value.trim() || null,
          formatId: formatFilterId.value ? Number(formatFilterId.value) : null,
          onlyIncomplete: onlyIncomplete.value,
          onlyMissingFormat: onlyMissingFormat.value,
        });
        rows.value = retry.rows;
        totalCount.value = retry.total_count;
        pageOffset.value = retry.offset;
      }
    }
  } catch (error) {
    emit("status", `Ошибка загрузки стартового протокола: ${String(error)}`);
  }
}

async function onFileSelected(file: File | null) {
  protocolFile.value = file;
  if (!protocolFile.value) return;
  await beginImportFlow();
}

async function importProtocol() {
  if (!protocolFile.value) {
    emit("status", "Выберите CSV стартового протокола.");
    return;
  }
  await beginImportFlow();
}

async function beginImportFlow() {
  try {
    const counts = await invoke<{ start_protocol: number }>("get_data_presence_counts");
    if (counts.start_protocol > 0) {
      importExistingCount.value = counts.start_protocol;
      importDialogOpen.value = true;
      return;
    }
    await runImport(true);
  } catch (error) {
    emit("status", `Ошибка проверки данных: ${String(error)}`);
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

async function runImport(reset: boolean) {
  if (!protocolFile.value) {
    emit("status", "Выберите CSV стартового протокола.");
    return;
  }
  try {
    const content = await protocolFile.value.text();
    const summary = await invoke<StartProtocolImportSummary>("import_start_protocol_content", {
      csvContent: content,
      reset,
    });
    emit(
      "status",
      reset
        ? `Стартовый протокол загружен заново: импортировано ${summary.imported_rows}, всего в БД ${summary.total_rows}.`
        : `Добавлены новые участники: ${summary.imported_rows}, всего в БД ${summary.total_rows}.`,
    );
    pageOffset.value = 0;
    await loadFormats();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка импорта стартового протокола: ${String(error)}`);
  }
}

async function applyFilters() {
  pageOffset.value = 0;
  await refresh();
}

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    searchTimer = null;
    void applyFilters();
  }, 250);
}

async function prevPage() {
  pageOffset.value = Math.max(0, pageOffset.value - pageSize.value);
  await refresh();
}

async function nextPage() {
  if (pageOffset.value + pageSize.value >= totalCount.value) return;
  pageOffset.value += pageSize.value;
  await refresh();
}

async function firstPage() {
  if (pageOffset.value <= 0) return;
  pageOffset.value = 0;
  await refresh();
}

async function lastPage() {
  const lastOffset = Math.max(0, (totalPages.value - 1) * pageSize.value);
  if (pageOffset.value === lastOffset) return;
  pageOffset.value = lastOffset;
  await refresh();
}

const totalPages = computed(() =>
  totalCount.value > 0 ? Math.ceil(totalCount.value / pageSize.value) : 1,
);
const currentPage = computed(() => Math.floor(pageOffset.value / pageSize.value) + 1);
const pageButtons = computed(() => {
  const pages: number[] = [];
  const max = totalPages.value;
  if (max <= 0) return pages;
  const cur = currentPage.value;
  const windowSize = 10;
  let start = Math.max(1, cur - Math.floor((windowSize - 1) / 2));
  let end = start + windowSize - 1;
  if (end > max) {
    end = max;
    start = Math.max(1, end - windowSize + 1);
  }
  for (let p = start; p <= end; p += 1) pages.push(p);
  return pages;
});

function pageFrom() {
  return totalCount.value === 0 ? 0 : pageOffset.value + 1;
}

function pageTo() {
  return Math.min(pageOffset.value + pageSize.value, totalCount.value);
}

const selectedRows = computed(() => {
  const set = new Set(selectedIds.value);
  return rows.value.filter((r) => set.has(r.id));
});

const canMergeSelection = computed(() => {
  if (selectedIds.value.length < 2) return false;
  const formats = new Set(
    selectedRows.value
      .map((r) => r.format_id)
      .filter((id): id is number => id != null && id > 0),
  );
  // If some selected are off-page, still allow merge — backend validates.
  if (selectedRows.value.length === selectedIds.value.length) {
    if (formats.size !== 1) return false;
    if (selectedRows.value.some((r) => r.format_id == null)) return false;
  }
  return selectedIds.value.length >= 2;
});

const selectedTeamIds = computed(() => {
  const ids = new Set<number>();
  for (const row of selectedRows.value) {
    if (row.team_id != null && row.team_id > 0) ids.add(row.team_id);
  }
  return [...ids];
});

const canLeaveSelection = computed(() =>
  selectedRows.value.some((r) => r.team_id != null && r.team_id > 0),
);

const canDissolveSelection = computed(() => selectedTeamIds.value.length === 1);

function isSelected(rowId: number) {
  return selectedIds.value.includes(rowId);
}

function toggleSelect(rowId: number, checked: boolean) {
  if (checked) {
    if (!selectedIds.value.includes(rowId)) {
      selectedIds.value = [...selectedIds.value, rowId];
    }
  } else {
    selectedIds.value = selectedIds.value.filter((id) => id !== rowId);
  }
}

function toggleSelectAllVisible(checked: boolean) {
  const visibleIds = rows.value.map((r) => r.id);
  if (checked) {
    const set = new Set(selectedIds.value);
    for (const id of visibleIds) set.add(id);
    selectedIds.value = [...set];
  } else {
    const drop = new Set(visibleIds);
    selectedIds.value = selectedIds.value.filter((id) => !drop.has(id));
  }
}

const allVisibleSelected = computed(
  () => rows.value.length > 0 && rows.value.every((r) => selectedIds.value.includes(r.id)),
);

function clearSelection() {
  selectedIds.value = [];
}

type TeamGroupPos = "none" | "start" | "mid" | "end" | "only";

function teamGroupPos(row: StartProtocolRow, index: number): TeamGroupPos {
  if (row.team_id == null || row.team_id <= 0) return "none";
  const prev = rows.value[index - 1];
  const next = rows.value[index + 1];
  const samePrev = prev != null && prev.team_id === row.team_id;
  const sameNext = next != null && next.team_id === row.team_id;
  if (!samePrev && !sameNext) return "only";
  if (!samePrev && sameNext) return "start";
  if (samePrev && sameNext) return "mid";
  return "end";
}

function isTeamBlockStart(row: StartProtocolRow, index: number) {
  const pos = teamGroupPos(row, index);
  return pos === "start" || pos === "only";
}

async function mergeSelectedIntoTeam() {
  if (selectedIds.value.length < 2) {
    emit("status", "Выберите минимум двух участников одного формата.");
    return;
  }
  teamBusy.value = true;
  try {
    const teamId = await invoke<number>("merge_start_protocol_team", {
      entryIds: selectedIds.value,
    });
    emit("status", `Создана/обновлена команда #${teamId} (${selectedIds.value.length} уч.). Результаты пересчитаны.`);
    clearSelection();
    await refresh();
    emit("teamsChanged");
  } catch (error) {
    emit("status", `Ошибка объединения в команду: ${String(error)}`);
  } finally {
    teamBusy.value = false;
  }
}

async function leaveSelectedFromTeams() {
  const toLeave = selectedRows.value.filter((r) => r.team_id != null && r.team_id > 0);
  if (!toLeave.length) return;
  teamBusy.value = true;
  try {
    const left = await invoke<number>("leave_start_protocol_team", {
      entryIds: toLeave.map((r) => r.id),
    });
    emit("status", `Исключено из команды: ${left}. Результаты пересчитаны.`);
    clearSelection();
    await refresh();
    emit("teamsChanged");
  } catch (error) {
    emit("status", `Ошибка исключения из команды: ${String(error)}`);
  } finally {
    teamBusy.value = false;
  }
}

async function dissolveSelectedTeam() {
  const teamId = selectedTeamIds.value[0];
  if (teamId == null) return;
  teamBusy.value = true;
  try {
    await invoke("dissolve_start_protocol_team", { teamId });
    emit("status", `Команда #${teamId} разъединена. Результаты пересчитаны.`);
    clearSelection();
    await refresh();
    emit("teamsChanged");
  } catch (error) {
    emit("status", `Ошибка разъединения команды: ${String(error)}`);
  } finally {
    teamBusy.value = false;
  }
}

async function goToPage(page: number) {
  const safePage = Math.min(Math.max(1, page), totalPages.value);
  pageOffset.value = (safePage - 1) * pageSize.value;
  await refresh();
}

async function changePageSize(size: string) {
  const parsed = Number(size);
  if (!Number.isFinite(parsed) || parsed <= 0) return;
  pageSize.value = parsed;
  pageOffset.value = 0;
  await refresh();
}

function normalizeGender(value: string | null | undefined): "мужской" | "женский" {
  const raw = String(value || "").trim().toLowerCase();
  if (raw.startsWith("жен") || raw === "f" || raw === "female") return "женский";
  return "мужской";
}

function beginEdit(row: StartProtocolRow) {
  if (editId.value === row.id) return;
  editChipId.value = row.participant_id;
  editName.value = row.name;
  editFormatId.value = row.format_id == null ? "" : String(row.format_id);
  editGender.value = normalizeGender(row.gender);
  editBirthDateIso.value = row.birth_date_iso || ruDateToIso(row.birth_date_raw);
  editId.value = row.id;
}

function cancelEdit() {
  editId.value = null;
  editChipId.value = "";
  editName.value = "";
  editFormatId.value = "";
  editGender.value = "мужской";
  editBirthDateIso.value = "";
}

async function saveEdit() {
  if (editId.value == null) return;
  try {
    await invoke("update_start_protocol_entry", {
      entryId: editId.value,
      participantId: editChipId.value,
      name: editName.value,
      formatId: editFormatId.value ? Number(editFormatId.value) : null,
      gender: editGender.value,
      birthDateRaw: isoDateToRu(editBirthDateIso.value) || null,
    });
    emit("status", `Запись стартового протокола #${editChipId.value} обновлена.`);
    cancelEdit();
    await loadFormats();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка обновления стартового протокола: ${String(error)}`);
  }
}

function isEditingRow(rowId: number) {
  return editId.value === rowId;
}

async function openFormatsModal() {
  formatsError.value = "";
  newFormatName.value = "";
  formatsModalOpen.value = true;
  await loadFormats();
}

function closeFormatsModal() {
  formatsModalOpen.value = false;
  formatsError.value = "";
  newFormatName.value = "";
}

async function openAddModal() {
  addModalOpen.value = true;
}

function closeAddModal() {
  addModalOpen.value = false;
}

async function onAddSaved() {
  await loadFormats();
  await refresh();
}

async function addFormat() {
  const name = newFormatName.value.trim();
  if (!name) {
    formatsError.value = "Введите имя нового формата.";
    return;
  }
  formatsBusy.value = true;
  formatsError.value = "";
  try {
    const rowsData = await invoke<StartProtocolFormatRow[]>("add_start_protocol_format", {
      formatName: name,
    });
    applyFormatRows(rowsData);
    newFormatName.value = "";
    emit("status", `Формат «${name}» добавлен.`);
  } catch (error) {
    formatsError.value = String(error);
  } finally {
    formatsBusy.value = false;
  }
}

async function renameFormat(formatId: number, oldName: string) {
  const newName = String(renameDrafts.value[formatId] || "").trim();
  if (!newName) {
    formatsError.value = "Имя формата не может быть пустым.";
    return;
  }
  if (newName === oldName) return;
  formatsBusy.value = true;
  formatsError.value = "";
  try {
    const rowsData = await invoke<StartProtocolFormatRow[]>("rename_start_protocol_format", {
      formatId,
      newFormatName: newName,
    });
    applyFormatRows(rowsData);
    await refresh();
    emit("status", `Формат «${oldName}» переименован в «${newName}».`);
  } catch (error) {
    formatsError.value = String(error);
  } finally {
    formatsBusy.value = false;
  }
}

async function deleteFormat(formatId: number, name: string, usageCount: number) {
  if (usageCount > 0) {
    formatsError.value = `Нельзя удалить формат «${name}»: есть зарегистрированные участники (${usageCount}).`;
    return;
  }
  formatsBusy.value = true;
  formatsError.value = "";
  try {
    const rowsData = await invoke<StartProtocolFormatRow[]>("delete_start_protocol_format", {
      formatId,
    });
    if (formatFilterId.value === String(formatId)) {
      formatFilterId.value = "";
    }
    if (editFormatId.value === String(formatId)) {
      editFormatId.value = "";
    }
    applyFormatRows(rowsData);
    await refresh();
    emit("status", `Формат «${name}» удалён.`);
  } catch (error) {
    formatsError.value = String(error);
  } finally {
    formatsBusy.value = false;
  }
}
</script>

<template>
  <section class="native-tools nested-card">
    <h2>Стартовый протокол</h2>
    <div class="native-row">
      <FilePickerButton @file-selected="onFileSelected" />
      <button :disabled="props.busy" @click="importProtocol">Импорт стартового протокола</button>
      <button :disabled="props.busy" @click="openAddModal">Добавить участника</button>
      <button :disabled="props.busy" @click="refresh">Обновить список</button>
      <button :disabled="props.busy" @click="openFormatsModal">Справочник форматов</button>
    </div>

    <div class="native-settings">
      <label>
        Поиск:
        <input
          v-model="search"
          type="search"
          placeholder="ID или имя"
          :disabled="props.busy"
          @input="onSearchInput"
        />
      </label>
      <label>
        Формат:
        <select v-model="formatFilterId" @change="applyFilters">
          <option value="">Все форматы</option>
          <option v-for="f in formatRows" :key="f.id" :value="String(f.id)">{{ f.format_name }}</option>
        </select>
      </label>
      <label class="checkbox-label">
        <input v-model="onlyIncomplete" type="checkbox" @change="applyFilters" />
        Только с незаполненными полями
      </label>
      <label class="checkbox-label">
        <input v-model="onlyMissingFormat" type="checkbox" @change="applyFilters" />
        Только без формата
      </label>
      <button :disabled="props.busy" @click="applyFilters">Применить фильтр</button>
      <span class="subtitle">Найдено: {{ totalCount }}</span>
    </div>

    <div v-if="selectedIds.length" class="native-row team-actions-bar">
      <span class="subtitle">Выбрано: {{ selectedIds.length }}</span>
      <button
        :disabled="props.busy || teamBusy || !canMergeSelection"
        title="Объединить выбранных участников одного формата в команду (Ctrl+G)"
        @click="mergeSelectedIntoTeam"
      >
        Объединить в команду
        <kbd class="hotkey-hint">Ctrl+G</kbd>
      </button>
      <button
        :disabled="props.busy || teamBusy || !canLeaveSelection"
        @click="leaveSelectedFromTeams"
      >
        Исключить из команды
      </button>
      <button
        :disabled="props.busy || teamBusy || !canDissolveSelection"
        @click="dissolveSelectedTeam"
      >
        Разъединить команду
      </button>
      <button :disabled="teamBusy" @click="clearSelection">Снять выбор</button>
      <span class="subtitle">В команду — только один формат</span>
    </div>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th class="team-check-col">
              <input
                type="checkbox"
                :checked="allVisibleSelected"
                :disabled="!rows.length || props.busy || teamBusy"
                title="Выбрать все на странице"
                @change="toggleSelectAllVisible(($event.target as HTMLInputElement).checked)"
              />
            </th>
            <th class="team-brace-col" title="Группировка команды"></th>
            <th>ID</th>
            <th>Имя</th>
            <th>Формат</th>
            <th>Команда</th>
            <th>Пол</th>
            <th>Дата рождения</th>
            <th>Есть в результатах</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td colspan="10">Стартовый протокол пуст</td>
          </tr>
          <tr
            v-for="(row, rowIndex) in rows"
            :key="row.id"
            class="editable-row"
            :class="{
              'row-incomplete': row.is_incomplete && !row.missing_format,
              'row-missing-format': row.missing_format,
              'row-team-block-start': isTeamBlockStart(row, rowIndex),
              'row-selected': isSelected(row.id),
            }"
            @click="beginEdit(row)"
          >
            <td class="team-check-col" @click.stop>
              <input
                type="checkbox"
                :checked="isSelected(row.id)"
                :disabled="props.busy || teamBusy"
                @change="toggleSelect(row.id, ($event.target as HTMLInputElement).checked)"
              />
            </td>
            <td class="team-brace-col" aria-hidden="true">
              <span
                v-if="teamGroupPos(row, rowIndex) !== 'none'"
                class="team-brace"
                :class="`team-brace-${teamGroupPos(row, rowIndex)}`"
              >
                <span class="team-brace-rail"></span>
              </span>
            </td>
            <td>{{ row.participant_id }}</td>
            <td>
              <input
                v-if="isEditingRow(row.id)"
                v-model="editName"
                class="row-edit-input"
                type="text"
                @click.stop
              />
              <span v-else>{{ row.name }}</span>
            </td>
            <td>
              <select
                v-if="isEditingRow(row.id)"
                :key="`format-${row.id}`"
                v-model="editFormatId"
                class="row-edit-input"
                @click.stop
                @mousedown.stop
              >
                <option value="">Не выбран</option>
                <option v-for="f in formatRows" :key="`format-edit-${f.id}`" :value="String(f.id)">
                  {{ f.format_name }}
                </option>
              </select>
              <span v-else>{{ row.format_name || "—" }}</span>
            </td>
            <td>
              <span
                v-if="row.team_id != null && row.team_id > 0"
                class="team-cell"
                :title="`Команда #${row.team_id}`"
              >
                <span class="team-badge">Т{{ row.team_id }}</span>
                <span v-if="row.teammates" class="team-mates">{{ row.teammates }}</span>
              </span>
              <span v-else class="team-cell muted">—</span>
            </td>
            <td>
              <select
                v-if="isEditingRow(row.id)"
                :key="`gender-${row.id}`"
                v-model="editGender"
                class="row-edit-input"
                @click.stop
                @mousedown.stop
              >
                <option value="мужской">мужской</option>
                <option value="женский">женский</option>
              </select>
              <span v-else>{{ row.gender || "" }}</span>
            </td>
            <td>
              <DateInput
                v-if="isEditingRow(row.id)"
                v-model="editBirthDateIso"
                class="row-edit-input"
                @click.stop
                @mousedown.stop
              />
              <span v-else>{{ row.birth_date_raw || "" }}</span>
            </td>
            <td>{{ row.has_result ? "да" : "нет" }}</td>
            <td>
              <template v-if="isEditingRow(row.id)">
                <IconActionButton
                  variant="save"
                  label="Сохранить"
                  :disabled="props.busy"
                  @click="saveEdit"
                />
                <IconActionButton
                  variant="cancel"
                  label="Отмена"
                  :disabled="props.busy"
                  @click="cancelEdit"
                />
              </template>
              <IconActionButton
                v-else
                variant="edit"
                label="Изменить"
                :disabled="props.busy"
                @click="beginEdit(row)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="native-settings">
      <IconActionButton
        variant="pageFirst"
        label="На первую страницу"
        :disabled="props.busy || pageOffset <= 0"
        @click="firstPage"
      />
      <IconActionButton
        variant="pagePrev"
        label="Назад"
        :disabled="props.busy || pageOffset <= 0"
        @click="prevPage"
      />
      <span class="subtitle">
        Показано {{ pageFrom() }}-{{ pageTo() }} из {{ totalCount }}
      </span>
      <IconActionButton
        variant="pageNext"
        label="Вперёд"
        :disabled="props.busy || pageOffset + pageSize >= totalCount"
        @click="nextPage"
      />
      <IconActionButton
        variant="pageLast"
        label="На последнюю страницу"
        :disabled="props.busy || pageOffset + pageSize >= totalCount"
        @click="lastPage"
      />
    </div>
    <div class="native-settings">
      <label>
        Строк на странице:
        <select :value="String(pageSize)" @change="changePageSize(($event.target as HTMLSelectElement).value)">
          <option value="25">25</option>
          <option value="50">50</option>
          <option value="100">100</option>
        </select>
      </label>
      <button
        v-for="p in pageButtons"
        :key="`protocol-page-${p}`"
        class="page-num-btn"
        :class="{ active: p === currentPage }"
        :disabled="props.busy || p === currentPage"
        @click="goToPage(p)"
      >
        {{ p }}
      </button>
      <span class="subtitle">Страница {{ currentPage }} из {{ totalPages }}</span>
    </div>

    <AddToStartProtocolDialog
      :open="addModalOpen"
      :busy="props.busy"
      title="Добавить участника"
      @close="closeAddModal"
      @saved="onAddSaved"
      @status="emit('status', $event)"
    />

    <div
      v-if="formatsModalOpen"
      class="modal-backdrop"
      @click.self="closeFormatsModal"
    >
      <div class="modal-card" role="dialog" aria-modal="true" aria-label="Справочник форматов">
        <div class="modal-header">
          <h3>Справочник форматов</h3>
          <button
            class="modal-close-btn"
            type="button"
            title="Закрыть"
            aria-label="Закрыть"
            :disabled="formatsBusy"
            @click="closeFormatsModal"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path
                d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
                fill="currentColor"
              />
            </svg>
          </button>
        </div>
        <p class="subtitle">
          Можно добавить новый формат или переименовать существующий.
          Переименование обновит все записи стартового протокола с этим форматом.
        </p>

        <div class="native-settings">
          <label>
            Новый формат:
            <input
              v-model="newFormatName"
              type="text"
              placeholder="Например: Бег 2ч"
              @keyup.enter="addFormat"
            />
          </label>
          <button :disabled="formatsBusy || props.busy" @click="addFormat">Добавить</button>
        </div>

        <p v-if="formatsError" class="status">{{ formatsError }}</p>

        <div class="native-results-wrap">
          <table class="native-results">
            <thead>
              <tr>
                <th>Текущее имя</th>
                <th>Новое имя</th>
                <th>Участников</th>
                <th>Действие</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!formatRows.length">
                <td colspan="4">Справочник форматов пуст</td>
              </tr>
              <tr v-for="item in formatRows" :key="item.id">
                <td>{{ item.format_name }}</td>
                <td>
                  <input
                    v-model="renameDrafts[item.id]"
                    class="row-edit-input"
                    type="text"
                    @keyup.enter="renameFormat(item.id, item.format_name)"
                  />
                </td>
                <td>
                  <span class="format-usage-cell">
                    <span>{{ item.usage_count }}</span>
                    <IconActionButton
                      v-if="item.usage_count === 0"
                      variant="delete"
                      label="Удалить формат"
                      :disabled="formatsBusy || props.busy"
                      @click="deleteFormat(item.id, item.format_name, item.usage_count)"
                    />
                  </span>
                </td>
                <td>
                  <button
                    :disabled="
                      formatsBusy ||
                      props.busy ||
                      String(renameDrafts[item.id] || '').trim() === item.format_name
                    "
                    @click="renameFormat(item.id, item.format_name)"
                  >
                    Переименовать
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <ImportExistingDataDialog
      :open="importDialogOpen"
      kind="start"
      :existing-count="importExistingCount"
      :file-name="protocolFile?.name"
      :busy="props.busy"
      @cancel="onImportDialogCancel"
      @merge="onImportDialogMerge"
      @replace="onImportDialogReplace"
    />
  </section>
</template>
