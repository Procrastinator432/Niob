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

Aktueller Prüfstand vom 10.11.2026 nach dem letzten Fix: **Rust 87/87 und Android 89/89 bestanden, insgesamt 176/176, Exitcode 0**. Alle sechs Persistenztests bestehen; kleine und fast ganzzahlige Gleitkommazahlen bleiben bei der Wiederherstellung unverändert. Android kompiliert und die APK entspricht dem aktuellen Quellstand. Prüfgrenzen und Protokolle stehen in [PRUEFSTAND.md](PRUEFSTAND.md).

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

Die gemeinsame Tabelle `app/tests/data/regressions.tsv` enthält 61 Fälle aus Bewertung und Nachprüfung. Sie beschreibt **korrektes Sollverhalten**, nicht die früher beobachteten falschen Werte. Android und Rust verwenden dieselben Sollwerte, mit getrennten Eingabeausdrücken für notwendige Syntax-Aliase.

Zahlentests prüfen sowohl den internen Wert als auch die sichtbare Dezimalausgabe. Für kleine Zahlen gilt eine relative Toleranz ohne absoluten Sockel: `6.6261e-34` darf dadurch nicht als `0` durchgehen. Exakttests erlauben dokumentierte Schreibvarianten, aber keine nur näherungsweise gleichen Brüche. Fehlerfälle müssen einen ausdrücklichen Rechenfehler liefern; `NaN` oder `Infinity` als erfolgreicher Wert reichen nicht aus.

Zusätzliche Tests prüfen unter anderem ANS, Vorschau ohne Zustandsänderung, komplexe Variablen, RAD/DEG, die Modulo-Einstellung und Plot-Eingaben. Rust prüft außerdem die Vollständigkeit der gemeinsamen Fallliste.

Die Erweiterung umfasst große exakte ANS-Werte, komplexe ANS-Werte, Matrixergebnisse als ANS, zugewiesene Variablen, atomare fehlerhafte Zuweisungen sowie nicht reelle Plotpunkte. Deterministische Familien prüfen 17 Ganzzahlverschiebungen, 11 Bruchidentitäten und 6 Polynomintegrale pro Implementierung. Eine solche Familie zählt als ein Test im Bericht. Java prüft nun ebenfalls das Umschalten der Modulo-Einstellung. Offensichtliche Java-Programmierfehler werden nicht als erwarteter Rechenfehler akzeptiert.

Baseline nach Einbau der Tests, vor mathematischen Fixes:

| Ziel | Bestanden | Fehlgeschlagen | Gesamt |
|---|---:|---:|---:|
| Rust, einschließlich der vier vorhandenen Tests | 34 | 19 | 53 |
| Android-Rechenkern | 15 | 33 | 48 |

Die Tests decken Rechenkern und Zahlenausgabe ab. Sie ersetzen keine Tests für Android-Oberfläche, Persistenz, Activity-Neustart oder Bedienhilfen. Der komplette Testprozess wird bei Überschreiten des Zeitlimits beendet; damit kann ein später eingeführter Endloslauf den Aufruf nicht unbegrenzt blockieren.

Aktueller Stand der Regressionstests: **Rust 87/87 und Android 89/89 bestanden, insgesamt 176/176, Exitcode 0**. Alle sechs Persistenztests bestehen; kleine und fast ganzzahlige Gleitkommazahlen bleiben bei der Wiederherstellung unverändert.

## Wo die Fixes hingehören

- Android: `app/android/src/com/jadecalculator/materialyou/CalculatorEngine.java`. Die Activity und die JVM-Tests verwenden direkt diese Klasse. Der vorhandene APK-Build sammelt sie automatisch zusammen mit den übrigen Produktionsquellen ein. Es wird kein Rechenkern aus Quelltextausschnitten nachgebaut.
- Rust: `app/src/engine.rs`, gegebenenfalls `app/src/constants.rs`. `app/tests/regressions.rs` bindet die tatsächlichen Produktionsmodule ein und wird von `cargo test` automatisch gefunden.
- Android-Testadapter: `app/android/tests/com/jadecalculator/materialyou/CalculatorRegressionTest.java`. Testquellen liegen außerhalb von `android/src` und gelangen nicht in die APK.
- Gemeinsame Sollwerte und Syntax-Aliase: `app/tests/data/regressions.tsv`.

Für einen neuen Fall eine Zeile in der Tabelle ergänzen und den Namen im Rust-`cases!`-Aufruf aufnehmen. Die Rust-Vollständigkeitsprüfung vergleicht Fallnamen und Anzahl automatisch mit dem Fixture; Java liest alle Zeilen ein und lehnt ein leeres Fixture oder doppelte Namen ab. Zusätzliche zulässige exakte Schreibweisen werden in der Sollwertspalte mit `|` getrennt. Sollwerte nur ändern, wenn sich die fachliche Anforderung ändert.

## Werkzeuge und Ergebnisse

Benötigt werden Windows PowerShell 5.1 oder PowerShell 7, Cargo/Rust und ein JDK ab Version 9. `java` und `javac` müssen über PATH oder `JAVA_HOME` erreichbar sein. Die Java-Tests werden für Java 8 kompiliert und brauchen weder Android SDK noch Emulator, Node oder zusätzliche Testbibliotheken. Cargo verwendet `--offline`; die vorhandenen Rust-Abhängigkeiten müssen bereits im lokalen Cache liegen.

Der gemeinsame Runner schreibt jeden Lauf in ein eigenes Verzeichnis unter `.test-results`:

- `latest.md`: Zusammenfassung und Links auf die Protokolle des letzten Laufs.
- `latest.json`: Maschinenlesbare Zusammenfassung mit Filter, Exitcodes und Zählerständen.
- Je Lauf `rust.log`, `android.log` und `android-compile.log`.
- `android-junit.xml`: JUnit-kompatibler XML-Bericht für IDEs oder CI.

Build-/Werkzeugfehler und Zeitüberschreitungen ergeben ebenfalls einen fehlgeschlagenen Gesamtlauf. Der Runner prüft die Programme nach jedem Aufruf und verwendet frische Java-Ausgabeverzeichnisse; alte `.class`-Dateien können einen fehlgeschlagenen Build deshalb nicht verdecken.


