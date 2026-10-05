export type WorkspaceMainTab =
  | "results"
  | "start_protocol"
  | "cp_legends"
  | "courses"
  | "settings"
  | "archive_upload";
export type WorkspaceMode = "live" | "archive";
export type WorkspaceSortBy =
  | "participant_id"
  | "name"
  | "points_raw"
  | "points_final"
  | "elapsed_seconds"
  | "place";
export type WorkspaceSortDir = "asc" | "desc";

export type WorkspaceUiState = {
  activeTab: WorkspaceMainTab;
  workspaceMode: WorkspaceMode;
  filterStatus: string;
  filterSearch: string;
  filterFormatId: string;
  filterAwardGroupId: string;
  filterCourseName: string;
  pageSize: number;
  pageOffset: number;
  sortBy: WorkspaceSortBy;
  sortDir: WorkspaceSortDir;
};

const STORAGE_KEY = "rogein.workspace.ui";

export function saveWorkspaceUiState(state: WorkspaceUiState): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // ignore quota / private mode
  }
}

export function loadWorkspaceUiState(): WorkspaceUiState | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<WorkspaceUiState>;
    if (!parsed || typeof parsed !== "object") return null;
    return {
      activeTab:
        parsed.activeTab === "results" ||
        parsed.activeTab === "start_protocol" ||
        parsed.activeTab === "cp_legends" ||
        parsed.activeTab === "courses" ||
        parsed.activeTab === "settings" ||
        parsed.activeTab === "archive_upload"
          ? parsed.activeTab
          : "results",
      workspaceMode: parsed.workspaceMode === "archive" ? "archive" : "live",
      filterStatus: String(parsed.filterStatus ?? ""),
      filterSearch: String(parsed.filterSearch ?? ""),
      filterFormatId: String(parsed.filterFormatId ?? ""),
      filterAwardGroupId: String(parsed.filterAwardGroupId ?? ""),
      filterCourseName: String(parsed.filterCourseName ?? ""),
      pageSize: Number.isFinite(Number(parsed.pageSize)) ? Number(parsed.pageSize) : 50,
      pageOffset: Number.isFinite(Number(parsed.pageOffset))
        ? Math.max(0, Number(parsed.pageOffset))
        : 0,
      sortBy:
        parsed.sortBy === "participant_id" ||
        parsed.sortBy === "name" ||
        parsed.sortBy === "points_raw" ||
        parsed.sortBy === "points_final" ||
        parsed.sortBy === "elapsed_seconds" ||
        parsed.sortBy === "place"
          ? parsed.sortBy
          : "points_final",
      sortDir: parsed.sortDir === "asc" || parsed.sortDir === "desc" ? parsed.sortDir : "desc",
    };
  } catch {
    return null;
  }
}

/** Mark that the next workspace mount should open the Results tab. */
export function markReturnToResultsTab(): void {
  const current = loadWorkspaceUiState();
  saveWorkspaceUiState({
    activeTab: current?.workspaceMode === "archive" ? "archive_upload" : "results",
    workspaceMode: current?.workspaceMode ?? "live",
    filterStatus: current?.filterStatus ?? "",
    filterSearch: current?.filterSearch ?? "",
    filterFormatId: current?.filterFormatId ?? "",
    filterAwardGroupId: current?.filterAwardGroupId ?? "",
    filterCourseName: current?.filterCourseName ?? "",
    pageSize: current?.pageSize ?? 50,
    pageOffset: current?.pageOffset ?? 0,
    sortBy: current?.sortBy ?? "points_final",
    sortDir: current?.sortDir ?? "desc",
  });
}

export async function closeAuxiliaryWindowOrClearHash(
  labels: string[] = [
    "participant-card",
    "errors-list",
    "course-map",
    "course-path",
    "cp-remap",
  ],
): Promise<boolean> {
  try {
    const { getCurrentWebviewWindow } = await import("@tauri-apps/api/webviewWindow");
    const current = getCurrentWebviewWindow();
    if (labels.includes(current.label)) {
      await current.close();
      return true;
    }
  } catch {
    // main window or non-tauri
  }
  window.location.hash = "";
  return false;
}

/** True if the main window or the current aux window is fullscreen. */
export async function shouldOpenAuxFullscreen(): Promise<boolean> {
  try {
    const { getAllWebviewWindows, getCurrentWebviewWindow } = await import(
      "@tauri-apps/api/webviewWindow"
    );
    const current = getCurrentWebviewWindow();
    if (await current.isFullscreen()) return true;
    const windows = await getAllWebviewWindows();
    for (const win of windows) {
      if (win.label === current.label) continue;
      if (await win.isFullscreen()) return true;
    }
  } catch {
    // browser / permissions
  }
  return false;
}

export type OpenAuxWindowOptions = {
  label: string;
  title: string;
  url: string;
  width?: number;
  height?: number;
};

/**
 * Open (or replace) an auxiliary webview window.
 * Inherits fullscreen from the main/current window when applicable.
 */
export async function openAuxWebviewWindow(
  options: OpenAuxWindowOptions,
): Promise<{ ok: true } | { ok: false; error: unknown }> {
  try {
    const { WebviewWindow } = await import("@tauri-apps/api/webviewWindow");
    const existing = await WebviewWindow.getByLabel(options.label);
    if (existing) {
      await existing.close();
    }
    const fullscreen = await shouldOpenAuxFullscreen();
    const win = new WebviewWindow(options.label, {
      title: options.title,
      width: options.width ?? 1220,
      height: options.height ?? 900,
      url: options.url,
      fullscreen,
    });
    return await new Promise((resolve) => {
      win.once("tauri://created", () => resolve({ ok: true }));
      win.once("tauri://error", (error) => resolve({ ok: false, error }));
    });
  } catch (error) {
    return { ok: false, error };
  }
}
