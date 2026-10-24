# Stronghold Crusader Asset Pipeline Research Dossier

This dossier separates confirmed information from inferred production details.

## Confirmed Findings

### Official modding utilities and pipeline hints

- Firefly-released Stronghold Crusader modding utilities included:
  - AIV Castle Editor
  - BMP to TGX converter
  - Text compilation tool
- The announcement states these were tools Firefly used when working on Crusader and explicitly identifies TGX as the game graphics format.

Sources:

- [Stronghold Nation - Modding Utilities listing](https://www.stronghold-nation.com/downloads/file/675-modding-utilities/)
- [Stronghold Heaven forum post quoting Firefly release text](https://stronghold.heavengames.com/cgi-bin/forum/display.cgi?action=st&fn=14&tn=3498)
- [Sourcehold issue referencing known mod tools](https://github.com/sourcehold/Sourcehold/issues/73)

### Remaster-era statements that illuminate legacy workflow

- Stronghold: Definitive Edition press materials describe the game as rebuilt from source artwork.
- Reporting around the remaster cites developers preserving around 60-70% of original textures/models and improving legacy sprite animation quality.

Sources:

- [Firefly Stronghold: Definitive Edition press sheet](https://fireflyworlds.com/press/sheet.php?p=Stronghold+Definitive+Edition)
- [Gamepressure remaster development article](http://gamepressure.com/newsroom/stronghold-remaster-devs-show-how-they-overhaul-the-iconic-game/z86110)

## High-Confidence Inferences

- The legacy art path likely involved source art/model production, an intermediate bitmap stage, then conversion into engine runtime assets (TGX).
- Unit readability constraints were likely intentional for RTS camera distance: strong silhouettes, tight value contrast, directional consistency, and compact animation cadence.
- A pragmatic modern recreation should preserve those constraints while using current tooling (PNG atlas + JSON metadata) as canonical output.

## Confidence Labels

- **High confidence (confirmed)**: Presence and names of official utilities, TGX usage, reuse of legacy assets in remaster, source artwork mention.
- **Medium confidence (inferred)**: Exact internal DCC stack and exact render/export script details used in 2001-2002.
- **Low confidence (unknown without direct Firefly technical postmortem)**: Precise original model packages, lighting rigs, and internal build automation details.

## Implementation Implications for This Project

- Canonical modern pipeline:
  - author/import sprite assets (PNG)
  - define metadata/animation timing in JSON
  - validate directional and pivot consistency
  - export deterministic atlas + manifest
- Compatibility bridge (optional later):
  - retain room for BMP/TGX adaptation workflows if reverse-conversion is needed for legacy interoperability.
