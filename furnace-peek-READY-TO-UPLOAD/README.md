# Furnace Peek (Fabric, Minecraft 26.3)

Look at a furnace, smoker or blast furnace and a small panel appears under your crosshair:

- whether it is burning right now (live, from the block itself)
- a status: smelting with about how long is left, done, finished, or stopped / needs fuel
- a progress bar
- how much fuel and output it had, and how long ago you last opened it

## How it works (and its one limit)
The server only tells your game how far along a furnace is while its screen is open. So Furnace Peek takes a
snapshot every time you open one, then combines that with the live "lit" state and the game clock to estimate
the rest. Furnaces you've never opened show "Open it once to start tracking". The numbers are estimates: they
can be off if somebody else (or a hopper) changes the furnace after you close it. Snapshots are kept in memory
only, so they are forgotten when you quit the game.

Text only; no mixins, no rendering changes. Standalone.

## Build
Upload this folder's contents to a GitHub repo (its workflow is `.github/workflows/furnace-peek.yml`), open
Actions, download `furnace-peek-jar`, use the jar WITHOUT -sources.
