# Dziennik postępu

Ten plik zbiera to, co zostało zrobione, jak i dlaczego, wraz z napotkanymi problemami i wynikami pomiarów.
Posłuży jako materiał do sprawozdania. Plan dalszych prac jest w [TODO.md](TODO.md).

## Wybrane podejście

Silnik `monkey-ball-rs` (Rust, wgpu) jest dołączony jako submoduł git na tagu `v1.0.0`. Aplikacja na Androida
to projekt Android Studio z aktywnością `GameActivity` (AndroidX Games). Silnik ładuje się jako biblioteka
natywna `libmonkeyball.so`, zbudowana z małego crate'a `native/`.

Rozważane warianty:

| Wariant | Ocena |
|---|---|
| `NativeActivity` (sam Rust) | Prawie bez kodu po stronie Androida; Android Studio służy wtedy tylko do pakowania. |
| **`GameActivity`** (wybrany) | Aktywność w Kotlinie, w której Android robi swoją część (wybór pliku, czujniki, cykl życia), a silnik działa bez zmian przez backend `android-game-activity` biblioteki winit. |
| Własny `SurfaceView` + JNI | Wymagałby napisania od nowa w Ruście pętli klatek i obsługi wejścia, bo sterownik silnika (`mb-driver`) jest oparty na winit. |
| WebView | Bez Rusta po stronie Androida, ale grafika idzie wtedy przez WebGL2, nie Vulkan. Powstanie na końcu jako osobna aplikacja do porównania. |

Założenia:
- **Minimum bibliotek zewnętrznych.** W Gradle jest tylko `androidx.games:games-activity` (wciąga `appcompat`). W Ruście dochodzą tylko `winit` (już używany przez silnik) i `jni` w wersji, którą i tak ma w drzewie zależności `cpal`.
- **SDK:** `compileSdk` i `targetSdk` 37, `minSdk` 26. Dolną granicę wyznacza AAudio, przez które `cpal` odtwarza dźwięk.

## Środowisko

| Narzędzie | Wersja |
|---|---|
| Android Studio | 2026.2.2 |
| Android Gradle Plugin / Gradle | 9.5.0 / 9.6.0 |
| Android SDK Platform | 37 |
| NDK | 30.0.16248370 |
| Rust | 1.98.1 (stable), targety `aarch64-linux-android` i `x86_64-linux-android` |
| cargo-ndk | 4.1.2 |
| monkey-ball-rs | v1.0.0 (`802f518`) |

## 2026-11-02: projekt Android Studio (etap 0)

- Gałąź `dev`. Submoduł `monkey-ball-rs` dodany przez `git submodule add` i ustawiony na `v1.0.0`.
- Projekt utworzony szablonem „No Activity” (Kotlin, Kotlin DSL), pakiet `io.github.rmesek.monkeyball`.
- NDK zainstalowane przez SDK Manager. Wersja ustawiona w `app/build.gradle.kts` (`ndkVersion`), żeby build nie zależał od tego, co akurat jest zainstalowane.
- Pusty projekt buduje się poleceniem `./gradlew assembleDebug`.

## 2026-11-02: silnik w APK (etap 1)

