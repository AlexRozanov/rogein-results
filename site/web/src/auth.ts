import { ref } from "vue";

export type Me = {
  id: number;
  email: string;
  display_name: string;
  roles: string[];
  permissions: string[];
};

export const me = ref<Me | null>(null);
export const meLoaded = ref(false);

export function hasPerm(code: string): boolean {
  return me.value?.permissions.includes(code) ?? false;
}

export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const res = await fetch(path, {
    ...options,
    credentials: "include",
    headers,
  });
  if (res.status === 401) {
    me.value = null;
  }
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || res.statusText);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export async function loadMe(): Promise<Me | null> {
  try {
    const res = await fetch("/api/auth/me", { credentials: "include" });
    if (!res.ok) {
      me.value = null;
      return null;
    }
    me.value = (await res.json()) as Me;
    return me.value;
  } finally {
    meLoaded.value = true;
  }
}

export async function login(email: string, password: string): Promise<Me> {
  const user = await api<Me>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  me.value = user;
  meLoaded.value = true;
  return user;
}

export async function logout(): Promise<void> {
  try {
    await api("/api/auth/logout", { method: "POST" });
  } finally {
    me.value = null;
  }
}
