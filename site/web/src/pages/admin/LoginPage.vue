<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { login } from "../../auth";

const route = useRoute();
const router = useRouter();
const email = ref("");
const password = ref("");
const error = ref("");
const pending = ref(false);

async function onSubmit() {
  error.value = "";
  pending.value = true;
  try {
    await login(email.value, password.value);
    const next = typeof route.query.next === "string" ? route.query.next : "/admin";
    await router.replace(next.startsWith("/admin") ? next : "/admin");
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}
</script>

<template>
  <div class="admin-login">
    <form class="admin-auth-card" @submit.prevent="onSubmit">
      <p class="admin-auth-brand">
        <span class="admin-auth-brand-mark" aria-hidden="true" />
        Админка
      </p>
      <h1>Вход организатора</h1>
      <p class="admin-auth-lead">Email и пароль сотрудника для доступа к календарю и бюллетеням.</p>
      <p v-if="error" class="err">{{ error }}</p>
      <div class="admin-auth-fields">
        <label>
          Email
          <input v-model="email" type="email" autocomplete="username" required />
        </label>
        <label>
          Пароль
          <input v-model="password" type="password" autocomplete="current-password" required />
        </label>
      </div>
      <button class="btn btn-primary admin-auth-submit" type="submit" :disabled="pending">
        {{ pending ? "Входим…" : "Войти" }}
      </button>
      <p class="admin-auth-footer">
        <router-link to="/admin/forgot">Забыли пароль?</router-link>
      </p>
    </form>
  </div>
</template>
