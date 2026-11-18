<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api, loadMe } from "../../auth";
import AdminIcon from "../../components/AdminIcon.vue";

const router = useRouter();
const currentPassword = ref("");
const newPassword = ref("");
const newPassword2 = ref("");
const error = ref("");
const notice = ref("");
const pending = ref(false);

async function onSubmit() {
  error.value = "";
  notice.value = "";
  if (newPassword.value !== newPassword2.value) {
    error.value = "Новые пароли не совпадают";
    return;
  }
  pending.value = true;
  try {
    await api("/api/auth/change-password", {
      method: "POST",
      body: JSON.stringify({
        current_password: currentPassword.value,
        new_password: newPassword.value,
      }),
    });
    await loadMe();
    currentPassword.value = "";
    newPassword.value = "";
    newPassword2.value = "";
    notice.value = "Пароль изменён";
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}
</script>

<template>
  <div class="admin-page">
    <div class="admin-page-head">
      <h1>Смена пароля</h1>
      <p class="muted">После смены все другие сессии будут завершены.</p>
    </div>
    <p v-if="error" class="err">{{ error }}</p>
    <p v-if="notice" class="ok">{{ notice }}</p>
    <form class="card admin-form" style="max-width: 24rem" @submit.prevent="onSubmit">
      <label>
        Текущий пароль
        <input
          v-model="currentPassword"
          type="password"
          autocomplete="current-password"
          required
        />
      </label>
      <label>
        Новый пароль
        <input
          v-model="newPassword"
          type="password"
          autocomplete="new-password"
          required
          minlength="10"
        />
      </label>
      <label>
        Ещё раз
        <input
          v-model="newPassword2"
          type="password"
          autocomplete="new-password"
          required
          minlength="10"
        />
      </label>
      <div class="admin-row-actions">
        <button class="btn btn-primary" type="submit" :disabled="pending">
          {{ pending ? "Сохраняем…" : "Сменить пароль" }}
        </button>
        <button
          class="admin-icon-btn"
          type="button"
          title="Назад"
          aria-label="Назад"
          @click="router.push('/admin')"
        >
          <AdminIcon name="back" />
        </button>
      </div>
    </form>
  </div>
</template>
