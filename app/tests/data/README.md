# Sollwerte und Herkunft

`regressions.tsv` ist die gemeinsame, offline nutzbare Testgrundlage für Rust und Android. Die sieben durch Tabulator getrennten Spalten sind:

1. Eindeutiger Testname.
2. Ausdruck für Android.
3. Ausdruck für Rust.
4. Prüfung: `number`, `exact`, `error`, `assignment` oder `symbolic_samples`.
5. Erwarteter Wert; bei `exact` sind mit `|` getrennte Schreibvarianten erlaubt.
6. Absolute Toleranz.
7. Relative Toleranz.

Numerische Vergleiche verwenden `absolute_tolerance + relative_tolerance * abs(expected)`. Bei erwarteter Null schützt eine absolute Toleranz gegen Rundungsrauschen. Bei sehr kleinen, von null verschiedenen Größen ist die absolute Toleranz null. Nicht endliche Werte werden immer abgelehnt.

Die Fallliste stammt aus dem lokalen Vergleich mit der ursprünglichen Referenz-APK, SHA-256 `04ad4f142defa7cc70b011ffc79f43ebf6dd46ca2d52d90a24f690d68bb6e4d3`. Originalausgaben stehen im historischen `checks/audit-2026-11-09/original-results.tsv`; Bytecode-/Ressourcenbefunde in den benachbarten Prüfdateien. Die Tests lesen diese Dateien nicht zur Laufzeit. Es besteht keine Abhängigkeit vom Downloads-Ordner oder einer fremden Installation.

Die Sollwerte wurden fachlich festgelegt: beispielsweise exakte Ganzzahl-/Bruchrechnung, bekannte Werte der Hyperbelfunktionen, Ableitung `cos(1000000)` und Integral `cos(800*pi*x)` über [0,1] = 0. Die auf sechs Stellen begrenzte Dezimalausgabe der Originalbibliothek wird nicht als Genauigkeitsgrenze des reparierten Rechners übernommen.

Für Fakultäten gilt hier entsprechend dem Original-Fehlervertrag: Der Operand muss eine natürliche Zahl einschließlich null sein; `0.5!` und `(-3)!` sind Fehler. Eine Gamma-Funktion kann separat angeboten werden. `NaN`/`Infinity` sind keine Ersatz-Fehlermeldungen.

Der frühere Fall `trailing_plot_garbage` ist hier korrekt als `symbolic_unknown` benannt: `x^2+foo` ist ein gültiger symbolischer Ausdruck. Wirklich ungültige Restzeichen wie `x^2)` und `2+3]` werden separat im Plotpfad geprüft.

Die Prüfung `assignment` führt zunächst die Zuweisung aus und prüft danach `m*4`. Alle Tabellenfälle starten mit einem neuen Rechenkern, damit Reihenfolge und frühere Zuweisungen die Ergebnisse nicht beeinflussen.

Die Tabelle enthält jetzt 61 Fälle. Neue Referenzen: Für jede der Frequenzen 1000, 2000 und 4000 ist das Integral von `cos(n*pi*x)` über [0,1] null. Die Potenz -1 hat die Stammfunktion `log(x)` für x > 0; im reellen Bereich gilt allgemeiner `log(abs(x))`. Stammfunktionen werden bis auf eine additive Konstante verglichen; die erwartete Ausgabe enthält diese Konstante nicht. Symbolische Sinus-Ableitung, Kettenregel und Exponential-Stammfunktion werden ausdrücklich geprüft. `sin(i)/i = sinh(1)` und `exp(i*pi) = -1` prüfen komplexe Funktionsauswertung.

Zusätzliche Zustands- und Familientests sind direkt in den beiden Testadaptern registriert. Ihre kleinen Ganzzahl-Sollwerte stammen aus unabhängigen Formeln, etwa `((a/7)+(c/13))*91 = 13*a+7*c`. Dies vermeidet eine zweite Kopie des produktiven Bruchalgorithmus als vermeintliche Referenz. Die Testfamilien laufen deterministisch; es ist keine zusätzliche Testbibliothek erforderlich.

Die Prüfung `symbolic_samples` berechnet zunächst den symbolischen Ausdruck und wertet dessen ausgegebenen Text an mehreren x-Stellen erneut aus. Die Sollwertspalte enthält `x:Wert;x:Wert`; Toleranzen stehen in den üblichen beiden Spalten. Die Punkte liegen in den gültigen Definitionsbereichen der jeweiligen Ableitungen. Ein separater Test prüft die Metadaten eines erfolgreich konvergierten Polynomintegrals.

