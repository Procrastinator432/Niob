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
- `polynomial`: Sollwert `y=…,z=…,w=…:Wert;…`. Das Ergebnis darf kein `)^`, `)*(` oder `)(` enthalten und muss an den Stützstellen mit dem Ausgangsausdruck übereinstimmen. Die absolute Toleranz ist mindestens 64·eps mal die Summe der Beträge der ausmultiplizierten Summanden an den Stützstellen: Heben sich große Summanden fast auf, weicht die Gleitkomma-Auswertung einer richtigen Entwicklung sonst schon um mehr als 10⁻⁹ ab (etwa `(y-2*z+2)^11` bei y = 1,47, z = 1,6).
- `integral_split`: Selbstprüfung ohne Sollwert. Spalte 5 enthält die beiden Teilintegrale `int(f,x,a,m);int(f,x,m,b)`. Das ganze Integral und beide Teile werden jeweils mit einem neuen Rechenkern berechnet; es muss `|I − I₁ − I₂| <= 10·(Δ + Δ₁ + Δ₂) + absolut + relativ·max(|I|, |I₁|, |I₂|)` gelten, wobei Δ der gemeldete Fehler aus dem Integrationsbericht ist (0 ohne Bericht). Eine Fehlermeldung oder ein als nicht konvergiert gemeldetes Teilergebnis ist zulässig. Die Prüfung schlägt also an, wenn die Fehlerschätzung zu optimistisch ist, auch für Integranden, für die es keinen Sollwert gibt.

- `diverges`: Das Integral divergiert. Verlangt ist eine Fehlermeldung oder ein als nicht konvergiert gemeldetes Ergebnis; ein als konvergiert gemeldeter Wert schlägt fehl. Spalte 5 ist ohne Bedeutung.
- `integral_value`: Das Integral ist endlich, und eine Fehlermeldung wäre falsch (etwa „Integral divergiert“ bei einem beschränkten Integranden). Verlangt ist ein Wert wie bei `integral` oder ein als nicht konvergiert gemeldetes Ergebnis; eine Fehlermeldung schlägt fehl.

Fehlermeldungen (bei `number_or_error`, `integral`, `integral_split` und `diverges`) und als nicht konvergiert gemeldete Integrale sind zulässig, werden aber in der Zusammenfassung gezählt, damit Lücken sichtbar bleiben, etwa „175 Fälle, 46 falsch, 18 mit Fehlermeldung, 7 als nicht konvergiert gemeldet“.

## Weitere Familien

- `integral_families.tsv`: Integranden mit vielen Knicken (`abs(sin(k*x))` über bis zu einige hundert Nullstellen), Knicken an nichtlinearen Stellen (`abs(cos(x)-c)`, `abs(x^2-c)`, `abs(exp(x)-c)`), Sprüngen (`floor`, `ceil`, `round` mit linearem Argument, `sign(sin(k*x))`), integrierbaren Singularitäten innerhalb des Intervalls und an zufälligen Grenzen sowie schmalen Spitzen in sechs Schreibweisen. Bei der ausmultiplizierten Spitze `exp(-k*(x^2-2c*x+c^2))` und bei `exp(-k*(x^2-q)^2)` enthält die absolute Toleranz den Stellenverlust, den schon die Auswertung der Eingabe verursacht. Am Ende stehen die Beispiele aus der vierten Nachprüfung.
- `derivative_families.tsv`: Ableitungen von 18 weiteren glatten Funktionen an Zufallsstellen, von Funktionen mit Knick oder Sprung abseits und an der Knickstelle (dort ist eine Fehlermeldung Pflicht, außer wo die Ableitung existiert, etwa `x*abs(x)` bei 0) und von Differenzen mit starker Auslöschung (`x-sin(x)`, `tan(x)-x`, `cosh(x)-x^2/2` nahe 0). Bei der Auslöschung gibt es keine absolute Toleranz: Ein Wert muss relativ auf 10⁻⁶ stimmen, sonst ist eine Fehlermeldung nötig.
- `determinants.tsv`: Determinanten ganzzahliger Matrizen mit Einträgen bis 10¹² (Produkte über 2⁵³), von Matrixpotenzen bis zur 24., von Matrizen mit Brüchen, von gut konditionierten Matrizen mit Einträgen von 10⁻³⁰ bis 10³⁰ und `det(A)*det(inv(A))`. Für ganzzahlige und rationale Eingaben gilt `exact_fraction` mit 10⁻¹⁰: exakt, oder als Dezimalzahl auf 10 Stellen richtig.
- `integral_splits.tsv`: 60 zufällige Kombinationen aus Knicken, Sprüngen, Spitzen, Singularitäten und glatten Termen für die Prüfung `integral_split`.

