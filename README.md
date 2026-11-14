# Niob — Wissenschaftlicher Rechner

Niob ist ein moderner, präziser und 100% offline-fähiger wissenschaftlicher Taschenrechner mit Material You (Material Design 3) Oberfläche.
Das Repository enthält sowohl die plattformübergreifende Rust-Desktop-Anwendung als auch die native Android-App (Android 12+ / API 31+).

## Features

- **Geteilte Tastatur & Ergonomie**:
  - Großes, haptisches Ziffernfeld (separat abgerundete Numpad-Karte)
  - 5 Schnelloperatoren rechts (`⌫`, `÷`, `×`, `+`, `-`)
  - Funktionstastatur mit Tabs für Trigonometrie (`sin`, `cos`, `tan`), Analysis (`Σ`, `∫`, `d/dx`), Algebra & Matrizen, Konstanten und Sonderfunktionen
- **Mathematische Darstellung**:
  - Echte vertikale Bruchdarstellung (Zähler über Nenner mit zentriertem Bruchstrich)
  - Korrekte mathematische Formelzeichen und Formatierung
  - Umschaltung zwischen exakter symbolischer Form und Dezimalwert (`> exakt`)
- **Leistungsfähiger Rechenkern**:
  - Symbolische Algebra und Ableitung
  - Numerische und analytische Integration
  - Vektor- und Matrixoperationen (Determinante, Inverse, Transponierte)
  - Umfassende physikalische und mathematische Konstanten
  - Zuverlässige Persistenz (Zustandswiederherstellung nach Neustart)

## Projektstruktur

```
├── app/
│   ├── android/            # Native Android-App (Java, Material Design 3, dynamic colors)
│   │   ├── src/            # Android UI & Rechenkern (MainActivity, CalculatorEngine)
│   │   ├── res/            # Layouts, Styles, Farben, Strings
│   │   ├── tests/          # JVM-basierte Regressionstests für Android
│   │   ├── AndroidManifest.xml
│   │   └── build-apk.ps1   # Standalone Build-Skript (aapt2, javac, d8, zipalign, apksigner)
│   ├── src/                # Rust-Desktop-App (Egui / Eframe)
│   ├── tests/              # Rust-Unit- & Integrationstests sowie gemeinsame Testdaten (regressions.tsv)
│   ├── Cargo.toml          # Rust Crate Konfiguration (niob)
│   └── Cargo.lock
├── test.ps1 / test.bat     # Test-Runner für alle Rust- und Android-JVM-Tests
├── run-android.ps1 / .bat  # Android Build-, Install- und Start-Skript
└── TESTING.md              # Dokumentation der Test-Suite und Ausführungsoptionen
```

## Tests ausführen

Der folgende Befehl führt alle Rust- und Android-JVM-Tests aus.

```powershell
.\test.ps1
```

Oder unter Windows cmd:

```cmd
test.bat
```

Gezielte Tests ausführen (z. B. nur Android oder Filter auf bestimmte Operationen):

```powershell
# Nur Android JVM Tests
.\test.ps1 -Target Android

# Nur Rust Tests
.\test.ps1 -Target Rust

# Spezifischer Testfilter (z. B. Ableitungen oder Trigonometrie)
.\test.ps1 -Filter derivative
.\test.ps1 -Filter trig
```

Weitere Details zur Testarchitektur und den Testsuiten stehen in [TESTING.md](TESTING.md).

## Android-App bauen

Um die Android APK direkt mit den Android SDK Build-Tools zu kompilieren und zu signieren:

```powershell
powershell -ExecutionPolicy Bypass -File .\app\android\build-apk.ps1
```

Oder direkt auf einem verbundenen Gerät / Emulator installieren und starten:

```powershell
.\run-android.ps1
```
