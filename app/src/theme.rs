//! Material You (Material 3) Dynamic Tonal Palette & Theme Engine
//! Combines Niob Scientific Calculator color modes (`light`, `dark`, `amoled`)
//! and accent colors (`res/values/colors.xml`) with modern Android Material You surfaces.

use egui::{Color32, CornerRadius, Stroke, Style, Visuals};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ThemeMode {
    Dark,
    Light,
    Amoled,
}

impl ThemeMode {
    pub fn label_de(self) -> &'static str {
        match self {
            Self::Dark => "Dunkel (Material You)",
            Self::Light => "Hell (Material You)",
            Self::Amoled => "AMOLED Tiefschwarz",
        }
    }

    pub fn label_en(self) -> &'static str {
        match self {
            Self::Dark => "Dark (Material You)",
            Self::Light => "Light (Material You)",
            Self::Amoled => "AMOLED Pure Black",
        }
    }

    /// Kurzname für die Kopfleiste.
    pub fn short_label(self, de: bool) -> &'static str {
        match (self, de) {
            (Self::Dark, true) => "Dunkel",
            (Self::Dark, false) => "Dark",
            (Self::Light, true) => "Hell",
            (Self::Light, false) => "Light",
            (Self::Amoled, _) => "AMOLED",
        }
    }

    /// Schlüssel für die gespeicherten Einstellungen.
    pub fn key(self) -> &'static str {
        match self {
            Self::Dark => "dark",
            Self::Light => "light",
            Self::Amoled => "amoled",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        [Self::Dark, Self::Light, Self::Amoled].into_iter().find(|m| m.key() == key)
    }
}

/// Relative Leuchtdichte nach WCAG 2.x.
fn relative_luminance(c: Color32) -> f64 {
    let channel = |v: u8| {
        let s = v as f64 / 255.0;
        if s <= 0.03928 {
            s / 12.92
        } else {
            ((s + 0.055) / 1.055).powf(2.4)
        }
    };
    0.2126 * channel(c.r()) + 0.7152 * channel(c.g()) + 0.0722 * channel(c.b())
}

/// Kontrastverhältnis nach WCAG (1 … 21).
pub fn contrast_ratio(a: Color32, b: Color32) -> f64 {
    let (la, lb) = (relative_luminance(a), relative_luminance(b));
    let (hi, lo) = if la > lb { (la, lb) } else { (lb, la) };
    (hi + 0.05) / (lo + 0.05)
}

/// `c` schrittweise abdunkeln (heller Hintergrund) bzw. aufhellen (dunkler
/// Hintergrund), bis der Kontrast zu allen `backgrounds` mindestens `min` ist.
/// Der Farbton bleibt erhalten, nur die Helligkeit ändert sich.
pub fn ensure_contrast(c: Color32, backgrounds: &[Color32], min: f64) -> Color32 {
    let Some(&first) = backgrounds.first() else {
        return c;
    };
    let target = if relative_luminance(first) > 0.18 {
        Color32::BLACK
    } else {
        Color32::WHITE
    };
    let ok = |col: Color32| backgrounds.iter().all(|&bg| contrast_ratio(col, bg) >= min);
    let mut t: f32 = 0.0;
    let mut out = c;
    while !ok(out) && t < 1.0 {
        t += 0.02;
        out = blend(c, target, t.min(1.0));
    }
    out
}

/// Schwarz oder Weiß, je nachdem, was auf `bg` besser lesbar ist.
pub fn readable_on(bg: Color32) -> Color32 {
    if contrast_ratio(Color32::BLACK, bg) >= contrast_ratio(Color32::WHITE, bg) {
        Color32::BLACK
    } else {
        Color32::WHITE
    }
}

/// Grundfarben der Plotter-Kurven f₁ … f₆.
const PLOT_BASE_COLORS: [Color32; 6] = [
    Color32::from_rgb(0x3D, 0xDC, 0x84),
    Color32::from_rgb(0x64, 0xB5, 0xF6),
    Color32::from_rgb(0xFF, 0xB7, 0x4D),
    Color32::from_rgb(0xF0, 0x62, 0x92),
    Color32::from_rgb(0xBA, 0x68, 0xC8),
    Color32::from_rgb(0x4D, 0xD0, 0xE1),
];
pub const PLOT_COLOR_COUNT: usize = PLOT_BASE_COLORS.len();

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AccentSeed {
    NiobEmerald,
    DynamicViolet,
    OceanBlue,
    SunsetOrange,
    RosePink,
    ForestGreen,
    CrimsonRed,
    GoldenAmber,
}