Co powstało:
- **`app/build.gradle.kts`:**
  - zależność `games-activity:4.4.0` (wersję podaje dokumentacja crate'a `android-activity` 0.6.1, z którego korzysta winit 0.30.13),
  - usunięte `material` i `core-ktx` z szablonu,
  - **bez** `prefab`, bo `android-activity` ma własną warstwę C dla GameActivity, a ta z AndroidX by z nią kolidowała.
- **Zadanie Gradle `cargoNdk`:**
  - uruchamiane przed `preBuild`, buduje `native/` poleceniem `cargo ndk --platform 26 -t arm64-v8a -t x86_64 ... build --release` do `app/src/main/jniLibs/`,
  - przycisk Run w Android Studio buduje więc też Rusta,
  - ścieżkę NDK przekazuje Gradle, więc nie trzeba ustawiać zmiennych środowiskowych.
- **Manifest:**
  - `MainActivity` z `meta-data android.app.lib_name = monkeyball`,
  - `configChanges`, dzięki którym obrót ekranu zmienia tylko rozmiar powierzchni, a nie tworzy aktywności od nowa,
  - `launchMode="singleTask"`.
- **Motyw** `Theme.AppCompat.NoActionBar` z czarnym tłem. Usunięte kolory i motyw nocny z szablonu.
- **`MainActivity.kt`:**
  - dziedziczy po `GameActivity` i trzyma ekran włączony,
  - `pickImage()` otwiera systemowy wybór dokumentu (`OpenDocument`, typ `*/*`, bo `.rvz` i `.gcm` nie mają typu MIME),
  - wybrany plik trafia do Rusta jako deskryptor (`detachFd()`) przez `external fun nativeImagePicked`.
- **`native/` (crate `cdylib`):**
  - `android_main` i implementacja `Platform` z `mb-driver`, wzorowana na wersji desktopowej,
  - dane gry (`game.pack`) i zapisy w katalogu wewnętrznym aplikacji,
  - przy pierwszym starcie ekran importu silnika (`ImportScreen`) prosi o obraz płyty,
  - Rust wywołuje `pickImage()` przez JNI; deskryptor wraca przez `Mutex` i jest otwierany w `poll_input`,
  - dźwięk wstrzymywany w tle, logi do logcat (tag `monkeyball`) przez `liblog` z NDK, bez dodatkowego crate'a.

Problemy i rozwiązania:
- AGP 9 nie ma już `android.ndkDirectory`. Ścieżkę NDK daje teraz `androidComponents.sdkComponents.ndkDirectory`.
- Gradle 9.6 oznacza `tasks.registering` jako przestarzałe. Użyte `tasks.register<Exec>`.
- Configuration cache (domyślnie włączony) nie pozwala na `doFirst` odwołujący się do skryptu. Ścieżka NDK jest odczytywana przy konfiguracji zadania jako zwykły napis.
- winit nie pozwala utworzyć drugiej pętli zdarzeń w tym samym procesie, a silnik inicjuje swoje tablice raz na proces. Android potrafi zachować proces po zamknięciu aktywności, dlatego `android_main` kończy proces po zakończeniu gry, a następny start jest czysty.

Wyniki:
- Silnik w wersji 1.0.0 skompilował się i zlinkował pod Androida za pierwszym razem, bez zmian w jego kodzie. Wcześniej był tylko sprawdzany (`clippy`) dla `aarch64-linux-android`, bez linkowania. Pierwsza kompilacja arm64 w trybie release trwała około 44 s.
- `clippy` dla `native/` nie zgłasza ostrzeżeń.
- `libmonkeyball.so`:

  | ABI | Rozmiar |
  |---|---|
  | arm64-v8a | 7,4 MB |
  | x86_64 | 8,2 MB |

  Biblioteka eksportuje `android_main` i `Java_..._nativeImagePicked`. Segmenty są wyrównane do 16 KB (wymóg Androida 15+ dla nowych aplikacji). Zależy tylko od bibliotek systemowych: `liblog`, `libandroid`, `libaaudio`, `libdl`, `libm`, `libc`.
- APK debug ma 19,3 MB.

Jeszcze nie sprawdzone w tym wpisie: uruchomienie (patrz następny wpis).

## 2026-11-02: pierwsze uruchomienie, wybór backendu i smoke test

Pierwsze uruchomienie na emulatorze (Pixel 9, Android 17, API 37) kończyło się zamknięciem aplikacji. Z logcat:

```
E BufferQueueProducer ... connect: already connected (cur=1 req=1)
E libEGL eglCreateWindowSurface: native_window_api_connect ... failed (already connected to another API?)
E monkeyball panic: ... wgpu error: Validation Error ... In Surface::configure ... Invalid surface
```

Przyczyna:
- Sterownik silnika domyślnie tworzy instancję wgpu ze wszystkimi backendami (`Backends::all()`), a wgpu robi wtedy powierzchnię dla każdego z nich.
- Na Androidzie okno (`ANativeWindow`) przyjmuje tylko jednego producenta obrazu. Vulkan podłączył się pierwszy, EGL dostał błąd, a wybrana potem powierzchnia była nieważna.
- Na desktopie i w przeglądarce ten problem nie występuje.

Rozwiązanie (`native/src/lib.rs`, `Platform::backends`):
- Przed startem sterownika aplikacja sprawdza, czy urządzenie ma adapter Vulkan (osobna instancja wgpu tylko z Vulkanem, `enumerate_adapters`).
- Jeśli ma, gra działa tylko na Vulkanie, a jeśli nie, tylko na GLES. Spełnia to wymaganie „Vulkan, a w razie problemów inaczej”.
- Właściwość systemowa `debug.monkeyball.backend` (`vulkan` / `gl`) wymusza wybór. Ustawia się ją z komputera (`adb shell setprop`), co przyda się w testach i porównaniu wydajności.
- Doszły zależności `wgpu` i `pollster` w wersjach, których silnik już używa, więc nic nowego się nie kompiluje.

Testy do samodzielnego iterowania: `scripts/smoke-test.sh [auto|vulkan|gl] [sekundy]`.
- Buduje i instaluje APK.
- Uruchamia grę od czystego stanu (`pm clear`), więc pokazuje się ekran importu z wyborem pliku.
- Czeka, aż silnik zgłosi adapter GPU.
- Kończy się błędem przy panicu, błędzie albo śmierci procesu. Wypisuje log aplikacji.

Wyniki na emulatorze (Pixel 9, API 37, host z NVIDIA GeForce RTX 4080):

| Backend | Adapter | Wynik |
|---|---|---|
| auto, wybrał Vulkan | Goldfish GFXStream (NVIDIA GeForce RTX 4080), Vulkan, VirtualGpu | działa, otwiera się wybór pliku |
| wymuszony GL | Android Emulator OpenGL ES Translator, GL | działa |

Jeszcze nie sprawdzone: telefon, dotyk, dźwięk, import obrazu płyty.
