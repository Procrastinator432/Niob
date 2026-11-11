//! Material You (Material 3) Dynamic Tonal Palette & Theme Engine
//! Combines Jade Scientific Calculator 1.1 color modes (`light`, `dark`, `amoled`)
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
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AccentSeed {
    JadeEmerald,
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
        Self::JadeEmerald,
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
            Self::JadeEmerald => "Jade Smaragd",
            Self::DynamicViolet => "Material Violett",
            Self::OceanBlue => "Ozeanblau",
            Self::SunsetOrange => "Bernstein-Orange",
            Self::RosePink => "Pastell-Rosa",
            Self::ForestGreen => "Waldgrün",
            Self::CrimsonRed => "Karminrot",
            Self::GoldenAmber => "Sonnengelb",
        }
    }

    pub fn seed_color(self) -> Color32 {
        match self {
            Self::JadeEmerald => Color32::from_rgb(0x3D, 0xDC, 0x84),   // accent_light_green #FF3DDC84
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
    pub fn generate(mode: ThemeMode, seed: AccentSeed) -> Self {
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
        visuals.widgets.noninteractive.bg_stroke = Stroke::new(1.0, self.outline_variant);
        visuals.widgets.noninteractive.fg_stroke = Stroke::new(1.0, self.on_surface);
        visuals.widgets.noninteractive.corner_radius = r12;

        visuals.widgets.inactive.bg_fill = self.surface_container_high;
        visuals.widgets.inactive.weak_bg_fill = self.surface_container_high;
        visuals.widgets.inactive.bg_stroke = Stroke::new(1.0, self.outline_variant);
        visuals.widgets.inactive.fg_stroke = Stroke::new(1.2, self.on_surface);
        visuals.widgets.inactive.corner_radius = r12;

        visuals.widgets.hovered.bg_fill = self.secondary_container;
        visuals.widgets.hovered.weak_bg_fill = self.secondary_container;
        visuals.widgets.hovered.bg_stroke = Stroke::new(1.5, self.primary);
        visuals.widgets.hovered.fg_stroke = Stroke::new(1.5, self.on_surface);
        visuals.widgets.hovered.corner_radius = r16;

        visuals.widgets.active.bg_fill = self.primary_container;
        visuals.widgets.active.weak_bg_fill = self.primary_container;
        visuals.widgets.active.bg_stroke = Stroke::new(1.5, self.primary);
        visuals.widgets.active.fg_stroke = Stroke::new(1.5, self.on_primary_container);
        visuals.widgets.active.corner_radius = r16;

        visuals.selection.bg_fill = self.primary_container;
        visuals.selection.stroke = Stroke::new(1.5, self.primary);

        style.visuals = visuals;
        style.spacing.item_spacing = egui::vec2(6.0, 6.0);
        style.spacing.button_padding = egui::vec2(10.0, 6.0);
        ctx.set_global_style(style);
    }
}
