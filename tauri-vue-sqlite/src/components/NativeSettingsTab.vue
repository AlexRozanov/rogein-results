<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import NativeSettingsForm from "./NativeSettingsForm.vue";
import NativeFormatOverrides from "./NativeFormatOverrides.vue";
import NativeFormatCpTypeRules from "./NativeFormatCpTypeRules.vue";
import NativeExclusionRules from "./NativeExclusionRules.vue";
import NativeAwardGroups from "./NativeAwardGroups.vue";
import NativeStartArchives from "./NativeStartArchives.vue";
import NativeSitePublish from "./NativeSitePublish.vue";
import NativeOrientRemovedCps from "./NativeOrientRemovedCps.vue";
import NativeOrientExclusionRules from "./NativeOrientExclusionRules.vue";
import CollapsiblePanel from "./CollapsiblePanel.vue";

type SportKind = "rogaine" | "orient";

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

type FormatOption = {
  format_id: number;
  format_name: string;
};

const props = defineProps<{
  busy: boolean;
  archiveMode?: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
  workspaceReset: [];
  finishStart: [];
}>();

const globalSettings = ref<NativeSettings | null>(null);
const formats = ref<FormatOption[]>([]);
const localBusy = ref(false);
const formatOverridesRef = ref<{ refresh: () => Promise<void> } | null>(null);
const sitePublishRef = ref<{ refresh: () => Promise<void> } | null>(null);
const removedCpsRef = ref<{ refresh: () => Promise<void> } | null>(null);
const orientExclusionRef = ref<{ refresh: () => Promise<void> } | null>(null);
const switchOpen = ref(false);
const pendingKind = ref<SportKind | null>(null);
const switchArchiveName = ref("");
const switchOverwrite = ref(false);

const isBusy = computed(() => props.busy || localBusy.value);
const isOrient = computed(() => globalSettings.value?.sport_kind === "orient");

onMounted(() => {
  void refresh();
});

