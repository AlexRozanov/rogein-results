<script setup lang="ts">
import { computed, ref, watch } from "vue";
import IconActionButton from "./IconActionButton.vue";

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
  start_mode: "station" | "time";
  start_cp: number | null;
  competition_date: string;
  competition_start_time: string;
};

const props = defineProps<{
  settings: NativeSettings | null;
  busy: boolean;
}>();

const emit = defineEmits<{
  save: [payload: NativeSettings];
}>();

const form = ref<NativeSettings>({
  control_minutes: 240,
  penalty_per_minute: 1,
  dq_minutes: 15,
  finish_cp: 240,
  start_mode: "station",
  start_cp: null,
  competition_date: "",
  competition_start_time: "",
});

const isStationMode = computed(() => form.value.start_mode === "station");
const isTimeMode = computed(() => form.value.start_mode === "time");

watch(
  () => props.settings,
  (next) => {
    if (next) {
      form.value = {
        ...next,
        start_mode: next.start_mode === "time" ? "time" : "station",
        start_cp: next.start_cp ?? null,
        competition_start_time: toTimeInputValue(next.competition_start_time),
      };
    }
  },
  { immediate: true },
);

function toTimeInputValue(raw: string) {
  const value = String(raw || "").trim();
  if (!value) return "";
  // HTML time input expects HH:MM or HH:MM:SS
  if (/^\d{2}:\d{2}(:\d{2})?$/.test(value)) return value.slice(0, 8);
  return value;
}

function onStartCpInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value;
  form.value.start_cp = raw === "" ? null : Number(raw);
}

function submit() {
  emit("save", {
    ...form.value,
    start_cp: form.value.start_cp && form.value.start_cp > 0 ? Number(form.value.start_cp) : null,
    competition_date: String(form.value.competition_date || "").trim(),
    competition_start_time: String(form.value.competition_start_time || "").trim(),
  });
}
</script>

<template>
  <p v-if="settings" class="subtitle">
    Тип старта:
    {{ settings.start_mode === "time" ? "по времени" : "по станции" }},
    старт КП {{ settings.start_cp ?? "—" }},
    CT {{ settings.control_minutes }} мин, DQ +{{ settings.dq_minutes }} мин,
    штраф {{ settings.penalty_per_minute }}/мин, финиш {{ settings.finish_cp }},
    дата {{ settings.competition_date || "—" }},
    время {{ settings.competition_start_time || "—" }}.
  </p>

  <div class="native-settings">
    <label>
      Тип старта:
      <select v-model="form.start_mode">
        <option value="station">Старт по станции</option>
        <option value="time">Старт по времени</option>
      </select>
    </label>
    <label>
      Стартовая станция (КП){{ isStationMode ? " *" : "" }}:
      <input
        :value="form.start_cp ?? ''"
        type="number"
        min="1"
        :placeholder="isStationMode ? 'обязательно' : 'опционально'"
        @input="onStartCpInput"
      />
    </label>
    <label>
      Дата соревнования{{ isTimeMode ? " *" : "" }}:
      <input v-model="form.competition_date" type="date" />
    </label>
    <label>
      Стартовое время{{ isTimeMode ? " *" : "" }}:
      <input v-model="form.competition_start_time" type="time" step="1" />
    </label>
    <label>
      Контроль (мин):
      <input v-model.number="form.control_minutes" type="number" min="1" />
    </label>
    <label>
      Штраф/мин:
      <input v-model.number="form.penalty_per_minute" type="number" min="0" />
    </label>
    <label>
      DQ после (мин):
      <input v-model.number="form.dq_minutes" type="number" min="0" />
    </label>
    <label>
      Финиш КП:
      <input v-model.number="form.finish_cp" type="number" min="1" />
    </label>
    <IconActionButton
      variant="save"
      label="Сохранить настройки"
      :disabled="busy"
      @click="submit"
    />
  </div>
  <p class="subtitle">
    При указанной стартовой станции КП до первой отметки на ней не учитываются.
    «По станции» — время с первой отметки стартовой станции.
    «По времени» — время со стартового времени (дата + время).
  </p>
</template>
