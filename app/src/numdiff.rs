//! Numerische Ableitung für die Plotter-Anzeige.
//!
//! Prüft, ob an einer Stelle eine endliche zweiseitige Ableitung existiert,
//! statt blind einen Differenzenquotienten anzuzeigen (früher: |x| und √x bei 0
//! ergaben f′ = 0). Die Schrittweite wird schrittweise verkleinert, damit auch
//! Stellen dicht an einem Knick oder am Rand des Definitionsbereichs richtig
//! behandelt werden (√x bei x = 10⁻⁷ hat f′ ≈ 1581,14). Alle Schwellen sind
//! relativ zur Größe der Werte, damit 10⁻⁶·|x| genauso als Knick erkannt wird
//! wie |x|.

/// Ergebnis der Ableitung an einer Stelle.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Derivative {
    /// Endliche zweiseitige Ableitung.
    Value(f64),
    /// Funktion ist an der Stelle definiert, aber nicht differenzierbar
    /// (Knick, Sprung, senkrechte Tangente oder Rand des Definitionsbereichs).
    DoesNotExist,
}

/// Ergebnis eines Prüfschritts mit Schrittweite h (und h/10).
enum Step {
    /// Glatt und konvergent: Ableitungswert.
    Smooth(f64),
    /// Nicht glatt bei dieser Schrittweite (Knick/Sprung/Divergenz im Fenster
    /// oder eine Seite nicht definiert) – kleinere Schrittweite versuchen.
    Rough,
    /// Rundungsfehler überwiegen; kleinere Schritte helfen nicht mehr.
    TooNoisy,
}

fn probe(f: &impl Fn(f64) -> Option<f64>, x: f64, f0: f64, h1: f64) -> Step {
    let h2 = h1 / 10.0;
    // Dritter Schritt in einem nicht ganzzahligen Verhältnis: Bei schnell
    // schwingenden Funktionen können h und h/10 zufällig dieselbe Phase treffen
    // (sin(2π·10⁵·x) bei 0 ergäbe sonst f′ ≈ 0).
    let h3 = h1 / std::f64::consts::PI;
    let (Some(p1), Some(m1), Some(p2), Some(m2), Some(p3), Some(m3)) =
        (f(x + h1), f(x - h1), f(x + h2), f(x - h2), f(x + h3), f(x - h3))
    else {
        return Step::Rough;
    };
    let fwd1 = (p1 - f0) / h1;
    let bwd1 = (f0 - m1) / h1;
    let fwd2 = (p2 - f0) / h2;
    let bwd2 = (f0 - m2) / h2;
    let c1 = (p1 - m1) / (2.0 * h1);
    let c2 = (p2 - m2) / (2.0 * h2);
    let c3 = (p3 - m3) / (2.0 * h3);
    if ![fwd1, bwd1, fwd2, bwd2, c1, c2, c3].iter().all(|v| v.is_finite()) {
        return Step::Rough;
    }
    // Rundungsrauschen der Quotienten bei der kleineren Schrittweite
    let fmax = [f0, p1, m1, p2, m2, p3, m3].iter().fold(0.0_f64, |m, v| m.max(v.abs()));
    let noise = 4.0 * f64::EPSILON * fmax / h2;
    let slope = c1.abs().max(c2.abs()).max(c3.abs());
    // Rauschen größer als 1 % der Steigung (oder absolut spürbar bei Steigung 0)
    if noise > 1e-2 * slope && noise > 1e-9 {
        return Step::TooNoisy;
    }

    // Knick: Abstand zwischen rechts- und linksseitigem Quotienten schrumpft
    // bei glatten Funktionen mit h, bei einem Knick bleibt er gleich. Abstände
    // unter einem Millionstel der Steigung sind Rundungsfehler (z. B. sin bei
    // großem Argument), kein Knick.
    let gap1 = (fwd1 - bwd1).abs();
    let gap2 = (fwd2 - bwd2).abs();
    let gap_floor = 10.0 * noise + 1e-6 * (fwd1.abs() + bwd1.abs());
    if gap1 > gap_floor && gap2 > 0.5 * gap1 {
        return Step::Rough;
    }
    // Konvergenz des zentralen Quotienten (Sprung, senkrechte Tangente) und
    // Übereinstimmung aller drei Schrittweiten
    let tol = 0.05 * slope + 10.0 * noise;
    if (c2 - c1).abs() > tol || (c3 - c2).abs() > tol {
        return Step::Rough;
    }
    // Richardson-Extrapolation (Fehler O(h⁴) statt O(h²))
    Step::Smooth((100.0 * c2 - c1) / 99.0)
}