## Formen aus der fünften Nachprüfung

Die vier Tabellen stammen aus der [fünften Nachprüfung](../../../../BEWERTUNG-2026-11-16.md) und enthalten die dort genannten Beispiele. Sie teilen sich einen Zufallsgenerator (Startwert 2611) in fester Reihenfolge.

- `integral_forms.tsv`: Knicke, Sprünge und Singularitäten mit nichtlinearem Argument (`abs(sin(x^2))`, `abs(sin(exp(x)))`, `floor(x^2)`, `floor(sqrt(x))`, `floor(exp(x))`, `floor(3*sin(x))`, `x mod 1`, `ln(abs(sin(x)))`, `1/sqrt(abs(x^2-c))`), 18 divergente Integrale mit der Prüfung `diverges`, mehrere Spitzen, Spitzen anderer Form und Schwingungen.
- `derivative_forms.tsv`: Ableitungen mit Auslöschung bei kleinem x, nahe 1 und bei großem x; an Knicken, wo die Ableitung existiert (`abs(x)^0.5*x` bei 0) oder nicht (`sqrt(abs(x))` und `abs(x)^(1/3)` bei 0, `floor(x)+floor(-x)` bei 3); die Fakultät (`deriv(x!,x,3)` muss abgelehnt werden); Sonderfälle und DEG-Modus.
- `determinant_forms.tsv`: 5×5- und 6×6-Matrizen mit Einträgen bis 10⁹, Produkte, Transponierte, exakt singuläre Matrizen mit Einträgen bis 10¹², Dezimalbrüche, Einträge 10⁹⁰, `det(inv(A))` und 4×4-Potenzen.
- `split_forms.tsv`: 80 Teilungsproben mit Bausteinen wie `floor(x^2)`, `floor(exp(x/3))`, `x mod 1`, `abs(sin(x^2))`, `ln(abs(sin(x)))`, `1/sqrt(abs(x^2-c))` und `sin(x^2)`.

Bei `floor(3*sin(x))` enthält die absolute Toleranz 5·10⁻⁸ je Stelle x = π/2 + 2πn im Intervall: Dort ist `sin(x)` in Gleitkomma auf einem etwa 2·10⁻⁸ breiten Streifen genau 1 und `floor(3*sin(x))` damit 3 statt 2. Das kann kein Integrationsverfahren ausgleichen.

## Formen aus der sechsten Nachprüfung

Die drei Tabellen stammen aus der [sechsten Nachprüfung](../../../../BEWERTUNG-2026-11-16-2.md) und enthalten die dort genannten Beispiele. Sie teilen sich einen Zufallsgenerator (Startwert 3001) in fester Reihenfolge.

- `integral_forms2.tsv`: weitere Sprünge und Knicke mit nichtlinearem Argument (`floor(x^3)`, `floor(x^3-3*x)`, `floor(1/x)`, `floor(ln(x))`, `floor(tan(x))`, `ceil(x^2)`, `round(sqrt(x))`, `x mod 0.7`, `x^2 mod 1`, `floor(5*sin(x)^2)`, `sign(sin(x^2))`, `abs(sin(x^3))`, `abs(sin(x)-x/10)` …) und 15 divergente Integrale, darunter solche mit Singularität einer Winkelfunktion (`abs(sin(x))^(-1.5)`, `1/sin(x)^2`, `1/cos(x)^2`). Extremstellen des Arguments sind Teilungspunkte der Referenz. Berührt das Argument dort eine Sprungstufe, wächst die absolute Toleranz um die Breite des Streifens, auf dem die Gleitkomma-Auswertung die Stufe erreicht (etwa 2·√(ulp(Stufe)/|f''|), mit Sicherheitsfaktor).
- `derivative_forms2.tsv`: Ableitungen an Stellen ohne Ableitung (`abs(x)^0.7` und `x^(2/3)` bei 0, `abs(x-2)^(2/3)` bei 2, `floor(x^2)` bei 2, `x mod 1` bei 3, `ceil(x)-floor(x)` bei 1, `(x+1)!`) und mit Ableitung trotz nahem Knick oder Sprung (`abs(x)^1.2`, `x mod 1` bei 0,5, `round(x)-x`, `sqrt(x^4)` bei 0).
- `split_forms2.tsv`: 60 Teilungsproben mit diesen Bausteinen.

