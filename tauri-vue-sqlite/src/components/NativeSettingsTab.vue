<script setup lang="ts">
import { onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import NativeSettingsForm from "./NativeSettingsForm.vue";
import NativeFormatOverrides from "./NativeFormatOverrides.vue";
import NativeFormatCpTypeRules from "./NativeFormatCpTypeRules.vue";
import NativeExclusionRules from "./NativeExclusionRules.vue";
import NativeAwardGroups from "./NativeAwardGroups.vue";
import NativeStartArchives from "./NativeStartArchives.vue";

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

type FormatOption = {
  format_id: number;
  format_name: string;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
  workspaceReset: [];
}>();

const globalSettings = ref<NativeSettings | null>(null);
const formats = ref<FormatOption[]>([]);
const localBusy = ref(false);
const formatOverridesRef = ref<{ refresh: () => Promise<void> } | null>(null);

onMounted(() => {
  void refresh();
});

async function refresh() {
  try {
    globalSettings.value = await invoke<NativeSettings>("get_settings");
    await formatOverridesRef.value?.refresh();
  } catch (error) {
    emit("status", `Ошибка загрузки настроек: ${String(error)}`);
  }
}

async function saveGlobal(form: NativeSettings) {
  localBusy.value = true;
  try {
    const updated = await invoke<NativeSettings>("set_settings", {
      controlMinutes: Number(form.control_minutes),
      penaltyPerMinute: Number(form.penalty_per_minute),
      dqMinutes: Number(form.dq_minutes),
      finishCp: Number(form.finish_cp),
      startMode: form.start_mode,
      startCp: form.start_cp,
      competitionDate: String(form.competition_date || "").trim(),
      competitionStartTime: String(form.competition_start_time || "").trim(),
    });
    globalSettings.value = updated;
    await invoke("recalculate_results");
    emit("status", "Общие настройки сохранены, выполнен пересчет.");
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения общих настроек: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });

async function onWorkspaceReset() {
  await refresh();
  emit("workspaceReset");
}
</script>

<template>
  <div>
    <NativeStartArchives
      :busy="props.busy || localBusy"
      @status="emit('status', $event)"
      @workspace-reset="onWorkspaceReset"
    />

    <section class="native-tools nested-card">
      <h2>Настройки</h2>
      <p class="subtitle">
        Общие значения используются по умолчанию. Для формата можно задать только отличия.
      </p>

      <h3>Общие настройки</h3>
      <NativeSettingsForm
        :settings="globalSettings"
        :busy="props.busy || localBusy"
        @save="saveGlobal"
      />

      <NativeFormatOverrides
        ref="formatOverridesRef"
        :busy="props.busy || localBusy"
        :global-settings="globalSettings"
        @status="emit('status', $event)"
        @saved="emit('saved')"
        @formats-loaded="formats = $event"
      />

      <NativeFormatCpTypeRules
        :busy="props.busy || localBusy"
        :formats="formats"
        @status="emit('status', $event)"
        @saved="emit('saved')"
      />

      <NativeExclusionRules
        :busy="props.busy || localBusy"
        :formats="formats"
        @status="emit('status', $event)"
        @saved="emit('saved')"
      />

      <NativeAwardGroups
        :busy="props.busy || localBusy"
        :formats="formats"
        @status="emit('status', $event)"
        @saved="emit('saved')"
      />
    </section>
  </div>
</template>
