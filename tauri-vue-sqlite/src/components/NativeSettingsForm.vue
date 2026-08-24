<script setup lang="ts">
import { computed, ref, watch } from "vue";
import IconActionButton from "./IconActionButton.vue";

export type SportKind = "rogaine" | "orient";

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
  start_mode: "station" | "time";
  start_cp: number | null;
  competition_date: string;
  competition_start_time: string;
  sport_kind: SportKind;
};

const props = defineProps<{
  settings: NativeSettings | null;
  busy: boolean;
}>();

const emit = defineEmits<{
  save: [payload: NativeSettings];
  requestSportKind: [kind: SportKind];
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
  sport_kind: "rogaine",
});

const isOrient = computed(() => form.value.sport_kind === "orient");
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
        sport_kind: next.sport_kind === "orient" ? "orient" : "rogaine",
        competition_start_time: toTimeInputValue(next.competition_start_time),
      };
    }
  },
  { immediate: true },
);

function toTimeInputValue(raw: string) {
  const value = String(raw || "").trim();
  if (!value) return "";
  if (/^\d{2}:\d{2}(:\d{2})?$/.test(value)) return value.slice(0, 8);
  return value;
}

function onStartCpInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value;
  form.value.start_cp = raw === "" ? null : Number(raw);
}

function requestKind(kind: SportKind) {
  if (kind === form.value.sport_kind) return;
  emit("requestSportKind", kind);
}

function submit() {
  emit("save", {
    ...form.value,
    start_cp: form.value.start_cp && form.value.start_cp > 0 ? Number(form.value.start_cp) : null,
    competition_date: String(form.value.competition_date || "").trim(),
    competition_start_time: String(form.value.competition_start_time || "").trim(),
    sport_kind: isOrient.value ? "orient" : "rogaine",
    start_mode: isOrient.value ? "station" : form.value.start_mode,
  });
}
</script>

<template>
  <p v-if="settings" class="subtitle">
    Вид:
    {{ settings.sport_kind === "orient" ? "заданное направление" : "рогейн" }},
    старт КП {{ settings.start_cp ?? "—" }},
    финиш {{ settings.finish_cp }},
    контроль {{ settings.control_minutes }} мин,
    дата {{ settings.competition_date || "—" }}.
  </p>

  <div class="sport-kind-row">
    <button
      type="button"
      class="sport-kind-btn"
      :class="{ active: form.sport_kind === 'rogaine' }"
      :disabled="busy"
      @click="requestKind('rogaine')"
    >
      Рогейн
    </button>
    <button
      type="button"
      class="sport-kind-btn"
      :class="{ active: form.sport_kind === 'orient' }"
      :disabled="busy"
      @click="requestKind('orient')"
    >
      Заданное направление
    </button>
  </div>

  <div class="native-settings">
    <template v-if="!isOrient">
      <label>
        Тип старта:
        <select v-model="form.start_mode">
          <option value="station">Старт по станции</option>
          <option value="time">Старт по времени</option>
        </select>
      </label>
    </template>
    <label>
      Стартовая станция (КП){{ isOrient || isStationMode ? " *" : "" }}:
      <input
        :value="form.start_cp ?? ''"
        type="number"
        min="1"
        :placeholder="isOrient || isStationMode ? 'обязательно' : 'опционально'"
        @input="onStartCpInput"
      />
    </label>
    <label>
      Дата соревнования{{ isTimeMode ? " *" : "" }}:
      <input v-model="form.competition_date" type="date" />
    </label>
    <label v-if="!isOrient">
      Стартовое время{{ isTimeMode ? " *" : "" }}:
      <input v-model="form.competition_start_time" type="time" step="1" />
    </label>
    <label>
      {{ isOrient ? "Контрольное время (мин)" : "Контроль (мин)" }}:
      <input v-model.number="form.control_minutes" type="number" min="1" />
    </label>
    <label v-if="!isOrient">
      Штраф/мин:
      <input v-model.number="form.penalty_per_minute" type="number" min="0" />
    </label>
    <label v-if="!isOrient">
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
    <template v-if="isOrient">
      Время считается от первой отметки старта до последней отметки финиша.
      КП внутри должны идти в порядке дистанции; лишние отметки между ними допустимы.
      Превышение контрольного времени — дисквалификация.
    </template>
    <template v-else>
      При указанной стартовой станции КП до первой отметки на ней не учитываются.
      «По станции» — время с первой отметки стартовой станции.
      «По времени» — время со стартового времени (дата + время).
    </template>
  </p>
</template>
