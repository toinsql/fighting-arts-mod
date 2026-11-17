# Santoryu animation and sword rig export

Source: `santoryu_cespada.bbmodel` (15 named groups, 348 cubes, one embedded 256x128 texture). Exporter: `tools/export-santoryu.js`.

The animation JSON is `assets/fightingarts/animations/santoryu.json`. Its 47 source clips are exported as `fa_<name>`, `fa_<name>_upper`, and `fa_<name>_legs` for a total of 141 tracks. `torso` is mapped to the renderer's `body`. Keyframe times use seconds, and the export includes channels with source keyframes. Model positions use XYZ because Punchy applies model `x += x`, `y -= y`, `z += z`. Punchy's HeldItemAnimation translates item coordinates by `(-x, -z, +y) / 16`.

The rig JSON is `assets/fightingarts/styles/santoryu_rig.json`. It retains the nested group hierarchy and each group's origin/rotation. Cube `from` / `to` and `origin` are Blockbench model units (16 per block); face UVs are preserved. Groups without children remain in the metadata because item adjustment groups are useful attachment transforms.

Sword attachment locators and expected item mapping:

| ID | Punchy animation bone | Source adjustment group |
|---|---|---|
| `left_main` | `left_item` | `espada_left_item_AJUSTE` |
| `left_extra` | `left_item2` | `espada_left_item2_AJUSTE` |
| `right_main` | `right_item` | `espada_right_item_AJUSTE` |
| `mouth` | `right_item2` | `espada_right_item2_AJUSTE` |

Each `sword_attachments` entry has `modelBone` and `adjustment`. The cube tree has `groups` (`name`, `origin`, `rotation`, `parent`) and `cubes` (`name`, `from`, `to`, `origin`, `rotation`, `parent`, `inflate`, `mirror`, `shade`, and per-face `uv`). Render grouped cubes in the source hierarchy; the adjustment group is nested under its corresponding item bone. The mouth sword is attached beneath the head and follows head aim; the hand swords follow their respective hand item anchors.

The source texture is extracted to `assets/fightingarts/textures/entity/steve_e_espada_minecraft.png`.

Run `node tools/verify-assets.js` to check the exported clip durations and masks, along with the rig groups, cubes, sword attachments, and texture.
