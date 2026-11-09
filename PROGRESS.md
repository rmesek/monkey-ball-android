# Dziennik postępu

Ten plik zbiera, krok po kroku, to, co zostało zrobione, jak i dlaczego, wraz z napotkanymi problemami i wynikami pomiarów.
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
| Android Gradle Plugin / Gradle | 9.5.1 / 9.8.2 (od kroku 17; wcześniej 9.5.0 / 9.6.0) |
| Android SDK Platform | 37 |
| NDK | 30.0.16248370 |
| Rust | 1.98.1 (stable), targety `aarch64-linux-android` i `x86_64-linux-android` |
| cargo-ndk | 4.1.2 |
| monkey-ball-rs | v1.0.0 (`802f518`) |

## Krok 1: projekt Android Studio (etap 0)

- Gałąź `dev`. Submoduł `monkey-ball-rs` dodany przez `git submodule add` i ustawiony na `v1.0.0`.
- Projekt utworzony szablonem „No Activity” (Kotlin, Kotlin DSL), pakiet `io.github.rmesek.monkeyball`.
- NDK zainstalowane przez SDK Manager. Wersja ustawiona w `app/build.gradle.kts` (`ndkVersion`), żeby build nie zależał od tego, co akurat jest zainstalowane.
- Pusty projekt buduje się poleceniem `./gradlew assembleDebug`.

## Krok 2: silnik w APK (etap 1)

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

Jeszcze nie sprawdzone w tym wpisie: uruchomienie (patrz następny krok).

## Krok 3: pierwsze uruchomienie na emulatorze, wybór backendu i smoke test

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

Jeszcze nie sprawdzone w tym wpisie: telefon, dotyk, dźwięk, import obrazu płyty (patrz następny krok).

## Krok 4: import obrazu płyty i pierwsza rozgrywka na emulatorze

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

## Krok 5: wcięcia ekranu (paski systemowe, wycięcie aparatu)

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

## Krok 6: testy instrumentacyjne, przycisk wstecz, tryb immersive, kopia zapasowa

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

Ekran importu nie ma czcionek, dopóki nie dostanie obrazu, więc o złym pliku mówi aktywność: toast (`showMessage`), a potem ponowny wybór. Sprawdzone w kroku 7.

## Krok 7: domknięcie etapu 2 (tło, zły plik, usuwanie danych gry)

Nowe testy instrumentacyjne:

| Test | Co sprawdza | Czas (emulator) |
|---|---|---|
| `aFileThatIsNotTheGameIsRefused` | w `Download` powstaje 2 MB losowych danych jako `.iso`; po wybraniu go: komunikat dla gracza, picker otwiera się ponownie, stan zostaje `import`, `game.pack` nie powstaje | 5,0 s |
| `playSurvivesGoingToTheBackground` | etap 1 uruchomiony klawiszem Enter; aktywność zatrzymana i wznowiona (`ActivityScenario.moveToState`); po powrocie stan `play`, bez błędów, a zrzut ekranu (`UiAutomation.takeScreenshot`) pokazuje obraz, a nie jednolity kolor | 12,6 s |

Wynik: 8 z 8 testów przechodzi, cały przebieg trwa około minuty razem z buildem.

Wyjście do tła i powrót to pierwsza sytuacja, w której silnik traci powierzchnię: wcześniej żadna platforma mu jej nie odbierała. Logcat z testu to potwierdza: `NativeWindowDestroyed`, potem `NativeWindowCreated` z innym adresem okna. Sterownik tworzy nową powierzchnię wgpu przy `resumed`, zachowuje urządzenie, renderer i stan gry, a etap rysuje się dalej.