async function refresh() {
  try {
    globalSettings.value = await invoke<NativeSettings>("get_settings");
    await sitePublishRef.value?.refresh();
    if (props.archiveMode) return;
    await formatOverridesRef.value?.refresh();
    await removedCpsRef.value?.refresh();
    await orientExclusionRef.value?.refresh();
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
      sportKind: form.sport_kind,
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

async function hasWorkingData(): Promise<boolean> {
  const counts = await invoke<{
    finish_participants: number;
    start_protocol: number;
    cp_legends: number;
    courses: number;
  }>("get_data_presence_counts");
  return (
    counts.finish_participants > 0 ||
    counts.start_protocol > 0 ||
    counts.cp_legends > 0 ||
    counts.courses > 0
  );
}

async function onRequestSportKind(kind: SportKind) {
  if (globalSettings.value?.sport_kind === kind) return;
  try {
    if (!(await hasWorkingData())) {
      await applySportKind(kind, false, null);
      return;
    }
    localBusy.value = true;
    pendingKind.value = kind;
    switchArchiveName.value = await invoke<string>("suggest_finish_start_name");
    switchOverwrite.value = false;
    switchOpen.value = true;
  } catch (error) {
    emit("status", `Ошибка смены вида соревнования: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function applySportKind(
  kind: SportKind,
  archiveCurrentFirst: boolean,
  archiveName: string | null,
) {
  localBusy.value = true;
  try {
    const result = await invoke<{
      settings: NativeSettings;
      archived: boolean;
      archive_title: string | null;
    }>("switch_sport_kind", {
      sportKind: kind,
      archiveCurrentFirst,
      archiveName,
      overwrite: switchOverwrite.value,
    });
    switchOpen.value = false;
    switchOverwrite.value = false;
    pendingKind.value = null;
    globalSettings.value = result.settings;
    if (result.archived && result.archive_title) {
      emit("status", `Старт «${result.archive_title}» сохранён в архив, вид соревнования изменён.`);
    } else {
      emit(
        "status",
        kind === "orient"
          ? "Вид соревнования: заданное направление."
          : "Вид соревнования: рогейн.",
      );
    }
    emit("workspaceReset");
  } catch (error) {
    const message = String(error);
    if (archiveCurrentFirst && message.includes("уже существует")) {
      switchOverwrite.value = true;
      emit("status", `${message} Нажмите ещё раз для перезаписи.`);
    } else {
      emit("status", `Ошибка смены вида соревнования: ${message}`);
    }
  } finally {
    localBusy.value = false;
  }
}

function closeSwitchDialog() {
  if (isBusy.value) return;
  switchOpen.value = false;
  pendingKind.value = null;
  switchOverwrite.value = false;
}

async function onWorkspaceReset() {
  await refresh();
  emit("workspaceReset");
}
</script>

<template>
  <div v-if="props.archiveMode" class="settings-tab">
    <NativeSitePublish
      ref="sitePublishRef"
      access-only
      :busy="props.busy || localBusy"
      @status="emit('status', $event)"
    />
    <div class="native-row" style="margin-top: 16px">
      <button type="button" :disabled="isBusy" @click="emit('finishStart')">
        Завершить старт
      </button>
    </div>
  </div>

  <div v-else class="settings-tab">
    <NativeStartArchives
      :busy="props.busy || localBusy"
      @status="emit('status', $event)"
      @workspace-reset="onWorkspaceReset"
    />

    <NativeSitePublish
      ref="sitePublishRef"
      :busy="props.busy || localBusy"
      @status="emit('status', $event)"
    />

    <CollapsiblePanel panel-id="general" title="Общие настройки">
      <p class="subtitle">
        Общие значения используются по умолчанию. Для формата можно задать только отличия.
      </p>
      <NativeSettingsForm
        :settings="globalSettings"
        :busy="props.busy || localBusy"
        @save="saveGlobal"
        @request-sport-kind="onRequestSportKind"
      />
    </CollapsiblePanel>

    <NativeOrientRemovedCps
      v-if="isOrient"
      ref="removedCpsRef"
      :busy="props.busy || localBusy"
      @status="emit('status', $event)"
      @saved="emit('saved')"
    />

    <NativeOrientExclusionRules
      v-if="isOrient"
      ref="orientExclusionRef"
      :busy="props.busy || localBusy"
      @status="emit('status', $event)"
      @saved="emit('saved')"
    />

    <NativeFormatOverrides
      v-if="!isOrient"
      ref="formatOverridesRef"
      :busy="props.busy || localBusy"
      :global-settings="globalSettings"
      @status="emit('status', $event)"
      @saved="emit('saved')"
      @formats-loaded="formats = $event"
    />

    <NativeFormatCpTypeRules
      v-if="!isOrient"
      :busy="props.busy || localBusy"
      :formats="formats"
      @status="emit('status', $event)"
      @saved="emit('saved')"
    />

    <NativeExclusionRules
      v-if="!isOrient"
      :busy="props.busy || localBusy"
      :formats="formats"
      @status="emit('status', $event)"
      @saved="emit('saved')"
    />

    <NativeAwardGroups
      v-if="!isOrient"
      :busy="props.busy || localBusy"
      :formats="formats"
      @status="emit('status', $event)"
      @saved="emit('saved')"
    />
  </div>

  <div
    v-if="switchOpen && pendingKind"
    class="modal-backdrop"
    @click.self="closeSwitchDialog"
  >
    <div class="modal-card" role="dialog" aria-modal="true" aria-label="Смена вида соревнования">
      <div class="modal-header">
        <h3>Сменить вид соревнования?</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Закрыть"
          aria-label="Закрыть"
          :disabled="isBusy"
          @click="closeSwitchDialog"
        >
          ×
        </button>
      </div>
      <p>
        Переключение на
        <strong>{{ pendingKind === "orient" ? "заданное направление" : "рогейн" }}</strong>
        очистит текущую рабочую базу: результаты, протоколы, легенды и карту.
        Рогейн и заданное направление лучше хранить разными стартами в архиве.
      </p>
      <label>
        Имя архива
        <input v-model="switchArchiveName" type="text" :disabled="isBusy" />
      </label>
      <p v-if="switchOverwrite" class="subtitle">
        Файл с таким именем уже есть — следующее подтверждение перезапишет его.
      </p>
      <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
        <button type="button" :disabled="isBusy" @click="closeSwitchDialog">Отменить</button>
        <button
          type="button"
          :disabled="isBusy"
          @click="pendingKind && applySportKind(pendingKind, false, null)"
        >
          Переключить без сохранения
        </button>
        <button
          type="button"
          :disabled="isBusy || !switchArchiveName.trim()"
          @click="pendingKind && applySportKind(pendingKind, true, switchArchiveName.trim())"
        >
          {{ switchOverwrite ? "Перезаписать архив и переключить" : "Сохранить в архив и переключить" }}
        </button>
      </div>
    </div>
  </div>
</template>
