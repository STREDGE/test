# SA Mod Manager

Android-приложение для GTA San Andreas (версия из Google Play, 2.11.x, 64 бит), без root:

- встраивает в установленную игру **AML** (AndroidModLoader) — патч APK прямо на телефоне;
- ставит **CLEO** для 64-битной игры (свой порт, открытого 64-битного CLEO нет) — *в работе*;
- раскладывает моды из архивов (zip/7z/rar) по папкам `CLEO/`, `mods/`, `configs/`;
- редактирует **gta3.img** (замена машин, объектов и т. д.) — *в работе*;
- после установки сам запускает игру и, если она не запустилась или вылетела, присылает уведомление.

Обход лицензии Google Play и DRM не делается: игра должна быть купленной копией из Google Play.

## Как это работает

1. Менеджер копирует APK установленной игры (base + split), добавляет в него:
   - вызов `samm.bridge.Bridge.afterGameLibs()` сразу после `System.loadLibrary("GTASA")`;
   - `BridgeProvider` — мост, через который менеджер (и только он) читает и пишет файлы в `Android/data/com.rockstargames.gtasa/`;
   - `libAML.so` рядом с `libGTASA.so`.
2. Подписывает результат ключом, созданным в Android Keystore телефона.
3. Оригинал удаляется (другая подпись), пропатченная версия ставится через `PackageInstaller`.
   OBB-файлы перед этим копируются в `SAMM/backup/obb` и возвращаются (нужен «Доступ ко всем файлам»).
4. Менеджер запускает игру и ждёт сигнала от моста: `samm/status.properties` + `samm/heartbeat`.

## Сборка

Собирается в GitHub Actions (`.github/workflows/build.yml`): AML из [RusJJ/AndroidModLoader](https://github.com/RusJJ/AndroidModLoader) (MIT) через NDK, затем тесты и APK. Готовый APK — в артефакте `SA-Mod-Manager-apk` у последнего запуска.

Модули:

| Модуль | Что внутри |
|---|---|
| `modcore` | IMG VER2 (чтение/запись/пересборка), распознавание модов из архивов |
| `patcher` | правка DEX (dexlib2), манифеста (ARSCLib), пересборка zip |
| `app` | приложение, мост (`samm.bridge`), подпись (apksig), установка, проверка запуска |

`keystore/samm-manager.jks` — ключ только для подписи самого менеджера, чтобы обновления ставились поверх. Игра им не подписывается.
