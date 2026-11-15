# Generierte Fälle

Die Dateien in diesem Ordner erzeugt `tools/generate_test_cases.py`. Sie nicht von Hand bearbeiten, sondern das Skript ändern und neu erzeugen. Die Sollwerte stammen aus sympy, mpmath (50 Stellen) und exakter Python-Arithmetik, nicht aus einem der beiden Rechenkerne. Rust (`app/tests/generated.rs`) und Android (`GeneratedCases.java`) lesen dieselben Dateien.

Jede Zeile hat acht durch Tabulator getrennte Spalten:

1. Eindeutiger Fallname.
2. Ausdruck für Android.
3. Ausdruck für Rust (derzeit identisch mit Spalte 2).
4. Prüfung, siehe unten.
5. Sollwert.
6. Absolute Toleranz.
7. Relative Toleranz.
8. Winkelmodus: `rad` oder `deg`.

Jeder Fall startet mit einem neuen Rechenkern und hat 30 Sekunden Zeit.

## Prüfungen

- `number`: Ein reelles Zahlenergebnis innerhalb von `absolut + relativ * |Sollwert|`. Die angezeigte Zahl muss ebenfalls passen; zusätzlich ist eine halbe Einheit der letzten angezeigten Stelle erlaubt.
- `number_or_error`: Wie `number`, eine ausdrückliche Fehlermeldung ist ebenfalls zulässig. Gilt für Ableitungen nahe Pol- und Definitionsrändern und bei starker Auslöschung (|f'(x0)|·max(1, |x0|) < 10⁻⁶·|f(x0)|). Ein falscher Wert schlägt fehl.
- `error`: Eine Fehlermeldung ist Pflicht, etwa für die Ableitung an einer Sprung- oder Knickstelle.
- `integral`: Wie `number_or_error`; zusätzlich gilt ein Ergebnis als zulässig, das der Integrationsbericht als nicht konvergiert kennzeichnet. Ein falscher Wert, der als konvergiert gemeldet wird, schlägt fehl. Bei kurzen Intervallen weit weg von 0 enthält die absolute Toleranz den Einfluss der Rundung der Grenzen auf Gleitkommazahlen.
- `exact_integer`: Der exakte Text muss genau die ganze Zahl sein. Eine Gleitkomma-Näherung reicht nicht.
- `exact_fraction`: Der exakte Bruch oder eine Dezimalzahl innerhalb der relativen Toleranz.
- `matrix`: Sollwert `Matrix;Schranke`. Ein Eintrag passt, wenn `|ist - soll| <= relativ * |soll| + 10⁴ * eps * Schranke` plus Rundung der Anzeige. Die Schranke ist die komponentenweise Fehlerschranke `|A⁻¹|·|A|·|A⁻¹|` bei der Inversen, `|A|·|B|` beim Produkt und `(|A|·|A⁻¹|)²` bei A·inv(A). Ein Eintrag, der nach dieser Schranke deutlich von 0 verschieden ist, darf also nicht als 0 erscheinen.
- `antiderivative`: Sollwert `x0:f(x0);…`. Das Ergebnis F wird an x0 ± h und x0 ± 2h ausgewertet; die Fünf-Punkte-Differenz muss f(x0) treffen. Geprüft werden mehrere Schrittweiten (10⁻³ bis 10⁻⁵, relativ zu max(1, |x0|)), damit eine korrekte Stammfunktion nahe einer Polstelle nicht fälschlich durchfällt. „Symbolische Integration nicht möglich“ zählt als nicht unterstützt, nicht als Fehler. Stellen, an denen F nicht reell auswertbar ist, werden übersprungen.
- `polynomial`: Sollwert `y=…,z=…,w=…:Wert;…`. Das Ergebnis darf kein `)^`, `)*(` oder `)(` enthalten und muss an den Stützstellen mit dem Ausgangsausdruck übereinstimmen.

## Zufallsfolgen

| Datei | Startwert |
|---|---|
| `integrals.tsv` | 7 |
| `matrices.tsv` | 3 |
| `exact_numbers.tsv` | 5 |
| `antiderivatives.tsv` | 11 (RAD), 23 (DEG) |
| `polynomials.tsv` | 4 |

`derivatives.tsv` verwendet ein festes Raster ohne Zufall.
