//! Physical, Electromagnetic, Chemical, and Mathematical Constants & UI Layout Metrics
//! extracted from Jade Scientific Calculator 1.1 (`Lc/a/a/e/h;-><clinit>()V`,
//! `VariableMoreActivity.<init>()V`, and `res/layout/*.xml`).

// --- Exact Mechanics Sheet Constants (`app/mechanics.json`) ---
pub const SPEED_OF_LIGHT: f64 = 299790000.0;
pub const PERMEABILITY_VACUUM: f64 = 0.0000012566;
pub const PERMITTIVITY_VACUUM: f64 = 8.8542e-12;
pub const GRAVITATION_CONST: f64 = 6.6738e-11;
pub const PLANCK_CONSTANT: f64 = 6.6261e-34;
pub const FINE_STRUCTURE_CONST: f64 = 0.0072974;
pub const BOLTZMANN_CONST: f64 = 1.3806e-23;
pub const GRAVITATIONAL_ACCELERATION: f64 = 9.8067;
pub const PLANCK_MASS: f64 = 2.1765e-8;
pub const PLANCK_TIME: f64 = 5.3911e-44;
pub const PLANCK_LENGTH: f64 = 1.6162e-35;

pub const ELECTRON_CHARGE: f64 = 1.6022e-19;
pub const IMPEDANCE_VACUUM: f64 = 376.73;
pub const MAGNETIC_FLUX_QUANTUM: f64 = 2.0678e-15;
pub const JOSEPHSON_CONST: f64 = 4.835e14;
pub const VON_KLITZING_CONST: f64 = 25813.0;
pub const CONDUCTANCE_QUANTUM: f64 = 0.000077481;
pub const STEFAN_BOLTZMANN_CONST: f64 = 5.6704e-8;
pub const RYDBERG_CONST: f64 = 10974000.0;
pub const HARTREE_ENERGY: f64 = 4.3597e-18;
pub const BOHR_MAGNETON: f64 = 13996000000.0;
pub const ELECTRON_MAGNETIC_MOMENT: f64 = -9.2848e-24;

pub const AVOGADRO_CONST: f64 = 6.0221e23;
pub const ATOMIC_MASS_CONST: f64 = 1.6605e-27;
pub const FARADAY_CONST: f64 = 96485.0;
pub const MOLAR_GAS_CONSTANT: f64 = 8.3145;
pub const MASS_ELECTRON: f64 = 9.1094e-31;
pub const MASS_PROTON: f64 = 1.6749e-24;
pub const MASS_NEUTRON: f64 = 1.6726e-24;

pub const PERCENT_VAR: f64 = 0.01;
pub const SYMBOLIC_WATCHDOG_TIMEOUT_MS: u64 = 4000;
pub const ASSIGNABLE_VARIABLE_SLOTS: usize = 11;

pub const INPUT_TERM_FONT_SIZE: f32 = 24.0;
pub const INPUT_TERM_MIN_FONT_SIZE: f32 = 16.0;
pub const HISTORY_TERM_FONT_SIZE: f32 = 18.0;
pub const KEYBOARD_CARD_CORNER_RADIUS: f32 = 20.0;
pub const KEYBOARD_SECTION_BAR_HEIGHT: f32 = 32.0;

pub const PLOTTER_MINOR_GRID_STROKE: f32 = 1.0;
pub const PLOTTER_MAJOR_GRID_STROKE: f32 = 1.25;
pub const PLOTTER_AXIS_LABEL_SIZE: f32 = 19.0;

/// The 11 assignable user variables from `Lc/a/a/e/h;-><clinit>()V`
pub const USER_VARIABLES: [&str; ASSIGNABLE_VARIABLE_SLOTS] = [
    "a", "b", "c", "d", "k", "l", "m", "n", "x", "y", "z",
];

