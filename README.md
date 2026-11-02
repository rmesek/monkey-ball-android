# Port silnika gry 3D na system Android

Celem pracy jest zbadanie, jak w praktyce przebiega proces przenoszenia aplikacji na platformę mobilną, na
przykładzie projektu w języku Rust wykorzystującego bibliotekę [wgpu](https://wgpu.rs/) jako wieloplatformowe
API graficzne wspierające m.in. Vulkan oraz WebAssembly.

## Wymagania

Obowiązkowe:

- Zbudowanie aplikacji w formie pakietu APK możliwego do zainstalowania na telefonie.
- Uruchomienie gry natywnie przez API Vulkan, a w razie problemów w komponencie WebView.
- Wczytanie danych gry z obrazu płyty wybranego z pamięci telefonu.
- Obsługa sterowania dotykiem na ekranie telefonu.
- Odtwarzanie dźwięków i muzyki z gry.

Opcjonalne:

- Uruchomienie gry zarówno natywnie przez API Vulkan, jak i w komponencie WebView.
- Porównanie możliwości i wydajności obu sposobów uruchomienia.
- Integracja żyroskopu jako alternatywnej opcji sterowania w grze.
- Wykorzystanie systemu plików Androida do przechowywania danych z obrazu gry.
- Wykorzystanie systemu plików Androida do zapisu stanu rozgrywki.

## Postęp

| Data | Postęp |
|---|---|
| 2026-10-07 | Rozpoczęcie prac nad silnikiem gry `monkey-ball-rs` |
| 2026-10-26 | Uzupełnienie README |
| 2026-11-02 | Utworzenie projektu w Android Studio i dołączenie silnika gry jako submodułu |
| 2026-11-02 | Budowanie silnika z poziomu Gradle i pierwsze APK z aktywnością `GameActivity` |
| 2026-11-02 | Pierwsze uruchomienie na emulatorze; poprawka wyboru API graficznego (Vulkan, a w razie problemów OpenGL ES) |
| 2026-11-02 | Wczytanie danych gry z obrazu płyty i pierwsza rozgrywka na emulatorze |
| 2026-11-02 | Dopasowanie interfejsu gry do pasków systemowych i wycięcia ekranu |
| 2026-11-02 | Testy automatyczne uruchamiane z Android Studio; systemowy przycisk wstecz, tryb pełnoekranowy, kopia zapasowa |
| 2026-11-02 | Testy powrotu z tła i wyboru złego pliku; sprawdzenie usuwania danych gry |
| 2026-11-02 | Pełna głośność dźwięku na telefonie; pomiar wydajności na emulatorze (60 FPS przez Vulkan) |
| 2026-11-02 | Naprawa „bladego” obrazu po powrocie do gry (podświetlenie fokusu Androida) |
| 2026-11-02 | Naprawa zawieszenia aplikacji po wyjściu i ponownym otwarciu |
| 2026-11-02 | Krótkie wyjaśnienie przed wyborem pliku z grą i powód odrzucenia złego pliku |
