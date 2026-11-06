# YaMus

Неофициальный клиент Яндекс Музыки для Android — от старых телефонов до актуальных версий системы.

![Android 4.0+](https://img.shields.io/badge/Android-4.0%2B-3DDC84?logo=android&logoColor=white)
![API 37](https://img.shields.io/badge/API-37-1E88E5)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow)

Приложение написано на Kotlin с XML-разметкой и AppCompat: без Material Components, чтобы нормально работать на Android 4.x и не раздувать APK. Поддерживает Android 4.0 (API 14) и Android 17 (API 37).

## Скриншоты

| Главная | Плеер |
|---|---|
| ![Главная](docs/screenshots/home.png) | ![Плеер](docs/screenshots/player.png) |

| Чёрная тема | Светлая тема |
|---|---|
| ![Чёрная тема](docs/screenshots/theme-black.png) | ![Светлая тема](docs/screenshots/theme-light.png) |

## Возможности

- Вход по OAuth-токену или по коду устройства.
- «Моя волна» на основе роторных сессий: станция получает очередь прослушанного и не повторяет треки. Каждый новый запуск звучит по-новому.
- «Моя волна по треку» и возврат к обычной волне.
- Поиск по трекам, артистам и альбомам.
- «Мне нравится», «Мне не нравится», плейлисты, страницы альбомов и артистов.
- Плеер с системным уведомлением, обложкой и управлением; медиа-клавиши и Bluetooth-гарнитуры.
- Три темы: чёрная, тёмная синяя и светлая.
- Выбор качества звука.
- Контекстные меню треков, альбомов и артистов, включая «Поделиться ссылкой».
- Автоматическая очистка дискового кэша при выходе из приложения.
- Все списки на `RecyclerView`, интерфейс рассчитан на небольшие экраны старых телефонов.

## Требования

- Android Studio с JDK 17 (подойдёт JBR из Android Studio).
- Android SDK Platform 37 и Build-Tools 37.0.0.
- Gradle wrapper 8.11.1 входит в репозиторий, отдельно устанавливать Gradle не нужно.

## Сборка

```bash
git clone <URL-репозитория> YaMus
cd YaMus
./gradlew assembleDebug
```

В Windows:

```bat
gradlew.bat assembleDebug
```

Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`.

Если Android Studio не создала `local.properties`, укажите путь к SDK вручную:

```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

`local.properties`, `keystore.properties`, ключи и папки `build/` в репозиторий не попадают — они перечислены в `.gitignore`.

## Release-сборка

Подпись необязательна: без настроенного keystore команда `assembleRelease` соберёт неподписанный APK. Для подписанной сборки:

1. Создайте ключ (он должен лежать вне репозитория или быть добавлен в `.gitignore`):

   ```bash
   keytool -genkeypair -v -keystore yamus-release.jks -alias yamus -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Скопируйте `keystore.properties.example` в `keystore.properties` и заполните значения.
3. Соберите релиз:

   ```bash
   ./gradlew assembleRelease
   ```

APK появится в `app/build/outputs/apk/release/`. Пароли и файл ключа не коммитьте.

## Структура проекта

```
app/src/main/java/ru/yamus/app/
├── core/api        Retrofit-клиент и описание эндпоинтов
├── core/auth       OAuth, хранение токена
├── core/data       лайки, дизлайки, история «Моей волны»
├── core/models     модели ответов API
├── core/net        TLS для Android 4.x
├── core/player     воспроизведение, сервис, кэш треков, волна
├── core/util       общие утилиты
└── ui/             экраны, диалоги и адаптеры
```

Разметка, иконки и строки — в `app/src/main/res`. Скриншоты для документации — в `docs/screenshots`.

## Конфиденциальность

- Токен хранится в приватных `SharedPreferences` приложения и передаётся только на `api.music.yandex.net`.
- В приложении нет аналитики, рекламы и сторонних трекеров.
- Кэш треков и обложек автоматически очищается при выходе, если музыка не играет. Если приложение закрыли принудительно, кэш очищается при следующем запуске.
- История «Моей волны» хранится локально (последние 150 треков) и очищается при выходе из аккаунта.

## Дисклеймер

Проект неофициальный и не связан с Яндексом. «Яндекс Музыка» — товарный знак соответствующего правообладателя. Приложение использует неофициальный API, который может измениться в любой момент. Для работы нужен собственный аккаунт Яндекс Музыки. Используйте на свой риск и не распространяйте скачанный контент.

## Лицензия

[MIT](LICENSE) © 2026 Sekorta

---

## English

YaMus is an unofficial Yandex Music client for Android, written in Kotlin with XML layouts and AppCompat. It supports Android 4.0 (API 14) through Android 17 (API 37), including the notification player, media keys, three themes, search, playlists and an endless “My Wave” based on rotor sessions.

Build with JDK 17 and Android SDK Platform 37:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. See the Russian sections above for release signing and privacy details.

This project is not affiliated with Yandex. Use your own account and at your own risk. Licensed under MIT.
