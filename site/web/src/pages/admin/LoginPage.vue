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
    <form class="card admin-form" @submit.prevent="onSubmit">
      <h1>Вход организатора</h1>
      <p v-if="error" class="err">{{ error }}</p>
      <label>
        Email
        <input v-model="email" type="email" autocomplete="username" required />
      </label>
      <label>
        Пароль
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <button class="btn btn-primary" type="submit" :disabled="pending">
        {{ pending ? "Входим…" : "Войти" }}
      </button>
      <p>
        <router-link class="admin-nav-link" to="/admin/forgot">Забыли пароль?</router-link>
      </p>
    </form>
  </div>
</template>