Pułapki przy pisaniu testów:
- **Bez powłoki.** `UiAutomation.executeShellCommand` nie uruchamia powłoki: komenda jest dzielona po spacjach, nie ma przekierowania `>` ani cudzysłowów. Plik testowy tworzy więc `dd if=/dev/urandom of=...`, a nie `head > plik`.
- **Indeksowanie pliku.** Plik utworzony z powłoki trzeba zgłosić do indeksu mediów (`content call --uri content://media/external/file --method scan_file`), inaczej picker go nie pokaże.
- **Czekanie na komendę.** Wynik `executeShellCommand` trzeba przeczytać do końca, żeby poczekać na zakończenie komendy.
- **Kolejność testów.** Test złego pliku usuwa `game.pack`, więc musi się wykonać przed importem. Kolejność jest alfabetyczna (`@FixMethodOrder`), stąd nazwa zaczynająca się od `a`.

Usuwanie danych gry sprawdzone ręcznie na emulatorze. Nie ma na to testu automatycznego, bo gra kończy wtedy proces, a razem z nim proces testu.
1. Options → REMOVE GAME DATA → „TAP AGAIN TO CONFIRM” → ponowne stuknięcie.
2. Gra się zamyka (tak jak na desktopie), w logu jest `game data removed`, a w `files/` nie ma już `game.pack`.
3. Następny start pokazuje ekran importu i otwiera picker.

Ekran opcji pokazuje „GAME DATA: 137 MB” (144 099 740 B) i „KEPT WITH THE SAVES IN THE APP'S STORAGE”.

Rust loguje też każdą zmianę stanu gry (`state: import`, `state: menu`, `state: play`), co ułatwia czytanie logcat.

## Krok 8: uproszczenie

Usunięte skrypty `scripts/smoke-test.sh` i `scripts/import-image.sh` (kroki 3 i 5). Robiły to samo co testy
instrumentacyjne `GameTest`, czyli start gry na obu backendach i import obrazu przez picker, ale tylko z linii
poleceń i przez `adb`. Testy JUnit uruchamia się z Android Studio, więc druga droga była zbędna.

## Krok 9: pełna głośność na telefonie

Silnik ustawia domyślnie muzykę i efekty na 80, co pasuje do komputera. Na emulatorze gra była za cicha.
Na telefonie głośność reguluje się przyciskami systemu, więc gra startuje z obiema wartościami na 100.

Silnik się nie zmienia. `native/src/lib.rs` przy pierwszym starcie, gdy nie ma jeszcze zapisu (klucz
`profile`), zapisuje profil domyślny z głośnościami 100. Późniejsze zmiany w opcjach gry zostają, bo zapis
już istnieje. Wyjątek: „Erase save data” w opcjach usuwa zapis i od razu przywraca ustawienia silnika (80).
Jeśli gra przed zamknięciem coś zapisze (np. ukończony etap), 80 zostaje. Jeśli nie, następny start znów
ustawi 100.

Test `importsTheDiscImageAndShowsTheMenu` sprawdza teraz, że pierwszy zapis zawiera
`setting music_volume 100` i `setting sfx_volume 100`. 8 z 8 testów przechodzi.

## Krok 10: wydajność na emulatorze

Gra na emulatorze wydawała się trochę wolna. Do sprawdzenia `native/src/lib.rs` co 5 s gry zapisuje w logcat
linię `perf:` z czasami klatek. Liczy je `PerfStats` silnika, to samo, czego używa desktop.

Pomiar: etap 1 (Beginner), około 25 s, emulator Pixel 9 (API 37, wyświetlacz 60 Hz) na komputerze z
NVIDIA GeForce RTX 4080. Pierwsze 5 s pominięte, bo obejmują ładowanie etapu.

| Wariant | FPS | Klatka p50 / p99 / najgorsza | Aktualizacja gry (śr.) | Renderowanie (śr.) |
|---|---|---|---|---|
| Emulator, Vulkan (GFXStream) | 59,6–60,0 | 16,7 / 19–28 / 20–35 ms | 0,21–0,28 ms | 16,4 ms |
| Emulator, GLES (OpenGL ES Translator) | 25–27,5 | 35–39 / 44–52 / 54–59 ms | 3,9 ms | 32–36 ms |
| Desktop, ten sam komputer, Vulkan (monitor 240 Hz) | 238,7 | 4,19 (śr.) / 4,47 / 51,6 ms | – | – |

