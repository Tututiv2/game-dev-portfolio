#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
flat in float animatedChatPhase;
flat in float animatedChatBrightness;
flat in float animatedChatDirection;
flat in float animatedChatTheme;

out vec4 fragColor;

vec3 rainbowColor(float hue) {
    vec3 wave = abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    vec3 fullSaturation = clamp(wave - 1.0, 0.0, 1.0);
    return mix(vec3(1.0), fullSaturation, 0.88);
}

vec3 threeColor(float phase, vec3 first, vec3 second, vec3 third) {
    float section = fract(phase) * 3.0;
    if (section < 1.0) {
        return mix(first, second, smoothstep(0.0, 1.0, section));
    }
    if (section < 2.0) {
        return mix(second, third, smoothstep(0.0, 1.0, section - 1.0));
    }
    return mix(third, first, smoothstep(0.0, 1.0, section - 2.0));
}

vec3 animatedThemeColor(float theme, float phase) {
    if (theme < 0.5) {
        return rainbowColor(phase);
    }
    if (theme < 1.5) {
        // Void Pulse: the Superior Crate's 1% VOID / Shadow exclusive.
        float pulse = 0.5 - 0.5 * cos(fract(phase) * 6.28318530718);
        return mix(vec3(0.55, 0.0, 1.0), vec3(0.004, 0.0, 0.008),
                smoothstep(0.08, 0.92, pulse));
    }
    if (theme < 2.5) {
        return threeColor(phase,
                vec3(0.42, 0.005, 0.015),
                vec3(1.0, 0.18, 0.01),
                vec3(1.0, 0.78, 0.06));
    }
    if (theme < 3.5) {
        return threeColor(phase,
                vec3(0.015, 0.08, 0.34),
                vec3(0.08, 0.72, 1.0),
                vec3(0.90, 0.98, 1.0));
    }
    if (theme < 4.5) {
        return threeColor(phase,
                vec3(0.36, 0.03, 0.80),
                vec3(1.0, 0.16, 0.70),
                vec3(0.08, 0.28, 1.0));
    }
    if (theme < 5.5) {
        return threeColor(phase,
                vec3(0.0, 0.10, 0.14),
                vec3(0.0, 0.84, 0.90),
                vec3(0.76, 1.0, 1.0));
    }
    if (theme < 6.5) {
        return threeColor(phase,
                vec3(0.0, 1.0, 1.0),
                vec3(1.0, 0.0, 0.82),
                vec3(0.34, 0.0, 1.0));
    }
    if (theme < 7.5) {
        return threeColor(phase,
                vec3(0.58, 0.015, 0.005),
                vec3(1.0, 0.22, 0.01),
                vec3(1.0, 0.78, 0.05));
    }
    if (theme < 8.5) {
        return threeColor(phase,
                vec3(0.40, 0.0, 0.015),
                vec3(1.0, 0.02, 0.04),
                vec3(1.0, 0.94, 0.94));
    }
    if (theme < 9.5) {
        return threeColor(phase,
                vec3(0.0, 0.025, 0.0),
                vec3(0.0, 1.0, 0.18),
                vec3(0.80, 1.0, 0.84));
    }
    if (theme < 10.5) {
        return threeColor(phase,
                vec3(0.008, 0.0, 0.0),
                vec3(1.0, 0.01, 0.01),
                vec3(1.0, 0.55, 0.0));
    }
    if (theme < 11.5) {
        return threeColor(phase,
                vec3(1.0, 0.72, 0.02),
                vec3(1.0, 0.98, 0.86),
                vec3(0.46, 0.02, 0.82));
    }
    if (theme < 12.5) {
        // Sunset Boulevard
        return threeColor(phase,
                vec3(0.24, 0.04, 0.36),
                vec3(1.0, 0.15, 0.45),
                vec3(1.0, 0.45, 0.05));
    }
    if (theme < 13.5) {
        // Ocean Current
        return threeColor(phase,
                vec3(0.0, 0.09, 0.35),
                vec3(0.0, 0.62, 0.85),
                vec3(0.55, 1.0, 0.85));
    }
    if (theme < 14.5) {
        // Toxic
        return threeColor(phase,
                vec3(0.06, 0.09, 0.02),
                vec3(0.35, 1.0, 0.05),
                vec3(0.85, 1.0, 0.10));
    }
    if (theme < 15.5) {
        // Cotton Candy
        return threeColor(phase,
                vec3(1.0, 0.62, 0.80),
                vec3(1.0, 0.96, 0.98),
                vec3(0.52, 0.78, 1.0));
    }
    if (theme < 16.5) {
        // Golden Hour
        return threeColor(phase,
                vec3(0.85, 0.45, 0.03),
                vec3(1.0, 0.78, 0.22),
                vec3(1.0, 0.95, 0.75));
    }
    if (theme < 17.5) {
        // Emerald Vault
        return threeColor(phase,
                vec3(0.01, 0.22, 0.08),
                vec3(0.05, 0.85, 0.35),
                vec3(0.65, 1.0, 0.80));
    }
    if (theme < 18.5) {
        // Bubblegum
        return threeColor(phase,
                vec3(1.0, 0.10, 0.55),
                vec3(0.75, 0.55, 1.0),
                vec3(0.45, 0.75, 1.0));
    }
    if (theme < 19.5) {
        // Bloodmoon
        return threeColor(phase,
                vec3(0.05, 0.0, 0.0),
                vec3(0.45, 0.01, 0.02),
                vec3(1.0, 0.10, 0.08));
    }
    if (theme < 20.5) {
        // Aurora
        return threeColor(phase,
                vec3(0.05, 0.90, 0.45),
                vec3(0.0, 0.65, 0.75),
                vec3(0.55, 0.20, 0.95));
    }
    if (theme < 21.5) {
        // Firefly
        return threeColor(phase,
                vec3(0.05, 0.14, 0.04),
                vec3(1.0, 0.90, 0.30),
                vec3(0.20, 0.40, 0.10));
    }
    if (theme < 22.5) {
        // Deep Space
        return threeColor(phase,
                vec3(0.01, 0.01, 0.06),
                vec3(0.20, 0.10, 0.55),
                vec3(0.92, 0.95, 1.0));
    }
    if (theme < 23.5) {
        // Cherry Blossom
        return threeColor(phase,
                vec3(1.0, 0.55, 0.70),
                vec3(1.0, 0.97, 0.97),
                vec3(0.95, 0.30, 0.45));
    }
    if (theme < 24.5) {
        // Vaporwave
        return threeColor(phase,
                vec3(1.0, 0.20, 0.65),
                vec3(0.10, 0.90, 0.95),
                vec3(0.65, 0.55, 0.95));
    }
    if (theme < 25.5) {
        // Lava Lamp
        return threeColor(phase,
                vec3(1.0, 0.35, 0.02),
                vec3(0.85, 0.08, 0.05),
                vec3(0.35, 0.05, 0.55));
    }
    if (theme < 26.5) {
        // Mermaid
        return threeColor(phase,
                vec3(0.0, 0.55, 0.55),
                vec3(0.15, 0.85, 0.55),
                vec3(1.0, 0.80, 0.85));
    }
    if (theme < 27.5) {
        // Phantom
        return threeColor(phase,
                vec3(0.95, 0.97, 1.0),
                vec3(0.55, 0.70, 0.95),
                vec3(0.25, 0.28, 0.35));
    }
    if (theme < 28.5) {
        // Witch's Brew
        return threeColor(phase,
                vec3(0.45, 0.10, 0.65),
                vec3(0.20, 0.75, 0.15),
                vec3(0.04, 0.02, 0.06));
    }
    if (theme < 29.5) {
        // Solar Flare
        return threeColor(phase,
                vec3(1.0, 0.98, 0.90),
                vec3(1.0, 0.55, 0.05),
                vec3(0.90, 0.10, 0.02));
    }
    if (theme < 30.5) {
        // Royalty
        return threeColor(phase,
                vec3(0.35, 0.05, 0.65),
                vec3(0.10, 0.20, 0.85),
                vec3(1.0, 0.80, 0.15));
    }
    if (theme < 31.5) {
        // Dragonfire
        return threeColor(phase,
                vec3(0.55, 0.10, 0.85),
                vec3(1.0, 0.35, 0.75),
                vec3(0.08, 0.0, 0.10));
    }
    if (theme < 32.5) {
        // Crimson Court (the owner's dark purple -> dark red)
        return threeColor(phase,
                vec3(0.25, 0.02, 0.30),
                vec3(0.45, 0.05, 0.15),
                vec3(0.35, 0.01, 0.03));
    }
    if (theme < 33.5) {
        // Midnight Rose
        return threeColor(phase,
                vec3(0.10, 0.01, 0.14),
                vec3(0.85, 0.25, 0.45),
                vec3(0.40, 0.02, 0.08));
    }
    if (theme < 34.5) {
        // Obsidian Flow
        return threeColor(phase,
                vec3(0.03, 0.01, 0.05),
                vec3(0.45, 0.15, 0.75),
                vec3(0.05, 0.02, 0.08));
    }
    if (theme < 35.5) {
        // Jungle Idol
        return threeColor(phase,
                vec3(0.03, 0.25, 0.08),
                vec3(0.95, 0.75, 0.20),
                vec3(0.25, 0.45, 0.15));
    }
    if (theme < 36.5) {
        // Arctic Night
        return threeColor(phase,
                vec3(0.03, 0.06, 0.25),
                vec3(0.85, 0.95, 1.0),
                vec3(0.35, 0.85, 0.65));
    }
    if (theme < 37.5) {
        // Emberfall
        return threeColor(phase,
                vec3(0.30, 0.28, 0.27),
                vec3(1.0, 0.45, 0.08),
                vec3(0.10, 0.08, 0.07));
    }
    if (theme < 38.5) {
        // Gilded Onyx
        return threeColor(phase,
                vec3(0.04, 0.03, 0.03),
                vec3(1.0, 0.80, 0.25),
                vec3(0.08, 0.06, 0.05));
    }
    if (theme < 39.5) {
        // Nebula Bloom
        return threeColor(phase,
                vec3(0.85, 0.10, 0.75),
                vec3(0.10, 0.10, 0.45),
                vec3(1.0, 0.30, 0.65));
    }
    if (theme < 40.5) {
        // Serpent's Eye
        return threeColor(phase,
                vec3(0.25, 0.85, 0.10),
                vec3(0.10, 0.45, 0.40),
                vec3(0.03, 0.06, 0.03));
    }
    if (theme < 41.5) {
        // Stormfront
        return threeColor(phase,
                vec3(0.35, 0.40, 0.45),
                vec3(0.98, 0.98, 1.0),
                vec3(0.15, 0.25, 0.55));
    }
    if (theme < 42.5) {
        // Sandstorm
        return threeColor(phase,
                vec3(0.85, 0.64, 0.25),
                vec3(0.95, 0.87, 0.69),
                vec3(1.0, 0.98, 0.94));
    }
    if (theme < 43.5) {
        // Rose Gold
        return threeColor(phase,
                vec3(0.88, 0.58, 0.44),
                vec3(0.97, 0.78, 0.78),
                vec3(1.0, 0.91, 0.77));
    }
    if (theme < 44.5) {
        // Electric Blue
        return threeColor(phase,
                vec3(0.02, 0.10, 0.40),
                vec3(0.12, 0.84, 1.0),
                vec3(0.94, 0.98, 1.0));
    }
    if (theme < 45.5) {
        // Peppermint
        return threeColor(phase,
                vec3(0.88, 0.10, 0.15),
                vec3(1.0, 1.0, 1.0),
                vec3(0.12, 0.66, 0.36));
    }
    if (theme < 46.5) {
        // Coral Reef
        return threeColor(phase,
                vec3(1.0, 0.44, 0.57),
                vec3(1.0, 0.62, 0.27),
                vec3(0.18, 0.83, 0.77));
    }
    if (theme < 47.5) {
        // Iron Forge
        return threeColor(phase,
                vec3(0.29, 0.33, 0.38),
                vec3(1.0, 0.95, 0.84),
                vec3(1.0, 0.48, 0.10));
    }
    if (theme < 48.5) {
        // Twilight Meadow
        return threeColor(phase,
                vec3(0.71, 0.55, 0.85),
                vec3(0.50, 0.65, 0.47),
                vec3(0.18, 0.25, 0.43));
    }
    if (theme < 49.5) {
        // Ultraviolet
        return threeColor(phase,
                vec3(0.10, 0.04, 0.24),
                vec3(0.61, 0.17, 1.0),
                vec3(0.95, 0.91, 1.0));
    }
    if (theme < 50.5) {
        // Monarch
        return threeColor(phase,
                vec3(1.0, 0.46, 0.09),
                vec3(0.08, 0.05, 0.03),
                vec3(1.0, 0.91, 0.76));
    }
    if (theme < 51.5) {
        // Jade Temple
        return threeColor(phase,
                vec3(0.12, 0.65, 0.48),
                vec3(0.90, 0.76, 0.30),
                vec3(0.04, 0.24, 0.20));
    }
    if (theme < 52.5) {
        // Rust Belt
        return threeColor(phase,
                vec3(0.70, 0.35, 0.12),
                vec3(0.42, 0.23, 0.10),
                vec3(0.25, 0.28, 0.30));
    }
    if (theme < 53.5) {
        // Blacklight
        return threeColor(phase,
                vec3(0.22, 1.0, 0.08),
                vec3(1.0, 0.0, 0.88),
                vec3(0.03, 0.01, 0.05));
    }
    if (theme < 54.5) {
        // Sea Glass
        return threeColor(phase,
                vec3(0.56, 0.85, 0.79),
                vec3(0.90, 0.84, 0.69),
                vec3(0.98, 1.0, 0.99));
    }
    if (theme < 55.5) {
        // Plasma
        return threeColor(phase,
                vec3(1.0, 1.0, 1.0),
                vec3(0.64, 0.15, 1.0),
                vec3(0.04, 0.08, 0.40));
    }
    if (theme < 56.5) {
        // Autumn Fall
        return threeColor(phase,
                vec3(0.70, 0.13, 0.09),
                vec3(0.88, 0.48, 0.09),
                vec3(0.55, 0.38, 0.09));
    }
    if (theme < 57.5) {
        // Cosmic Latte
        return threeColor(phase,
                vec3(1.0, 0.95, 0.86),
                vec3(0.79, 0.64, 0.48),
                vec3(0.91, 0.66, 0.63));
    }
    if (theme < 58.5) {
        // Noir
        return threeColor(phase,
                vec3(0.02, 0.02, 0.02),
                vec3(0.43, 0.43, 0.43),
                vec3(1.0, 1.0, 1.0));
    }
    if (theme < 59.5) {
        // Carnival
        return threeColor(phase,
                vec3(0.88, 0.11, 0.11),
                vec3(1.0, 0.83, 0.10),
                vec3(0.10, 0.42, 0.88));
    }
    if (theme < 60.5) {
        // Glacier
        return threeColor(phase,
                vec3(0.180, 0.420, 0.659),
                vec3(0.749, 0.914, 1.000),
                vec3(0.949, 0.984, 1.000));
    }
    if (theme < 61.5) {
        // Magma Vein
        return threeColor(phase,
                vec3(0.071, 0.027, 0.020),
                vec3(1.000, 0.290, 0.051),
                vec3(1.000, 0.690, 0.227));
    }
    if (theme < 62.5) {
        // Orchid
        return threeColor(phase,
                vec3(0.482, 0.173, 0.749),
                vec3(1.000, 0.561, 0.839),
                vec3(0.235, 0.039, 0.341));
    }
    if (theme < 63.5) {
        // Gunmetal
        return threeColor(phase,
                vec3(0.169, 0.188, 0.220),
                vec3(0.431, 0.478, 0.533),
                vec3(0.902, 0.925, 0.949));
    }
    if (theme < 64.5) {
        // Absinthe
        return threeColor(phase,
                vec3(0.122, 0.239, 0.047),
                vec3(0.498, 0.878, 0.169),
                vec3(0.910, 1.000, 0.541));
    }
    if (theme < 65.5) {
        // Sunken Temple
        return threeColor(phase,
                vec3(0.039, 0.227, 0.227),
                vec3(0.180, 0.545, 0.447),
                vec3(0.788, 0.635, 0.153));
    }
    if (theme < 66.5) {
        // Ember Moth
        return threeColor(phase,
                vec3(0.227, 0.212, 0.188),
                vec3(0.788, 0.416, 0.118),
                vec3(1.000, 0.851, 0.627));
    }
    if (theme < 67.5) {
        // Stained Glass
        return threeColor(phase,
                vec3(0.757, 0.071, 0.122),
                vec3(0.118, 0.227, 0.541),
                vec3(0.910, 0.725, 0.137));
    }
    if (theme < 68.5) {
        // Venom
        return threeColor(phase,
                vec3(0.043, 0.043, 0.059),
                vec3(0.224, 1.000, 0.078),
                vec3(0.541, 0.169, 0.886));
    }
    if (theme < 69.5) {
        // Last Light
        return threeColor(phase,
                vec3(0.063, 0.110, 0.227),
                vec3(1.000, 0.478, 0.184),
                vec3(1.000, 0.851, 0.651));
    }
    if (theme < 70.5) {
        // Tidal
        return threeColor(phase,
                vec3(0.039, 0.239, 0.384),
                vec3(0.247, 0.757, 0.788),
                vec3(0.910, 1.000, 1.000));
    }
    if (theme < 71.5) {
        // Fault Line
        return threeColor(phase,
                vec3(0.227, 0.180, 0.180),
                vec3(0.702, 0.227, 0.227),
                vec3(1.000, 0.820, 0.820));
    }
    if (theme < 72.5) {
        // Kindling
        return threeColor(phase,
                vec3(0.169, 0.078, 0.000),
                vec3(1.000, 0.549, 0.102),
                vec3(1.000, 0.878, 0.639));
    }
    if (theme < 73.5) {
        // Wisp
        return threeColor(phase,
                vec3(0.106, 0.165, 0.290),
                vec3(0.561, 0.722, 0.871),
                vec3(0.941, 0.969, 1.000));
    }
    if (theme < 74.5) {
        // Corrupted
        return threeColor(phase,
                vec3(0.043, 0.043, 0.043),
                vec3(0.000, 1.000, 0.612),
                vec3(1.000, 0.000, 0.659));
    }
    if (theme < 75.5) {
        // Living
        return threeColor(phase,
                vec3(0.290, 0.055, 0.122),
                vec3(0.878, 0.141, 0.369),
                vec3(1.000, 0.761, 0.820));
    }
    if (theme < 76.5) {
        // Malware (Corrupted's palette, no motion)
        return threeColor(phase,
                vec3(0.043, 0.043, 0.043),
                vec3(0.000, 1.000, 0.612),
                vec3(1.000, 0.000, 0.659));
    }
    if (theme < 77.5) {
        // Chrome
        return threeColor(phase,
                vec3(0.180, 0.204, 0.251),
                vec3(0.722, 0.769, 0.812),
                vec3(1.000, 1.000, 1.000));
    }
    if (theme < 78.5) {
        // Hologram
        return threeColor(phase,
                vec3(0.039, 0.165, 0.227),
                vec3(0.208, 0.839, 1.000),
                vec3(0.812, 0.965, 1.000));
    }
    if (theme < 79.5) {
        // Ember
        return threeColor(phase,
                vec3(0.165, 0.039, 0.000),
                vec3(1.000, 0.420, 0.102),
                vec3(1.000, 0.851, 0.541));
    }
    if (theme < 80.5) {
        // Broadcast
        return threeColor(phase,
                vec3(0.165, 0.000, 0.000),
                vec3(1.000, 0.102, 0.102),
                vec3(1.000, 0.878, 0.878));
    }
    if (theme < 81.5) {
        // Orbit
        return threeColor(phase,
                vec3(0.102, 0.063, 0.208),
                vec3(0.482, 0.361, 1.000),
                vec3(0.878, 0.839, 1.000));
    }
    if (theme < 82.5) {
        // Cascade
        return threeColor(phase,
                vec3(0.055, 0.165, 0.220),
                vec3(0.153, 0.769, 0.851),
                vec3(0.875, 0.984, 1.000));
    }
    if (theme < 83.5) {
        // Ripple
        return threeColor(phase,
                vec3(0.024, 0.188, 0.227),
                vec3(0.122, 0.663, 0.627),
                vec3(0.812, 0.969, 0.941));
    }
    if (theme < 84.5) {
        // Pendulum
        return threeColor(phase,
                vec3(0.169, 0.129, 0.094),
                vec3(0.788, 0.588, 0.247),
                vec3(1.000, 0.910, 0.761));
    }
    if (theme < 85.5) {
        // Vertigo
        return threeColor(phase,
                vec3(0.106, 0.043, 0.180),
                vec3(0.608, 0.302, 1.000),
                vec3(0.914, 0.851, 1.000));
    }
    if (theme < 86.5) {
        // Neon
        return threeColor(phase,
                vec3(0.078, 0.000, 0.118),
                vec3(1.000, 0.169, 0.820),
                vec3(1.000, 0.851, 0.965));
    }
    if (theme < 87.5) {
        // Static
        return threeColor(phase,
                vec3(0.063, 0.063, 0.063),
                vec3(0.604, 0.604, 0.604),
                vec3(0.949, 0.949, 0.949));
    }
    if (theme < 88.5) {
        // Caustics
        return threeColor(phase,
                vec3(0.012, 0.188, 0.247),
                vec3(0.184, 0.714, 0.769),
                vec3(0.875, 1.000, 0.984));
    }
    if (theme < 89.5) {
        // Glint
        return threeColor(phase,
                vec3(0.102, 0.102, 0.133),
                vec3(0.749, 0.780, 0.839),
                vec3(1.000, 1.000, 1.000));
    }
    if (theme < 90.5) {
        // Reactor
        return threeColor(phase,
                vec3(0.039, 0.165, 0.039),
                vec3(0.361, 1.000, 0.239),
                vec3(0.937, 1.000, 0.851));
    }
    // ---- third wave of MOTION themes (2026-07-28). A motion theme still needs a palette, so
    // each of these carries one chosen to sell its movement.
    if (theme < 91.5) {
        // Heartbeat - arterial red over near-black
        return threeColor(phase,
                vec3(0.102, 0.008, 0.031),
                vec3(0.839, 0.078, 0.184),
                vec3(1.000, 0.435, 0.463));
    }
    if (theme < 92.5) {
        // Serpent - venom green through jungle dark
        return threeColor(phase,
                vec3(0.031, 0.118, 0.063),
                vec3(0.318, 0.769, 0.235),
                vec3(0.827, 1.000, 0.494));
    }
    if (theme < 93.5) {
        // Magnetic - iron blue and pole red
        return threeColor(phase,
                vec3(0.086, 0.157, 0.310),
                vec3(0.365, 0.549, 0.855),
                vec3(0.949, 0.353, 0.310));
    }
    if (theme < 94.5) {
        // Spring - bright coil steel with a warm snap
        return threeColor(phase,
                vec3(0.157, 0.180, 0.235),
                vec3(0.612, 0.792, 0.855),
                vec3(1.000, 0.851, 0.529));
    }
    if (theme < 95.5) {
        // Stagger - sodium streetlight amber, unsteady
        return threeColor(phase,
                vec3(0.141, 0.098, 0.043),
                vec3(0.902, 0.588, 0.176),
                vec3(1.000, 0.898, 0.639));
    }
    if (theme < 96.5) {
        // Stomp - dust and heavy stone
        return threeColor(phase,
                vec3(0.180, 0.161, 0.141),
                vec3(0.612, 0.545, 0.463),
                vec3(0.902, 0.851, 0.780));
    }
    if (theme < 97.5) {
        // Haunted - grave-light cyan on cold black
        return threeColor(phase,
                vec3(0.043, 0.075, 0.090),
                vec3(0.400, 0.741, 0.729),
                vec3(0.859, 0.976, 0.949));
    }
    if (theme < 98.5) {
        // Helix - twin-strand violet and teal
        return threeColor(phase,
                vec3(0.325, 0.157, 0.686),
                vec3(0.114, 0.769, 0.749),
                vec3(0.925, 0.902, 1.000));
    }
    if (theme < 99.5) {
        // Shockwave - white front over deep blue
        return threeColor(phase,
                vec3(0.020, 0.055, 0.200),
                vec3(0.294, 0.510, 0.949),
                vec3(1.000, 1.000, 1.000));
    }
    if (theme < 100.5) {
        // Zero G - weightless deep space
        return threeColor(phase,
                vec3(0.043, 0.031, 0.110),
                vec3(0.404, 0.365, 0.729),
                vec3(0.847, 0.898, 1.000));
    }
    // ---- fourth wave of MOTION themes (2026-07-28).
    if (theme < 101.5) {
        // Flutter - moth-wing pale gold
        return threeColor(phase,
                vec3(0.204, 0.149, 0.086),
                vec3(0.867, 0.741, 0.408),
                vec3(1.000, 0.973, 0.882));
    }
    if (theme < 102.5) {
        // Whirlwind - storm grey torn with white
        return threeColor(phase,
                vec3(0.129, 0.153, 0.180),
                vec3(0.478, 0.573, 0.647),
                vec3(0.965, 0.980, 1.000));
    }
    if (theme < 103.5) {
        // Carousel - fairground red, cream and brass
        return threeColor(phase,
                vec3(0.639, 0.106, 0.157),
                vec3(1.000, 0.929, 0.808),
                vec3(0.914, 0.686, 0.243));
    }
    if (theme < 104.5) {
        // Marionette - lacquered wood and pale string
        return threeColor(phase,
                vec3(0.239, 0.137, 0.075),
                vec3(0.706, 0.478, 0.290),
                vec3(0.949, 0.906, 0.816));
    }
    if (theme < 105.5) {
        // Accordion - bellows crimson over black keys
        return threeColor(phase,
                vec3(0.086, 0.067, 0.075),
                vec3(0.702, 0.176, 0.239),
                vec3(0.976, 0.882, 0.784));
    }
    if (theme < 106.5) {
        // Buzz - live-wire yellow on insulation black
        return threeColor(phase,
                vec3(0.078, 0.075, 0.031),
                vec3(0.941, 0.902, 0.157),
                vec3(1.000, 1.000, 0.780));
    }
    if (theme < 107.5) {
        // Scramble - shifting terminal amber and cyan
        return threeColor(phase,
                vec3(0.063, 0.086, 0.098),
                vec3(0.949, 0.612, 0.184),
                vec3(0.310, 0.859, 0.859));
    }
    if (theme < 108.5) {
        // Burst - firework white-hot into ember
        return threeColor(phase,
                vec3(1.000, 1.000, 0.949),
                vec3(1.000, 0.612, 0.157),
                vec3(0.639, 0.106, 0.086));
    }
    if (theme < 109.5) {
        // Drain - swirling water blue into dark
        return threeColor(phase,
                vec3(0.020, 0.106, 0.161),
                vec3(0.176, 0.549, 0.690),
                vec3(0.780, 0.937, 0.965));
    }
    if (theme < 110.5) {
        // Metronome - polished brass on lacquer black
        return threeColor(phase,
                vec3(0.055, 0.051, 0.043),
                vec3(0.808, 0.643, 0.267),
                vec3(1.000, 0.949, 0.816));
    }
    // ---- fifth wave of MOTION themes (2026-07-28).
    if (theme < 111.5) {
        // Swarm - hive amber over dark comb
        return threeColor(phase,
                vec3(0.129, 0.086, 0.020),
                vec3(0.910, 0.643, 0.114),
                vec3(1.000, 0.910, 0.573));
    }
    if (theme < 112.5) {
        // Typewriter - ribbon black and paper white
        return threeColor(phase,
                vec3(0.078, 0.075, 0.071),
                vec3(0.545, 0.529, 0.502),
                vec3(0.980, 0.973, 0.949));
    }
    if (theme < 113.5) {
        // Pulseline - monitor green on clinical black
        return threeColor(phase,
                vec3(0.008, 0.075, 0.043),
                vec3(0.180, 0.949, 0.475),
                vec3(0.839, 1.000, 0.898));
    }
    if (theme < 114.5) {
        // Jelly - translucent lime and pale gel
        return threeColor(phase,
                vec3(0.286, 0.522, 0.176),
                vec3(0.639, 0.902, 0.400),
                vec3(0.933, 1.000, 0.831));
    }
    if (theme < 115.5) {
        // Snap - stark white on hard black
        return threeColor(phase,
                vec3(0.043, 0.043, 0.051),
                vec3(0.847, 0.855, 0.878),
                vec3(1.000, 1.000, 1.000));
    }
    if (theme < 116.5) {
        // Undertow - deep water green pulling under
        return threeColor(phase,
                vec3(0.012, 0.114, 0.129),
                vec3(0.114, 0.478, 0.463),
                vec3(0.686, 0.906, 0.851));
    }
    if (theme < 117.5) {
        // Clockwork - brass gears and oiled steel
        return threeColor(phase,
                vec3(0.180, 0.153, 0.114),
                vec3(0.749, 0.573, 0.263),
                vec3(0.898, 0.878, 0.831));
    }
    if (theme < 118.5) {
        // Gravity - falling iron into dust
        return threeColor(phase,
                vec3(0.098, 0.106, 0.125),
                vec3(0.478, 0.510, 0.557),
                vec3(0.847, 0.816, 0.741));
    }
    if (theme < 119.5) {
        // Waveform - oscilloscope cyan on black
        return threeColor(phase,
                vec3(0.020, 0.055, 0.075),
                vec3(0.208, 0.808, 0.898),
                vec3(0.831, 0.988, 1.000));
    }
    if (theme < 120.5) {
        // Shiver - cold breath blue-white
        return threeColor(phase,
                vec3(0.118, 0.176, 0.239),
                vec3(0.573, 0.729, 0.855),
                vec3(0.949, 0.988, 1.000));
    }
    // ---- sixth wave of MOTION themes (2026-07-28).
    if (theme < 121.5) {
        // Riptide - dragging ocean green-blue
        return threeColor(phase,
                vec3(0.016, 0.157, 0.196),
                vec3(0.086, 0.588, 0.612),
                vec3(0.749, 0.949, 0.925));
    }
    if (theme < 122.5) {
        // Stutter - broken-signal magenta and grey
        return threeColor(phase,
                vec3(0.129, 0.114, 0.137),
                vec3(0.804, 0.196, 0.647),
                vec3(0.925, 0.914, 0.933));
    }
    if (theme < 123.5) {
        // Slingshot - drawn-back leather and hot release
        return threeColor(phase,
                vec3(0.216, 0.129, 0.086),
                vec3(0.851, 0.376, 0.145),
                vec3(1.000, 0.882, 0.678));
    }
    if (theme < 124.5) {
        // Rumble - deep earth brown shot with dust
        return threeColor(phase,
                vec3(0.141, 0.110, 0.086),
                vec3(0.494, 0.376, 0.271),
                vec3(0.851, 0.788, 0.694));
    }
    if (theme < 125.5) {
        // Moth - dusty wing grey against lamplight
        return threeColor(phase,
                vec3(0.184, 0.169, 0.157),
                vec3(0.639, 0.596, 0.518),
                vec3(1.000, 0.949, 0.741));
    }
    if (theme < 126.5) {
        // Pendulum Wave - brass bobs on a dark frame
        return threeColor(phase,
                vec3(0.075, 0.086, 0.110),
                vec3(0.851, 0.702, 0.318),
                vec3(0.984, 0.949, 0.855));
    }
    if (theme < 127.5) {
        // Zipper - split steel teeth
        return threeColor(phase,
                vec3(0.110, 0.118, 0.129),
                vec3(0.616, 0.647, 0.686),
                vec3(0.949, 0.965, 0.980));
    }
    if (theme < 128.5) {
        // Chatter - frostbitten blue and bone white
        return threeColor(phase,
                vec3(0.145, 0.204, 0.267),
                vec3(0.494, 0.678, 0.812),
                vec3(0.973, 0.988, 1.000));
    }
    if (theme < 129.5) {
        // Warp - lensed violet bending to white
        return threeColor(phase,
                vec3(0.157, 0.055, 0.267),
                vec3(0.549, 0.267, 0.859),
                vec3(0.949, 0.910, 1.000));
    }
    if (theme < 130.5) {
        // Coil - braided copper and verdigris
        return threeColor(phase,
                vec3(0.478, 0.243, 0.129),
                vec3(0.898, 0.541, 0.290),
                vec3(0.400, 0.769, 0.686));
    }
    if (theme < 131.5) {
        // Void Rift - byte-identical to Void Pulse (theme 1). Same identity, now moving.
        return threeColor(phase,
                vec3(0.549, 0.000, 1.000),
                vec3(0.102, 0.000, 0.200),
                vec3(0.549, 0.000, 1.000));
    }
    // ---- seventh wave of MOTION themes (2026-07-29).
    if (theme < 132.5) {
        // Tether - rope hemp and worn brass
        return threeColor(phase,
                vec3(0.141, 0.110, 0.055),
                vec3(0.678, 0.549, 0.310),
                vec3(0.910, 0.863, 0.753));
    }
    if (theme < 133.5) {
        // Datastream - terminal green scrolling on black
        return threeColor(phase,
                vec3(0.008, 0.071, 0.039),
                vec3(0.129, 0.839, 0.420),
                vec3(0.769, 1.000, 0.863));
    }
    if (theme < 134.5) {
        // Quake - shaken dust and cracked stone
        return threeColor(phase,
                vec3(0.122, 0.102, 0.090),
                vec3(0.541, 0.435, 0.333),
                vec3(0.878, 0.804, 0.706));
    }
    if (theme < 135.5) {
        // Piston Wave - oiled machinery and warm brass
        return threeColor(phase,
                vec3(0.169, 0.125, 0.094),
                vec3(0.710, 0.506, 0.247),
                vec3(0.922, 0.835, 0.682));
    }
    if (theme < 136.5) {
        // Twitch - nervous violet spark
        return threeColor(phase,
                vec3(0.102, 0.082, 0.125),
                vec3(0.769, 0.400, 0.878),
                vec3(0.957, 0.894, 0.980));
    }
    if (theme < 137.5) {
        // Thread - fine silver filament
        return threeColor(phase,
                vec3(0.086, 0.102, 0.122),
                vec3(0.624, 0.706, 0.788),
                vec3(0.941, 0.965, 0.984));
    }
    if (theme < 138.5) {
        // Lull - still deep water at night
        return threeColor(phase,
                vec3(0.051, 0.106, 0.165),
                vec3(0.290, 0.498, 0.647),
                vec3(0.812, 0.894, 0.949));
    }
    if (theme < 139.5) {
        // Fracture - split stone and pale seam
        return threeColor(phase,
                vec3(0.110, 0.106, 0.102),
                vec3(0.467, 0.443, 0.439),
                vec3(0.839, 0.816, 0.796));
    }
    if (theme < 140.5) {
        // Uprise - rising green through pale mint
        return threeColor(phase,
                vec3(0.063, 0.122, 0.106),
                vec3(0.208, 0.659, 0.541),
                vec3(0.812, 0.949, 0.902));
    }
    // Shunt - final fallback: shoved amethyst grey
    return threeColor(phase,
            vec3(0.125, 0.102, 0.133),
            vec3(0.549, 0.420, 0.659),
            vec3(0.886, 0.839, 0.933));
}

