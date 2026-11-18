<script setup lang="ts">
import { ref } from "vue";
import { api } from "../../auth";

const email = ref("");
const error = ref("");
const notice = ref("");
const pending = ref(false);

async function onSubmit() {
  error.value = "";
  notice.value = "";
  pending.value = true;
  try {
    const res = await api<{ ok: boolean; message: string }>("/api/auth/forgot-password", {
      method: "POST",
      body: JSON.stringify({ email: email.value }),
    });
    notice.value = res.message;
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
      <h1>Сброс пароля</h1>
      <p class="muted">Укажите email — если аккаунт есть, придёт ссылка на смену пароля.</p>
      <p v-if="error" class="err">{{ error }}</p>
      <p v-if="notice" class="ok">{{ notice }}</p>
      <label>
        Email
        <input v-model="email" type="email" autocomplete="username" required />
      </label>
      <button class="btn btn-primary" type="submit" :disabled="pending">
        {{ pending ? "Отправляем…" : "Отправить ссылку" }}
      </button>
      <p>
        <router-link class="admin-nav-link" to="/admin/login">Ко входу</router-link>
      </p>
    </form>
  </div>
</template>