/// Ableitung von `f` an `x`. `None`, wenn `f(x)` selbst nicht definiert ist.
pub fn derivative_at(f: impl Fn(f64) -> Option<f64>, x: f64) -> Option<Derivative> {
    let f0 = f(x)?;
    let mut h = 1e-4 * x.abs().max(1.0);
    // Ein Knick genau bei x zeigt sich bei jeder Schrittweite. Liegt er nur in
    // der Nähe, verschwindet er, sobald h kleiner ist als der Abstand.
    for attempt in 0..9 {
        match probe(&f, x, f0, h) {
            Step::Smooth(d) => return Some(Derivative::Value(d)),
            // Große Funktionswerte (z. B. 10¹⁰ + x): mit größerem Schritt messen
            Step::TooNoisy if attempt == 0 => {
                return Some(match probe(&f, x, f0, h * 100.0) {
                    Step::Smooth(d) => Derivative::Value(d),
                    _ => Derivative::DoesNotExist,
                });
            }
            Step::TooNoisy => break,
            Step::Rough => h /= 10.0,
        }
    }
    Some(Derivative::DoesNotExist)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn real(v: f64) -> Option<f64> {
        if v.is_finite() {
            Some(v)
        } else {
            None
        }
    }

    fn value(d: Option<Derivative>) -> f64 {
        match d {
            Some(Derivative::Value(v)) => v,
            other => panic!("Ableitung erwartet, erhalten: {other:?}"),
        }
    }

    fn close(actual: f64, expected: f64, rel: f64) {
        assert!(
            (actual - expected).abs() <= rel * expected.abs().max(1e-300),
            "erwartet {expected}, erhalten {actual}"
        );
    }

    #[test]
    fn smooth_functions_have_derivatives() {
        close(value(derivative_at(|x| Some(x * x), 1.0)), 2.0, 1e-8);
        assert!(value(derivative_at(|x| Some(x * x), 0.0)).abs() < 1e-8);
        close(value(derivative_at(|x| Some(x.sin()), 0.0)), 1.0, 1e-8);
        close(value(derivative_at(|x| Some((100.0 * x).sin()), 0.3)), 100.0 * 30f64.cos(), 1e-6);
        close(value(derivative_at(|x| real(x.sqrt()), 4.0)), 0.25, 1e-8);
        close(value(derivative_at(|x| Some(x.abs()), -2.0)), -1.0, 1e-8);
        close(value(derivative_at(|x| Some(1e6 * x), 7.0)), 1e6, 1e-8);
        close(value(derivative_at(|x| Some(x.exp()), 20.0)), 20f64.exp(), 1e-6);
        close(value(derivative_at(|x| Some(1e10 + x), 3.0)), 1.0, 1e-3);
        close(value(derivative_at(|x| Some(x * x), 1e8)), 2e8, 1e-6);
        // Befund: h und h/10 trafen dieselbe Phase, angezeigt wurde f′ ≈ 0
        let w = 628318.5307179586;
        close(value(derivative_at(|x| Some((w * x).sin()), 0.0)), w, 1e-6);
        close(value(derivative_at(|x| Some((w * x).sin()), 1e-3)), w * (w * 1e-3).cos(), 1e-5);
        close(value(derivative_at(|x| Some((62831.853071795864 * x).sin()), 0.0)), 62831.853071795864, 1e-6);
    }

    #[test]
    fn points_close_to_a_kink_or_edge() {
        // Befund: √x bei 10⁻⁷ meldete „existiert nicht“, richtig ist ≈ 1581,14
        close(value(derivative_at(|x| real(x.sqrt()), 1e-7)), 1.0 / (2.0 * 1e-7f64.sqrt()), 1e-6);
        // Neben dem Knick, aber nicht darauf
        close(value(derivative_at(|x| Some(x.abs()), 1e-7)), 1.0, 1e-8);
        close(value(derivative_at(|x| Some(1e-6 * x.abs()), -1e-7)), -1e-6, 1e-6);
        close(value(derivative_at(|x| Some(x.cbrt()), 1e-6)), 1.0 / (3.0 * 1e-6f64.cbrt().powi(2)), 1e-5);
    }

    #[test]
    fn kinks_edges_and_jumps_have_none() {
        assert_eq!(derivative_at(|x| real(x.sqrt()), 0.0), Some(Derivative::DoesNotExist));
        assert_eq!(derivative_at(|x| Some(x.abs()), 0.0), Some(Derivative::DoesNotExist));
        // Befund: 10⁻⁶·|x| zeigte bei 0 weiterhin f′ = 0
        assert_eq!(derivative_at(|x| Some(1e-6 * x.abs()), 0.0), Some(Derivative::DoesNotExist));
        assert_eq!(derivative_at(|x| Some(1e-12 * x.abs()), 0.0), Some(Derivative::DoesNotExist));
        assert_eq!(derivative_at(|x| Some(x.abs() + 5.0), 0.0), Some(Derivative::DoesNotExist));
        // Senkrechte Tangente
        assert_eq!(derivative_at(|x| Some(x.cbrt()), 0.0), Some(Derivative::DoesNotExist));
        // Sprünge
        assert_eq!(derivative_at(|x| Some(x.signum() * (x != 0.0) as i32 as f64), 0.0), Some(Derivative::DoesNotExist));
        assert_eq!(derivative_at(|x| Some(x.floor()), 1.0), Some(Derivative::DoesNotExist));
    }

    #[test]
    fn undefined_point_has_no_value() {
        assert_eq!(derivative_at(|x| real(x.sqrt()), -1.0), None);
        assert_eq!(derivative_at(|x| real(1.0 / x), 0.0), None);
    }
}
