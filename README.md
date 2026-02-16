# Tutor Timer

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

## Installation

### From RuneLite Plugin Hub
1. Open RuneLite
2. Click the wrench icon (Configuration)
3. Navigate to Plugin Hub
4. Search for "Tutor Timer"
5. Click Install

### Manual Installation (for development)
1. Clone this repository
2. Run `./gradlew build`
3. The plugin JAR will be in `build/libs/`

## Development

This plugin is built with Gradle and requires JDK 11+.

```bash
# Run tests
./gradlew test

# Build plugin JAR
./gradlew build

# Run in development mode
./gradlew run
```

## License

See [LICENSE](LICENSE) file for details.