Wnioski:
- **Vulkan na emulatorze trzyma 60 FPS**, czyli tyle, ile odświeża wyświetlacz emulatora. Czas „renderowania” około 16,4 ms to w większości czekanie na vsync (tryb `Fifo`), a nie praca GPU. Aktualizacja gry (fizyka, logika, HUD) zajmuje około 0,25 ms na klatkę, więc procesor ma ogromny zapas.
- **p99 do 28 ms** to pojedyncze zacięcia. Na emulatorze typowe, bo obraz przechodzi przez warstwę GFXStream do GPU komputera i z powrotem do okna emulatora.
- **GLES na emulatorze jest dwa razy wolniejszy.** Emulator tłumaczy GLES na OpenGL komputera, a silnik po każdym wysłaniu pracy do GPU czeka na jej zakończenie (`glFinish`, opisane w dokumentacji silnika jako świadomy wybór dla WebGL). To wariant zapasowy, używany tylko bez Vulkana.
- **Port nie ma problemu z wydajnością**, który byłby widoczny na emulatorze. Odczucie „trochę wolno” to najpewniej sam emulator: 60 Hz zamiast 240 Hz monitora i dodatkowe opóźnienie wyświetlania. Rzeczywiste liczby da dopiero telefon (etap 4).

Build release (`./gradlew assembleRelease`) składa się. APK bez podpisu ma 16,4 MB.

## Krok 11: „blady” obraz

Objaw: po otwarciu aplikacji kolory gry były czasami mniej nasycone. Przy zimnym starcie, powrocie z tła i
w przejściach menu nie dało się tego wywołać. Pojawiło się po tym, jak testy zostawiły otwarty picker,
a Ty wybrałeś w nim plik.

Diagnoza:
- Tło menu miało kolor (35, 47, 85) zamiast (5, 18, 61). To dokładnie gra przykryta białą warstwą o krycie około 12%.
- `dumpsys SurfaceFlinger`: w stanie bladym okno aktywności, które leży nad powierzchnią gry (`SurfaceView`), miało treść na całym ekranie. W normalnym stanie jest całe przezroczyste i nie trafia do kompozycji.
- `dumpsys activity`: okno zawiera tylko `GameActivity$InputEnabledSurfaceView` i ten widok ma fokus.
- Odtworzenie za każdym razem: gra w tle, dowolny klawisz (urządzenie przechodzi w tryb klawiatury, wychodzi z trybu dotyku), powrót do gry.

Przyczyna: od Androida 8 system rysuje nad widokiem z fokusem półprzezroczyste podświetlenie
(`defaultFocusHighlight`), gdy urządzenie jest w trybie klawiatury. Widok gry ma fokus (potrzebny do
klawiszy) i zajmuje cały ekran, więc podświetlenie przykrywało całą grę. Na emulatorze tryb klawiatury
włączają klawiatura komputera i `adb shell input keyevent`, z którego korzystają testy. Stąd „czasami”.

Poprawka: jedna linia w `MainActivity.onCreate`, `mSurfaceView.defaultFocusHighlightEnabled = false`.
Sprawdzone ręcznie: bez poprawki obraz był blady za każdym razem, z poprawką dwa razy z rzędu prawidłowy.

Test automatyczny nie pomógł. Ten sam przebieg w teście instrumentacyjnym (także z
`setInTouchMode(false)`) przechodził również bez poprawki, bo w środowisku testowym efekt się nie
pojawia. Test, który niczego nie wykrywa, został usunięty.

Przy okazji widać było osobną rzecz. Instalacja nowej wersji przy otwartej aplikacji (Run w Android Studio)
potrafi odtworzyć starą aktywność w nowym procesie i zostawić czarny ekran. Dotyczy to tylko
reinstalacji w trakcie pracy, nie zwykłego używania. Ponowne uruchomienie pomaga.

