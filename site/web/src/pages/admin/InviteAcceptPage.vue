<script setup lang="ts">
import { onMounted, ref } from "vue";
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
    <form class="card admin-form" @submit.prevent="onSubmit">
      <h1>Установка пароля</h1>
      <p v-if="loading" class="muted">Проверяем ссылку…</p>
      <template v-else-if="email">
        <p class="muted">
          Аккаунт <strong>{{ email }}</strong>. Придумайте пароль не короче 10 символов.
          <template v-if="expiresAt"> Ссылка действует до {{ expiresAt }}.</template>
        </p>
        <p v-if="error" class="err">{{ error }}</p>
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
        <button class="btn btn-primary" type="submit" :disabled="pending">
          {{ pending ? "Сохраняем…" : "Войти в админку" }}
        </button>
      </template>
      <template v-else>
        <p class="err">{{ error || "Ссылка недействительна" }}</p>
        <router-link class="btn" to="/admin/login">Ко входу</router-link>
      </template>
    </form>
  </div>
</template>
