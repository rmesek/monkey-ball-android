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
| 2026-11-02 | Projekt w Android Studio z silnikiem gry jako submodułem, budowanym z poziomu Gradle; aplikacja z aktywnością `GameActivity` |
| 2026-11-02 | Pierwsze uruchomienie na emulatorze: grafika przez Vulkan (OpenGL ES w razie problemów), wczytanie danych gry z obrazu płyty, sterowanie dotykiem, dźwięk |
| 2026-11-02 | Testy automatyczne uruchamiane z Android Studio; poprawki działania na Androidzie (paski systemowe, przycisk wstecz, powrót z tła, ponowne otwarcie) i pomiar wydajności |
