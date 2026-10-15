use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

const APP_ID: &str = "ru.rogein.chip";

const SNAPSHOT_RUN_AS: &[&str] = &[
    "files/chip-queue.json",
    "/sdcard/Android/data/ru.rogein.chip/files/chip-queue.json",
    "/storage/emulated/0/Android/data/ru.rogein.chip/files/chip-queue.json",
];

const SNAPSHOT_PATHS: &[&str] = &[
    "/sdcard/Android/data/ru.rogein.chip/files/chip-queue.json",
    "/storage/emulated/0/Android/data/ru.rogein.chip/files/chip-queue.json",
    "/sdcard/Download/rogein/chip-queue.json",
    "/storage/emulated/0/Download/rogein/chip-queue.json",
];

const START_PACK_PATHS: &[&str] = &[
    "/sdcard/Android/data/ru.rogein.chip/files/start-pack.json",
    "/storage/emulated/0/Android/data/ru.rogein.chip/files/start-pack.json",
    "/sdcard/Download/rogein/start-pack.json",
    "/storage/emulated/0/Download/rogein/start-pack.json",
];

pub struct PhoneSnapshotRead {
    pub serial: String,
    pub path: String,
    pub body: String,
}

pub struct SnapshotDelete {
    pub deleted: bool,
    pub note: String,
}

pub fn try_read_phone_snapshot() -> Result<Option<PhoneSnapshotRead>, String> {
    let Ok(adb) = locate_adb() else {
        return Ok(None);
    };
    let Ok(serial) = connected_serial(&adb) else {
        return Ok(None);
    };
    read_snapshot_body(&adb, &serial)
}

pub fn phone_is_ready() -> bool {
    locate_adb()
        .ok()
        .and_then(|adb| connected_serial(&adb).ok())
        .is_some()
}

pub fn read_phone_snapshot() -> Result<PhoneSnapshotRead, String> {
    let adb = locate_adb()?;
    let serial = connected_serial(&adb)?;
    read_snapshot_body(&adb, &serial)?.ok_or_else(|| {
        "На телефоне нет снимка очереди (chip-queue.json). Считайте финиш в приложении и не закрывайте его."
            .to_string()
    })
}

fn read_snapshot_body(adb: &Path, serial: &str) -> Result<Option<PhoneSnapshotRead>, String> {
    for rel in SNAPSHOT_RUN_AS {
        match cat_run_as(adb, serial, rel) {
            Ok(Some(body)) => {
                return Ok(Some(PhoneSnapshotRead {
                    serial: serial.to_string(),
                    path: format!("run-as {APP_ID} {rel}"),
                    body,
                }));
            }
            Ok(None) | Err(_) => {}
        }
    }
    for path in SNAPSHOT_PATHS {
        match cat_file(adb, serial, path) {
            Ok(Some(body)) => {
                return Ok(Some(PhoneSnapshotRead {
                    serial: serial.to_string(),
                    path: (*path).to_string(),
                    body,
                }));
            }
            Ok(None) | Err(_) => {}
        }
    }
    Ok(None)
}

fn cat_run_as(adb: &Path, serial: &str, rel: &str) -> Result<Option<String>, String> {
    let output = run_adb(
        adb,
        &["-s", serial, "exec-out", "run-as", APP_ID, "cat", rel],
    )?;
    if !output.status.success() {
        return Ok(None);
    }
    let body = String::from_utf8(output.stdout)
        .map_err(|_| "снимок очереди не в UTF-8".to_string())?;
    if body.trim().is_empty() {
        return Ok(None);
    }
    Ok(Some(body))
}

/// Снимок на телефоне оставляем: его перезаписывает приложение, десктоп читает повторно.
pub fn delete_snapshot_if_unchanged(_read: &PhoneSnapshotRead) -> Result<SnapshotDelete, String> {
    Ok(SnapshotDelete {
        deleted: false,
        note: String::new(),
    })
}

#[allow(dead_code)]
fn delete_via_media_store(adb: &Path, serial: &str) {
    for uri in [
        "content://media/external/downloads",
        "content://media/external_primary/downloads",
    ] {
        let Ok(output) = run_adb(
            adb,
            &[
                "-s",
                serial,
                "shell",
                "content",
                "query",
                "--uri",
                uri,
                "--projection",
                "_id:_display_name",
                "--where",
                "_display_name='chip-queue.json'",
            ],
        ) else {
            continue;
        };
        if !output.status.success() {
            continue;
        }
        let text = String::from_utf8_lossy(&output.stdout);
        for id in media_ids(&text) {
            let item = format!("{uri}/{id}");
            let _ = run_adb(
                adb,
                &["-s", serial, "shell", "content", "delete", "--uri", &item],
            );
        }
    }
}

fn media_ids(query_output: &str) -> Vec<String> {
    let mut ids = Vec::new();
    for token in query_output.split(|c: char| c == ',' || c == ' ' || c == '\n') {
        let Some(value) = token.trim().strip_prefix("_id=") else {
            continue;
        };
        let id: String = value.chars().take_while(|c| c.is_ascii_digit()).collect();
        if !id.is_empty() {
            ids.push(id);
        }
    }
    ids
}

