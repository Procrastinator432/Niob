# Regressionstests ausführen

Im Projektordner:

```powershell
.\test.ps1
```

Alternativ in PowerShell oder cmd:

```bat
test.bat
```

Der Aufruf führt die Rust-Tests und die Tests des Android-Rechenkerns aus. Alle ausgewählten Tests müssen bestehen, damit der Befehl Exitcode 0 liefert. Fehler sind normale fehlgeschlagene Tests: Sie sind weder ignoriert noch als erwartete Fehlschläge versteckt.

Beim aktuellen Prüfstand vom 16.11.2026 bestanden **386 Tests** (183 Rust und 203 Android/JVM, 0 Fehler, inkl. `NIOB_GENERATED_ALL='1'` über alle 10 generierten Testsuiten mit 3 329 Fällen pro Kern). Die Ergebnisse der vorangegangenen Emulatorprüfungen stehen in der [Nachprüfung](CODE-SMELL-NACHPRUEFUNG.md); die Befunde und Nachprüfungen zu den Rechenkernen, Desktop-Persistenz und Release-Konfiguration sind in [BEWERTUNG-2026-11-15-2.md](BEWERTUNG-2026-11-15-2.md) und [BEWERTUNG-2026-11-15-4.md](BEWERTUNG-2026-11-15-4.md) dokumentiert.

## Einen Fix gezielt ausprobieren

```powershell
# Nur Android, nur die Ausgabe der Planck-Konstante
.\test.ps1 -Target Android -Filter planck

# Derselbe Test für beide Implementierungen
.\test.ps1 -Filter planck

# Alle Integral-Fälle für Rust
.\test.ps1 -Target Rust -Filter integral

# Höchstens 30 Sekunden pro Compiler-/Testprozess
.\test.ps1 -Target Android -TimeoutSeconds 30

# Neue Persistenz-Gegenproben (ANS und Variablen)
.\test.ps1 -Target Android -Filter persistence

# Nur die generierten Matrixfälle
.\test.ps1 -Filter generated_matrices
```

`-Filter` sucht nach einem Teil des Testnamens und unterscheidet Groß-/Kleinschreibung. Ein Filter ohne Treffer liefert einen Fehler statt eines scheinbar erfolgreichen Laufs. Für einen bereits grünen Kontrollfall eignet sich `-Filter basic`.

Rust ist zusätzlich direkt in den normalen Cargo-Testlauf eingebunden:

```powershell
cargo test --offline --manifest-path .\app\Cargo.toml
cargo test --offline --manifest-path .\app\Cargo.toml --test regressions false_exact -- --exact
```

Nach einem Fix zuerst den passenden Filter ausführen, anschließend den gesamten Testlauf. Der gemeinsame Aufruf prüft beide Implementierungen unabhängig voneinander, auch wenn eine fehlschlägt. Er baut oder installiert keine APK und verändert keine Gerätedaten.

Für den anschließenden Test der Android-App auf dem Gerät die APK wie bisher mit dem vorhandenen Buildskript neu erzeugen:

```powershell
.\app\android\build-apk.ps1
```

Das Buildskript benötigt zusätzlich das lokal konfigurierte Android SDK. Der Rechenkern-Test selbst braucht dieses SDK nicht; eine bereits vorhandene APK wird durch einen Testlauf nicht aktualisiert.

## Was getestet wird

Die gemeinsame Tabelle `app/tests/data/regressions.tsv` enthält 126 Fälle aus Bewertung und Nachprüfung. Sie beschreibt **korrektes Sollverhalten**, nicht die früher beobachteten falschen Werte. Android und Rust verwenden dieselben Sollwerte, mit getrennten Eingabeausdrücken für notwendige Syntax-Aliase.

Zahlentests prüfen sowohl den internen Wert als auch die sichtbare Dezimalausgabe. Für kleine Zahlen gilt eine relative Toleranz ohne absoluten Sockel: `6.6261e-34` darf dadurch nicht als `0` durchgehen. Exakttests erlauben dokumentierte Schreibvarianten, aber keine nur näherungsweise gleichen Brüche. Fehlerfälle müssen einen ausdrücklichen Rechenfehler liefern; `NaN` oder `Infinity` als erfolgreicher Wert reichen nicht aus.

Zusätzliche Tests prüfen unter anderem ANS, Vorschau ohne Zustandsänderung, komplexe Variablen, RAD/DEG, die Modulo-Einstellung und Plot-Eingaben. Rust prüft außerdem die Vollständigkeit der gemeinsamen Fallliste.

Die Erweiterung umfasst große exakte ANS-Werte, komplexe ANS-Werte, Matrixergebnisse als ANS, zugewiesene Variablen, atomare fehlerhafte Zuweisungen sowie nicht reelle Plotpunkte. Deterministische Familien prüfen 17 Ganzzahlverschiebungen, 11 Bruchidentitäten und 6 Polynomintegrale pro Implementierung. Eine solche Familie zählt als ein Test im Bericht. Java prüft nun ebenfalls das Umschalten der Modulo-Einstellung. Offensichtliche Java-Programmierfehler werden nicht als erwarteter Rechenfehler akzeptiert.

