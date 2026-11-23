<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, hasPerm, loadMe, me, meLoaded } from "../../auth";
import AdminIcon from "../../components/AdminIcon.vue";

type PublishSetup = {
  api_base_url: string;
  publish_token: string;
};

const loading = ref(true);
const error = ref("");
const setup = ref<PublishSetup | null>(null);
const showToken = ref(false);
const showApi = ref(true);
const copied = ref("");

/** Владелец: роль owner или право roles.manage (только у owner). */
const canSeeSecrets = computed(
  () =>
    (me.value?.roles.includes("owner") ?? false) || hasPerm("roles.manage"),
);

const apiBase = computed(() => {
  const fromApi = setup.value?.api_base_url?.trim() ?? "";
  if (fromApi) return fromApi.replace(/\/$/, "");
  if (typeof window !== "undefined") return window.location.origin;
  return "";
});

const token = computed(() => setup.value?.publish_token ?? "");

async function load() {
  loading.value = true;
  error.value = "";
  setup.value = null;
  try {
    if (!meLoaded.value) await loadMe();
    // Не блокируем по клиентской роли — решение за API; иначе бывает «пустая» страница.
    setup.value = await api<PublishSetup>("/api/admin/publish-setup");
  } catch (e) {
    const msg = e instanceof Error ? e.message : "Не удалось загрузить";
    error.value = msg.trim() || "Не удалось загрузить параметры online";
    setup.value = null;
  } finally {
    loading.value = false;
  }
}

async function copyText(label: string, value: string) {
  if (!value) return;
  try {
    await navigator.clipboard.writeText(value);
    copied.value = label;
    window.setTimeout(() => {
      if (copied.value === label) copied.value = "";
    }, 1600);
  } catch {
    error.value = "Не удалось скопировать в буфер обмена";
  }
}

onMounted(load);
</script>

