<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, hasPerm } from "../../auth";

type AdminUser = {
  id: number;
  email: string;
  status: string;
  display_name: string;
  roles: string[];
  created_at: string;
};

type InviteResult = {
  user: AdminUser;
  email_sent: boolean;
  email_error: string | null;
  invite_url?: string;
};

const STAFF_ROLES = [
  { code: "admin", label: "Администратор" },
  { code: "secretary", label: "Секретарь" },
  { code: "editor", label: "Редактор" },
  { code: "finish", label: "Финиш" },
  { code: "owner", label: "Владелец" },
] as const;

const users = ref<AdminUser[]>([]);
const email = ref("");
const name = ref("");
const selectedRoles = ref<string[]>(["secretary"]);
const error = ref("");
const notice = ref("");
const inviteUrl = ref("");
const pending = ref(false);
const loading = ref(true);

const canGrantOwner = computed(() => hasPerm("roles.manage"));
const roleOptions = computed(() =>
  STAFF_ROLES.filter((role) => role.code !== "owner" || canGrantOwner.value),
);

const statusLabel: Record<string, string> = {
  active: "активен",
  invited: "приглашён",
  disabled: "отключён",
};

async function loadUsers() {
  loading.value = true;
  error.value = "";
  try {
    users.value = await api<AdminUser[]>("/api/admin/users");
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    loading.value = false;
  }
}

function toggleRole(code: string) {
  if (selectedRoles.value.includes(code)) {
    selectedRoles.value = selectedRoles.value.filter((item) => item !== code);
  } else {
    selectedRoles.value = [...selectedRoles.value, code];
  }
}

async function onInvite() {
  error.value = "";
  notice.value = "";
  inviteUrl.value = "";
  pending.value = true;
  try {
    const result = await api<InviteResult>("/api/admin/users", {
      method: "POST",
      body: JSON.stringify({
        email: email.value,
        name: name.value || undefined,
        roles: selectedRoles.value,
      }),
    });
    applyInviteResult(result, "Приглашение создано");
    email.value = "";
    name.value = "";
    await loadUsers();
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}

async function onResend(id: number) {
  error.value = "";
  notice.value = "";
  inviteUrl.value = "";
  pending.value = true;
  try {
    const result = await api<InviteResult>(`/api/admin/users/${id}/resend-invite`, {
      method: "POST",
      body: "{}",
    });
    applyInviteResult(result, "Приглашение отправлено повторно");
    await loadUsers();
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}

async function onDisable(id: number) {
  if (!confirm("Отключить этого пользователя?")) return;
  error.value = "";
  notice.value = "";
  inviteUrl.value = "";
  pending.value = true;
  try {
    await api(`/api/admin/users/${id}/disable`, { method: "POST", body: "{}" });
    notice.value = "Пользователь отключён";
    await loadUsers();
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}

function applyInviteResult(result: InviteResult, okText: string) {
  if (result.email_sent) {
    notice.value = `${okText}. Письмо отправлено на ${result.user.email}.`;
    return;
  }
  notice.value = `${okText}. Письмо не ушло${
    result.email_error ? `: ${result.email_error}` : ""
  }. Скопируйте ссылку вручную.`;
  inviteUrl.value = result.invite_url || "";
}

async function copyInviteUrl() {
  if (!inviteUrl.value) return;
  try {
    await navigator.clipboard.writeText(inviteUrl.value);
    notice.value = "Ссылка скопирована";
  } catch {
    error.value = "Не удалось скопировать ссылку";
  }
}

onMounted(loadUsers);
</script>

<template>
  <div class="admin-page">
    <div class="admin-page-head">
      <h1>Сотрудники</h1>
      <p class="muted">Приглашение в админку по email со ссылкой для установки пароля.</p>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-if="notice" class="ok">{{ notice }}</p>
    <div v-if="inviteUrl" class="admin-invite-fallback">
      <code class="admin-invite-url">{{ inviteUrl }}</code>
      <button type="button" class="btn" :disabled="pending" @click="copyInviteUrl">
        Скопировать
      </button>
    </div>

    <form class="card admin-form" @submit.prevent="onInvite">
      <h2>Пригласить</h2>
      <label>
        Email
        <input v-model="email" type="email" required autocomplete="off" />
      </label>
      <label>
        Имя (необязательно)
        <input v-model="name" type="text" autocomplete="off" />
      </label>
      <fieldset>
        <legend>Роли</legend>
        <label v-for="role in roleOptions" :key="role.code" class="admin-check">
          <input
            type="checkbox"
            :checked="selectedRoles.includes(role.code)"
            @change="toggleRole(role.code)"
          />
          {{ role.label }}
        </label>
      </fieldset>
      <button class="btn btn-primary" type="submit" :disabled="pending || !selectedRoles.length">
        {{ pending ? "Сохраняем…" : "Пригласить" }}
      </button>
    </form>

    <div class="card" style="margin-top: 1rem">
      <h2>Список</h2>
      <p v-if="loading" class="muted">Загрузка…</p>
      <table v-else-if="users.length" class="admin-users-table">
        <thead>
          <tr>
            <th>Email</th>
            <th>Имя</th>
            <th>Роли</th>
            <th>Статус</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in users" :key="row.id">
            <td>{{ row.email }}</td>
            <td>{{ row.display_name || "—" }}</td>
            <td>{{ row.roles.join(", ") || "—" }}</td>
            <td>{{ statusLabel[row.status] || row.status }}</td>
            <td class="admin-row-actions">
              <button
                v-if="row.status === 'invited'"
                type="button"
                class="btn btn-quiet"
                :disabled="pending"
                @click="onResend(row.id)"
              >
                Ещё раз
              </button>
              <button
                v-if="row.status !== 'disabled'"
                type="button"
                class="btn btn-quiet"
                :disabled="pending"
                @click="onDisable(row.id)"
              >
                Отключить
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">Пока никого нет.</p>
    </div>
  </div>
</template>
