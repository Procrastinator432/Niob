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
- `exact_fraction`: Der exakte Bruch (oder die exakte ganze Zahl, wenn der Sollwert keinen Bruchstrich hat) oder eine Dezimalzahl innerhalb der relativen Toleranz.
- `matrix`: Sollwert `Matrix;Schranke`. Ein Eintrag passt, wenn `|ist - soll| <= relativ * |soll| + 10⁴ * eps * Schranke` plus Rundung der Anzeige. Die Schranke ist die komponentenweise Fehlerschranke `|A⁻¹|·|A|·|A⁻¹|` bei der Inversen, `|A|·|B|` beim Produkt und `(|A|·|A⁻¹|)²` bei A·inv(A). Ein Eintrag, der nach dieser Schranke deutlich von 0 verschieden ist, darf also nicht als 0 erscheinen.
- `antiderivative`: Sollwert `x0:f(x0);…`. Das Ergebnis F wird an x0 ± h und x0 ± 2h ausgewertet; die Fünf-Punkte-Differenz muss f(x0) treffen. Geprüft werden mehrere Schrittweiten (10⁻³ bis 10⁻⁵, relativ zu max(1, |x0|)), damit eine korrekte Stammfunktion nahe einer Polstelle nicht fälschlich durchfällt. „Symbolische Integration nicht möglich“ zählt als nicht unterstützt, nicht als Fehler. Stellen, an denen F nicht reell auswertbar ist, werden übersprungen.
- `polynomial`: Sollwert `y=…,z=…,w=…:Wert;…`. Das Ergebnis darf kein `)^`, `)*(` oder `)(` enthalten und muss an den Stützstellen mit dem Ausgangsausdruck übereinstimmen.
- `integral_split`: Selbstprüfung ohne Sollwert. Spalte 5 enthält die beiden Teilintegrale `int(f,x,a,m);int(f,x,m,b)`. Das ganze Integral und beide Teile werden jeweils mit einem neuen Rechenkern berechnet; es muss `|I − I₁ − I₂| <= 10·(Δ + Δ₁ + Δ₂) + absolut + relativ·max(|I|, |I₁|, |I₂|)` gelten, wobei Δ der gemeldete Fehler aus dem Integrationsbericht ist (0 ohne Bericht). Eine Fehlermeldung oder ein als nicht konvergiert gemeldetes Teilergebnis ist zulässig. Die Prüfung schlägt also an, wenn die Fehlerschätzung zu optimistisch ist, auch für Integranden, für die es keinen Sollwert gibt.

Fehlermeldungen (bei `number_or_error`, `integral` und `integral_split`) und als nicht konvergiert gemeldete Integrale sind zulässig, werden aber in der Zusammenfassung gezählt, damit Lücken sichtbar bleiben, etwa „175 Fälle, 46 falsch, 18 mit Fehlermeldung, 7 als nicht konvergiert gemeldet“.

## Weitere Familien

- `integral_families.tsv`: Integranden mit vielen Knicken (`abs(sin(k*x))` über bis zu einige hundert Nullstellen), Knicken an nichtlinearen Stellen (`abs(cos(x)-c)`, `abs(x^2-c)`, `abs(exp(x)-c)`), Sprüngen (`floor`, `ceil`, `round` mit linearem Argument, `sign(sin(k*x))`), integrierbaren Singularitäten innerhalb des Intervalls und an zufälligen Grenzen sowie schmalen Spitzen in sechs Schreibweisen. Bei der ausmultiplizierten Spitze `exp(-k*(x^2-2c*x+c^2))` und bei `exp(-k*(x^2-q)^2)` enthält die absolute Toleranz den Stellenverlust, den schon die Auswertung der Eingabe verursacht. Am Ende stehen die Beispiele aus der vierten Nachprüfung.
- `derivative_families.tsv`: Ableitungen von 18 weiteren glatten Funktionen an Zufallsstellen, von Funktionen mit Knick oder Sprung abseits und an der Knickstelle (dort ist eine Fehlermeldung Pflicht, außer wo die Ableitung existiert, etwa `x*abs(x)` bei 0) und von Differenzen mit starker Auslöschung (`x-sin(x)`, `tan(x)-x`, `cosh(x)-x^2/2` nahe 0). Bei der Auslöschung gibt es keine absolute Toleranz: Ein Wert muss relativ auf 10⁻⁶ stimmen, sonst ist eine Fehlermeldung nötig.
- `determinants.tsv`: Determinanten ganzzahliger Matrizen mit Einträgen bis 10¹² (Produkte über 2⁵³), von Matrixpotenzen bis zur 24., von Matrizen mit Brüchen, von gut konditionierten Matrizen mit Einträgen von 10⁻³⁰ bis 10³⁰ und `det(A)*det(inv(A))`. Für ganzzahlige und rationale Eingaben gilt `exact_fraction` mit 10⁻¹⁰: exakt, oder als Dezimalzahl auf 10 Stellen richtig.
- `integral_splits.tsv`: 60 zufällige Kombinationen aus Knicken, Sprüngen, Spitzen, Singularitäten und glatten Termen für die Prüfung `integral_split`.

## Zufallsfolgen

| Datei | Startwert |
|---|---|
| `integrals.tsv` | 7 |
| `matrices.tsv` | 3 |
| `exact_numbers.tsv` | 5 |
| `antiderivatives.tsv` | 11 (RAD), 23 (DEG) |
| `polynomials.tsv` | 4 |
| `integral_families.tsv` | 41 |
| `derivative_families.tsv` | 43 |
| `determinants.tsv` | 47 |
| `integral_splits.tsv` | 53 |

`derivatives.tsv` verwendet ein festes Raster ohne Zufall.
