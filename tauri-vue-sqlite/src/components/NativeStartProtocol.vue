<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";

type StartProtocolRow = {
  participant_id: string;
  name: string;
  format_id: number | null;
  format_name: string;
  gender: string | null;
  birth_date_raw: string | null;
  birth_date_iso: string | null;
  source_row: number | null;
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
}>();

const rows = ref<StartProtocolRow[]>([]);
const totalCount = ref(0);
const search = ref("");
const formatFilterId = ref<string>("");
const onlyIncomplete = ref(false);
const onlyMissingFormat = ref(false);
const protocolFile = ref<File | null>(null);
const pageSize = ref(50);
const pageOffset = ref(0);
const formatRows = ref<StartProtocolFormatRow[]>([]);

const editId = ref<string | null>(null);
const editName = ref("");
const editFormatId = ref<string>("");
const editGender = ref<"мужской" | "женский">("мужской");
const editBirthDateIso = ref("");

const formatsModalOpen = ref(false);
const formatsBusy = ref(false);
const formatsError = ref("");
const newFormatName = ref("");
const renameDrafts = ref<Record<number, string>>({});

onMounted(() => {
  void loadFormats();
  void refresh();
});

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
}

async function importProtocol() {
  if (!protocolFile.value) {
    emit("status", "Выберите CSV стартового протокола.");
    return;
  }
  try {
    const content = await protocolFile.value.text();
    const summary = await invoke<StartProtocolImportSummary>("import_start_protocol_content", {
      csvContent: content,
      reset: true,
    });
    emit(
      "status",
      `Стартовый протокол загружен: импортировано ${summary.imported_rows}, всего в БД ${summary.total_rows}.`,
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

async function prevPage() {
  pageOffset.value = Math.max(0, pageOffset.value - pageSize.value);
  await refresh();
}

async function nextPage() {
  if (pageOffset.value + pageSize.value >= totalCount.value) return;
  pageOffset.value += pageSize.value;
  await refresh();
}

const totalPages = computed(() =>
  totalCount.value > 0 ? Math.ceil(totalCount.value / pageSize.value) : 1,
);
const currentPage = computed(() => Math.floor(pageOffset.value / pageSize.value) + 1);
const pageButtons = computed(() => {
  const pages: number[] = [];
  const max = totalPages.value;
  const cur = currentPage.value;
  const start = Math.max(1, cur - 2);
  const end = Math.min(max, cur + 2);
  for (let p = start; p <= end; p += 1) pages.push(p);
  return pages;
});

function pageFrom() {
  return totalCount.value === 0 ? 0 : pageOffset.value + 1;
}

function pageTo() {
  return Math.min(pageOffset.value + pageSize.value, totalCount.value);
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
  if (editId.value === row.participant_id) return;
  editName.value = row.name;
  editFormatId.value = row.format_id == null ? "" : String(row.format_id);
  editGender.value = normalizeGender(row.gender);
  editBirthDateIso.value = row.birth_date_iso || ruDateToIso(row.birth_date_raw);
  editId.value = row.participant_id;
}

function cancelEdit() {
  editId.value = null;
  editName.value = "";
  editFormatId.value = "";
  editGender.value = "мужской";
  editBirthDateIso.value = "";
}

async function saveEdit() {
  if (!editId.value) return;
  try {
    await invoke("update_start_protocol_entry", {
      participantId: editId.value,
      name: editName.value,
      formatId: editFormatId.value ? Number(editFormatId.value) : null,
      gender: editGender.value,
      birthDateRaw: isoDateToRu(editBirthDateIso.value) || null,
    });
    emit("status", `Запись стартового протокола #${editId.value} обновлена.`);
    cancelEdit();
    await loadFormats();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка обновления стартового протокола: ${String(error)}`);
  }
}

function isEditingRow(participantId: string) {
  return editId.value === participantId;
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
      <button :disabled="props.busy" @click="refresh">Обновить список</button>
      <button :disabled="props.busy" @click="openFormatsModal">Справочник форматов</button>
    </div>

    <div class="native-settings">
      <label>
        Поиск:
        <input v-model="search" type="text" placeholder="id или имя" />
      </label>
      <label>
        Формат:
        <select v-model="formatFilterId">
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

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th>ID</th>
            <th>Имя</th>
            <th>Формат</th>
            <th>Пол</th>
            <th>Дата рождения</th>
            <th>Есть в результатах</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td colspan="7">Стартовый протокол пуст</td>
          </tr>
          <tr
            v-for="row in rows"
            :key="row.participant_id"
            class="editable-row"
            :class="{
              'row-incomplete': row.is_incomplete && !row.missing_format,
              'row-missing-format': row.missing_format,
            }"
            @click="beginEdit(row)"
          >
            <td>{{ row.participant_id }}</td>
            <td>
              <input
                v-if="isEditingRow(row.participant_id)"
                v-model="editName"
                class="row-edit-input"
                type="text"
                @click.stop
              />
              <span v-else>{{ row.name }}</span>
            </td>
            <td>
              <select
                v-if="isEditingRow(row.participant_id)"
                :key="`format-${row.participant_id}`"
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
              <select
                v-if="isEditingRow(row.participant_id)"
                :key="`gender-${row.participant_id}`"
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
              <input
                v-if="isEditingRow(row.participant_id)"
                v-model="editBirthDateIso"
                class="row-edit-input"
                type="date"
                @click.stop
              />
              <span v-else>{{ row.birth_date_raw || "" }}</span>
            </td>
            <td>{{ row.has_result ? "да" : "нет" }}</td>
            <td>
              <template v-if="isEditingRow(row.participant_id)">
                <button :disabled="props.busy" @click.stop="saveEdit">Сохранить</button>
                <button :disabled="props.busy" @click.stop="cancelEdit">Отмена</button>
              </template>
              <button v-else :disabled="props.busy" @click.stop="beginEdit(row)">Изменить</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="native-settings">
      <button :disabled="props.busy || pageOffset <= 0" @click="prevPage">← Назад</button>
      <span class="subtitle">
        Показано {{ pageFrom() }}-{{ pageTo() }} из {{ totalCount }}
      </span>
      <button
        :disabled="props.busy || pageOffset + pageSize >= totalCount"
        @click="nextPage"
      >
        Вперед →
      </button>
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
        :disabled="props.busy || p === currentPage"
        @click="goToPage(p)"
      >
        {{ p }}
      </button>
      <span class="subtitle">Страница {{ currentPage }} из {{ totalPages }}</span>
    </div>

    <div
      v-if="formatsModalOpen"
      class="modal-backdrop"
      @click.self="closeFormatsModal"
    >
      <div class="modal-card" role="dialog" aria-modal="true" aria-label="Справочник форматов">
        <div class="modal-header">
          <h3>Справочник форматов</h3>
          <button :disabled="formatsBusy" @click="closeFormatsModal">Закрыть</button>
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
                    <button
                      v-if="item.usage_count === 0"
                      class="icon-btn danger"
                      type="button"
                      title="Удалить формат"
                      aria-label="Удалить формат"
                      :disabled="formatsBusy || props.busy"
                      @click="deleteFormat(item.id, item.format_name, item.usage_count)"
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                        <path
                          d="M9 3h6l1 2h4v2H4V5h4l1-2zm1 6h2v9h-2V9zm4 0h2v9h-2V9zM7 9h2v9H7V9z"
                          fill="currentColor"
                        />
                      </svg>
                    </button>
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
  </section>
</template>
