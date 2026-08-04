<script setup lang="ts">
import { ref, watch } from "vue";

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
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
  competition_date: "",
  competition_start_time: "",
});

watch(
  () => props.settings,
  (next) => {
    if (next) {
      form.value = { ...next };
    }
  },
  { immediate: true },
);

function submit() {
  emit("save", { ...form.value });
}
</script>

<template>
  <p v-if="settings" class="subtitle">
    Текущие настройки: CT {{ settings.control_minutes }} мин, DQ +
    {{ settings.dq_minutes }} мин, штраф {{ settings.penalty_per_minute }}/мин,
    финиш {{ settings.finish_cp }}, дата {{ settings.competition_date }},
    старт {{ settings.competition_start_time }}.
  </p>

  <div class="native-settings">
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
    <label>
      Дата соревнования:
      <input v-model="form.competition_date" type="date" />
    </label>
    <label>
      Время общего старта:
      <input v-model="form.competition_start_time" type="time" step="1" />
    </label>
    <button :disabled="busy" @click="submit">Сохранить настройки</button>
  </div>
</template>
