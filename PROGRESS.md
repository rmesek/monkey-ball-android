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

Jeszcze nie sprawdzone w tym wpisie: telefon, dotyk, dźwięk, import obrazu płyty (patrz następny wpis).

## 2026-11-02: import obrazu płyty i pierwsza rozgrywka na emulatorze

Obraz `Super Monkey Ball (USA).rvz` (475 MB) wgrany do `Download` na emulatorze. Systemowy wybór pliku
obsłużony z komputera: `uiautomator dump` podaje położenie pliku, a `adb shell input tap` w niego stuka.

Sprawdzone na emulatorze (Pixel 9, API 37, Vulkan przez GFXStream):

| Co | Wynik |
|---|---|
| Wybór pliku, przekazanie deskryptora do Rusta, `Importer::open` | działa: plik rozpoznany jako gra |
| Import do pamięci aplikacji | `files/game.pack`, 144 099 740 B; trwał poniżej 6 s (ekran importu zniknął przed pierwszym zrzutem po 6 s) |
| Menu tytułowe po imporcie | wyświetla się, czcionki i grafika z obrazu płyty |
| Dotyk w menu (`adb shell input tap`) | działa: PRACTICE → BEGINNER → 1 PLAIN |
| Etap 1 (PLAIN) | renderuje się w 3D: plansza, niebo, kulka, HUD (wynik, czas, banany), wirtualna gałka i pauza |
| Ponowny start | od razu menu, bez pytania o plik; `am start -W`: TotalTime 584 ms |
| Pamięć w trakcie etapu 1 (`dumpsys meminfo`) | TOTAL PSS 84,4 MB, z tego Native Heap 43,3 MB |

Znalezione problemy:
- Elementy przy górnej krawędzi (przycisk wstecz w menu, wynik w HUD) wchodzą pod pasek stanu. Gra nie
  zna jeszcze wcięć ekranu (`Platform::insets`). Do zrobienia w etapie 2.
- Pasek podpowiedzi w menu pokazuje klawisze (ARROWS / ENTER / ESC), bo emulator zgłasza klawiaturę
  sprzętową. Na telefonie bez klawiatury powinny być podpowiedzi dotykowe; do sprawdzenia.

Jeszcze nie sprawdzone: telefon, dźwięk (emulator otwiera strumień AAudio, ale odsłuchu nie było),
sterowanie kulką, gest wstecz, wyjście do tła i powrót, obrót ekranu.

## 2026-11-02: wcięcia ekranu (paski systemowe, wycięcie aparatu)

Problem: przy `targetSdk` 35+ okno zajmuje cały ekran, od krawędzi do krawędzi, pod paskiem stanu i paskiem nawigacji. Gra rysowała przycisk wstecz i wynik w HUD pod paskiem stanu.

Skąd wziąć wcięcia:
- `android-activity` 0.6 ich nie udostępnia.
- `AndroidApp::content_rect()` obejmuje cały ekran (logcat: `ContentRectChanged (0 0) (1080 2424)`).
- Klasa `GameActivity` sama implementuje `OnApplyWindowInsetsListener` (sprawdzone przez `javap` na `classes.jar` z `games-activity` 4.4.0).

Rozwiązanie:
- **Kotlin:** `MainActivity.onApplyWindowInsets` woła `super`, a potem przekazuje wcięcia `systemBars() | displayCutout()` w pikselach przez `external fun nativeInsets(...)`.
- **Rust:** cztery zmienne `AtomicU32` (zapis na wątku UI, odczyt na wątku gry). `Platform::insets` zwraca je co klatkę. Powierzchnia ma rozdzielczość okna, więc piksele okna to piksele powierzchni.

Narzędzie testowe `scripts/import-image.sh [część nazwy] [timeout]`:
- wybiera obraz płyty w systemowym pickerze (`uiautomator dump` i `input tap` w element z tekstem nazwy pliku),
- czeka, aż powstanie `game.pack`.

Razem ze `smoke-test.sh` daje pełny przebieg od czystej instalacji do menu, bez dotykania emulatora. Dwie pułapki napotkane przy pisaniu:
- ikona podglądu pliku ma nazwę w `content-desc`, więc trzeba dopasować atrybut `text`,
- `am start` aplikacji z `launchMode=singleTask` zamyka picker leżący nad nią.

Wyniki na emulatorze (zrzuty ekranu):
- **Pion:** przycisk wstecz i tytuł menu zaczynają się pod paskiem stanu (142 px). HUD etapu (SCORE, czas, BANANA) jest pod paskiem, a FLOOR nad paskiem gestów (63 px).
- **Poziom** (obrót w trakcie etapu przez `settings put system user_rotation 1`): bez awarii, HUD odsunięty od wycięcia aparatu po lewej.
- Smoke test (Vulkan) przechodzi, a `clippy` dla arm64 i x86_64 nie zgłasza ostrzeżeń.

## 2026-11-02: testy instrumentacyjne, przycisk wstecz, tryb immersive, kopia zapasowa

