# Prime SMP Plugin Pack

Four standalone Paper/Spigot plugins for **Prime SMP**, built for 1.20.x
(Java 17+). Each is its own independent Maven project — you can build and
install them individually.

| Plugin | What it does |
|---|---|
| **PrimeEC** | `/ec [player]` — open your ender chest from anywhere. Admins with `primeec.others` can view/edit other players' ender chests too. Optional cooldown. |
| **PrimeTPA** | Teleport requests: `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`. Configurable warmup countdown (cancels if you move or take damage) and per-player cooldown. |
| **PrimeHomes** | `/sethome [name]`, `/home [name]`, `/delhome [name]`, `/homes`. Per-player home limits via permissions (`primehomes.limit.<n>` or `primehomes.limit.unlimited`), same warmup/cooldown protection as PrimeTPA. |
| **PrimeSpawn** | `/spawn`, `/setspawn` (admin). Optionally teleports brand-new players to spawn on their first join, and/or overrides death respawn location. |
| **PrimeKits** | `/kit [name]` — configurable kits (starter gear, daily kit, PvP kit, or your own) with per-kit cooldowns, one-time kits, and permission gating. `/kit reload`, `/kit give <player> <kit>` for admins. |
| **PrimeRTP** | `/rtp` (alias `/wild`) — random teleport to a safe spot in the wilderness, configurable min/max radius, avoids water/lava/void. |
| **PrimeWarps** | `/warp <name>`, `/warps`, `/setwarp`/`/delwarp` (admin) — named server-wide locations, e.g. an arena, shop, or event area. |
| **PrimeCombatLog** | No commands — tags players in PvP for a configurable window and kills them instantly if they disconnect while tagged, so people can't dodge a death (and your kill/death ranks) by logging out mid-fight. Shows a boss bar countdown while tagged. |
| **PrimeBack** | `/back` — returns you to wherever you were before your last teleport (warp, home, tpa, rtp, spawn) or to your death location, whichever happened most recently. |
| **PrimeGraves** | No commands besides `/graves` (lists your own) — on death, items go into a chest at the death spot instead of scattering or being lost. Only you (or admins) can open it for a protection window; after that it's fair game. Expires after a configurable time, dropping any remaining items on the ground rather than deleting them. |
| **PrimeVanish** | `/vanish` (alias `/v`) — staff-only toggle that hides you from players without `primevanish.see`, suppresses your quit message while vanished, and stops mobs from targeting you. |
| **PrimeAFK** | `/afk` to toggle manually, or auto-detected after a period of no movement/chat/commands. Optional auto-kick after a longer AFK period. Broadcast-only (no display-name changes), so it won't conflict with PrimeRanks' name prefixes. |

## Building

Each plugin folder is self-contained with the same two build options used
for PrimeRanks:

**Option A — GitHub Actions (no Java/Maven needed locally):** push a
plugin's folder to its own GitHub repo (contents at repo root) and the
included `.github/workflows/build.yml` builds the jar automatically —
grab it from the Actions tab under "Artifacts".

**Option B — build locally** (needs Java 17+ and Maven):
```bash
cd PrimeEC && ./build.sh      # or build.bat on Windows
```
Repeat per plugin. Each produces `target/<PluginName>.jar`.

If you're on Spigot/Bukkit instead of Paper, swap `io.papermc.paper:paper-api`
for `org.spigotmc:spigot-api` in each `pom.xml` (same version string) —
no code changes needed.

## Installing

Drop each built jar into your server's `plugins/` folder and restart (or
`/reload` if you're brave). They're fully independent — install any subset
you want.

## Notes

- None of these depend on each other or on PrimeRanks — mix and match freely.
- **PrimeCombatLog** pairs especially well with **PrimeRanks**: without it,
  players can dodge a death (and its effect on their rank score) just by
  quitting mid-fight.
- **PrimeGraves** overwrites whatever block was at the death location with
  a chest and doesn't restore it afterward — fine for most survival deaths,
  but worth knowing if someone dies inside a build you care about.
- **PrimeVanish**'s vanish state is per-session — it resets if the player
  relogs, rather than persisting across restarts.
- **PrimeAFK** deliberately avoids touching display names/prefixes (it only
  broadcasts chat messages) so it won't fight with PrimeRanks' rank tags for
  control of the player's name.
- Permissions default sensibly (players can use `/ec`, `/spawn`, `/home`,
  `/warp`, `/kit`, `/rtp`, `/back`, `/afk` out of the box; admin-only actions
  like `/setspawn`, `/setwarp`, `/vanish`, and viewing others' ender chests
  default to `op`). Adjust via LuckPerms or your permissions plugin of
  choice — home limits and kit access can be tied to your PrimeRanks tiers
  via permission nodes if you want higher ranks to get more homes or better
  kits.
- As with PrimeRanks, these weren't compiled in the environment that
  generated them (no local JDK/Maven/network access there) — the code
  follows standard Bukkit/Paper API patterns, but give each a test build
  and an in-game once-over before relying on them on your live server.