## Krok 12: zawieszenie po wyjściu i ponownym otwarciu

Objaw: po wyjściu z gry i ponownym otwarciu zostawał ekran powitalny z logo Androida.

Diagnoza z logcat:
- Po wyjściu przyciskiem wstecz z ekranu tytułowego aktywność dostaje `Destroy`, a wątek gry `APP_CMD_DESTROY`, ale gra działa dalej.
- System zgłasza `Activity stop timeout` i `top resumed state loss timeout`: główny wątek aplikacji jest zablokowany.

Przyczyna:
- **winit 0.30** na Androidzie ignoruje `Destroy` (w jego kodzie: „TODO: forward onDestroy notification to application”).
- **Sterownik silnika** sprawdza `Platform::wants_exit` tylko podczas rysowania klatki, a po `Destroy` okna już nie ma.
- **Skutek:** `android_main` się nie kończy, a `GameActivity.onDestroy` czeka na wątek gry. Proces wisi, a następne otwarcie trafia do niego i nie może narysować aktywności.

Poprawka (bez zmian w silniku): `MainActivity.onDestroy` kończy proces (`exitProcess(0)`), zanim
GameActivity zacznie czekać.
- To ta sama zasada, co dotąd: jedna gra na proces, która zapisuje postęp na bieżąco.
- Następne otwarcie dostaje świeży proces.
- Zmiany konfiguracji (obrót itd.) nie niszczą aktywności dzięki `configChanges` w manifeście.

Sprawdzone ręcznie, trzy razy z rzędu: wstecz na ekranie tytułowym, proces kończy się od razu, ponowne
otwarcie pokazuje menu. Testu automatycznego nie ma, bo zakończenie procesu aplikacji kończy też proces
testu, który w nim działa. Istniejące 8 testów przechodzi.

## Krok 13: ponowny pomiar wydajności przed pull requestem

Gra na emulatorze wydawała się wolniejsza niż na początku. Ten sam pomiar co w kroku 10 (etap 1, Vulkan,
linie `perf:` co 5 s):

| Kiedy | FPS | Klatka p50 / p99 | Aktualizacja gry | Renderowanie |
|---|---|---|---|---|
| Krok 10 (commit `2622ef8`) | 60 | 16,7 / 19–28 ms | 0,25 ms | 16,4 ms |
| Teraz, ten sam kod Rust | 24–25 | 35–43 / 72–74 ms | 8–10 ms | 31–33 ms |
| Teraz, bez linii z podświetleniem fokusu (A/B) | 24–25 | 35–42 / 74 ms | 8,7–9,7 ms | 31–32 ms |
| Po restarcie systemu emulatora (pierwsze minuty) | 16–19 | 48–57 / 109–160 ms | 9–25 ms | 37–45 ms |
| 2 minuty po restarcie | 26–27 | 36–43 / 62–80 ms | 8,6–10 ms | 27–29 ms |
| **Emulator w osobnym oknie** (poza panelem Android Studio), 2 minuty po starcie | **60** | 16,6 / 18,4–18,7 ms | 0,15–0,17 ms | 16,5 ms |

Co wiadomo:
- **Kod gry się nie zmienił:** od commita `2622ef8` (pomiar z 60 FPS) zmienił się tylko `MainActivity.kt` (16 linii). `native/` jest bez zmian.
- **Podświetlenie fokusu nie jest przyczyną:** pomiar A/B z tą linią i bez niej daje to samo.
- **Wyświetlacz emulatora** cały czas ma 60 Hz.
- **Pętla w powłoce emulatora** (300 000 iteracji, niezależna od gry) trwała 414 ms przed restartem, 973 ms tuż po restarcie i 429 ms dwie minuty później. Szybkość emulatora mocno się waha, a wraz z nią czas aktualizacji gry.
- **Komputer** był obciążony w około 20%.