<template>
  <div class="admin-page live-setup">
    <div class="admin-page-head">
      <h1>Online / live</h1>
      <router-link class="admin-icon-btn" to="/admin" title="Назад">
        <AdminIcon name="back" />
      </router-link>
    </div>

    <p v-if="loading" class="muted">Загрузка…</p>

    <p v-if="!loading && error" class="live-error">{{ error }}</p>

    <p v-if="!loading && !error && !setup" class="live-error">
      Нет данных. Обновите страницу или зайдите снова.
      <button type="button" class="linkish" @click="load">Повторить</button>
    </p>

    <template v-if="!loading && setup">
      <section class="live-card">
        <h2>Параметры для телефона и десктопа</h2>
        <p class="muted">
          Вставляются в приложение: <strong>Ещё → Публикация</strong>.
          Токен виден только владельцу.
        </p>

        <div class="secret-row">
          <div class="secret-meta">
            <span class="secret-label">Адрес API (сайт)</span>
            <code class="secret-value">{{
              showApi ? apiBase || "—" : "••••••••••••••••"
            }}</code>
          </div>
          <div class="secret-actions">
            <button
              type="button"
              class="admin-icon-btn"
              :title="showApi ? 'Скрыть' : 'Показать'"
              @click="showApi = !showApi"
            >
              <AdminIcon :name="showApi ? 'eyeOff' : 'eye'" />
            </button>
            <button
              type="button"
              class="admin-icon-btn"
              title="Копировать"
              :disabled="!apiBase"
              @click="copyText('api', apiBase)"
            >
              <AdminIcon name="copy" />
            </button>
          </div>
          <span v-if="copied === 'api'" class="copied">скопировано</span>
        </div>

        <div class="secret-row">
          <div class="secret-meta">
            <span class="secret-label">Токен публикации</span>
            <code class="secret-value">{{
              showToken ? token || "—" : "••••••••••••••••••••"
            }}</code>
          </div>
          <div class="secret-actions">
            <button
              type="button"
              class="admin-icon-btn"
              :title="showToken ? 'Скрыть' : 'Показать'"
              @click="showToken = !showToken"
            >
              <AdminIcon :name="showToken ? 'eyeOff' : 'eye'" />
            </button>
            <button
              type="button"
              class="admin-icon-btn"
              title="Копировать"
              :disabled="!token"
              @click="copyText('token', token)"
            >
              <AdminIcon name="copy" />
            </button>
          </div>
          <span v-if="copied === 'token'" class="copied">скопировано</span>
        </div>
      </section>

      <section class="live-card">
        <h2>Как настроить live на телефоне</h2>
        <ol class="steps">
          <li>
            Установите приложение из раздела
            <router-link to="/admin/app">Приложение</router-link>
            (SFR Reader).
          </li>
          <li>
            Откройте <strong>Ещё → Публикация</strong>.
          </li>
          <li>
            Вставьте <strong>адрес API</strong> (кнопка копирования выше) —
            обычно это адрес сайта без пути, например
            <code>https://malahit-sprint.ru</code>.
          </li>
          <li>
            Вставьте <strong>токен публикации</strong> (сначала «глаз», затем «копировать»).
          </li>
          <li>
            Укажите <strong>slug</strong> старта — тот же, что в календаре / ссылке
            <code>/live/…</code>, и название старта.
          </li>
          <li>
            Сохраните. Включите отправку online (флаг публикации), если выключена.
          </li>
          <li>
            При полевом обмене live шлёт только <strong>хост</strong> (локальная сеть)
            или <strong>издатель</strong> (облако). Не включайте издателя на двух
            телефонах сразу.
          </li>
          <li>
            Проверьте: после финиша / «Отправить online» страница
            <code>/live/&lt;slug&gt;</code> обновляется.
          </li>
        </ol>
      </section>

      <section class="live-card">
        <h2>Десктоп</h2>
        <p class="muted">
          Настройки → «Публикация на сайт»: тот же адрес API и токен. Они
          сохраняются в данных программы и не сбрасываются при завершении старта.
        </p>
      </section>
    </template>

    <p v-if="!loading && !canSeeSecrets && error" class="muted tip">
      Нужна роль <code>owner</code> в учёте. Сейчас:
      {{ me?.roles?.join(", ") || "ролей нет" }}.
    </p>
  </div>
</template>

<style scoped>
.live-setup {
  max-width: 40rem;
}

.live-error {
  margin: 1rem 0;
  padding: 0.85rem 1rem;
  background: #f8e8e0;
  color: #8a3b12;
  border-radius: 8px;
  border: 1px solid #e0c4b4;
}

.linkish {
  margin-left: 0.5rem;
  background: none;
  border: none;
  color: var(--green-dark, #178a2d);
  text-decoration: underline;
  cursor: pointer;
  font: inherit;
}

.live-card {
  margin: 1.25rem 0;
  padding: 1.1rem 1.2rem;
  background: var(--card, #fffcf6);
  border: 1px solid var(--line, #d9d3c4);
  border-radius: 12px;
}

.live-card h2 {
  margin: 0 0 0.5rem;
  font-size: 1.1rem;
}

.secret-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.35rem 0.75rem;
  align-items: start;
  margin-top: 1rem;
  padding-top: 0.85rem;
  border-top: 1px solid var(--line, #d9d3c4);
}

.secret-label {
  display: block;
  font-size: 0.85em;
  color: var(--slate, #3d4f5c);
  margin-bottom: 0.25rem;
}

.secret-value {
  display: block;
  word-break: break-all;
  font-size: 0.95em;
}

.secret-actions {
  display: flex;
  gap: 0.25rem;
  align-items: center;
}

.copied {
  grid-column: 1 / -1;
  font-size: 0.85em;
  color: var(--green-dark, #178a2d);
}

.steps {
  margin: 0.5rem 0 0;
  padding-left: 1.25rem;
  line-height: 1.5;
}

.steps li {
  margin: 0.45rem 0;
}

.tip {
  margin-top: 1rem;
  font-size: 0.9em;
}

code {
  font-size: 0.9em;
}
</style>
