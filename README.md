# TwitchHUD

A Twitch chat overlay for Minecraft. Your chat, live on screen, without alt-tabbing to the browser every five seconds.

Built for Forge, NeoForge, Fabric and Quilt — same features everywhere, no loader is the "lesser" version.

## Features

- Draggable, resizable chat box that sits wherever you put it
- Native Twitch emotes, badges, colored usernames, and 7TV/BTTV/FFZ emotes
- Long messages wrap properly instead of getting cut off
- Scroll back through chat history, copy messages/links, hide annoying users
- Pick a font (Nunito, Inter, Poppins, or vanilla Minecraft) and scale everything to taste
- Optional widgets you can drop anywhere: viewer count, stream title, follower count, Hype Train, Creator Goals
- Compact mode for a denser chat feed, and mention highlighting so you never miss someone tagging you
- Save/load/export settings profiles, so switching between channels (or PCs) doesn't mean redoing your whole setup
- Everything is custom-drawn — no clunky vanilla buttons in the settings menu

## Install

1. Grab the jar for your loader and Minecraft version from the [Releases](../../releases) page.
2. Drop it in your `mods` folder.
3. **Quilt users:** use the Fabric 1.21.1 jar directly — Quilt Loader runs Fabric mods natively through its
   compatibility layer, no separate build needed.
4. Launch the game, press **K** in-game to open settings and connect a channel, **L** to open interactive chat.

| Loader | Minecraft version |
|---|---|
| Fabric | 1.21.1 |
| Fabric | 1.21.11 |
| Fabric | 26.2 |
| Forge | 1.21.1 |
| NeoForge | 1.21.1 |
| Quilt | 1.21.1 (via the Fabric jar) |

## Twitch account & permissions

TwitchHUD connects to Twitch in two ways:

- **Reading chat** uses Twitch's public, anonymous IRC endpoint — no login required, no account needed.
- **Optional features** (Hype Train, Creator Goals, Channel Points, follower alerts) need you to authorize the mod
  with your own broadcaster account through Twitch's official device-code flow (the same "enter this code on
  twitch.tv/activate" flow used by TV apps).

TwitchHUD **never requests moderator permissions**. The only scopes it asks for are
`channel:read:redemptions channel:read:hype_train channel:read:goals`. It cannot read moderation logs, ban anyone,
or delete messages. Without moderator access, Twitch doesn't expose who your newest follower is, so follow alerts
can only report that your follower count went up, not who followed.

No data is sent anywhere except directly to Twitch (and, for third-party emotes, directly to 7TV/BTTV/FFZ's own
public emote APIs). Nothing is collected, logged, or sent to any server the mod's author controls.

## Known limitations

- Animated emotes (Twitch, 7TV, BTTV, FFZ) render as a static frame
- No latest-follower identity or moderator-only chat features, by design (see above)
- The public data fallback (used when you haven't authorized the mod) updates slightly slower than the
  official/authorized path

## Building from source

Requires Java 21 (Java 25 for the Fabric 26.2 module and for running Gradle itself — Fabric Loom needs it).

```bash
./gradlew build
```

Each loader module produces its own jar under `<module>/build/libs/`.

## License

All Rights Reserved. You may install and use this mod, and include it in modpacks, but the source code may not be
redistributed or modified without permission.