Wniosek: spadek z 60 do około 25 FPS wynika z emulatora, a nie z portu. Potwierdza to ostatni wiersz tabeli.
Ten sam APK w emulatorze uruchomionym jako osobne okno (`emulator -avd Pixel_9`) wraca do stałych 60 FPS
z aktualizacją gry 0,16 ms. W panelu Running Devices Android Studio pobiera każdą klatkę z GPU emulatora,
żeby ją pokazać, i to spowalnia emulator mniej więcej o połowę.

Do pomiarów i do grania na emulatorze lepiej używać osobnego okna emulatora (w Device Manager: „Launch in a
tool window” wyłączone albo uruchomienie z linii poleceń). Wiarygodne liczby da dopiero telefon (etap 4).

## Krok 14: wyjaśnienie przed wyborem pliku

Przy pierwszym starcie od razu otwierał się systemowy wybór pliku, bez słowa, czego gra potrzebuje. Gra nie
może tego napisać sama, bo czcionki ma dopiero z obrazu płyty.

Teraz `MainActivity.pickImage` pokazuje najpierw krótki dialog (`AlertDialog` z AppCompat, bez nowych
bibliotek):
- „Choose the game file”: obraz płyty Super Monkey Ball, np. `Super Monkey Ball (USA).rvz`, czyli kopia płyty GameCube (RVZ, ISO albo GCM).
- „Choose file” otwiera picker. „Cancel” zamyka grę, jak wcześniej anulowanie pickera.
- Gdy wybrany plik nie jest grą, ten sam dialog ma tytuł „This file cannot be used”, a jako treść powód podany przez silnik (np. „This file is not a GameCube disc image. Supported: …”). Wcześniej był to toast, który ucinał tekst do dwóch linii.
- Toast został tylko dla błędów krytycznych (`onGameError`).

Testy przechodzą przez dialog (przycisk „Choose file”). Test złego pliku sprawdza dialog z powodem.
Po otwarciu pickera testy czekają 1,5 s, bo stuknięcia w trakcie animacji otwierania przepadają. 8 z 8
testów przechodzi.

Banany przy napisie MONKEY BALL: silnik rysuje je tylko wtedy, gdy obok napisu jest miejsce. W pionie napis
zajmuje prawie całą szerokość, więc ich nie ma. W poziomie są, jak na desktopie (sprawdzone zrzutem).
To zachowanie silnika, nie błąd portu. Decyzja: gra zostaje w obu orientacjach, bez blokady w manifeście.

## Krok 15: sprawdzenie ręczne na emulatorze i APK na telefon

- Dźwięk cichnie, gdy aplikacja jest w tle, i wraca po powrocie. Po powrocie słychać chwilami trzaski. Emulator często tak gra, więc rozstrzygnie dopiero telefon.
- Joystick w grze działa. Aktywuje się tylko w lewej dolnej części ekranu: tak jest w silniku (`Layout::stick_zone` w `mb-app/src/ui.rs`, w poziomie lewe 45% szerokości, w pionie lewe 55% dolnych 40%). To nie błąd portu.
- Poprawione ostrzeżenia IntelliJ: `onDestroy` celowo bez `super.onDestroy()` (to ono czeka na wątek gry), `@SuppressLint("MissingSuperCall")` z wyjaśnieniem; nieużywane parametry; pisownia „canceled”.
- Na telefon idzie APK debug (`app/build/outputs/apk/debug/app-debug.apk`), instalowany ręcznie z pliku, bez debugowania USB. Kod Rusta jest w nim i tak budowany w profilu release, więc wydajność odpowiada wersji wydanej. APK ma biblioteki dla `arm64-v8a` (telefon) i `x86_64` (emulator).

## Krok 16: telefon

APK debug zainstalowany ręcznie na telefonie działa bez zastrzeżeń: grafika, dźwięk (bez trzasków po powrocie
z tła, więc te były winą emulatora) i sterowanie kulką. Etapy 1 i 2 są zamknięte.

