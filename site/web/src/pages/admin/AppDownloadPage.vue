<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

type AppVersion = {
  versionName: string;
  versionCode: number;
  url?: string;
  updatedAt?: string;
  apkAvailable?: boolean;
};

const version = ref<AppVersion | null>(null);
const loadError = ref("");

const pageUrl = computed(() => {
  if (typeof window === "undefined") return "https://malahit-sprint.ru/admin/app";
  return `${window.location.origin}/admin/app`;
});

const downloadUrl = "/api/admin/app/download";
const apkMissing = computed(() => version.value?.apkAvailable === false);

const qrSrc = computed(
  () =>
    `https://api.qrserver.com/v1/create-qr-code/?size=220x220&margin=8&data=${encodeURIComponent(pageUrl.value)}`,
);

onMounted(async () => {
  try {
    const res = await fetch("/api/admin/app/version", {
      credentials: "include",
      cache: "no-store",
    });
    if (res.status === 401) {
      loadError.value = "нужен вход в личный кабинет";
      return;
    }
    if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
    version.value = (await res.json()) as AppVersion;
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : "нет данных о версии";
  }
});
</script>

<template>
  <div class="admin-page app-download">
    <h1>Rogein Chip</h1>
    <p class="lead muted">
      Android для судейской команды: чипы, обмен на старте, online.
      Страница только для вошедших в личный кабинет; файл тоже отдаётся после входа.
    </p>

    <div class="app-panel">
      <div class="app-meta">
        <p v-if="version">
          Версия <strong>{{ version.versionName }}</strong>
          <span class="muted">({{ version.versionCode }})</span>
          <template v-if="version.updatedAt">
            · {{ version.updatedAt }}
          </template>
        </p>
        <p v-else-if="loadError" class="warn">{{ loadError }}</p>
        <p v-else class="muted">Загрузка версии…</p>

        <p v-if="apkMissing" class="warn">
          APK на сервере ещё не выложен. Соберите release и
          <code>site/deploy/publish-android-apk.sh</code>, затем деплой.
        </p>

        <p class="actions">
          <a
            class="btn-download"
            :href="downloadUrl"
            :class="{ disabled: apkMissing || !!loadError }"
          >
            Скачать APK
          </a>
        </p>
      </div>

      <figure class="app-qr">
        <img :src="qrSrc" width="220" height="220" alt="QR-код на эту страницу" />
        <figcaption>QR → вход в кабинет → эта страница</figcaption>
      </figure>
    </div>

    <ol class="steps">
      <li>Войдите в личный кабинет на телефоне (или откройте QR и войдите).</li>
      <li>Откройте «Приложение» / эту страницу → «Скачать APK».</li>
      <li>Разрешите установку из этого источника, если Android спросит.</li>
      <li>Обновления ставятся <strong>поверх</strong> — данные на телефоне сохраняются.</li>
    </ol>
  </div>
</template>

<style scoped>
.lead {
  line-height: 1.45;
  max-width: 40rem;
}

.app-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem 2rem;
  align-items: flex-start;
  margin: 1.5rem 0;
  padding: 1.25rem;
  background: var(--card, #fffcf6);
  border: 1px solid var(--line, #d9d3c4);
  border-radius: 12px;
  max-width: 40rem;
}

.app-meta {
  flex: 1 1 14rem;
  min-width: 0;
}

.actions {
  margin: 1rem 0 0;
}

.btn-download {
  display: inline-block;
  padding: 0.7rem 1.2rem;
  background: var(--green, #1fae3a);
  color: #fff;
  font-weight: 600;
  text-decoration: none;
  border-radius: 8px;
}

.btn-download:hover {
  background: var(--green-dark, #178a2d);
}

.btn-download.disabled {
  pointer-events: none;
  opacity: 0.45;
}

.app-qr {
  margin: 0;
  text-align: center;
}

.app-qr img {
  display: block;
  background: #fff;
  border-radius: 8px;
}

.app-qr figcaption {
  margin-top: 0.5rem;
  font-size: 0.85em;
  color: var(--slate, #3d4f5c);
}

.steps {
  line-height: 1.5;
  padding-left: 1.25rem;
  max-width: 40rem;
}

.steps li {
  margin: 0.4rem 0;
}

.warn {
  color: #8a3b12;
}

code {
  font-size: 0.9em;
}
</style>
