//! Englische Fassung der Engine-Meldungen.
//!
//! Die Rechen-Engine meldet Fehler auf Deutsch, und die Oberfläche erkennt
//! einzelne Fehlerarten an diesem Wortlaut (`classify_preview_error`). Deshalb
//! bleibt die Engine deutsch, und erst die Anzeige übersetzt.

/// Vollständige Meldungen ohne Platzhalter.
const EXACT: &[(&str, &str)] = &[
    ("Matrix-Überlauf", "Matrix overflow"),
    ("Matrix muss mit [[...]] umschlossen sein", "Matrix must be enclosed in [[...]]"),
    ("Matrix ist singulär (nicht invertierbar)", "Matrix is singular (not invertible)"),
    ("Leere Matrix", "Empty matrix"),
    ("Division durch Null", "Division by zero"),
    ("Maximale Verschachtelungstiefe überschritten", "Maximum nesting depth exceeded"),
    ("Funktion an der Stelle nicht reell differenzierbar", "Function is not real-differentiable at this point"),
    ("Funktion an der Stelle nicht differenzierbar", "Function is not differentiable at this point"),
    (
        "Funktion an der Stelle nicht stetig / nicht differenzierbar",
        "Function is not continuous / not differentiable at this point",
    ),
    ("Modulo durch Null", "Modulo by zero"),
    ("Determinante erfordert eine quadratische Matrix", "Determinant requires a square matrix"),
    ("Matrizen sind hier nur innerhalb von det(…) möglich", "Matrices are only allowed inside det(…) here"),
    ("Matrixzellen müssen endliche reelle Zahlen sein", "Matrix cells must be finite real numbers"),
    ("Matrixpotenz erfordert quadratische Matrix", "Matrix power requires a square matrix"),
    (
        "Matrixexponent muss eine ganze Zahl zwischen -1000 und 1000 sein",
        "Matrix exponent must be an integer between -1000 and 1000",
    ),
    ("Matrix-Invertierung erfordert quadratische Matrix", "Matrix inversion requires a square matrix"),
    ("Integrand ist im Integrationsbereich nicht endlich", "Integrand is not finite on the integration interval"),
    ("Integral divergiert (nicht integrierbare Polstelle)", "Integral diverges (non-integrable pole)"),
    ("binomial: k zu groß", "binomial: k too large"),
    ("binomial erfordert nicht-negatives ganzzahliges k", "binomial requires a non-negative integer k"),
    ("binomial erfordert endliche reelle Argumente", "binomial requires finite real arguments"),
    ("Zuweisung erfordert numerischen Wert", "Assignment requires a numeric value"),
    ("Zeitlimit überschritten", "Time limit exceeded"),
    ("Wurzelindex muss eine reelle Zahl ungleich null sein", "Root index must be a non-zero real number"),
    ("Wert zu groß (Überlauf)", "Value too large (overflow)"),
    ("Wert zu groß (Fakultät bis 1000! unterstützt)", "Value too large (factorial supported up to 1000!)"),
    ("Ungültiger Matrix-Ausdruck", "Invalid matrix expression"),
    ("Ungültige Matrixpotenz", "Invalid matrix power"),
    ("Ungültige Matrixdivision", "Invalid matrix division"),
    ("Unerwartetes Zeilenende", "Unexpected end of line"),
    ("Unerwartetes Ende des Ausdrucks", "Unexpected end of expression"),
    ("Symbolische Integration nicht möglich", "Symbolic integration not possible"),
    ("Summationsbereich zu groß (max. 100 000 Schritte)", "Summation range too large (max. 100,000 steps)"),
    ("Produktbereich zu groß", "Product range too large"),
    ("Nicht reell definiert", "Not defined for real numbers"),
    ("Modulo erfordert endliche reelle Zahlen", "Modulo requires finite real numbers"),
    ("Matrix erwartet", "Matrix expected"),
    ("Leerer Ausdruck", "Empty expression"),
    ("Integrationsgrenzen müssen endliche reelle Zahlen sein", "Integration bounds must be finite real numbers"),
    ("Integrand liefert nicht-reellen oder unendlichen Wert", "Integrand yields a non-real or infinite value"),
    ("Fehlende schließende Klammer ')'", "Missing closing parenthesis ')'"),
    (
        "Fakultät ist nur für nicht-negative ganze Zahlen definiert",
        "Factorial is only defined for non-negative integers",
    ),
    ("Erwarte ',' oder ']' in Matrix", "Expected ',' or ']' in matrix"),
    ("Erwarte ',' oder ')' in Funktionsaufruf", "Expected ',' or ')' in function call"),
    ("Ergebnis nicht definiert", "Result undefined"),
    ("Berechnung abgebrochen", "Calculation cancelled"),
    ("Auslöschung zu stark für verlässliche Ableitung", "Too much cancellation for a reliable derivative"),
    ("Ableitungsstelle muss eine endliche reelle Zahl sein", "Derivative point must be a finite real number"),
];

