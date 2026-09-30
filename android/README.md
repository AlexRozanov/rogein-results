# Малахит Чип (Android)

Первый этап: чтение SFR-чипа по NFC (ISO 15693) и постановка дампа в локальную очередь.

## Сборка

Нужны JDK 17 и Android SDK 35 (Android Studio).

```bash
cd android
# Android Studio создаст local.properties с sdk.dir=...
./gradlew :chip-parse:test
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

На телефоне включите NFC. Откройте приложение, приложите чип к зоне NFC (обычно верх крышки). Номер, отметки и время появятся в очереди.

Очередь хранится в SQLite на устройстве и переживает закрытие приложения. Отправка на десктоп — следующий этап.