Strefa joysticka (lewa dolna część ekranu) zostaje bez zmian: wynika z silnika, a zmiana wymagałaby zmian w
`monkey-ball-rs`, których port nie robi.

## Krok 17: żyroskop (etap 3)

Silnik ma już całe sterowanie przechyleniem, napisane dla przeglądarki:
- opcję „Controls: stick / tilt” i zakres przechylenia w menu, widoczne tylko przy `Caps::tilt`,
- polecenia `Command::Tilt { enabled, range_deg }` (start menu i każda zmiana opcji) oraz `Command::CalibrateTilt` (start etapu, przycisk „+” w grze),
- `TiltSteering`, czyli zamianę kątów na kierunek z martwą strefą i kalibracją.

Port dostarcza więc tylko kąty z czujnika i blokuje obrót ekranu, tak jak strona WWW (`web/main.js`) dostarcza zdarzenia `deviceorientation`.

Co powstało:
- **Kotlin, `MainActivity`:**
  - `hasTiltSensor()` jest wołane z Rusta raz, przed startem gry, i ustawia `Caps::tilt`.
  - `setTilt(on)` jest wołane z Rusta, gdy menu zmienia sposób sterowania. Rejestruje lub wyrejestrowuje słuchacza czujnika i ustawia `requestedOrientation` na `SCREEN_ORIENTATION_LOCKED` (zostaje bieżący obrót) albo `UNSPECIFIED`.
  - Czujnik: `TYPE_GAME_ROTATION_VECTOR`, a gdy go nie ma, `TYPE_ROTATION_VECTOR`. Wariant „game” nie używa kompasu: gra potrzebuje tylko kierunku „w dół”, a magnes w pobliżu nie obróci wtedy odczytu.
  - Czujnik działa tylko przy aktywności na wierzchu (`onResume` / `onPause`), więc w tle nie zużywa baterii.
- **Kotlin, `Tilt.kt`:**
  - `deviceTilt` liczy z macierzy obrotu (`getRotationMatrixFromVector`) kąty beta i gamma tak samo jak Chromium dla `deviceorientation`.
  - `getOrientation` odpadło, bo jego pitch kończy się na ±90°: telefon przechylony za pion sterowałby w złą stronę.
  - Obrót ekranu (`Display.rotation` × 90) idzie razem z kątami, bo `TiltSteering` sam zamienia osie w poziomie.
- **Rust, `native/src/lib.rs`:**
  - `nativeTilt` zapisuje ostatnią próbkę w `Mutex`.
  - `poll_input` przekazuje ją do `TiltSteering`, obsługuje oba polecenia i co klatkę wpisuje osie `TiltX`/`TiltY` do wejścia gry.
  - Wywołania JNI mają teraz wspólną funkcję `with_activity`.
- Nowych bibliotek nie ma.

Testy (15 z 15 przechodzi):

| Test | Co sprawdza |
|---|---|
| `TiltTest` (6 testów, bez uruchamiania gry) | kąty `deviceTilt` dla znanych obrotów: płasko, przód/tył, lewo/prawo, oba naraz, za pionem, ekranem w dół |
| `tiltSteeringListensToTheSensorAndKeepsTheRotation` | Options → Controls (klawisze w menu): włączone przechylenie, zablokowany obrót, próbki z czujnika; w tle brak próbek, po powrocie znowu są; po wyłączeniu obrót odblokowany i brak próbek |

Sprawdzone ręcznie na emulatorze (czujniki ustawiane z komputera: `adb emu sensor set acceleration`):
- Options pokazuje „CONTROLS TILT” i „TILT RANGE 25 DEG”. W etapie zamiast gałki jest przycisk „+” (kalibracja).
- Emulator w pozycji wyjściowej: kulka stoi (00 mph).
- Emulator przechylony o 45° do przodu: kulka sama jedzie prosto do mety (GOAL, 16,65 s).
- Obrót systemu do poziomu przy włączonym przechyleniu: gra zostaje w pionie. Po wyłączeniu przechylenia przechodzi w poziom, a po ponownym włączeniu zostaje w poziomie.
- W logcat są linie `tilt steering: true/false`, bez błędów.