/// Default 11 variable/constant slots on the `a b c` keyboard (`Lc/a/a/a;->e`)
pub const DEFAULT_KEYBOARD_SLOTS: [&str; ASSIGNABLE_VARIABLE_SLOTS] = [
    "%", "a", "b", "c", "k", "l", "m", "n", "x", "y", "z",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConstantCategory {
    Universal,
    Electromagnetic,
    Chemical,
    Mathematical,
}

impl ConstantCategory {
    pub fn title_de(self) -> &'static str {
        match self {
            Self::Universal => "Universelle Naturkonstanten",
            Self::Electromagnetic => "Elektromagnetische Konstanten",
            Self::Chemical => "Chemische & Atomare Konstanten",
            Self::Mathematical => "Mathematische Konstanten",
        }
    }

    pub fn title_en(self) -> &'static str {
        match self {
            Self::Universal => "Universal Physical Constants",
            Self::Electromagnetic => "Electro-Magnetic Constants",
            Self::Chemical => "Chemical Constants",
            Self::Mathematical => "Mathematical Constants",
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct ScientificConstant {
    pub key: &'static str,
    pub symbol: &'static str,
    pub token: &'static str,
    pub name_de: &'static str,
    pub name_en: &'static str,
    pub display_value: &'static str,
    pub value: f64,
    pub unit: &'static str,
    pub category: ConstantCategory,
}

pub const SCIENTIFIC_CONSTANTS: &[ScientificConstant] = &[
    // Universal Physical Constants
    ScientificConstant {
        key: "speed_of_light",
        symbol: "c₀",
        token: "c0",
        name_de: "Lichtgeschwindigkeit im Vakuum",
        name_en: "Speed of Light in Vacuum",
        display_value: "2.9979 × 10⁸",
        value: SPEED_OF_LIGHT,
        unit: "m / s",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "permeability_vacuum",
        symbol: "μ₀",
        token: "mu0",
        name_de: "Magnetische Feldkonstante",
        name_en: "Permeability of Vacuum",
        display_value: "12.566 × 10⁻⁷",
        value: PERMEABILITY_VACUUM,
        unit: "N / A²",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "permittivity_vacuum",
        symbol: "ε₀",
        token: "eps0",
        name_de: "Elektrische Feldkonstante",
        name_en: "Permittivity of Vacuum",
        display_value: "8.8542 × 10⁻¹²",
        value: PERMITTIVITY_VACUUM,
        unit: "F / m",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "gravitation_const",
        symbol: "G",
        token: "G_const",
        name_de: "Gravitationskonstante",
        name_en: "Gravitational Constant",
        display_value: "6.6738 × 10⁻¹¹",
        value: GRAVITATION_CONST,
        unit: "m³ / (kg · s²)",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "planck_constant",
        symbol: "h",
        token: "h_planck",
        name_de: "Plancksches Wirkungsquantum",
        name_en: "Planck Constant",
        display_value: "6.6261 × 10⁻³⁴",
        value: PLANCK_CONSTANT,
        unit: "J · s",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "fine_structure_const",
        symbol: "α",
        token: "alpha_fs",
        name_de: "Feinstrukturkonstante",
        name_en: "Fine-Structure Constant",
        display_value: "7.2974 × 10⁻³",
        value: FINE_STRUCTURE_CONST,
        unit: "1",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "boltzmann_const",
        symbol: "k_B",
        token: "k_B",
        name_de: "Boltzmann-Konstante",
        name_en: "Boltzmann Constant",
        display_value: "1.3806 × 10⁻²³",
        value: BOLTZMANN_CONST,
        unit: "J / K",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "gravitational_acceleration",
        symbol: "g",
        token: "g_earth",
        name_de: "Normfallbeschleunigung",
        name_en: "Gravitational Acceleration",
        display_value: "9.8067",
        value: GRAVITATIONAL_ACCELERATION,
        unit: "m / s²",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "planck_mass",
        symbol: "m_P",
        token: "m_P",
        name_de: "Planck-Masse",
        name_en: "Planck Mass",
        display_value: "2.1765 × 10⁻⁸",
        value: PLANCK_MASS,
        unit: "kg",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "planck_time",
        symbol: "t_P",
        token: "t_P",
        name_de: "Planck-Zeit",
        name_en: "Planck Time",
        display_value: "5.3911 × 10⁻⁴⁴",
        value: PLANCK_TIME,
        unit: "s",
        category: ConstantCategory::Universal,
    },
    ScientificConstant {
        key: "planck_length",
        symbol: "l_P",
        token: "l_P",
        name_de: "Planck-Länge",
        name_en: "Planck Length",
        display_value: "1.6162 × 10⁻³⁵",
        value: PLANCK_LENGTH,
        unit: "m",
        category: ConstantCategory::Universal,
    },

    // Electromagnetic Constants
    ScientificConstant {
        key: "electron_charge",
        symbol: "e⁻",
        token: "e_charge",
        name_de: "Elementarladung",
        name_en: "Elementary Charge",
        display_value: "1.6022 × 10⁻¹⁹",
        value: ELECTRON_CHARGE,
        unit: "C",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "impedance_vacuum",
        symbol: "Z₀",
        token: "Z0",
        name_de: "Wellenwiderstand des Vakuums",
        name_en: "Characteristic Impedance of Vacuum",
        display_value: "376.73",
        value: IMPEDANCE_VACUUM,
        unit: "Ω",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "magnetic_flux_quantum",
        symbol: "Φ₀",
        token: "Phi0",
        name_de: "Magnetisches Flussquant",
        name_en: "Magnetic Flux Quantum",
        display_value: "2.0678 × 10⁻¹⁵",
        value: MAGNETIC_FLUX_QUANTUM,
        unit: "Wb",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "josephson_const",
        symbol: "K_J",
        token: "K_J",
        name_de: "Josephson-Konstante",
        name_en: "Josephson Constant",
        display_value: "4.835 × 10¹⁴",
        value: JOSEPHSON_CONST,
        unit: "Hz / V",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "von_klitzing_const",
        symbol: "R_K",
        token: "R_K",
        name_de: "Von-Klitzing-Konstante",
        name_en: "Von Klitzing Constant",
        display_value: "2.5813 × 10⁴",
        value: VON_KLITZING_CONST,
        unit: "Ω",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "conductance_quantum",
        symbol: "G₀",
        token: "G0_cond",
        name_de: "Leitwertsquant",
        name_en: "Conductance Quantum",
        display_value: "7.7481 × 10⁻⁵",
        value: CONDUCTANCE_QUANTUM,
        unit: "S",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "stefan_boltzmann_const",
        symbol: "σ",
        token: "sigma_sb",
        name_de: "Stefan-Boltzmann-Konstante",
        name_en: "Stefan-Boltzmann Constant",
        display_value: "5.6704 × 10⁻⁸",
        value: STEFAN_BOLTZMANN_CONST,
        unit: "W / (m² · K⁴)",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "rydberg_const",
        symbol: "R_∞",
        token: "R_inf",
        name_de: "Rydberg-Konstante",
        name_en: "Rydberg Constant",
        display_value: "1.0974 × 10⁷",
        value: RYDBERG_CONST,
        unit: "1 / m",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "hartree_energy",
        symbol: "E_H",
        token: "E_H",
        name_de: "Hartree-Energie",
        name_en: "Hartree Energy",
        display_value: "4.3597 × 10⁻¹⁸",
        value: HARTREE_ENERGY,
        unit: "J",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "bohr_magneton",
        symbol: "μ_B",
        token: "mu_B",
        name_de: "Bohrsches Magneton",
        name_en: "Bohr Magneton",
        display_value: "1.3996 × 10¹⁰",
        value: BOHR_MAGNETON,
        unit: "Hz / T",
        category: ConstantCategory::Electromagnetic,
    },
    ScientificConstant {
        key: "electron_magnetic_moment",
        symbol: "μ_e",
        token: "mu_e",
        name_de: "Magnetisches Moment des Elektrons",
        name_en: "Electron Magnetic Moment",
        display_value: "-9.2848 × 10⁻²⁴",
        value: ELECTRON_MAGNETIC_MOMENT,
        unit: "J / T",
        category: ConstantCategory::Electromagnetic,
    },

    // Chemical Constants
    ScientificConstant {
        key: "avogadro_const",
        symbol: "N_A",
        token: "N_A",
        name_de: "Avogadro-Konstante",
        name_en: "Avogadro Constant",
        display_value: "6.0221 × 10²³",
        value: AVOGADRO_CONST,
        unit: "1 / mol",
        category: ConstantCategory::Chemical,
    },
    ScientificConstant {
        key: "atomic_mass_const",
        symbol: "u",
        token: "u_amu",
        name_de: "Atomare Masseneinheit",
        name_en: "Atomic Mass Constant",
        display_value: "1.6605 × 10⁻²⁷",
        value: ATOMIC_MASS_CONST,
        unit: "kg",
        category: ConstantCategory::Chemical,
    },
    ScientificConstant {
        key: "faraday_const",
        symbol: "F",
        token: "F_faraday",
        name_de: "Faraday-Konstante",
        name_en: "Faraday Constant",
        display_value: "9.6485 × 10⁴",
        value: FARADAY_CONST,
        unit: "C / mol",
        category: ConstantCategory::Chemical,
    },
    ScientificConstant {
        key: "molar_gas_constant",
        symbol: "R",
        token: "R_gas",
        name_de: "Universelle Gaskonstante",
        name_en: "Molar Gas Constant",
        display_value: "8.3145",
        value: MOLAR_GAS_CONSTANT,
        unit: "J / (mol · K)",
        category: ConstantCategory::Chemical,
    },
    ScientificConstant {
        key: "mass_electron",
        symbol: "m_e",
        token: "m_e",
        name_de: "Elektronenmasse",
        name_en: "Mass of an Electron",
        display_value: "9.1094 × 10⁻³¹",
        value: MASS_ELECTRON,
        unit: "kg",
        category: ConstantCategory::Chemical,
    },
    ScientificConstant {
        key: "mass_proton",
        symbol: "m_p",
        token: "m_p",
        name_de: "Protonenmasse",
        name_en: "Mass of a Proton",
        display_value: "1.6749 × 10⁻²⁴",
        value: MASS_PROTON,
        unit: "g",
        category: ConstantCategory::Chemical,
    },
    ScientificConstant {
        key: "mass_neutron",
        symbol: "m_n",
        token: "m_n",
        name_de: "Neutronenmasse",
        name_en: "Mass of a Neutron",
        display_value: "1.6726 × 10⁻²⁴",
        value: MASS_NEUTRON,
        unit: "g",
        category: ConstantCategory::Chemical,
    },

    // Mathematical Constants
    ScientificConstant {
        key: "percent_var",
        symbol: "%",
        token: "%",
        name_de: "Prozent (Hundertstel)",
        name_en: "Percent (1/100)",
        display_value: "1 / 100 (0.01)",
        value: PERCENT_VAR,
        unit: "",
        category: ConstantCategory::Mathematical,
    },
    ScientificConstant {
        key: "pi_var",
        symbol: "π",
        token: "π",
        name_de: "Kreiszahl Pi",
        name_en: "Archimedes' Constant π",
        display_value: "3.14159265…",
        value: std::f64::consts::PI,
        unit: "",
        category: ConstantCategory::Mathematical,
    },
    ScientificConstant {
        key: "e_var",
        symbol: "e",
        token: "e",
        name_de: "Eulersche Zahl e",
        name_en: "Euler's Number e",
        display_value: "2.71828182…",
        value: std::f64::consts::E,
        unit: "",
        category: ConstantCategory::Mathematical,
    },
];
