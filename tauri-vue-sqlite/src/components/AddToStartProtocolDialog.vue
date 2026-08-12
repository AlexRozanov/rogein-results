<script setup lang="ts">
import { ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";

export type StartProtocolFormatOption = {
  id: number;
  format_name: string;
  usage_count?: number;
};

export type AddToStartProtocolDraft = {
  participant_id?: string | null;
  name?: string | null;
  format_id?: number | null;
  gender?: string | null;
  birth_date_iso?: string | null;
  birth_date_raw?: string | null;
};

const props = defineProps<{
  open: boolean;
  busy?: boolean;
  title?: string;
  initial?: AddToStartProtocolDraft | null;
}>();

const emit = defineEmits<{
  close: [];
  saved: [payload: { participantId: string; name: string }];
  status: [message: string];
}>();

const formatRows = ref<StartProtocolFormatOption[]>([]);
const localBusy = ref(false);
const error = ref("");
const participantId = ref("");
const name = ref("");
const formatId = ref("");
const gender = ref<"мужской" | "женский">("мужской");
const birthDateIso = ref("");

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

function normalizeGender(value: string | null | undefined): "мужской" | "женский" {
  const raw = String(value || "").trim().toLowerCase();
  if (raw.startsWith("жен") || raw === "f" || raw === "female") return "женский";
  return "мужской";
}

function applyInitial(draft: AddToStartProtocolDraft | null | undefined) {
  participantId.value = String(draft?.participant_id || "").trim();
  name.value = String(draft?.name || "").trim();
  formatId.value = draft?.format_id != null ? String(draft.format_id) : "";
  gender.value = normalizeGender(draft?.gender);
  birthDateIso.value =
    String(draft?.birth_date_iso || "").trim() || ruDateToIso(draft?.birth_date_raw);
  error.value = "";
}

async function loadFormats() {
  try {
    formatRows.value = await invoke<StartProtocolFormatOption[]>("get_start_protocol_format_rows");
  } catch (err) {
    error.value = `Ошибка загрузки форматов: ${String(err)}`;
  }
}

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) return;
    applyInitial(props.initial);
    await loadFormats();
  },
);

function close() {
  if (localBusy.value) return;
  emit("close");
}

async function save() {
  const pid = participantId.value.trim();
  const personName = name.value.trim();
  if (!pid || !personName) {
    error.value = "Заполните номер (ID) и имя участника.";
    return;
  }
  localBusy.value = true;
  error.value = "";
  try {
    await invoke("add_start_protocol_entry", {
      participantId: pid,
      name: personName,
      formatId: formatId.value ? Number(formatId.value) : null,
      gender: gender.value,
      birthDateRaw: isoDateToRu(birthDateIso.value) || null,
    });
    await invoke("recalculate_results");
    emit("status", `Участник «${personName}» (#${pid}) добавлен в стартовый протокол.`);
    emit("saved", { participantId: pid, name: personName });
    emit("close");
  } catch (err) {
    error.value = String(err);
  } finally {
    localBusy.value = false;
  }
}
</script>

<template>
  <div
    v-if="open"
    class="modal-backdrop"
    @click.self="close"
  >
    <div
      class="modal-card"
      role="dialog"
      aria-modal="true"
      :aria-label="title || 'Добавить в протокол'"
    >
      <div class="modal-header">
        <h3>{{ title || "Добавить в протокол" }}</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Закрыть"
          aria-label="Закрыть"
          :disabled="localBusy"
          @click="close"
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
        Заполните данные участника для стартового протокола.
        Известные поля подставлены из финиша — их можно изменить.
      </p>

      <div class="native-settings">
        <label>
          ID:
          <input
            v-model="participantId"
            type="text"
            placeholder="Номер участника"
            :disabled="localBusy || busy"
            @keyup.enter="save"
          />
        </label>
        <label>
          Имя:
          <input
            v-model="name"
            type="text"
            placeholder="ФИО"
            :disabled="localBusy || busy"
            @keyup.enter="save"
          />
        </label>
        <label>
          Формат:
          <select v-model="formatId" :disabled="localBusy || busy">
            <option value="">Не выбран</option>
            <option v-for="f in formatRows" :key="`dialog-format-${f.id}`" :value="String(f.id)">
              {{ f.format_name }}
            </option>
          </select>
        </label>
        <label>
          Пол:
          <select v-model="gender" :disabled="localBusy || busy">
            <option value="мужской">мужской</option>
            <option value="женский">женский</option>
          </select>
        </label>
        <label>
          Дата рождения:
          <input v-model="birthDateIso" type="date" :disabled="localBusy || busy" />
        </label>
      </div>

      <p v-if="error" class="status">{{ error }}</p>

      <div class="native-row">
        <IconActionButton
          variant="save"
          label="Сохранить"
          :disabled="localBusy || busy"
          @click="save"
        />
        <IconActionButton
          variant="cancel"
          label="Отмена"
          :disabled="localBusy"
          @click="close"
        />
      </div>
    </div>
  </div>
</template>
