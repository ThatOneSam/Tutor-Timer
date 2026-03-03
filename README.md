# Tutor Timer

Version: 1.2.4

[![Build](https://github.com/ThatOneSam/Tutor-Timer/actions/workflows/build.yml/badge.svg)](https://github.com/ThatOneSam/Tutor-Timer/actions/workflows/build.yml)

A [RuneLite](https://runelite.net/) plugin that tracks the 30‑minute cooldown for
claiming free runes and training arrows from the combat tutors in Lumbridge.

---

## How It Works

Every 30 minutes you can visit the **Magic Combat Tutor** to get 30 air runes and
30 mind runes. Or you can go to the **Ranged Combat Tutor** for 25 training
arrows. The two tutors share the same 30‑minute cooldown; claiming from either
one starts the timer for both.

This plugin listens for the claim and starts a countdown so you know exactly
when to come back. The timer shows up as an info box with a mind‑rune icon.

### Timer States

- **?** – Plugin hasn’t seen you claim yet – claim once to start tracking
- **< 30m** – Plugin detected that you spoke to a tutor but either didn’t
  actually receive the items or were told you must wait; the exact remaining
  time is unknown but is guaranteed to be less than half an hour. This state
  persists across restarts until the cooldown expires or you make a successful
  claim.
- **12:34** – Time remaining until you can claim again (known from a previous
  successful claim).
- **Ready!** – Go grab your free stuff!

> _Note:_ the **< 30m** indicator only appears when the plugin observes the tutor chat messages but doesn’t see the “gives you” text. In-game this happens if you open the dialogue and then close it without getting runes/arrows, or if the tutor replies a message that includes the phrase “every half an hour”. The plugin will still know you triggered a cooldown even though it doesn’t know the exact start time.

---

## Settings

- **Show info box only when ready**: when enabled, the info box is hidden until the cooldown expires and the timer shows **Ready!**
- **Show info box**: master switch for displaying the info box
- **Notify when ready**: send a desktop notification when the cooldown
  expires
- **Show seconds**: toggle seconds in the countdown display

---

## License

See [LICENSE](LICENSE) for details.
