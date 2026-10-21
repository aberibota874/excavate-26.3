# Excavate 26.3 (prototype)

Client-side Fabric controller for Minecraft Java 26.3, intended for private servers where automation is permitted.

## Current capabilities
- /excavate pos1 and /excavate pos2: mark opposite corners at the player's feet
- /excavate chest: store a waypoint next to a chest
- /excavate start: tell Baritone to excavate the marked rectangular volume
- /excavate status, /excavate pause, /excavate stop, /excavate resume
- When inventory has at most three empty slots, stop excavation and navigate to storage

**Not implemented:** opening a chest, transferring items, or resuming automatically after unloading. This is a prototype that still requires compilation and in-game testing.

## Build on GitHub
Pushing to main launches the "Build Minecraft 26.3 Fabric Mod" GitHub Actions workflow, using Java 25, Gradle 9.7.1, Fabric Loom 1.18-SNAPSHOT, Fabric API 0.164.0+26.3, and the checksum-verified official Baritone API v1.20.0.

Open the Actions tab, select the build run, and download the "excavate-26.3-fabric" artifact after a successful run. The artifact contains the mod JAR. Until a build succeeds, do not treat this repository as a functional mod.

## Playing
Install the Fabric Loader for Minecraft 26.3, Fabric API 0.164.0+26.3, the official "baritone-standalone-fabric-1.20.0.jar" release, and the resulting Excavate JAR in the client mods folder. The "baritone-api-1.20.0.jar" file downloaded in CI is for compiling only, and should not be installed in mods.

Define a **small**, nonvaluable test region in a disposable world before use on a backed-up private server. Set /excavate chest while standing next to the storage chest. Unload inventory manually when prompted, then use /excavate resume.

Sources: https://fabricmc.net/develop/ and https://github.com/cabaletta/baritone/releases/tag/v1.20.0