Baseline nach Einbau der Tests, vor mathematischen Fixes:

| Ziel | Bestanden | Fehlgeschlagen | Gesamt |
|---|---:|---:|---:|
| Rust, einschließlich der vier vorhandenen Tests | 34 | 19 | 53 |
| Android-Rechenkern | 15 | 33 | 48 |

Die Tests decken Rechenkern und Zahlenausgabe ab. Sie ersetzen keine Tests für Android-Oberfläche, Persistenz, Activity-Neustart oder Bedienhilfen. Der komplette Testprozess wird bei Überschreiten des Zeitlimits beendet; damit kann ein später eingeführter Endloslauf den Aufruf nicht unbegrenzt blockieren.

## Generierte Fälle

Zusätzlich zu den Einzelfällen prüfen zehn Tests je Implementierung zusammen rund 3 300 erzeugte Fälle, fast alle mit unabhängig berechneten Sollwerten:

| Test | Fälle | Inhalt |
|---|---:|---|
| `generated_derivatives` | 836 | Ableitungen von 36 Funktionen an Stellen von 10⁻⁹ bis 10⁶, nahe Polstellen sowie an Sprung- und Knickstellen |
| `generated_integrals` | 286 | Bestimmte Integrale mit geschlossener oder hochgenauer Lösung: schmale Spitzen, Schwingungen, Knicke, Endpunkt-Singularitäten, kurze Intervalle |
| `generated_matrices` | 600 | Inverse, Determinante, Produkt und A·inv(A) für 2×2- bis 4×4-Matrizen mit Einträgen von 10⁻²⁰ bis 10²⁰ |
| `generated_exact_numbers` | 400 | Ganzzahl- und Bruchrechnung bis 300 Stellen, einschließlich `mod` und Fakultät |
| `generated_antiderivatives` | 370 | Symbolische Stammfunktionen, RAD und DEG; Gegenprobe durch Ableiten an Stützstellen |
| `generated_polynomials` | 200 | Ausmultiplizieren von Produkten und Potenzen linearer Terme in `y`, `z`, `w` |
| `generated_integral_families` | 175 | Viele Knicke (`abs(sin(k*x))`), Knicke an nichtlinearen Stellen, Sprünge (`floor`, `ceil`, `round`, `sign(sin(k*x))`), Singularitäten innerhalb und am Rand, Spitzen in sechs Schreibweisen |
| `generated_derivative_families` | 268 | 18 weitere glatte Funktionen, Funktionen mit Knick oder Sprung, starke Auslöschung (`x-sin(x)` nahe 0) |
| `generated_determinants` | 134 | Ganzzahlige Matrizen mit großen Einträgen, Matrixpotenzen, Brüche, Einträge von 10⁻³⁰ bis 10³⁰, `det(A)*det(inv(A))` |
| `generated_integral_splits` | 60 | Selbstprüfung ohne Sollwert: `int(f,a,b)` gleich `int(f,a,m)+int(f,m,b)` im Rahmen der gemeldeten Fehler, für zufällig zusammengesetzte Integranden |

Die Fälle liegen in `app/tests/data/generated/`. Rust (`app/tests/generated.rs`) und Android (`GeneratedCases.java`) lesen dieselben Dateien; beide Rechenkerne werden also gegen dieselben Sollwerte geprüft. Die Sollwerte stammen nicht aus einem der beiden Kerne, sondern aus sympy, mpmath und exakter Python-Arithmetik. Für den Testlauf ist kein Python nötig.

Grundsatz: Ein korrektes Ergebnis oder eine ausdrückliche Fehlermeldung ist in Ordnung, ein falsches Ergebnis nicht. Wo Gleitkomma an seine Grenzen kommt (nahe Polstellen, starke Auslöschung), ist deshalb auch eine Fehlermeldung zulässig; bei Integralen zusätzlich ein Ergebnis, das als nicht konvergiert gekennzeichnet ist. Die Prüfungen sind in [app/tests/data/generated/README.md](app/tests/data/generated/README.md) beschrieben.

Jeder der zehn Tests meldet im Fehlerfall die Anzahl falscher Fälle und die ersten 30 davon. Zulässige Fehlermeldungen und als nicht konvergiert gemeldete Integrale werden mitgezählt, schlagen aber nicht fehl. Alle Fehlschläge zeigt:

```powershell
$env:NIOB_GENERATED_ALL = '1'; .\test.ps1 -Filter generated_; Remove-Item Env:NIOB_GENERATED_ALL
```

Beim Einbau am 15.11.2026 schlugen diese Tests fehl (Rust 5 von 6, Android 3 von 6):

