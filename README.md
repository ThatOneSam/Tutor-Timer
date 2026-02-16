# Tutor Timer

[![Build](https://github.com/ThatOneSam/Tutor-Timer/actions/workflows/build.yml/badge.svg)](https://github.com/ThatOneSam/Tutor-Timer/actions/workflows/build.yml)

A [RuneLite](https://runelite.net/) plugin that tracks the 30-minute cooldown for claiming free runes and training arrows from the combat tutors in Lumbridge.

## How It Works

Every 30 minutes you can visit the Magic Combat Tutor to get 30 air runes and 30 mind runes. Or, you can go to the Ranged Combat Tutor for 30 training arrows. They share a 30-minute cooldown and claiming from either one starts the timer for both.

This plugin listens for the claim and starts a countdown so you know exactly when to come back. The timer shows up as an info box with a mind rune icon.

### Timer States

| Display | Meaning |
| --- | --- |
| `?` | Plugin hasn't seen you claim yet - claim once to start tracking |
| `12:34` | Time remaining until you can claim again |
| `Ready!` | Go grab your free stuff! |

## Settings

- `Show info box only when ready` - When enabled, the info box is hidden until the cooldown expires and the timer shows `Ready!`. When disabled (default), the info box is shown at all times.
- `Show info box` - Master switch for displaying the info box.
- `Notify when ready` - Sends a desktop notification when the cooldown expires.
- `Show seconds` - Toggle seconds in the countdown display.

## License

See [LICENSE](LICENSE) file for details.
