# Project preferences

- Game display name: Morph Hide n Seek.
- The user authorized automatically replacing this mod after a verified build in:
  `/Users/dyrox/Library/Application Support/ModrinthApp/profiles/Morph Hide n seek/mods`
- Back up replaced morphhide JARs outside the mods directory. Identify this mod by its
  fabric.mod.json id (`morphhide`); do not change Fabric API, Iris, Sodium, or other mods.
- Do not terminate a running game to install. Tell the user that a restart is needed.
- Use Java 25. Run appropriate checks and actual client gametests for gameplay/UI work.
- Keep Minecraft muted during client gametests (master volume 0, saved in the gametest options).
- Bump mod_version in gradle.properties before every install into the Modrinth mods folder (minor for features, patch for fixes); never reinstall a changed build under an old version number.
- Add an entry to CHANGELOG.md (Chinese, newest first) for every version installed into the Modrinth mods folder.
