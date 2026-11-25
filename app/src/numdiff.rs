//! Numerische Ableitung für die Plotter-Anzeige.
//!
//! Der frühere zentrale Differenzenquotient lieferte an Knicken und Rändern des
//! Definitionsbereichs falsche Werte: bei |x| heben sich +1 und −1 zu 0 auf,
//! bei √x fehlte f(−h) und es wurde stillschweigend 0 angezeigt. Hier wird
//! geprüft, ob überhaupt eine endliche zweiseitige Ableitung existiert.

/// Ergebnis der Ableitung an einer Stelle.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Derivative {
    /// Endliche zweiseitige Ableitung.
    Value(f64),
    /// Funktion ist an der Stelle definiert, aber nicht differenzierbar
    /// (Knick, Sprung, senkrechte Tangente oder Rand des Definitionsbereichs).
    DoesNotExist,
}

/// Ableitung von `f` an `x`. `None`, wenn `f(x)` selbst nicht definiert ist.
///
/// Vorgehen:
/// 1. `f` muss in x, x ± h und x ± h/10 definiert sein, sonst liegt x am Rand
///    des Definitionsbereichs (z. B. √x bei 0).
/// 2. Links- und rechtsseitiger Differenzenquotient müssen sich annähern, wenn
///    h kleiner wird. Bei glatten Funktionen schrumpft ihr Abstand mit h, bei
///    einem Knick (|x|) bleibt er gleich.
/// 3. Der zentrale Quotient muss konvergieren. Bei Sprüngen oder senkrechten
///    Tangenten (sign(x), ∛x bei 0) wächst er mit kleinerem h.
pub fn derivative_at(f: impl Fn(f64) -> Option<f64>, x: f64) -> Option<Derivative> {
    let f0 = f(x)?;
    let h1 = 1e-5 * x.abs().max(1.0);
    let h2 = h1 / 10.0;
    let (Some(p1), Some(m1), Some(p2), Some(m2)) = (f(x + h1), f(x - h1), f(x + h2), f(x - h2)) else {
        return Some(Derivative::DoesNotExist);
    };
    let fwd1 = (p1 - f0) / h1;
    let bwd1 = (f0 - m1) / h1;
    let fwd2 = (p2 - f0) / h2;
    let bwd2 = (f0 - m2) / h2;
    let c1 = (p1 - m1) / (2.0 * h1);
    let c2 = (p2 - m2) / (2.0 * h2);
    if ![fwd1, bwd1, fwd2, bwd2, c1, c2].iter().all(|v| v.is_finite()) {
        return Some(Derivative::DoesNotExist);
    }
    let scale = 1.0_f64.max(c1.abs());

    // 2. Einseitige Quotienten: Abstand muss mit h schrumpfen.
    let gap1 = (fwd1 - bwd1).abs();
    let gap2 = (fwd2 - bwd2).abs();
    if gap1 > 1e-4 * scale && gap2 > 0.5 * gap1 {
        return Some(Derivative::DoesNotExist);
    }

    // 3. Zentraler Quotient muss konvergieren.
    if (c2 - c1).abs() > 1e-3 * scale {
        return Some(Derivative::DoesNotExist);
    }
    Some(Derivative::Value(c1))
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

    #[test]
    fn smooth_functions_have_derivatives() {
        assert!((value(derivative_at(|x| Some(x * x), 1.0)) - 2.0).abs() < 1e-6);
        assert!(value(derivative_at(|x| Some(x * x), 0.0)).abs() < 1e-6);
        assert!((value(derivative_at(|x| Some(x.sin()), 0.0)) - 1.0).abs() < 1e-6);
        assert!((value(derivative_at(|x| Some((100.0 * x).sin()), 0.3)) - 100.0 * (30.0f64).cos()).abs() < 1e-2);
        assert!((value(derivative_at(|x| real(x.sqrt()), 4.0)) - 0.25).abs() < 1e-6);
        assert!((value(derivative_at(|x| Some(x.abs()), -2.0)) + 1.0).abs() < 1e-6);
        assert!((value(derivative_at(|x| Some(1e6 * x), 7.0)) - 1e6).abs() < 1e-1);
        assert!((value(derivative_at(|x| Some(x.exp()), 20.0)) - 20f64.exp()).abs() < 1e-3 * 20f64.exp());
    }

    #[test]
    fn kinks_edges_and_jumps_have_none() {
        // Befunde aus dem UI-Test: √x und |x| bei 0
        assert_eq!(derivative_at(|x| real(x.sqrt()), 0.0), Some(Derivative::DoesNotExist));
        assert_eq!(derivative_at(|x| Some(x.abs()), 0.0), Some(Derivative::DoesNotExist));
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
