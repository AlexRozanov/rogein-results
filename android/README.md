# Малахит Чип (Android)

Первый этап: чтение SFR-чипа по NFC (ISO 15693) и постановка дампа в локальную очередь.

## Сборка

Нужны JDK 17 и Android SDK 35 (Android Studio).

```bash
cd android
# Android Studio создаст local.properties с sdk.dir=...
./gradlew :chip-parse:test
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APK: `app/build/outputs/apk/debug/app-debug.apk` или `app/build/outputs/apk/release/app-release.apk`.

Release подписан debug-ключом, чтобы ставить поверх сборок из Android Studio без удаления данных. Для Play Store позже нужен отдельный ключ — тогда тестерам придётся удалить приложение.

На телефоне включите NFC. Откройте приложение, приложите чип к зоне NFC (обычно верх крышки). Номер, отметки и время появятся в очереди.

Очередь хранится в SQLite на устройстве и переживает закрытие приложения.

Стартовый протокол с компьютера приходит по USB в `Android/data/ru.rogein.chip/files/start-pack.json`.
Очередь финиша уходит обратно тем же каталогом: `chip-queue.json` (копия также пишется в Загрузки/rogein).
Кнопка «Обновить» читает стартовый пакет. На выдаче выберите участника и приложите чип — номер запишется, отметки очистятся. На финише приложите чип, отметки останутся в очереди.