### Testy uruchamiane z Android Studio

Skrypty `adb` (`scripts/`) zostały, ale główne testy to teraz testy instrumentacyjne JUnit w
`app/src/androidTest/java/io/github/rmesek/monkeyball/GameTest.kt`.
- **Uruchamianie:** zielona strzałka przy klasie albo przy teście w Android Studio, albo `./gradlew connectedDebugAndroidTest` (raport HTML w `app/build/reports/androidTests/connected/`).
- **Jak testy widzą grę:** Rust zgłasza przez JNI do `MainActivity` gotowość GPU (`onGameReady`), zmianę stanu gry na wierzchu (`onGameState`: `import`, `menu`, `play`...), głębokość nawigacji (`onNavDepth`) i błędy (`onGameError`). `MainActivity` zapisuje to w obiekcie `GameEvents`, na który czekają testy.
- **Każdy test w osobnym procesie (Android Test Orchestrator).** winit pozwala na jedną pętlę zdarzeń na proces, a `android_main` kończy proces. Kilka testów w jednym procesie nie mogłoby więc uruchomić gry ponownie.
- **Picker systemowy:** obsługiwany przez `UiAutomation`, część Androida, bez UiAutomator czy Espresso. Wyszukiwanie elementów idzie przez drzewo dostępności, a stuknięcie przez `input tap`. `ACTION_CLICK` z dostępności nie działa na liście plików DocumentsUI: test czekał 180 s z otwartym pickerem.
- **Backend:** testy ustawiają `debug.monkeyball.backend` przez `UiAutomation.executeShellCommand("setprop ...")`.
- **Zależności testów** (nie trafiają do aplikacji): `androidx.test.ext:junit`, `androidx.test:runner` i `androidx.test:orchestrator`. Espresso i lokalne testy JUnit z szablonu usunięte.
- **Uwaga:** `connectedDebugAndroidTest` po zakończeniu odinstalowuje aplikację razem z jej danymi, więc import robi się na nowo przy każdym przebiegu (około 4 s na emulatorze).

| Test | Co sprawdza | Czas (emulator) |
|---|---|---|
| `firstStartAsksForTheDiscImage` | bez danych: GPU wstaje, ekran importu, otwiera się picker | 1,7 s |
| `importsTheDiscImageAndShowsTheMenu` | wybór obrazu w pickerze, `game.pack` > 100 MB, stany `import` → `menu` | 4,5 s |
| `laterStartsGoStraightToTheMenu` | z danymi: pierwszy stan to `menu` | 1,3 s |
| `menuSystemBackStepsOutOfSubMenus` | Enter → podmenu (`nav_depth` > 0), systemowy wstecz → tytuł, aktywność dalej RESUMED | 1,8 s |
| `startsOnGles` / `startsOnVulkan` | wymuszony backend, adapter zgłasza `(Gl, ...)` / `(Vulkan, ...)` | 1,1 s |

Wynik: 6 z 6 testów przechodzi. Testy, które potrzebują obrazu płyty, są pomijane (a nie oblane), gdy obrazu nie ma na urządzeniu.

### Systemowy przycisk wstecz

Przy targetSdk 37 (predictive back) przycisk wstecz nie trafia do gry jako klawisz: AppCompat zamykał aktywność nawet z podmenu.

Rozwiązanie wzorowane na wersji przeglądarkowej:
- Silnik podaje `Game::nav_depth`, czyli liczbę kroków do ekranu tytułowego.
- Rust zgłasza ją do Kotlina, a `OnBackPressedCallback` jest włączony tylko przy głębokości większej od zera.
- Wtedy wstecz trafia do gry (`nativeBack`) jako jedno naciśnięcie `Cancel`.
- Na ekranie tytułowym wstecz działa jak w każdej aplikacji i ją zamyka.

### Tryb immersive

`WindowInsetsControllerCompat.hide(systemBars())`, z pokazywaniem pasków na chwilę po przeciągnięciu od krawędzi. Wcięcia z poprzedniego wpisu działają dalej: przy ukrytych paskach zostaje tylko wycięcie aparatu.

### Kopia zapasowa

Przy przenoszeniu na nowy telefon (`device-transfer`) aplikacja przenosi wszystko, razem z `game.pack`, więc na nowym telefonie nie trzeba importować ponownie.

Kopia w chmurze (`cloud-backup` oraz `backup_rules.xml` dla Androida 11 i starszych) pomija `game.pack`. Auto Backup ma limit 25 MB na aplikację, a po jego przekroczeniu przestaje robić kopię całej aplikacji, razem z zapisami gry.

Niedokończony import (`game.pack.tmp`) jest pomijany wszędzie.

### Komunikat przy złym pliku

Ekran importu nie ma czcionek, dopóki nie dostanie obrazu, więc o złym pliku mówi aktywność: toast (`showMessage`), a potem ponowny wybór. Na złym pliku jeszcze nie sprawdzone.