## Formen aus der siebten Nachprüfung

`integral_forms3.tsv` stammt aus der [siebten Nachprüfung](../../../../BEWERTUNG-2026-11-16-3.md) und prüft die Erkennung divergenter Integrale (Startwert 3301):

- 24 divergente Fälle aus der Nachprüfung (`diverges`), darunter `1e-7*abs(sin(x))^(-1.5)` und Pole an Nullstellen von `x^3-x-1` und `x^5-x-1`;
- 12 konvergente Fälle aus der Nachprüfung; die sechs mit steilem, aber beschränktem Integranden oder einem Exponenten knapp über −1 mit `integral_value`, die übrigen mit `integral`;
- 16 divergente Integrale, mit 10⁻³ bis 10⁻¹² multipliziert: Ein konstanter Faktor darf am Ergebnis nichts ändern;
- 14 Pole an einfachen Nullstellen zufälliger Polynome 3. und 5. Grades (`1/p^2`, `1/abs(p)`, `abs(p)^(-1.5)`);
- je 10 beschränkte steile Integranden `1/(abs(x-c)+ε)` und normierte schmale Spitzen `ε/((x-c)^2+ε^2)` mit ε von 10⁻⁶ bis 10⁻¹² (`integral_value`). Die absolute Toleranz enthält den Abstand der Gleitkomma-Stützstellen nahe c im Verhältnis zur Breite ε.

## Formen aus der achten Nachprüfung

`integral_forms4.tsv` stammt aus der [achten Nachprüfung](../../../../BEWERTUNG-2026-11-16-4.md) (Startwert 4401):

- 17 divergente Formen (`diverges`): Faktoren bis 10⁻²⁰, Pole an Nullstellen von `x*exp(x)-1`, `x^x-2`, `tan(x)-x`, `cos(x)*cosh(x)+1`, `sinh(x)-1`, zwei Pole im Abstand 10⁻⁷, `sin(1/x)/x^2`;
- 17 endliche Integrale mit `integral_value`: normierte Spitzen der Breite 10⁻¹² und 10⁻¹³, `1/(x^2+1e-30)` bei 0, `1/(abs(x-c)^1.5+ε)`, Exponent −0,99 sowie die schwingenden Integranden `sin(1/x)/x`, `sin(2/x)/x` und `cos(1/x)/x`, die nicht als Pol gelten dürfen; `abs(sin(x))^(-0.999)` mit `integral`;
- 30 zufällige und 7 in der Nachprüfung genannte schmale Spitzen auf [0, 1000], deren Nenner abseits der Spitze überläuft: `1/cosh(k*(x-c))`, `1/cosh(k*(x-c))^2`, `x/cosh(k*(x-c))^2`, `1/(1+cosh(k*(x-c)))` und die logistische Stufe `1/(1+exp(k*(x-c)))` mit k = 100, 1000, 10000 (`integral`). Vor der Korrektur fehlte bei 12 der 30 zufälligen und bei allen 7 genannten Spitzen die halbe oder ganze Fläche, obwohl das Ergebnis als konvergiert galt.

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
| `integral_forms.tsv`, `derivative_forms.tsv`, `determinant_forms.tsv`, `split_forms.tsv` | 2611 (gemeinsam) |
| `integral_forms2.tsv`, `derivative_forms2.tsv`, `split_forms2.tsv` | 3001 (gemeinsam) |
| `integral_forms3.tsv` | 3301 |
| `integral_forms4.tsv` | 4401 |

`derivatives.tsv` verwendet ein festes Raster ohne Zufall.