| Test | Rust falsch | Android falsch |
|---|---:|---:|
| `generated_derivatives` | 55 | 55 |
| `generated_integrals` | 8 | 8 |
| `generated_matrices` | 64 | 0 |
| `generated_exact_numbers` | 131 | 0 |
| `generated_antiderivatives` | 0 (56 nicht unterstützt) | 0 (56 nicht unterstützt) |
| `generated_polynomials` | 8 | 8 |

Beim Einbau der vier weiteren Tests (`integral_families` bis `integral_splits`) am 15.11.2026, nach der [vierten Nachprüfung](BEWERTUNG-2026-11-15-4.md), schlugen alle vier in beiden Kernen fehl:

| Test | Rust falsch | Android falsch |
|---|---:|---:|
| `generated_integral_families` | 46 (18 mit Fehlermeldung) | 46 (27 mit Fehlermeldung) |
| `generated_derivative_families` | 26 | 26 |
| `generated_determinants` | 1 | 14 |
| `generated_integral_splits` | 19 | 19 |

Nach Abschluss der Fixes in Rust und Android bestehen nun alle 10 generierten Suiten sowie die Regressionstests in beiden Rechenkernen mit **0 Fehlern**.

Die erzeugten Integrale brauchen in einem Rust-Debug-Build Zeit: Alle generierten Tests zusammen liefen auf einem Linux-Server in rund 80 Sekunden (Android-JVM rund 12 Sekunden). Das Standard-Zeitlimit von `test.ps1` beträgt deshalb 300 Sekunden.

Neue Fälle entstehen nicht von Hand, sondern im Generator `tools/generate_test_cases.py` (benötigt Python 3.9 oder neuer sowie `pip install sympy mpmath`):

```powershell
python .\tools\generate_test_cases.py
```

Die Zufallsfolgen sind fest; ein erneuter Aufruf erzeugt dieselben Dateien. Sollwerte ändern sich nur, wenn sich das Skript ändert.

## Wo die Fixes hingehören

- Android: `app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java`. Die Activity und die JVM-Tests verwenden direkt diese Klasse. Der vorhandene APK-Build sammelt sie automatisch zusammen mit den übrigen Produktionsquellen ein. Es wird kein Rechenkern aus Quelltextausschnitten nachgebaut.
- Rust: `app/src/engine.rs`, gegebenenfalls `app/src/constants.rs`. `app/tests/regressions.rs` bindet die tatsächlichen Produktionsmodule ein und wird von `cargo test` automatisch gefunden.
- Android-Testadapter: `app/android/tests/com/niobcalculator/materialyou/CalculatorRegressionTest.java`. Testquellen liegen außerhalb von `android/src` und gelangen nicht in die APK.
- Gemeinsame Sollwerte und Syntax-Aliase: `app/tests/data/regressions.tsv`.
- Generierte Fälle: `app/tests/data/generated/*.tsv`, erzeugt von `tools/generate_test_cases.py`; Testadapter `app/tests/generated.rs` und `app/android/tests/com/niobcalculator/materialyou/GeneratedCases.java`.

Für einen neuen Fall eine Zeile in der Tabelle ergänzen und den Namen im Rust-`cases!`-Aufruf aufnehmen. Die Rust-Vollständigkeitsprüfung vergleicht Fallnamen und Anzahl automatisch mit dem Fixture; Java liest alle Zeilen ein und lehnt ein leeres Fixture oder doppelte Namen ab. Zusätzliche zulässige exakte Schreibweisen werden in der Sollwertspalte mit `|` getrennt. Sollwerte nur ändern, wenn sich die fachliche Anforderung ändert.

## Werkzeuge und Ergebnisse

Benötigt werden Windows PowerShell 5.1 oder PowerShell 7, Cargo/Rust und ein JDK ab Version 9. `java` und `javac` müssen über PATH oder `JAVA_HOME` erreichbar sein. Die Java-Tests werden für Java 8 kompiliert und brauchen weder Android SDK noch Emulator, Node oder zusätzliche Testbibliotheken. Cargo verwendet `--offline`; die vorhandenen Rust-Abhängigkeiten müssen bereits im lokalen Cache liegen.

Der gemeinsame Runner schreibt jeden Lauf in ein eigenes Verzeichnis unter `.test-results`:

- `latest.md`: Zusammenfassung und Links auf die Protokolle des letzten Laufs.
- `latest.json`: Maschinenlesbare Zusammenfassung mit Filter, Exitcodes und Zählerständen.
- Je Lauf `rust.log`, `android.log` und `android-compile.log`.
- `android-junit.xml`: JUnit-kompatibler XML-Bericht für IDEs oder CI.

Build-/Werkzeugfehler und Zeitüberschreitungen ergeben ebenfalls einen fehlgeschlagenen Gesamtlauf. Der Runner prüft die Programme nach jedem Aufruf und verwendet frische Java-Ausgabeverzeichnisse; alte `.class`-Dateien können einen fehlgeschlagenen Build deshalb nicht verdecken.