Jeszcze nie sprawdzone: telefon, a w szczególności kierunki sterowania w poziomie. Na emulatorze sprawdzony był tylko pion.

Przy okazji: Android Studio podniósł AGP do 9.5.1 i Gradle do 9.8.2. Lint zgłasza tylko nowsze wersje (Gradle 9.9.0, games-activity 4.4.2); ostrzeżeń w kodzie nie ma. Ostrzeżenie Gradle o przestarzałym `Configuration.setVisible` pochodzi z AGP, nie z naszego skryptu.

## Krok 18: obrót ekranu w menu przy sterowaniu przechyleniem

Na telefonie sterowanie przechyleniem działa w pionie i w poziomie. Brakowało jednak sposobu na zmianę orientacji: obrót był zablokowany od chwili wybrania „Controls: tilt”, także w menu.

Teraz blokada obowiązuje tylko w etapie:
- Rust (`after_update` w `native/src/lib.rs`) woła `setTilt(true)`, gdy przechylenie jest wybrane i na wierzchu jest stan `play`, a `setTilt(false)` po wyjściu z etapu.
- W menu czujnik jest wyłączony, a ekran obraca się jak zwykle. Gracz ustawia telefon tak, jak chce grać, i dopiero wtedy wchodzi do etapu.
- Pauza należy do etapu, więc w pauzie obrót dalej jest zablokowany. Gra pauzuje się sama, gdy przechodzi w tło, więc po powrocie obrót jest dalej zablokowany, a gracz wraca do menu pauzy.
- Kalibracja na starcie etapu i na „GO” zostaje bez zmian, więc pierwsze odczyty po włączeniu czujnika ustawiają poziom.

| Test | Co sprawdza |
|---|---|
| `tiltSteeringListensToTheSensorAndKeepsTheRotationInAStage` | przechylenie wybrane w menu: obrót wolny i brak odczytów; w etapie: obrót zablokowany i odczyty z czujnika, w tle brak odczytów, po powrocie znowu są (gra w pauzie); po wyjściu z etapu przez pauzę: obrót wolny i brak odczytów; po powrocie do gałki etap bez blokady |

## Krok 19: przegląd kodu aplikacji

Przegląd kodu Kotlin, Rust (`native/`) i konfiguracji pod kątem dobrych praktyk Androida, bez zmian w silniku.

Poprawione:
- **Awaria przy zakończeniu gry.** Anulowanie wyboru pliku przy pierwszym starcie i „Remove game data” kończą `android_main` przez zakończenie procesu. `std::process::exit` uruchamiał destruktory statyczne bibliotek, a jeden z nich zwalniał referencję JNI z cudzym `JNIEnv`. W wersji debug (CheckJNI) kończyło się to SIGABRT i raportem awarii. Teraz `_exit(0)` z bionic: proces kończy się od razu, bez raportu („exited cleanly (0)”). Wszystko, co trzeba zachować, jest wtedy już zapisane.
- **Reguła R8** (`rules.keep`) zachowywała tylko metody `void`, a `hasTiltSensor()` zwraca `boolean`. Dziś R8 i tak nie zmienia klas aplikacji (`packageScope` obejmuje tylko androidx i kotlin), ale reguła zachowuje teraz wszystkie publiczne metody `MainActivity`. Usunięte komentarze z szablonu.
- Usunięta nieużywana przestrzeń nazw `tools` w manifeście.

Bez zmian, bo są proste i zgodne z praktykami: jedna aktywność `GameActivity`, wywołania JNI w obie strony bez dodatkowych bibliotek, czujnik tylko w etapie i tylko na wierzchu, `OnBackPressedCallback` zależny od `nav_depth`, `dataExtractionRules`, `FLAG_KEEP_SCREEN_ON`, immersive przez `WindowInsetsControllerCompat`.