/**
 * EFFECT themes (2026-07-27) — a third axis beyond palette and motion. These change how the
 * glyph is LIT rather than what colour it is or where it sits: a sweeping highlight, hologram
 * scanlines, an ember glow, a strobe. 0 = none, so every existing theme is untouched.
 */
int effectFor(float theme) {
    if (theme > 76.5 && theme < 77.5) { return 1; }   // Chrome     - sweeping sheen
    if (theme > 77.5 && theme < 78.5) { return 2; }   // Hologram   - scanlines + instability
    if (theme > 78.5 && theme < 79.5) { return 3; }   // Ember      - hot bottom, cooling top
    if (theme > 79.5 && theme < 80.5) { return 4; }   // Broadcast  - strobe pulse
    if (theme > 85.5 && theme < 86.5) { return 5; }   // Neon
    if (theme > 86.5 && theme < 87.5) { return 6; }   // Static
    if (theme > 87.5 && theme < 88.5) { return 7; }   // Caustics
    if (theme > 88.5 && theme < 89.5) { return 8; }   // Glint
    if (theme > 89.5 && theme < 90.5) { return 9; }   // Reactor
    return 0;
}

/**
 * Brightness multiplier for the effect axis. Screen-space (gl_FragCoord) rather than atlas UV:
 * the font atlas coordinate jumps per character, so a UV-based band would break up between
 * glyphs instead of sweeping smoothly across the whole line.
 */
