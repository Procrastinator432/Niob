# Nachprüfung des Code-Smell-Reviews

Prüfstand vom 12. November 2026 für Commit `5e033fd`. Die Android-APK ließ sich bauen. Die reguläre Suite bestand mit **194 von 194 Tests** (89 Rust, 105 Android/JVM). Zusätzlich wurden der vom Graphen verwendete Auswertungspfad und der Android-Neustart separat geprüft. [Ursprünglicher Review](CODE-SMELL-REVIEW.md) · [Testergebnisse](.test-results/code-smell-recheck-20261112-191742-357/core-results.json)

## Umsetzungsstand im Arbeitsbaum

Die fünf Punkte wurden umgesetzt. Die Befundabschnitte darunter dokumentieren den ursprünglichen Stand von `5e033fd` und die Abnahmekriterien. Der aktuelle Testlauf besteht mit **202 von 202 Tests** (91 Rust, 111 Android/JVM). [Aktueller Testlauf](.test-results/code-smell-finish-20261112-203544-843/core-results.json)

| Punkt | Umsetzung und Prüfung |
|---|---|
| Auswertung im Plotter | Android wertet vollständige numerische Ausdrücke im Rechner und Plotter über denselben Syntaxbaum aus. Modulo, Fakultät, `ANS`, gebundene Laufvariablen und Matrixzellen sind durch Gegenproben abgedeckt. Der Emulator zeichnet für das große Modulo den Punkt `(2, 3)`. [Graph](.test-results/code-smell-finish-20261112-203544-843/final-modulo-curve.png) |
| Bestätigte Rechnungen | Variablen, `ANS` und der neue Verlaufseintrag werden zusammen im Hintergrund gespeichert. Der Rechner zeigt den Eintrag nach erfolgreichem Speichern an. Nach dem sofortigen Prozessstopp wurde die Zuweisung samt `ANS` und Verlauf wiederhergestellt. Eine Versionsnummer verhindert, dass der zugehörige Entwurf erneut erscheint. [Neustart](.test-results/code-smell-finish-20261112-203544-843/final-commit-restored.png) |
| Exakte Anzeige und Übernahme | Symbolische Ausgaben folgen aus der Ausdrucksstruktur. Wiederverwendbare Werte liegen separat von den gerundeten Anzeigeformaten vor. Das gilt auch für Matrizen. Im Emulator behielt der aus dem Verlauf eingefügte Wert für `sqrt(2)+1e-15` nach Neustart den vollständigen Zahlenwert. [Wiederhergestellter Entwurf](.test-results/code-smell-finish-20261112-203544-843/reused-draft-restored.png) |
| Syntax und Definitionslücken | Der Plotter prüft den Ausdruck samt Funktionsnamen, Argumentanzahl und Variablenbindung vor dem Zeichnen. `√(x−2)` bleibt gültig. `1/(x-1)+foo(x)` wird als Fehler markiert. [Wurzelgraph](.test-results/code-smell-finish-20261112-203544-843/domain-root.png) · [Fehlermarkierung](.test-results/code-smell-finish-20261112-203544-843/final-syntax-error.png) |
| Zeichnen und Entwürfe | Der Plotter berechnet Stützstellen im Hintergrund mit einer Momentaufnahme des Rechenzustands und verwirft überholte Aufträge. Entwürfe werden gebündelt und separat vom Verlauf gespeichert. Nach Schwenken und Tabwechsel während einer aufwendigen Summe erschien die neu eingegebene Kurve. [Kurve nach dem Wechsel](.test-results/code-smell-finish-20261112-203544-843/latest-curve-after-cancellation.png) |

## Vor einer Freigabe beheben

### 1. Der Android-Plotter kann falsche Kurven zeichnen

**Befund.** Für `mod(9007199254740993,2)+x` ergibt die numerische Auswertung bei `x = 2` korrekt `3`. Im Emulator lag die Kurve bei `(2, 2)`. Der neue Plotter-Auswerter wandelt Funktionsargumente über `double` zurück in Text und verliert dabei exakte Ganzzahlen. Er wertet außerdem alle Argumente vor dem Funktionsaufruf aus. Dadurch scheitert beispielsweise `sum(k*x,k,1,3)` am noch ungebundenen `k`. Die Gegenproben für `ans*x`, `x!` und `defint(t,t,0,x)` scheiterten ebenfalls im kompilierten Pfad. [Plotter-Auswerter](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L206) · [Argumentumwandlung](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L260) · [Emulatorbild](.test-results/code-smell-recheck-20261112-191742-357/modulo-curve-shift.png)

