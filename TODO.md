# Plan zadań

Co i jak zostało zrobione: [PROGRESS.md](PROGRESS.md).

Podejście: aplikacja w Kotlinie oparta na `GameActivity` z AndroidX Games. Silnik w Ruście (wgpu, Vulkan, GLES
jako fallback) jest dołączony jako biblioteka `libmonkeyball.so`. Wariant WebView powstanie na końcu jako
osobna aplikacja, czyli drugi moduł tego samego projektu Android Studio z własnym APK.

Zasady:
- Minimum bibliotek zewnętrznych:
  - Gradle: tylko `androidx.games:games-activity` (wciąga `appcompat` i `core`).
  - Rust: crate'y silnika, `winit` i `jni` (ta sama wersja, której już używają `cpal` i `winit`, więc nic nowego nie dochodzi).
- Konfiguracja: `compileSdk` i `targetSdk` 37, `minSdk` 26 (AAudio, z którego korzysta `cpal`).

## Układ repozytorium

```
monkey-ball-android/
  app/              aplikacja natywna (Kotlin, GameActivity)
  webview/          aplikacja WebView (etap 5)
  gradle/, settings.gradle.kts, build.gradle.kts, gradlew
  native/           crate Rust typu cdylib: android_main, Platform, funkcje JNI
  monkey-ball-rs/   submoduł z silnikiem na tagu v1.0.0
```

## Etap 0: środowisko

- [x] Android Studio: SDK Platform 37, NDK (Side by side), CMake niepotrzebny.
- [x] `rustup target add aarch64-linux-android x86_64-linux-android` (x86_64 dla emulatora).
- [x] `cargo install cargo-ndk`.
- [x] Gałąź `dev`, submoduł `monkey-ball-rs` na `v1.0.0`.

## Etap 1: pusty projekt i APK

- [x] Projekt „No Activity” w Kotlinie, z Gradle Kotlin DSL i katalogiem wersji.
- [x] `games-activity:4.4.0` w zależnościach, **bez** `prefab` (android-activity ma własną warstwę C).
- [x] `MainActivity : GameActivity` oraz `meta-data android.app.lib_name = monkeyball`, motyw `Theme.AppCompat.NoActionBar`.
- [x] `native/` z `android_main`, które uruchamia grę na ekranie importu (`LatePack` z `ImportScreen`).
- [x] Zadanie Gradle `cargoNdk` uruchamiane przed `preBuild` i budujące do `app/src/main/jniLibs/`.
- [ ] APK instaluje się i uruchamia na emulatorze (zrobione, `scripts/smoke-test.sh`) i na telefonie. Logi czytane w Logcat.

## Etap 2: gra natywnie (wymagania obowiązkowe)

- [ ] `Platform` w `native/`:
  - dane w `filesDir` (`app.internal_data_path()`),
  - `Host` z `remove_data`, bez `quit`,
  - `SlotStorage::new(FileStorage::new(dir))`.
- [ ] Start jak `start()` w `mb-desktop/src/lib.rs`:
  - gotowy `game.pack` uruchamia menu,
  - w przeciwnym razie `LatePack` i `ImportScreen`.
- [ ] Wybór obrazu płyty (wywołanie pickera i przekazanie deskryptora gotowe w etapie 1, brakuje toastu przy błędzie):
  1. `Command::PickImage` w Ruście wywołuje przez JNI metodę `pickImage()` z Kotlina.
  2. `OpenDocument("*/*")` zwraca deskryptor, `detachFd()` przekazuje go do `external fun nativeImagePicked(fd)`.
  3. Przy następnym `poll_input` deskryptor trafia do `Importer::open` i do `ImageSlot`.
  4. Błąd: toast i ponowny wybór.
- [ ] `Command::RemoveGameData`: zakończ grę i usuń `game.pack`.
- [ ] Dźwięk: `audio_cpal` (AAudio), zawieszany w `on_visible(false)`.
- [ ] Dotyk i przycisk wstecz: obsługuje je sterownik. Dotyk w menu działa na emulatorze; sprawdzić sterowanie kulką i systemowy gest wstecz, na telefonie.
- [ ] Ekran zawsze włączony (`FLAG_KEEP_SCREEN_ON`) i tryb immersive.
- [ ] Wycięcia ekranu (`Platform::insets`): odczytać z Kotlina, jeśli winit ich nie podaje. **Potrzebne**: na emulatorze przycisk wstecz i HUD wchodzą pod pasek stanu.
- [ ] `dataExtractionRules`: wyłączyć `game.pack` z kopii zapasowej, zapisy zostawić.
- [ ] Sprawdzić odtwarzanie powierzchni po wyjściu z aplikacji i powrocie oraz po obrocie.
- [x] Fallback na GLES, gdy Vulkan nie działa (`Platform::backends`): Vulkan, gdy jest adapter, inaczej GLES; wymuszenie przez `debug.monkeyball.backend`.

## Etap 3: żyroskop (opcjonalne)

- [ ] `SensorManager` z `TYPE_ROTATION_VECTOR`.
  - Z `getRotationMatrixFromVector` i `getOrientation` wyznaczyć beta i gamma w stopniach oraz obrót ekranu.
  - Wartości trafiają przez `external fun nativeTilt(...)` do zmiennych atomowych, a w `poll_input` do `TiltSteering`.
  - Ustawić `Caps::tilt`.

## Etap 4: pomiary wariantu natywnego (opcjonalne)

- [ ] Mierzyć:
  - FPS i p99 czasu klatki (`Platform::on_frame`), Vulkan i GLES,
  - pamięć (Android Studio Profiler, `dumpsys meminfo`),
  - czas startu,
  - CPU i zużycie baterii.
- [ ] Zawsze te same etapy i ten sam telefon.

## Etap 5: osobna aplikacja WebView (na koniec)

- [ ] Moduł `webview/` (**File → New → New Module → Phone & Tablet**) z własnym `applicationId`, bez zależności od `native/`.
- [ ] `cargo xtask build-web` w `monkey-ball-rs`. Skopiować `target/site` do `webview/src/main/assets/site` przez zadanie Gradle `Copy`.
- [ ] `WebActivity`: `shouldInterceptRequest` serwuje `assets/site` pod `https://appassets.androidplatform.net/` (ok. 20 linii, bez `androidx.webkit`).
- [ ] Ustawienia i kod zgodnie z `monkey-ball-rs/docs/ANDROID_WEBVIEW.md`:
  - `onShowFileChooser`,
  - przycisk wstecz,
  - `onPause`/`onResume`,
  - `onRenderProcessGone`,
  - wyłączenie `app_webview` z kopii zapasowej.
- [ ] Te same pomiary co w etapie 4 (nakładka `?fps=1`). Porównanie obu wariantów w README.