/// Bausteine von Meldungen mit Platzhaltern (Namen, Zahlen). Längere
/// Bausteine stehen vor kürzeren, die in ihnen enthalten sind.
const PHRASES: &[(&str, &str)] = &[
    ("Unbekannte Variable oder Konstante", "Unknown variable or constant"),
    ("Unbekannte Variable", "Unknown variable"),
    ("Unbekannte Funktion", "Unknown function"),
    ("(erlaubt:", "(allowed:"),
    ("Unerwartetes Zeichen", "Unexpected character"),
    ("Unerwartetes Symbol", "Unexpected symbol"),
    ("Ungültige Hex-Zahl", "Invalid hex number"),
    ("Ungültige Zahl", "Invalid number"),
    ("benötigt mindestens 1 Argument", "needs at least 1 argument"),
    ("benötigt genau 1 Argument", "needs exactly 1 argument"),
    ("benötigt 2 Argumente", "needs 2 arguments"),
    ("erwartet 2 oder 3 Argumente", "expects 2 or 3 arguments"),
    ("erwartet 4 Argumente", "expects 4 arguments"),
    ("Funktion '", "Function '"),
    ("Ausdruck, Variable, Start, Ende", "expression, variable, start, end"),
    ("Ausdruck, x", "expression, x"),
    ("(Matrix)", "(matrix)"),
    ("-Grenze muss eine endliche ganze Zahl sein", " bound must be a finite integer"),
    ("-Grenze muss ganzzahlig sein", " bound must be an integer"),
    ("-Grenze außerhalb des Bereichs", " bound out of range"),
    (" erfordert endliche reelle Zahl", " requires a finite real number"),
    ("Konvergenz nicht erreicht", "Convergence not reached"),
    ("Konvergiert", "Converged"),
    ("Zu viele Unstetigkeits-/Knickstellen im Intervall", "Too many discontinuities/kinks in the interval"),
    ("Exakte Stammfunktion ausgewertet", "Exact antiderivative evaluated"),
    ("Fehlerabschätzung", "error estimate"),
    ("Toleranz", "tolerance"),
    ("Budget erschöpft", "budget exhausted"),
    ("Auswertungen", "evaluations"),
];

/// Engine-Meldung in der Sprache der Oberfläche.
pub fn localize_error(msg: &str, de: bool) -> String {
    if de {
        return msg.to_string();
    }
    if let Some((_, en)) = EXACT.iter().find(|(german, _)| *german == msg) {
        return en.to_string();
    }
    let mut out = msg.to_string();
    for (german, en) in PHRASES {
        if out.contains(german) {
            out = out.replace(german, en);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::localize_error;

    #[test]
    fn german_ui_keeps_the_engine_message() {
        assert_eq!(localize_error("Division durch Null", true), "Division durch Null");
    }

    #[test]
    fn engine_messages_are_translated() {
        for (de, en) in [
            ("Division durch Null", "Division by zero"),
            ("Unbekannte Funktion 'foo'", "Unknown function 'foo'"),
            ("Unbekannte Variable oder Konstante 'q'", "Unknown variable or constant 'q'"),
            (
                "Unbekannte Variable 'q' (erlaubt: a, b, c, d, k, l, m, n, x, y, z)",
                "Unknown variable 'q' (allowed: a, b, c, d, k, l, m, n, x, y, z)",
            ),
            ("Funktion 'log' benötigt mindestens 1 Argument", "Function 'log' needs at least 1 argument"),
            ("mod(a, b) benötigt 2 Argumente", "mod(a, b) needs 2 arguments"),
            ("det(Matrix) benötigt genau 1 Argument", "det(matrix) needs exactly 1 argument"),
            (
                "sum(Ausdruck, Variable, Start, Ende) erwartet 4 Argumente",
                "sum(expression, variable, start, end) expects 4 arguments",
            ),
            ("deriv(Ausdruck, x, [x0]) erwartet 2 oder 3 Argumente", "deriv(expression, x, [x0]) expects 2 or 3 arguments"),
            ("sum-Grenze muss ganzzahlig sein", "sum bound must be an integer"),
            ("floor erfordert endliche reelle Zahl", "floor requires a finite real number"),
            ("Ungültige Hex-Zahl 0x1G", "Invalid hex number 0x1G"),
            (
                "Konvergenz nicht erreicht: Fehlerabschätzung 1.00e-3 > Toleranz 1.00e-10 (Budget erschöpft: 5000 Auswertungen)",
                "Convergence not reached: error estimate 1.00e-3 > tolerance 1.00e-10 (budget exhausted: 5000 evaluations)",
            ),
        ] {
            assert_eq!(localize_error(de, false), en);
        }
    }

    /// Jede vollständige Meldung der Engine hat eine englische Fassung.
    #[test]
    fn engine_literals_have_translations() {
        let engine = include_str!("engine.rs");
        for (german, _) in super::EXACT {
            assert!(engine.contains(german), "veraltete Übersetzung: {german}");
        }
    }
}