**Vorgehen.** Kurzfristig die Optimierung auf nachweislich gleich ausgewertete Ausdrücke beschränken. Funktionsaufrufe und Sonderknoten mit ungeklärter Semantik über den bisherigen numerischen Pfad auswerten. Als dauerhafte Lösung Rechner und Plotter über denselben Syntaxbaum und dieselbe typisierte Auswertung führen. Ganzzahlen und Brüche behalten ihren Werttyp. Summen und Integrale binden ihre Laufvariable erst im eigenen Gültigkeitsbereich. Der Plotter setzt `x` für die jeweilige Stützstelle und liest `ans` sowie die Einstellungen aus demselben Rechenkontext.

**Abnahme.** Tests direkt gegen `compilePlotFunction()` ergänzen und die Ergebnisse mit analytischen Sollwerten prüfen. Bei `x = 2` muss das Modulo-Beispiel `3` und die Summe `12` liefern. Mit `ANS = 7` muss `ans*x` bei `x = 3` den Wert `21` liefern. `x!` ergibt bei `x = 3` den Wert `6`, `defint(t,t,0,x)` bei `x = 2` ungefähr `2`. Den Modulo-Punkt zusätzlich im Emulator im gezeichneten Graphen prüfen.

### 2. Bestätigte Zuweisungen können nach einem Prozessstopp verloren gehen

**Befund.** Im Emulator wurde zuerst `a = 43` gespeichert. Die anschließende Zuweisung `a:=67` im Hauptrechner erschien im Verlauf, und die Vorschau zeigte `ANS = 67`. Nach einem sofortigen Prozessstopp zeigte der Variablendialog wieder `a = 43`. Beim Bestätigen werden Verlauf und Entwurf geschrieben. Der Pfad für Variablen und `ANS` läuft bei einer gewöhnlichen Rechnerzuweisung erst über die Lebenszyklus-Callbacks. [Bestätigung im Rechner](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L1800) · [Speicherung im Lebenszyklus](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L266) · [Vor dem Neustart](.test-results/code-smell-recheck-20261112-191742-357/calculator-assignment-committed.png) · [Nach dem Neustart](.test-results/code-smell-recheck-20261112-191742-357/calculator-assignment-after-restart.png)

**Vorgehen.** Beim Bestätigen einer Rechnung die geänderten Variablen, `ANS` und den neuen Verlaufseintrag als zusammengehörigen Rechenzustand sichern. Für die bestehende Speicherung kann ein gemeinsamer Schreibvorgang die Daten in denselben Stand bringen. Falls die Bestätigung eine dauerhafte Speicherung zusagt, den erfolgreichen Abschluss des Schreibens vor der Bestätigung abwarten und den Schreibvorgang außerhalb des UI-Threads ausführen. Die getrennte Speicherung beim Tippen im Entwurf beibehalten.

**Abnahme.** `a:=67` im Hauptrechner bestätigen und den Prozess im Vordergrund sofort stoppen. Nach dem Neustart müssen Variable, `ANS` und Verlauf den bestätigten Wert enthalten. Den Test auch mit einer gewöhnlichen Rechnung, einer abgewiesenen Zuweisung und nach einem Wechsel in den Hintergrund durchführen. Die bereits bestandenen Reset-Tests für Variablen mit und ohne Standardwert erneut ausführen.

## Weitere offene Befunde

### 3. Exakte Darstellung beruht weiterhin auf Zahlennähe

