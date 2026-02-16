package com.tutortimer;

import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;

import lombok.extern.slf4j.Slf4j;

import com.google.inject.Provides;



@Slf4j
@PluginDescriptor(
    name = "Tutor Timer",
    description = "Tracks the 30-minute cooldown for claiming free runes and training arrows from the Lumbridge combat tutors.",
    tags = {"magic", "ranged", "tutor", "runes", "arrows", "timer", "cooldown", "combat", "free", "lumbridge"}
)
public class TutorTimerPlugin extends Plugin
{
    static final Duration COOLDOWN = Duration.ofMinutes(30);
    private static final String CONFIG_GROUP = "tutortimer";
    private static final String LAST_CLAIM_KEY = "lastClaim";
    private static final String LAST_KNOWN_COOLDOWN_KEY = "lastKnownCooldown";

    // Success triggers: these only appear when items are actually given
    private static final String MIKASI_GIVES = "Mikasi gives you";
    private static final String NEMARTI_GIVES = "Nemarti gives you";

    // Cooldown rejection: shared between both tutors
    private static final String COOLDOWN_REJECT = "every half an hour";

    @Provides
    TutorTimerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(TutorTimerConfig.class);
    }

    @Inject
    private ConfigManager configManager;

    @Inject
    private InfoBoxManager infoBoxManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private Notifier notifier;

    @Inject
    private TutorTimerConfig config;

    @Nullable
    private Instant lastClaimTime;

    // When we observed a cooldown rejection but didn't get a claim timestamp.
    @Nullable
    private Instant lastKnownCooldownTime;

    private boolean knownOnCooldown = false;
    private boolean notifiedReady = false;
    // Throttle checking for known-cooldown expiry to avoid per-tick work
    private long lastKnownCooldownExpiryCheck = 0L;
    private static final long KNOWN_COOLDOWN_CHECK_INTERVAL_MS = 60_000L;

    private TutorTimerInfoBox infoBox;

    @Override
    protected void startUp()
    {
        log.info("Tutor Timer started");
        loadLastClaimTime();
        addInfoBox();
    }

    @Override
    protected void shutDown()
    {
        log.info("Tutor Timer stopped");
        removeInfoBox();
    }

    private void addInfoBox()
    {
        removeInfoBox();
        BufferedImage icon = itemManager.getImage(558); // MIND_RUNE item ID
        infoBox = new TutorTimerInfoBox(icon, this);
        infoBoxManager.addInfoBox(infoBox);
    }

    private void removeInfoBox()
    {
        if (infoBox != null)
        {
            infoBoxManager.removeInfoBox(infoBox);
            infoBox = null;
        }
    }

    private void loadLastClaimTime()
    {
        String saved = configManager.getConfiguration(CONFIG_GROUP, LAST_CLAIM_KEY);
        // Log what we loaded so it's easy to verify persistence at startup
        log.debug("Loaded lastClaim config value: {}", saved);

        if (saved != null)
        {
            try
            {
                lastClaimTime = Instant.ofEpochMilli(Long.parseLong(saved));
            }
            catch (NumberFormatException e)
            {
                lastClaimTime = null;
            }
        }

        // Load the "known cooldown" timestamp (set when the tutor explicitly rejects a claim).
        // If it's recent (within COOLDOWN) and we don't have a lastClaimTime, keep knownOnCooldown.
        String savedKnown = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY);
        log.debug("Loaded lastKnownCooldown config value: {}", savedKnown);

        if (savedKnown != null)
        {
            try
            {
                lastKnownCooldownTime = Instant.ofEpochMilli(Long.parseLong(savedKnown));

                // Use null-safe helpers to determine whether the stored known-cooldown is still active.
                if (lastClaimTime == null && isKnownCooldownActive())
                {
                    knownOnCooldown = true;
                }
                else if (!isKnownCooldownActive())
                {
                    // expired or invalid - clear stored value
                    lastKnownCooldownTime = null;
                    configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY, null);
                }
            }
            catch (NumberFormatException e)
            {
                lastKnownCooldownTime = null;
            }
        }
    }

    private void saveLastClaimTime()
    {
        if (lastClaimTime != null)
        {
            configManager.setConfiguration(CONFIG_GROUP, LAST_CLAIM_KEY,
                String.valueOf(lastClaimTime.toEpochMilli()));
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.DIALOG
            && type != ChatMessageType.GAMEMESSAGE
            && type != ChatMessageType.SPAM
            && type != ChatMessageType.MESBOX)
        {
            return;
        }

        String msg = event.getMessage();
        log.debug("ChatMessage received (type={}): {}", type, msg);

        if (msg.contains(MIKASI_GIVES) || msg.contains(NEMARTI_GIVES))
        {
            handleTutorClaim(msg);
        }
        else if (msg.contains("I work with the Ranged Combat tutor") || msg.contains("I work with the Magic tutor"))
        {
            handleTutorIntro(msg);
        }
        else if (msg.contains(COOLDOWN_REJECT))
        {
            handleCooldownRejection(msg);
        }
    }

    private void handleTutorClaim(String msg)
    {
        log.info("Tutor claim detected — recording lastClaimTime (message='{}')", msg);
        lastClaimTime = Instant.now();
        knownOnCooldown = true;
        notifiedReady = false;
        saveLastClaimTime();

        if (notifier != null) {
            notifier.notify("Tutor Timer: claim saved");
        }

        lastKnownCooldownTime = null;
        configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY, null);
    }

    private void handleTutorIntro(String msg)
    {
        log.info("Tutor intro detected — treating as possible known-cooldown (message='{}')", msg);

        if (lastClaimTime == null)
        {
            lastKnownCooldownTime = Instant.now();
            knownOnCooldown = true;
            if (lastKnownCooldownTime != null)
            {
                configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY,
                    String.valueOf(lastKnownCooldownTime.toEpochMilli()));
            }
        }
    }

    private void handleCooldownRejection(String msg)
    {
        log.info("Cooldown rejection detected — storing known-cooldown (message='{}')", msg);
        lastKnownCooldownTime = Instant.now();
        knownOnCooldown = true;
        if (lastKnownCooldownTime != null)
        {
            configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY,
                String.valueOf(lastKnownCooldownTime.toEpochMilli()));
        }
    }



    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Handle notification
        if (config.notifyOnReady() && !notifiedReady && isReady())
        {
            notifier.notify("Your free runes and arrows are ready to claim!");
            notifiedReady = true;
        }

        // Decide whether InfoBox should be visible
        // New semantics: when `showWhenReady` is true, only show the InfoBox once the timer is ready.
        // When it's false the InfoBox is visible at all times (preserves previous default behavior).
        boolean shouldShow = config.showInfoBox() && (!config.showWhenReady() || isReady());

        if (!shouldShow)
        {
            removeInfoBox();
        }
        else if (infoBox == null)
        {
            addInfoBox();
        }

        // Throttled check: clear persisted known-cooldown in-session when it expires
        long now = System.currentTimeMillis();
        if (now - lastKnownCooldownExpiryCheck >= KNOWN_COOLDOWN_CHECK_INTERVAL_MS)
        {
            lastKnownCooldownExpiryCheck = now;
            checkAndClearExpiredKnownCooldown();
        }
    }

    // Package-private helper for tests: perform the same expiry check as the throttled onGameTick logic.
    void checkAndClearExpiredKnownCooldown()
    {
        // Only clear when we don't have a real lastClaimTime and the known-cooldown has expired
        if (lastClaimTime == null && lastKnownCooldownTime != null && !isKnownCooldownActive())
        {
            log.info("Known-cooldown expired in-session — clearing persisted known cooldown");
            lastKnownCooldownTime = null;
            knownOnCooldown = false;
            if (configManager != null)
            {
                configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY, null);
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        log.debug("ConfigChanged event received - group='{}' key='{}' oldValue='{}' newValue='{}'",
            event.getGroup(), event.getKey(), event.getOldValue(), event.getNewValue());

        if (!CONFIG_GROUP.equals(event.getGroup()))
        {
            return;
        }

        removeInfoBox();

        if (notifier != null)
        {
            notifier.notify("Tutor Timer: saved timer cleared");
        }
    }

    //Tooltip text used by the InfoBox.
    public String getTooltipText()
    {
        if (lastClaimTime == null && !knownOnCooldown)
        {
            return "Tutor Timer - claim runes or arrows to start tracking";
        }
        if (lastClaimTime == null)
        {
            return "Tutor Timer - on cooldown, but unknown time remaining";
        }
        if (isReady())
        {
            return "Tutor Timer - ready to claim!";
        }
        return "Tutor Timer - " + getTimerText() + " remaining";
    }

    public String getTimerText()
    {
        if (lastClaimTime == null)
        {
            return knownOnCooldown ? "< 30m" : "?";
        }

        Duration elapsed = Duration.between(lastClaimTime, Instant.now());
        Duration remaining = COOLDOWN.minus(elapsed);

        if (remaining.isNegative() || remaining.isZero())
        {
            return "Ready!";
        }

        long minutes = remaining.toMinutes();
        long seconds = remaining.getSeconds() % 60;

        if (config.showSeconds())
        {
            return String.format("%d:%02d", minutes, seconds);
        }
        return String.format("%dm", minutes);
    }

    public boolean isReady()
    {
        if (lastClaimTime == null) return false;
        return Duration.between(lastClaimTime, Instant.now()).compareTo(COOLDOWN) >= 0;
    }

    public boolean isUnknown()
    {
        return lastClaimTime == null;
    }

    // Returns true if a cooldown rejection occurred recently (i.e., within COOLDOWN)
    public boolean isKnownCooldownActive()
    {
        if (lastKnownCooldownTime == null)
        {
            return false;
        }
        Instant expireTime = lastKnownCooldownTime.plus(COOLDOWN);
        return Instant.now().isBefore(expireTime);
    }
    
    // Returns remaining duration for a known cooldown or Duration. ZERO when none/expired.
    public Duration getKnownCooldownRemaining()
    {
        if (lastKnownCooldownTime == null)
        {
            return Duration.ZERO;
        }

        Instant expireTime = lastKnownCooldownTime.plus(COOLDOWN);
        Duration remaining = Duration.between(Instant.now(), expireTime);
        if (remaining.isNegative())
        {
            return Duration.ZERO;
        }
        return remaining;
    }

    // Package-private setter for tests
    void setLastKnownCooldownTime(Instant instant)
    {
        this.lastKnownCooldownTime = instant;
    }
}
