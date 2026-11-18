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

const ROLE_LABEL: Record<string, string> = Object.fromEntries(
  STAFF_ROLES.map((role) => [role.code, role.label]),
);

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

function roleLabel(code: string): string {
  return ROLE_LABEL[code] || code;
}

function statusClass(status: string): string {
  if (status === "active") return "is-active";
  if (status === "invited") return "is-invited";
  return "is-disabled";
}

function statusLabel(status: string): string {
  if (status === "active") return "активен";
  if (status === "invited") return "приглашён";
  if (status === "disabled") return "отключён";
  return status;
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
  <div class="admin-page admin-users-page">
    <div class="admin-page-head admin-users-head">
      <div>
        <h1>Сотрудники</h1>
        <p class="muted">Приглашение в админку по email со ссылкой для установки пароля.</p>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-if="notice" class="ok">{{ notice }}</p>
    <div v-if="inviteUrl" class="admin-invite-fallback">
      <code class="admin-invite-url">{{ inviteUrl }}</code>
      <button type="button" class="btn" :disabled="pending" @click="copyInviteUrl">
        Скопировать
      </button>
    </div>

    <section class="admin-users-panel">
      <h2 class="admin-users-title">Пригласить</h2>
      <form class="admin-users-form" @submit.prevent="onInvite">
        <div class="admin-users-fields">
          <label class="admin-users-field">
            <span>Email</span>
            <input v-model="email" type="email" required autocomplete="off" />
          </label>
          <label class="admin-users-field">
            <span>Имя <em class="muted">(необязательно)</em></span>
            <input v-model="name" type="text" autocomplete="off" />
          </label>
        </div>

        <div class="admin-users-roles">
          <p class="admin-users-roles-label">Роли</p>
          <div class="admin-role-chips" role="group" aria-label="Роли">
            <button
              v-for="role in roleOptions"
              :key="role.code"
              type="button"
              class="admin-role-chip"
              :class="{ 'is-on': selectedRoles.includes(role.code) }"
              :aria-pressed="selectedRoles.includes(role.code)"
              @click="toggleRole(role.code)"
            >
              {{ role.label }}
            </button>
          </div>
        </div>

        <button
          class="btn btn-primary admin-users-submit"
          type="submit"
          :disabled="pending || !selectedRoles.length"
        >
          {{ pending ? "Сохраняем…" : "Пригласить" }}
        </button>
      </form>
    </section>

    <section class="admin-users-panel">
      <h2 class="admin-users-title">Список</h2>
      <p v-if="loading" class="muted">Загрузка…</p>
      <ul v-else-if="users.length" class="admin-users-list">
        <li v-for="row in users" :key="row.id" class="admin-user-row">
          <div class="admin-user-main">
            <div class="admin-user-identity">
              <strong class="admin-user-email">{{ row.email }}</strong>
              <span v-if="row.display_name" class="admin-user-name">{{ row.display_name }}</span>
            </div>
            <div class="admin-user-meta">
              <span
                class="admin-status-pill"
                :class="statusClass(row.status)"
              >
                {{ statusLabel(row.status) }}
              </span>
              <div class="admin-user-role-tags">
                <span
                  v-for="code in row.roles"
                  :key="code"
                  class="admin-role-tag"
                >
                  {{ roleLabel(code) }}
                </span>
                <span v-if="!row.roles.length" class="muted">без ролей</span>
              </div>
            </div>
          </div>
          <div class="admin-user-actions">
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
          </div>
        </li>
      </ul>
      <p v-else class="muted">Пока никого нет.</p>
    </section>
  </div>
</template>