**Befund.** Android zeigt `sqrt(2)+1e-10` jetzt als Dezimalwert an, aber `sqrt(2)+1e-15` weiterhin als `√2`. Rust gibt schon für `sqrt(2)+1e-10` den Exakt-Text `√2` aus. Android übernimmt beim Antippen im Verlauf den Exakt-Anzeigetext. Damit kann sowohl die symbolische Erkennung als auch eine gerundete Dezimalanzeige den Wert der Folgerechnung verändern. [Android-Formatierung](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L2330) · [Rust-Formatierung](app/src/engine.rs#L964) · [Übernahme aus dem Verlauf](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L1210)

**Vorgehen.** Die Exaktheit aus dem Ergebnis oder aus einer nachweislich gleichwertigen symbolischen Umformung ableiten. Den gespeicherten Wert oder einen verlustfrei wieder einlesbaren Ausdruck separat vom Anzeigetext führen. Bei alten Verlaufseinträgen die verfügbare Genauigkeit erhalten und bei einer möglichen Neuauswertung den damaligen Rechenkontext berücksichtigen.

**Abnahme.** Beide Abweichungen von `√2` über Anzeige, Verlauf, Neustart und Ergebnisübernahme prüfen. Der übernommene Wert muss dieselbe Genauigkeit wie der ursprünglich berechnete Wert haben. `sqrt(2)` und `1/3` dienen als Gegenproben für symbolisch und rational exakt darstellbare Ergebnisse.

### 4. Die Plotter-Validierung wertet weiterhin eine einzelne Stützstelle aus

**Befund.** `sqrt(x-2)` wird jetzt ohne rote Fehlermarkierung gezeichnet. Für `1/(x-1)+foo(x)` akzeptiert die Prüfung den Ausdruck, weil bei `x = 1` die Division durch null zuerst auftritt. Der unbekannte Funktionsname wird dadurch verdeckt. Das ließ sich im Auswertungstest und im Emulator nachvollziehen. [Validierung](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L196) · [Emulatorbild](.test-results/code-smell-recheck-20261112-191742-357/hidden-unknown-function.png)

**Vorgehen.** Den vollständigen Ausdruck parsen und Funktionsnamen, Anzahl der Argumente und Variablenbindungen vor der Auswertung prüfen. Fehler beim Auswerten einzelner Stützstellen als Definitionslücken behandeln. Für diese Prüfung dieselben Regeln wie im ausführenden Plotter-Pfad verwenden.

**Abnahme.** `sqrt(x-2)` und `1/(x-1)` sind gültig und zeigen die jeweiligen Lücken. `1/(x-1)+foo(x)` zeigt einen Fehler für die unbekannte Funktion. Fehlerhafte Klammerung und falsche Argumentanzahl werden ebenfalls erkannt.

### 5. Der Zeichenpfad berechnet Stützstellen weiterhin auf dem UI-Thread

**Befund.** Beim Tippen speichert `saveDraft()` den Entwurf getrennt vom Verlauf. Der Graph berechnet seine Stützstellen weiterhin in `onDraw()`. Bei Funktionsargumenten entsteht zusätzlicher Aufwand, weil der Auswerter sie in Text umwandelt und wieder einliest. Wenn der AST-Parser keinen Baum liefert, wird der gesamte Ausdruck pro Stützstelle über den numerischen Pfad geparst. Dieser Befund folgt aus dem Aufrufpfad. Laufzeiten wurden bei dieser Nachprüfung nicht gemessen. [Entwurfspeicherung](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L191) · [Zeichenpfad](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L2075) · [Auswertung und Ausweichpfad](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L206)

**Vorgehen.** Nach der Korrektur der Auswertungssemantik einmal pro Formeländerung parsen und Funktionsargumente typisiert weitergeben. Kurvenpunkte bei Bedarf im Hintergrund aus einem konsistenten Stand von Formel, Variablen, Winkelmodus und Ausschnitt berechnen. Bei neuen Änderungen ältere Berechnungen verwerfen. Im Zeichenpfad die zuletzt passenden Kurvenpunkte darstellen.

**Abnahme.** Parseraufrufe und Dauer der Kurvenberechnung bei Eingabe, Zoom und Verschieben erfassen. Mit aufwendigen Formeln und längerem Verlauf prüfen, ob die Oberfläche bedienbar bleibt und immer die jüngste gültige Eingabe dargestellt wird. Beim Tippen darf die Zahl der Verlaufsschreibvorgänge nicht steigen.

## Bestätigte Verbesserungen

- Beide Modulo-Schreibweisen liefern im Hauptrechner und im Rust-Kern für `9007199254740993` modulo `2` jetzt `1`. Auch negative und gebrochene Operanden sowie der Fehler bei Modulo durch null wurden geprüft.
- `nthroot(3,-8)` ergibt auf Android und Desktop `−2`. Beide Kerne weisen den Wurzelindex null zurück.
- Android liefert bei `d(1e-3*x,x)` und `d(1e - 3*x,x)` jeweils `0,001`. Die entsprechende Rust-Gegenprobe mit Leerzeichen schlägt noch fehl.
- Im Emulator blieben `a` und `z` nach dem Zurücksetzen und einem Neustart ungesetzt. Eine anschließende direkte Zuweisung über den Variablendialog blieb gespeichert.
- `sqrt(x-2)` wurde im Emulator ohne Fehlermarkierung gezeichnet. Der Kurvenpunkt `(3, 1)` wurde im Screenshot bestätigt.

## Empfohlene Reihenfolge

1. Zuerst die Plotter-Semantik und die Persistenz bestätigter Rechnungen absichern. Die Gegenproben auf den tatsächlichen Zeichenpfad und den Prozessneustart anwenden.
2. Danach Exaktheit und Übernahme aus dem Verlauf korrigieren sowie die Formelvalidierung von der Stützstellenauswertung trennen.
3. Anschließend den gemeinsamen Auswertungspfad weiter vereinheitlichen und den Aufwand für Zeichnen und Speichern anhand von Messungen reduzieren.

Die Protokolle und Screenshots liegen im lokalen, von Git ignorierten Testordner. Die Testfälle und Sollwerte sind oben ausgeschrieben. [Android-Gegenproben](.test-results/code-smell-recheck-20261112-191742-357/android-recheck.txt) · [Rust-Gegenproben](.test-results/code-smell-recheck-20261112-191742-357/rust-recheck.txt) · [Variablenreset im Emulator](.test-results/code-smell-recheck-20261112-191742-357/unset-after-restart-a.png) · [Wurzelgraph im Emulator](.test-results/code-smell-recheck-20261112-191742-357/root-no-error.png)
