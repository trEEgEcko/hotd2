# Flycast fork – UI Spec (House of the Dead 2)

Applies to: House of the Dead 2 (Sega NAOMI), a Flycast fork. Repo: `treegecko/hotd2`.
(Daytona USA 2, Sega Rally 2 and Scud Race Plus are a different fork, see UI_SPEC_Super3.md.)
Status: PARTIAL. Written only from a gameplay screenshot. The hotd2 source has not been read yet.

## 0. Design principles (the owner's standard)
- Minimalist. No flashy buttons, no decoration. Plain, semi-transparent controls.
- Menus are straight to the point: everything reachable in one tap from the menu icon (or Back).
- Standard top-right cluster on every build: **play/pause, screen switcher, menu icon** (menu opens Quick options). The screen switcher is the fullscreen-style icon between play/pause and menu.
- Gameplay controls stay out of the way; COIN / START and SERVICE / TEST can be hidden.
- New games should copy the closest existing layout instead of inventing a new one.

---


## 1. Layout (from screenshot)
- Top-right cluster of three icons: play/pause, screen switcher (fullscreen-style icon), menu (hamburger). Menu opens the menu.
- COIN and START pills at bottom-right (coin icon on COIN).
- Light-gun game: no stick or pedals. Aim by tapping the screen directly.
- "TAP TO RELOAD" pad at bottom-left.
- A small "Paused" pill appears bottom-centre while paused.

## 2. Not yet documented (needs the hotd2 repo zip)
- Exact sizes, margins and button IDs.
- Menu contents and order.
- Saved settings and defaults.
- Auto-boot behaviour and touch-to-aim details.