fn cat_file(adb: &Path, serial: &str, path: &str) -> Result<Option<String>, String> {
    let output = run_adb(adb, &["-s", serial, "exec-out", "cat", path])?;
    if output.status.success() {
        let body = String::from_utf8(output.stdout)
            .map_err(|_| "снимок очереди не в UTF-8".to_string())?;
        if body.trim().is_empty() {
            return Ok(None);
        }
        return Ok(Some(body));
    }
    let stderr = String::from_utf8_lossy(&output.stderr);
    let lower = stderr.to_ascii_lowercase();
    if lower.contains("permission denied") {
        return Err(format!("нет доступа к {path}: {}", stderr.trim()));
    }
    if lower.contains("no such file")
        || lower.contains("not a directory")
        || (stderr.trim().is_empty() && output.stdout.is_empty())
    {
        return Ok(None);
    }
    Err(format!("не удалось прочитать {path}: {}", stderr.trim()))
}

fn connected_serial(adb: &Path) -> Result<String, String> {
    let output = run_adb(adb, &["devices"])?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("adb devices: {}", stderr.trim()));
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let mut ready = Vec::new();
    let mut unauthorized = false;
    for line in text.lines().skip(1) {
        let mut parts = line.split_whitespace();
        let Some(serial) = parts.next() else {
            continue;
        };
        match parts.next() {
            Some("device") => ready.push(serial.to_string()),
            Some("unauthorized") => unauthorized = true,
            _ => {}
        }
    }
    match ready.len() {
        1 => Ok(ready.remove(0)),
        0 if unauthorized => Err(
            "Телефон подключён, но отладка по USB не разрешена. Подтвердите ключ этого компьютера на телефоне."
                .to_string(),
        ),
        0 => Err(
            "Телефон не найден. Подключите его по USB и включите отладку по USB.".to_string(),
        ),
        _ => Err(
            "Подключено несколько устройств. Оставьте по USB только телефон с очередью чипов."
                .to_string(),
        ),
    }
}

pub fn push_start_pack(body: &str) -> Result<(), String> {
    let adb = locate_adb()?;
    let serial = connected_serial(&adb)?;
    let local = env::temp_dir().join(format!("rogein-start-pack-{serial}.json"));
    fs::write(&local, body).map_err(|e| format!("не удалось записать временный файл: {e}"))?;
    let local_str = local.to_string_lossy().to_string();
    let mut pushed = Vec::new();
    let mut last_error = String::new();
    for remote in START_PACK_PATHS {
        let parent = remote.rsplit_once('/').map(|(dir, _)| dir).unwrap_or("/sdcard");
        let _ = run_adb(&adb, &["-s", &serial, "shell", "mkdir", "-p", parent]);
        match run_adb(&adb, &["-s", &serial, "push", &local_str, remote]) {
            Ok(output) if output.status.success() => {
                pushed.push((*remote).to_string());
            }
            Ok(output) => {
                let stderr = String::from_utf8_lossy(&output.stderr);
                last_error = format!("{remote}: {}", stderr.trim());
            }
            Err(error) => last_error = error,
        }
    }
    let _ = fs::remove_file(&local);
    if pushed.is_empty() {
        return Err(format!(
            "Не удалось записать стартовый протокол на телефон. {last_error}"
        ));
    }
    Ok(())
}

fn locate_adb() -> Result<PathBuf, String> {
    if let Some(path) = search_path("adb") {
        return Ok(path);
    }
    let mut candidates = Vec::new();
    if let Ok(home) = env::var("HOME") {
        let home = PathBuf::from(home);
        candidates.push(home.join("Library/Android/sdk/platform-tools/adb"));
        candidates.push(home.join("Android/Sdk/platform-tools/adb"));
    }
    if let Some(sdk) = env::var("ANDROID_HOME")
        .ok()
        .or_else(|| env::var("ANDROID_SDK_ROOT").ok())
    {
        let sdk = PathBuf::from(sdk);
        candidates.push(sdk.join("platform-tools/adb"));
        candidates.push(sdk.join("platform-tools/adb.exe"));
    }
    for candidate in candidates {
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    Err(
        "Не найден adb. Установите Android platform-tools и включите отладку по USB на телефоне."
            .to_string(),
    )
}

fn search_path(name: &str) -> Option<PathBuf> {
    let path = env::var_os("PATH")?;
    for dir in env::split_paths(&path) {
        let candidate = dir.join(name);
        if candidate.is_file() {
            return Some(candidate);
        }
        let exe = dir.join(format!("{name}.exe"));
        if exe.is_file() {
            return Some(exe);
        }
    }
    None
}

fn run_adb(adb: &Path, args: &[&str]) -> Result<std::process::Output, String> {
    Command::new(adb)
        .args(args)
        .output()
        .map_err(|e| format!("запуск adb: {e}"))
}

#[cfg(test)]
mod tests {
    use super::media_ids;

    #[test]
    fn parses_media_store_ids() {
        let text = "Row: 0 _id=47, _display_name=chip-queue.json\nRow: 1 _id=48, _display_name=other\n";
        assert_eq!(media_ids(text), vec!["47".to_string(), "48".to_string()]);
    }
}
