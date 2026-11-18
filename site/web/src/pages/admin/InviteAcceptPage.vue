<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, loadMe } from "../../auth";

const route = useRoute();
const router = useRouter();

const email = ref("");
const password = ref("");
const password2 = ref("");
const error = ref("");
const pending = ref(false);
const loading = ref(true);
const expiresAt = ref("");

const token = String(route.params.token || "");

const expiresLabel = computed(() => formatExpiresRu(expiresAt.value));

function formatExpiresRu(iso: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

async function loadInvite() {
  loading.value = true;
  error.value = "";
  try {
    const info = await api<{ email: string; expires_at: string }>(
      `/api/auth/invite/${encodeURIComponent(token)}`,
    );
    email.value = info.email;
    expiresAt.value = info.expires_at;
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    loading.value = false;
  }
}

async function onSubmit() {
  error.value = "";
  if (password.value !== password2.value) {
    error.value = "Пароли не совпадают";
    return;
  }
  pending.value = true;
  try {
    await api(`/api/auth/invite/${encodeURIComponent(token)}/accept`, {
      method: "POST",
      body: JSON.stringify({ password: password.value }),
    });
    await loadMe();
    await router.replace("/admin");
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}

onMounted(loadInvite);
</script>

<template>
  <div class="admin-login">
    <form class="admin-auth-card" @submit.prevent="onSubmit">
      <p class="admin-auth-brand">
        <span class="admin-auth-brand-mark" aria-hidden="true" />
        Админка
      </p>
      <h1>Установка пароля</h1>

      <p v-if="loading" class="admin-auth-lead">Проверяем ссылку…</p>

      <template v-else-if="email">
        <p class="admin-auth-lead">Придумайте пароль не короче 10 символов.</p>
        <div class="admin-auth-meta">
          <span class="admin-auth-chip">{{ email }}</span>
          <span v-if="expiresLabel" class="admin-auth-chip is-muted">
            до {{ expiresLabel }}
          </span>
        </div>
        <p v-if="error" class="err">{{ error }}</p>
        <div class="admin-auth-fields">
          <label>
            Пароль
            <input
              v-model="password"
              type="password"
              autocomplete="new-password"
              required
              minlength="10"
            />
          </label>
          <label>
            Ещё раз
            <input
              v-model="password2"
              type="password"
              autocomplete="new-password"
              required
              minlength="10"
            />
          </label>
        </div>
        <button class="btn btn-primary admin-auth-submit" type="submit" :disabled="pending">
          {{ pending ? "Сохраняем…" : "Войти в админку" }}
        </button>
      </template>

      <template v-else>
        <p class="err">{{ error || "Ссылка недействительна" }}</p>
        <p class="admin-auth-footer">
          <router-link to="/admin/login">Ко входу</router-link>
        </p>
      </template>
    </form>
  </div>
</template>
