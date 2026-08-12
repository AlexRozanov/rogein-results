<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";

type FormatOption = {
  format_id: number;
  format_name: string;
};

type AwardGroupRow = {
  id: number;
  name: string;
  gender_mode: string;
  sort_order: number;
  format_ids: number[];
  format_names: string[];
};

type GenderMode = "any" | "male" | "female" | "mixed";

const props = defineProps<{
  busy: boolean;
  formats: FormatOption[];
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const groups = ref<AwardGroupRow[]>([]);
const localBusy = ref(false);
const formOpen = ref(false);
const formMode = ref<"create" | "edit">("create");
const editId = ref<number | null>(null);
const name = ref("");
const genderMode = ref<GenderMode>("any");
const selectedFormatIds = ref<string[]>([]);

const formTitle = computed(() =>
  formMode.value === "edit" && editId.value != null
    ? `Изменить группу #${editId.value}`
    : "Добавить группу награждения",
);

onMounted(() => {
  void refresh();
});

watch(
  () => props.formats.map((f) => f.format_id).join(","),
  () => {
    const allowed = new Set(props.formats.map((f) => String(f.format_id)));
    selectedFormatIds.value = selectedFormatIds.value.filter((id) => allowed.has(id));
  },
);

async function refresh() {
  try {
    groups.value = await invoke<AwardGroupRow[]>("get_award_groups");
  } catch (error) {
    emit("status", `Ошибка загрузки групп награждения: ${String(error)}`);
  }
}

function genderModeText(mode: string) {
  if (mode === "male") return "Мужчины и мужские команды";
  if (mode === "female") return "Женщины и женские команды";
  if (mode === "mixed") return "Смешанные команды";
  return "Без ограничения по полу";
}

function resetForm() {
  editId.value = null;
  name.value = "";
  genderMode.value = "any";
  selectedFormatIds.value = [];
}

function openCreate() {
  formMode.value = "create";
  resetForm();
  formOpen.value = true;
}

function openEdit(group: AwardGroupRow) {
  formMode.value = "edit";
  editId.value = group.id;
  name.value = group.name;
  genderMode.value =
    group.gender_mode === "male" ||
    group.gender_mode === "female" ||
    group.gender_mode === "mixed"
      ? group.gender_mode
      : "any";
  selectedFormatIds.value = group.format_ids.map(String);
  formOpen.value = true;
}

function closeForm() {
  if (localBusy.value) return;
  formOpen.value = false;
  resetForm();
}

function toggleFormat(formatId: string, checked: boolean) {
  if (checked) {
    if (!selectedFormatIds.value.includes(formatId)) {
      selectedFormatIds.value = [...selectedFormatIds.value, formatId];
    }
  } else {
    selectedFormatIds.value = selectedFormatIds.value.filter((id) => id !== formatId);
  }
}

async function saveForm() {
  const trimmed = name.value.trim();
  if (!trimmed) {
    emit("status", "Укажите название группы награждения.");
    return;
  }
  if (!selectedFormatIds.value.length) {
    emit("status", "Выберите хотя бы один формат.");
    return;
  }
  localBusy.value = true;
  try {
    await invoke("upsert_award_group", {
      groupId: formMode.value === "edit" ? editId.value : null,
      name: trimmed,
      genderMode: genderMode.value,
      formatIds: selectedFormatIds.value.map(Number),
      sortOrder: null,
    });
    emit(
      "status",
      formMode.value === "edit"
        ? `Группа «${trimmed}» обновлена.`
        : `Группа «${trimmed}» создана.`,
    );
    emit("saved");
    formOpen.value = false;
    resetForm();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка сохранения группы: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function removeGroup(group: AwardGroupRow) {
  localBusy.value = true;
  try {
    await invoke("delete_award_group", { groupId: group.id });
    emit("status", `Группа «${group.name}» удалена.`);
    emit("saved");
    await refresh();
  } catch (error) {
    emit("status", `Ошибка удаления группы: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <section class="native-tools nested-card">
    <div class="modal-header" style="margin-bottom: 8px">
      <h3 style="margin: 0">Группы награждения</h3>
      <button :disabled="props.busy || localBusy" @click="openCreate">Добавить группу</button>
    </div>
    <p class="subtitle">
      Группа = набор форматов и правило пола (мужчины / женщины / смешанные команды / без ограничения).
      В результатах можно фильтровать финишный протокол по группе.
    </p>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th>Название</th>
            <th>Форматы</th>
            <th>Пол</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!groups.length">
            <td colspan="4">Групп награждения пока нет</td>
          </tr>
          <tr v-for="g in groups" :key="g.id">
            <td>{{ g.name }}</td>
            <td>{{ g.format_names.join(", ") || "—" }}</td>
            <td>{{ genderModeText(g.gender_mode) }}</td>
            <td>
              <IconActionButton
                variant="edit"
                label="Изменить"
                :disabled="props.busy || localBusy"
                @click="openEdit(g)"
              />
              <IconActionButton
                variant="delete"
                label="Удалить"
                :disabled="props.busy || localBusy"
                @click="removeGroup(g)"
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
            Название
            <input v-model="name" type="text" placeholder="Например: Бег 2 часа — мужчины" />
          </label>
          <label>
            Пол / состав
            <select v-model="genderMode">
              <option value="any">Без ограничения по полу</option>
              <option value="male">Мужчины и мужские команды</option>
              <option value="female">Женщины и женские команды</option>
              <option value="mixed">Только смешанные команды</option>
            </select>
          </label>
        </div>

        <p class="subtitle">Форматы в группе (логическое ИЛИ):</p>
        <div class="award-format-list">
          <label
            v-for="fmt in props.formats"
            :key="fmt.format_id"
            class="checkbox-label"
          >
            <input
              type="checkbox"
              :checked="selectedFormatIds.includes(String(fmt.format_id))"
              @change="
                toggleFormat(
                  String(fmt.format_id),
                  ($event.target as HTMLInputElement).checked,
                )
              "
            />
            {{ fmt.format_name }}
          </label>
          <p v-if="!props.formats.length" class="subtitle">Сначала добавьте форматы в справочник.</p>
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
