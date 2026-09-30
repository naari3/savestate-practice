# Savestate Practice

Savestates for Minecraft 1.16.1 speedrun practice (Fabric, client side, singleplayer).

Save the current state of the world to a slot and load it back as many times as you want. The in-memory mode restores entities (including their AI state), blocks, scheduled ticks, POI and RNG, so a loaded state plays out the same way every time.

This is a practice tool. Do not use it in runs.

## Requirements

- Minecraft 1.16.1
- Fabric Loader 0.16.0 or later
- [SpeedrunAPI](https://github.com/contariaa/SpeedrunAPI) 2.2 or later (optional, adds the settings screen)

Fabric API is not needed.

## Keys

| Action | Default |
|---|---|
| Save state | F6 |
| Load state | F7 |
| Next slot | F8 |
| Previous slot | unbound |
| Undo last load | unbound |

Keys can be changed in Controls, or in the SpeedrunAPI settings screen.

## Settings

- Mode: `MEMORY` (default) or `DISK` (copies the world folder and reopens it)
- Slots: 1 to 9

With SpeedrunAPI, change them in its settings screen. Without it, create `config/mcsr/savestate-practice.json`:

```json
{ "mode": "MEMORY", "slotCount": 9 }
```

## Build

```
./gradlew build
```