float animatedChatEffect(int effect, float t) {
    if (effect == 0) {
        return 1.0;
    }
    if (effect == 1) {                       // Chrome - a specular band travelling the line
        float band = fract(gl_FragCoord.x * 0.012 - t * 1.5);
        return 1.0 + 1.9 * exp(-40.0 * band * band);
    }
    if (effect == 2) {                       // Hologram - scanlines + slow flicker
        float scan = 0.75 + 0.25 * sin(gl_FragCoord.y * 2.4 + t * 12.0);
        float drop = step(0.02, fract(sin(floor(t * 8.0) * 91.17) * 43758.5453));
        return scan * mix(0.35, 1.0, drop);
    }
    if (effect == 3) {                       // Ember - hot at the baseline, cooler above
        float rise = fract(gl_FragCoord.y * 0.05 - t * 0.8);
        return 0.75 + 0.9 * exp(-6.0 * rise);
    }
    if (effect == 4) {                       // Broadcast - hard strobe
        return mix(0.45, 1.6, step(0.5, fract(t * 2.2)));
    }
    if (effect == 5) {                       // Neon - a tube with a bad connection
        float hum = 0.9 + 0.1 * sin(t * 40.0);
        float bad = step(0.06, fract(sin(floor(t * 14.0) * 33.77) * 43758.5453));
        return hum * mix(0.25, 1.45, bad);
    }
    if (effect == 6) {                       // Static - untuned television
        float n = fract(sin(dot(floor(gl_FragCoord.xy), vec2(12.9898, 78.233))
                + floor(t * 20.0)) * 43758.5453);
        return 0.55 + n * 0.95;
    }
    if (effect == 7) {                       // Caustics - light through moving water
        float c = sin(gl_FragCoord.x * 0.05 + t * 1.7)
                * sin(gl_FragCoord.y * 0.07 - t * 1.1);
        return 0.85 + 0.75 * c * c;
    }
    if (effect == 8) {                       // Glint - mostly still, one sharp spark crossing
        float sweep = fract(t * 0.35);
        float d = gl_FragCoord.x * 0.0016 - sweep * 1.4 + 0.2;
        return 1.0 + 2.6 * exp(-260.0 * d * d);
    }
    if (effect == 9) {                       // Reactor - slow throb with hot spots
        float throb = 0.85 + 0.35 * sin(t * 1.6);
        float spot = exp(-24.0 * pow(fract(gl_FragCoord.x * 0.02 - t * 0.4) - 0.5, 2.0));
        return throb + spot * 0.9;
    }
    return 1.0;
}

void main() {
    vec4 sampleColor = texture(Sampler0, texCoord0);
    vec4 color = sampleColor * vertexColor * ColorModulator;

    if (animatedChatPhase >= 0.0) {
        // GameTime advances smoothly on the render thread. 300 produces one full
        // palette cycle in about four seconds while the server sends the line once.
        float phase = fract(animatedChatPhase
                + GameTime * 300.0 * animatedChatDirection);
        color.rgb = sampleColor.rgb
                * animatedThemeColor(animatedChatTheme, phase)
                * animatedChatBrightness
                * animatedChatEffect(effectFor(animatedChatTheme), GameTime * 1200.0)
                * ColorModulator.rgb;
        color.a = sampleColor.a * vertexColor.a * ColorModulator.a;
    }

    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
