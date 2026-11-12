# Code-Smell-Review für Niob

Prüfstand vom 12. November 2026. Untersucht wurden der lokale Android-Arbeitsstand, die zuletzt geänderte Formeleingabe und der Plotter. Ergänzende Gegenproben liefen gegen den Rust-Rechenkern der Desktop-App. Der reguläre Lauf mit `.	est.ps1` bestand mit **187 von 187 Tests** (87 Rust, 100 Android/JVM). Die zusätzlichen Gegenproben und die vorgeschlagenen Abnahmetests stehen bei den jeweiligen Befunden.

## Hohe Priorität

### 1. Unterschiedliche Ergebnisse für zwei Modulo-Schreibweisen

**Befund.** Auf Android ergibt `9007199254740993 mod 2` den Wert `0`, während `mod(9007199254740993,2)` korrekt `1` ergibt. Der Infix-Zweig rechnet mit `double`. Bei dieser Größe lässt sich die ungerade Ganzzahl darin nicht mehr exakt darstellen. Die Funktionsschreibweise verwendet für ganzzahlige Operanden bereits `BigInteger`. Im Rust-Kern ergaben beide Schreibweisen in der Gegenprobe `0`. [Android-Infixzweig](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L621) · [Android-Funktionszweig](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L1138) · [Rust-Modulo](app/src/engine.rs#L3000)

**Vorgeschlagene Lösung.** Beide Schreibweisen pro Plattform auf dieselbe Operation führen. Ganze Operanden im exakten Zahlentyp halten und den Gleitkommapfad auf zulässige reelle Operanden beschränken. Null als Divisor und die Option für einen stets nichtnegativen Rest zentral behandeln.

**Abnahme.** In beiden Schreibweisen und auf beiden Plattformen muss der Beispielausdruck `1` liefern. Zusätzlich negative Operanden in beiden Modulo-Einstellungen, einen gebrochenen Operanden und Division durch null prüfen. Die erwarteten Werte in die [gemeinsamen Regressionstestdaten](app/tests/data/regressions.tsv) aufnehmen.

### 2. Der Exakt-Modus macht aus einer Näherung einen anderen Wert

**Befund.** `sqrt(2)+1e-10` erscheint im Exakt-Modus als `√2`. Die Formatierung erkennt Wurzeln anhand der Nähe eines Gleitkommawerts zu bekannten Werten. Beim Antippen eines Verlaufsergebnisses wird der angezeigte Exakt-Text in die nächste Rechnung eingesetzt. Dadurch kann die Näherung auch spätere Berechnungen verändern. [Exakt-Formatierung](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L2224) · [Übernahme aus dem Verlauf](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L1163)

**Vorgeschlagene Lösung.** Näherungswerte als solche kennzeichnen. Eine symbolische Exaktdarstellung nur aus dem ursprünglichen Ausdruck, aus nachweislich äquivalenten Umformungen oder aus einem exakten Zahlentyp erzeugen. Für neue Verlaufseinträge den übernehmbaren Wert bzw. Ausdruck separat vom Anzeigetext speichern. Alte Verlaufseinträge mit vorhandenem Originalausdruck anhand dieses Ausdrucks prüfen. Eine erneute Auswertung bei der Übernahme kommt nur infrage, wenn sie denselben Wert mit den damaligen Variablen- und Winkeleinstellungen reproduziert.

**Abnahme.** `sqrt(2)+1e-10` behält bei Anzeige und Weiterrechnung seine Abweichung von `√2`. `sqrt(2)` und `1/3` bleiben bei symbolisch bzw. rational exakt darstellbaren Eingaben exakt. Den übernommenen Wert intern prüfen, da die sichtbare Dezimalanzeige runden kann.

### 3. Zurückgesetzte Variablen kehren nach dem Neustart zurück

**Befund.** Im Emulator wurde `a` auf `42` gesetzt und über **Konstanten → Benutzerdefinierte Variablen → a → Variable zurücksetzen** gelöscht. Der Dialog zeigte zunächst „nicht gesetzt“, nach dem App-Neustart wieder `42`. `saveState()` schreibt die noch vorhandenen Variablen. Der alte Schlüssel `var_a` bleibt dabei gespeichert und wird beim Start wieder geladen. [Speicherung](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L199) · [Wiederherstellung](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L134) · [Standardwerte](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L293)

**Vorgeschlagene Lösung.** Einen explizit ungesetzten Zustand speichern und beim Laden auf den Rechenkern anwenden. Ein fehlender Speichereintrag steht für den Ausgangszustand. Das ist bei `a`, `b` und `x` relevant, weil der Rechenkern ihnen beim Start Standardwerte gibt. Beim Zurücksetzen muss der gespeicherte Wert durch den ungesetzten Zustand ersetzt werden.

**Abnahme.** Eine Variable mit Standardwert und eine Variable ohne Standardwert setzen, zurücksetzen und die App vollständig neu starten. Beide müssen danach „nicht gesetzt“ anzeigen. Erneutes Setzen und ein weiterer Neustart müssen den neuen Wert erhalten.

## Mittlere Priorität

### 4. Numerik, Symbolik und Formeleditor interpretieren Eingaben unterschiedlich

**Befund.** `d(1e-3*x,x)` ergibt `0,001`. `d(1e - 3*x,x)` scheitert, obwohl der numerische Pfad `1e - 3*x` als wissenschaftliche Schreibweise verarbeitet. Numerische Auswertung und symbolischer Parser verwenden unterschiedliche Normalisierung. Der Formeleditor parst Eingaben für die Darstellung erneut und erzeugt daraus wieder Text für die Auswertung. [Normalisierung](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L552) · [AST-Parser](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L1932) · [Formeleditor](app/android/src/com/niobcalculator/materialyou/FormulaEditor.java#L356)

**Vorgeschlagene Lösung.** Zuerst die Normalisierung wissenschaftlicher Zahlen zwischen den Pfaden vereinheitlichen. Danach Parser und Ausdrucksbaum für Berechnung, Symbolik und Formeldarstellung zusammenführen. Der Editor kann leere Felder und Cursorpositionen separat verwalten und vollständige Ausdrücke an den Parser übergeben.

**Abnahme.** Beide Ableitungsschreibweisen liefern `0,001`. Beim Umwandeln eines linearen Ausdrucks in eine mathematische Darstellung und beim Wiederöffnen gespeicherter Formeln bleiben Vorrang und Auswertung gleich. Als Gegenproben eignen sich `-x^2`, `(x+1)^2/(2*x)` und `log(2,x^2/4)`.

### 5. Eine Definitionslücke wird im Plotter als Formel-Fehler angezeigt

**Befund.** Der Plotter markiert `sqrt(x-2)` als „Ungültiger Ausdruck“, weil bei der Probe `x = 1` kein reeller Wert vorliegt. Die Formel ist syntaktisch gültig und der Graph zeigt beispielsweise den Punkt `(3, 1)`. [Formelvalidierung](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L1793) · [Kurvenberechnung](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L2012)

**Vorgeschlagene Lösung.** Die Syntax beim Ändern der Formel prüfen. Bei der Kurvenberechnung Definitionslücken als Lücken im Graphen behandeln. Syntaxfehler und unbekannte Namen mit einer passenden Meldung am Eingabefeld anzeigen.

**Abnahme.** `sqrt(x-2)` wird ohne Fehlermarkierung für `x ≥ 2` gezeichnet. `1/(x-1)` hat bei `x = 1` eine Unterbrechung. Eine abgeschlossene Eingabe mit unzulässigen Zeichen oder unbekannter Funktion zeigt einen Fehler.

### 6. Plotten und Speichern verursachen wiederholte Arbeit im UI-Pfad

**Befund.** `onDraw()` berechnet pro aktiver Formel alle drei Pixel einen Kurvenpunkt. Jeder Aufruf von `evaluateForX()` legt einen Variablen-Override an und parst die Formel erneut. Die Änderungsrückrufe des Editors rufen außerdem `saveState()` auf, das bei jeder Eingabe den gesamten Verlauf durchläuft. Der Aufwand wächst damit mit der Breite des Graphen, der Komplexität der Formel und der Länge des Verlaufs. Der Befund beruht auf den Aufrufpfaden im Code. [Zeichenpfad](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L2012) · [Auswertung pro Punkt](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L175) · [Speicherung](app/android/src/com/niobcalculator/materialyou/MainActivity.java#L185)

**Vorgeschlagene Lösung.** Ausdrücke einmal pro Formeländerung parsen und für die Stützstellen wiederverwenden. Aufwendige Kurvenberechnungen im Hintergrund mit einem konsistenten Stand von Variablen, Winkelmodus und Ausschnitt durchführen. Veraltete Berechnungen nach Änderungen verwerfen. Entwurf, Einstellungen und Verlauf getrennt speichern. Den Entwurf bei Eingaben gebündelt sichern und beim Verlassen der App den letzten Stand übernehmen.

**Abnahme.** Parseraufrufe pro Formeländerung und Schreibvorgänge pro Verlaufsänderung zählen. Mit längerem Verlauf und einer aufwendigen Formel Eingabe, Zoomen und Verschieben messen. Zusätzlich Neustart und Wechsel der App während einer Eingabe prüfen.

## Abweichung zwischen Android und Desktop

`nthroot(3,-8)` ergibt auf Android `−2` und auf dem Desktop einen komplexen Wert. Android behandelt ungerade Wurzelindizes bei negativen reellen Radikanden ausdrücklich. Rust verwendet die allgemeine komplexe Potenz. Für die wissenschaftliche Wurzeltaste die gewünschte reelle Semantik festlegen und mit denselben Sollwerten auf beiden Plattformen absichern. Geeignete Fälle sind `nthroot(3,-8) = -2`, `nthroot(3,8) = 2`, ein gerader Index bei negativem Radikanden und der unzulässige Index null. [Android-Wurzel](app/android/src/com/niobcalculator/materialyou/CalculatorEngine.java#L1161) · [Rust-Wurzel](app/src/engine.rs#L2965)

## Umsetzung und Nachweise

1. Die Gegenproben für Modulo, Exakt-Anzeige und Variablenreset als Regressionstests festhalten und diese drei Befunde zuerst beheben.
2. Syntax und Definitionslücken im Plotter trennen. Die Wurzelsemantik gemeinsam für Android und Desktop testen.
3. Die Parserpfade vereinheitlichen und anschließend den Zeichen- und Speicheraufwand messen und reduzieren.

Die folgenden Nachweise liegen lokal im von Git ignorierten Testordner. Die Reproduktionsschritte und Sollwerte stehen in diesem Bericht.

- [Ergebnis des regulären Testlaufs](.test-results/code-smell-review-20261112-183003-303/core-results.json)
- [Android-Gegenproben](.test-results/code-smell-review-20261112-183003-303/engine-probes.txt) und [Rust-Gegenproben](.test-results/code-smell-review-20261112-183003-303/desktop-probes.txt)
- Variablenreset [vor](.test-results/code-smell-review-20261112-183003-303/variable-reset-before-restart.png) und [nach dem Neustart](.test-results/code-smell-review-20261112-183003-303/variable-reset-after-restart.png)
- [Fehlermarkierung im Plotter bei gezeichneter Kurve](.test-results/code-smell-review-20261112-183003-303/valid-plot-marked-invalid.png)