impl AccentSeed {
    pub const ALL: [AccentSeed; 8] = [
        Self::NiobEmerald,
        Self::DynamicViolet,
        Self::OceanBlue,
        Self::SunsetOrange,
        Self::RosePink,
        Self::ForestGreen,
        Self::CrimsonRed,
        Self::GoldenAmber,
    ];

    pub fn name_de(self) -> &'static str {
        match self {
            Self::NiobEmerald => "Niob Smaragd",
            Self::DynamicViolet => "Material Violett",
            Self::OceanBlue => "Ozeanblau",
            Self::SunsetOrange => "Bernstein-Orange",
            Self::RosePink => "Pastell-Rosa",
            Self::ForestGreen => "Waldgrün",
            Self::CrimsonRed => "Karminrot",
            Self::GoldenAmber => "Sonnengelb",
        }
    }

    pub fn name_en(self) -> &'static str {
        match self {
            Self::NiobEmerald => "Niob Emerald",
            Self::DynamicViolet => "Material Violet",
            Self::OceanBlue => "Ocean Blue",
            Self::SunsetOrange => "Amber Orange",
            Self::RosePink => "Pastel Pink",
            Self::ForestGreen => "Forest Green",
            Self::CrimsonRed => "Crimson Red",
            Self::GoldenAmber => "Sunny Yellow",
        }
    }

    pub fn name(self, de: bool) -> &'static str {
        if de {
            self.name_de()
        } else {
            self.name_en()
        }
    }

    /// Schlüssel für die gespeicherten Einstellungen.
    pub fn key(self) -> &'static str {
        match self {
            Self::NiobEmerald => "niob_emerald",
            Self::DynamicViolet => "dynamic_violet",
            Self::OceanBlue => "ocean_blue",
            Self::SunsetOrange => "sunset_orange",
            Self::RosePink => "rose_pink",
            Self::ForestGreen => "forest_green",
            Self::CrimsonRed => "crimson_red",
            Self::GoldenAmber => "golden_amber",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|s| s.key() == key)
    }

    pub fn seed_color(self) -> Color32 {
        match self {
            Self::NiobEmerald => Color32::from_rgb(0x3D, 0xDC, 0x84),   // accent_light_green #FF3DDC84
            Self::DynamicViolet => Color32::from_rgb(0x7C, 0x4D, 0xFF), // Material You Violet
            Self::OceanBlue => Color32::from_rgb(0x19, 0x72, 0xE7),     // accent_blue #FF1972E7
            Self::SunsetOrange => Color32::from_rgb(0xF5, 0x7C, 0x00),  // accent_orange #FFF57C00
            Self::RosePink => Color32::from_rgb(0xFD, 0xA2, 0xB7),      // accent_rose #FFFDA2B7
            Self::ForestGreen => Color32::from_rgb(0x68, 0x9F, 0x38),   // accent_dark_green #FF689F38
            Self::CrimsonRed => Color32::from_rgb(0xE5, 0x39, 0x35),    // accent_red_light #FFE53935
            Self::GoldenAmber => Color32::from_rgb(0xE6, 0xCB, 0x36),   // accent_yellow #FFE6CB36
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct MaterialYouPalette {
    pub bg_surface: Color32,
    pub surface_container_low: Color32,
    pub surface_container: Color32,
    pub surface_container_high: Color32,
    pub surface_container_highest: Color32,
    pub primary: Color32,
    pub on_primary: Color32,
    pub primary_container: Color32,
    pub on_primary_container: Color32,
    pub secondary_container: Color32,
    pub on_secondary_container: Color32,
    pub tertiary_container: Color32,
    pub on_tertiary_container: Color32,
    pub on_surface: Color32,
    pub on_surface_variant: Color32,
    pub outline_variant: Color32,
    pub error: Color32,
    pub plotter_minor_grid: Color32,
    pub plotter_major_grid: Color32,
}

fn blend(a: Color32, b: Color32, t: f32) -> Color32 {
    let inv = 1.0 - t;
    Color32::from_rgb(
        (a.r() as f32 * inv + b.r() as f32 * t).round() as u8,
        (a.g() as f32 * inv + b.g() as f32 * t).round() as u8,
        (a.b() as f32 * inv + b.b() as f32 * t).round() as u8,
    )
}

impl MaterialYouPalette {
    /// Palette für Farbmodus und Akzent. Text- und Akzentfarben werden so
    /// nachgeregelt, dass sie WCAG AA (4,5:1) auf allen Flächen erreichen,
    /// auf denen sie vorkommen – auch bei hellen Akzenten im Hell-Modus.
    pub fn generate(mode: ThemeMode, seed: AccentSeed) -> Self {
        let mut pal = Self::base(mode, seed);
        let surfaces = [
            pal.bg_surface,
            pal.surface_container_low,
            pal.surface_container,
            pal.surface_container_high,
        ];
        const AA: f64 = 4.6; // kleine Reserve über 4,5
        pal.primary = ensure_contrast(pal.primary, &surfaces, AA);
        if contrast_ratio(pal.on_primary, pal.primary) < AA {
            pal.on_primary = readable_on(pal.primary);
        }
        pal.error = ensure_contrast(pal.error, &surfaces, AA);
        pal.on_surface_variant = ensure_contrast(pal.on_surface_variant, &surfaces, AA);
        pal.on_primary_container = ensure_contrast(pal.on_primary_container, &[pal.primary_container], AA);
        pal.on_secondary_container =
            ensure_contrast(pal.on_secondary_container, &[pal.secondary_container], AA);
        pal.on_tertiary_container =
            ensure_contrast(pal.on_tertiary_container, &[pal.tertiary_container], AA);
        pal
    }

    /// Farbe der Plotter-Kurve `idx`, lesbar auf dem Plot-Hintergrund und als
    /// Beschriftung („f₁(x) =“) auf den Karten.
    pub fn plot_color(&self, idx: usize) -> Color32 {
        ensure_contrast(
            PLOT_BASE_COLORS[idx % PLOT_COLOR_COUNT],
            &[self.surface_container_low, self.surface_container],
            4.5,
        )
    }

    fn base(mode: ThemeMode, seed: AccentSeed) -> Self {
        let base = seed.seed_color();
        match mode {
            ThemeMode::Dark => {
                let dark_bg = blend(Color32::from_rgb(0x12, 0x13, 0x16), base, 0.05);
                let low = blend(Color32::from_rgb(0x1A, 0x1C, 0x20), base, 0.07);
                let container = blend(Color32::from_rgb(0x21, 0x23, 0x29), base, 0.09);
                let high = blend(Color32::from_rgb(0x2B, 0x2E, 0x35), base, 0.12);
                let highest = blend(Color32::from_rgb(0x35, 0x38, 0x41), base, 0.15);
                let primary = blend(base, Color32::WHITE, 0.25);
                let primary_container = blend(Color32::from_rgb(0x16, 0x18, 0x1E), base, 0.42);
                let secondary_container = blend(Color32::from_rgb(0x25, 0x28, 0x30), base, 0.22);
                let tertiary_container = blend(
                    Color32::from_rgb(0x2A, 0x24, 0x34),
                    Color32::from_rgb(0xBB, 0x86, 0xFC),
                    0.26,
                );
                Self {
                    bg_surface: dark_bg,
                    surface_container_low: low,
                    surface_container: container,
                    surface_container_high: high,
                    surface_container_highest: highest,
                    primary,
                    on_primary: Color32::from_rgb(0x0D, 0x15, 0x12),
                    primary_container,
                    on_primary_container: blend(base, Color32::WHITE, 0.72),
                    secondary_container,
                    on_secondary_container: Color32::from_rgb(0xE2, 0xE6, 0xEE),
                    tertiary_container,
                    on_tertiary_container: Color32::from_rgb(0xF0, 0xE6, 0xFF),
                    on_surface: Color32::from_rgb(0xF1, 0xF3, 0xF6),
                    on_surface_variant: Color32::from_rgb(0xAA, 0xB0, 0xBC),
                    outline_variant: blend(Color32::from_rgb(0x3D, 0x42, 0x4C), base, 0.15),
                    error: Color32::from_rgb(0xFF, 0x6B, 0x6B),
                    plotter_minor_grid: Color32::from_rgb(0x26, 0x2A, 0x32),
                    plotter_major_grid: Color32::from_rgb(0x3E, 0x44, 0x52),
                }
            }
            ThemeMode::Amoled => {
                let primary = blend(base, Color32::WHITE, 0.22);
                let primary_container = blend(Color32::from_rgb(0x0A, 0x0A, 0x0D), base, 0.38);
                let secondary_container = blend(Color32::from_rgb(0x14, 0x16, 0x1A), base, 0.20);
                Self {
                    bg_surface: Color32::from_rgb(0x00, 0x00, 0x00), // background_amoled #FF000000
                    surface_container_low: Color32::from_rgb(0x09, 0x0A, 0x0C),
                    surface_container: blend(Color32::from_rgb(0x11, 0x12, 0x15), base, 0.06),
                    surface_container_high: blend(Color32::from_rgb(0x1A, 0x1C, 0x20), base, 0.10),
                    surface_container_highest: blend(Color32::from_rgb(0x24, 0x27, 0x2D), base, 0.14),
                    primary,
                    on_primary: Color32::BLACK,
                    primary_container,
                    on_primary_container: blend(base, Color32::WHITE, 0.78),
                    secondary_container,
                    on_secondary_container: Color32::from_rgb(0xE5, 0xE9, 0xF0),
                    tertiary_container: Color32::from_rgb(0x24, 0x1C, 0x30),
                    on_tertiary_container: Color32::from_rgb(0xEC, 0xDC, 0xFF),
                    on_surface: Color32::WHITE,
                    on_surface_variant: Color32::from_rgb(0xA6, 0xAC, 0xB8),
                    outline_variant: Color32::from_rgb(0x2D, 0x31, 0x39),
                    error: Color32::from_rgb(0xFF, 0x52, 0x52),
                    plotter_minor_grid: Color32::from_rgb(0x1A, 0x1C, 0x20),
                    plotter_major_grid: Color32::from_rgb(0x34, 0x38, 0x42),
                }
            }
            ThemeMode::Light => {
                // Uses FunctionPlotting2DView.onDraw original light grid colors #FFE6E6E6 & #FFBEBEBE
                let bg = blend(Color32::from_rgb(0xFA, 0xFB, 0xFD), base, 0.04);
                let low = blend(Color32::from_rgb(0xF2, 0xF4, 0xF8), base, 0.06);
                let container = blend(Color32::from_rgb(0xEA, 0xED, 0xF3), base, 0.09);
                let high = blend(Color32::from_rgb(0xE1, 0xE5, 0xED), base, 0.12);
                let highest = blend(Color32::from_rgb(0xD7, 0xDC, 0xE6), base, 0.15);
                let primary = blend(base, Color32::from_rgb(0x0B, 0x20, 0x27), 0.25);
                let primary_container = blend(Color32::WHITE, base, 0.28);
                let secondary_container = blend(Color32::from_rgb(0xEA, 0xEE, 0xF4), base, 0.18);
                Self {
                    bg_surface: bg,
                    surface_container_low: low,
                    surface_container: container,
                    surface_container_high: high,
                    surface_container_highest: highest,
                    primary,
                    on_primary: Color32::WHITE,
                    primary_container,
                    on_primary_container: blend(base, Color32::BLACK, 0.62),
                    secondary_container,
                    on_secondary_container: Color32::from_rgb(0x1E, 0x24, 0x2B),
                    tertiary_container: Color32::from_rgb(0xEB, 0xDD, 0xFF),
                    on_tertiary_container: Color32::from_rgb(0x28, 0x13, 0x44),
                    on_surface: Color32::from_rgb(0x17, 0x1A, 0x20),
                    on_surface_variant: Color32::from_rgb(0x52, 0x58, 0x66),
                    outline_variant: blend(Color32::from_rgb(0xC6, 0xCC, 0xD6), base, 0.15),
                    error: Color32::from_rgb(0xD3, 0x2F, 0x2F),
                    plotter_minor_grid: Color32::from_rgb(0xE6, 0xE6, 0xE6), // #FFE6E6E6 from onDraw
                    plotter_major_grid: Color32::from_rgb(0xBE, 0xBE, 0xBE), // #FFBEBEBE from onDraw
                }
            }
        }
    }

    pub fn apply(&self, ctx: &egui::Context, mode: ThemeMode) {
        let mut style: Style = (*ctx.global_style()).clone();
        let mut visuals = match mode {
            ThemeMode::Light => Visuals::light(),
            ThemeMode::Dark | ThemeMode::Amoled => Visuals::dark(),
        };

        visuals.panel_fill = self.bg_surface;
        visuals.window_fill = self.surface_container;
        visuals.extreme_bg_color = self.surface_container_low;
        visuals.faint_bg_color = self.surface_container_low;
        visuals.override_text_color = Some(self.on_surface);

        let r12 = CornerRadius::same(12);
        let r16 = CornerRadius::same(16);

        visuals.widgets.noninteractive.bg_fill = self.surface_container;
        visuals.widgets.noninteractive.weak_bg_fill = self.surface_container;
        visuals.widgets.noninteractive.bg_stroke = Stroke::new(1.0_f32, self.outline_variant);
        visuals.widgets.noninteractive.fg_stroke = Stroke::new(1.0_f32, self.on_surface);
        visuals.widgets.noninteractive.corner_radius = r12;

        visuals.widgets.inactive.bg_fill = self.surface_container_high;
        visuals.widgets.inactive.weak_bg_fill = self.surface_container_high;
        visuals.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, self.outline_variant);
        visuals.widgets.inactive.fg_stroke = Stroke::new(1.2_f32, self.on_surface);
        visuals.widgets.inactive.corner_radius = r12;

        visuals.widgets.hovered.bg_fill = self.secondary_container;
        visuals.widgets.hovered.weak_bg_fill = self.secondary_container;
        visuals.widgets.hovered.bg_stroke = Stroke::new(1.5_f32, self.primary);
        visuals.widgets.hovered.fg_stroke = Stroke::new(1.5_f32, self.on_surface);
        visuals.widgets.hovered.corner_radius = r16;

        visuals.widgets.active.bg_fill = self.primary_container;
        visuals.widgets.active.weak_bg_fill = self.primary_container;
        visuals.widgets.active.bg_stroke = Stroke::new(1.5_f32, self.primary);
        visuals.widgets.active.fg_stroke = Stroke::new(1.5_f32, self.on_primary_container);
        visuals.widgets.active.corner_radius = r16;

        visuals.selection.bg_fill = self.primary_container;
        visuals.selection.stroke = Stroke::new(1.5_f32, self.primary);
        visuals.text_cursor.stroke.color = self.primary;

        style.visuals = visuals;
        style.spacing.item_spacing = egui::vec2(6.0, 6.0);
        style.spacing.button_padding = egui::vec2(10.0, 6.0);
        ctx.set_global_style(style);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Befund aus der UI-Bewertung: Im Hell-Modus lagen Ergebnistext und
    /// „=“-Taste mit hellen Akzenten bei 2,2–3,7:1.
    #[test]
    fn all_palettes_reach_wcag_aa_for_text() {
        for mode in [ThemeMode::Dark, ThemeMode::Light, ThemeMode::Amoled] {
            for seed in AccentSeed::ALL {
                let p = MaterialYouPalette::generate(mode, seed);
                let surfaces = [p.bg_surface, p.surface_container_low, p.surface_container, p.surface_container_high];
                for bg in surfaces {
                    assert!(contrast_ratio(p.primary, bg) >= 4.5, "{mode:?}/{seed:?} primary");
                    assert!(contrast_ratio(p.error, bg) >= 4.5, "{mode:?}/{seed:?} error");
                    assert!(contrast_ratio(p.on_surface_variant, bg) >= 4.5, "{mode:?}/{seed:?} variant");
                }
                assert!(contrast_ratio(p.on_primary, p.primary) >= 4.5, "{mode:?}/{seed:?} on_primary");
                assert!(contrast_ratio(p.on_primary_container, p.primary_container) >= 4.5);
                assert!(contrast_ratio(p.on_secondary_container, p.secondary_container) >= 4.5);
                assert!(contrast_ratio(p.on_tertiary_container, p.tertiary_container) >= 4.5);
                for i in 0..PLOT_COLOR_COUNT {
                    assert!(contrast_ratio(p.plot_color(i), p.surface_container) >= 4.5, "{mode:?} plot {i}");
                }
            }
        }
    }

    #[test]
    fn theme_and_accent_keys_round_trip() {
        for seed in AccentSeed::ALL {
            assert_eq!(AccentSeed::from_key(seed.key()), Some(seed));
        }
        for mode in [ThemeMode::Dark, ThemeMode::Light, ThemeMode::Amoled] {
            assert_eq!(ThemeMode::from_key(mode.key()), Some(mode));
        }
    }
}
