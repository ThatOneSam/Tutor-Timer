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
    private static final String LAST_SHUTDOWN_KEY = "lastShutdown";

    private static final String MIKASI_GIVES = "Mikasi gives you";
    private static final String NEMARTI_GIVES = "Nemarti gives you";
    private static final String COOLDOWN_REJECT = "every half an hour";

    private static final long KNOWN_COOLDOWN_CHECK_INTERVAL_MS = 60_000L;

    @Provides
    TutorTimerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(TutorTimerConfig.class);
    }

    @Inject private ConfigManager configManager;
    @Inject private InfoBoxManager infoBoxManager;
    @Inject private ItemManager itemManager;
    @Inject private Notifier notifier;
    @Inject private TutorTimerConfig config;

    // use Optionals rather than nullable fields; avoids any risk of NPEs
    private java.util.Optional<Instant> lastClaimTime = java.util.Optional.empty();
    private java.util.Optional<Instant> lastKnownCooldownTime = java.util.Optional.empty();
    private boolean knownOnCooldown;
    private boolean notifiedReady;
    private TutorTimerInfoBox infoBox;
    private long lastKnownCooldownExpiryCheck;

    @Override
    protected void startUp()
    {
        try
        {
            loadLastClaimTime();
            addInfoBox();
        }
        catch (Exception ex)
        {
            log.error("Tutor Timer startup failed", ex);
        }
    }

    @Override
    protected void shutDown()
    {
        try
        {
            configManager.setConfiguration(CONFIG_GROUP, LAST_SHUTDOWN_KEY,
                String.valueOf(System.currentTimeMillis()));
            removeInfoBox();
        }
        catch (Exception ex)
        {
            log.error("Tutor Timer shutdown failed", ex);
        }
    }

    // Safely clear a config key. Guards against null configManager and the
    // NPE that ConfigManager.setConfiguration throws for null values.
    private void safeClearConfig(String key)
    {
        if (configManager == null) return;
        try
        {
            if (configManager.getConfiguration(CONFIG_GROUP, key) == null) return;
            configManager.setConfiguration(CONFIG_GROUP, key, null);
        }
        catch (NullPointerException ignored) { // ConfigManager throws NPE for null values
        }
        catch (Exception ex)
        {
            log.error("Failed to clear config key '{}'", key, ex);
        }
    }

    // Load persisted state from config. Package-private for tests.
    void loadLastClaimTime()
    {
        if (configManager == null) return;

        loadLastClaimTimeFromConfig();
        loadLastKnownCooldownFromConfig();
        detectStaleClaim();
        safeClearConfig(LAST_SHUTDOWN_KEY);
    }

    private void loadLastClaimTimeFromConfig()
    {
        String saved = configManager.getConfiguration(CONFIG_GROUP, LAST_CLAIM_KEY);
        if (saved != null)
        {
            try { lastClaimTime = java.util.Optional.of(Instant.ofEpochMilli(Long.parseLong(saved))); }
            catch (NumberFormatException e) { lastClaimTime = java.util.Optional.empty(); }
        }
    }

    private void loadLastKnownCooldownFromConfig()
    {
        String savedKnown = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY);
        if (savedKnown != null)
        {
            try
            {
                lastKnownCooldownTime = java.util.Optional.of(Instant.ofEpochMilli(Long.parseLong(savedKnown)));
                if (lastClaimTime.isEmpty() && isKnownCooldownActive())
                {
                    knownOnCooldown = true;
                }
                else if (!isKnownCooldownActive())
                {
                    lastKnownCooldownTime = java.util.Optional.empty();
                    safeClearConfig(LAST_KNOWN_COOLDOWN_KEY);
                }
            }
            catch (NumberFormatException e) { lastKnownCooldownTime = java.util.Optional.empty(); }
        }
    }

    private void detectStaleClaim()
    {
        String savedShutdown = configManager.getConfiguration(CONFIG_GROUP, LAST_SHUTDOWN_KEY);
        if (savedShutdown != null && lastClaimTime.isPresent())
        {
            try
            {
                Instant shutdown = Instant.ofEpochMilli(Long.parseLong(savedShutdown));
                Instant expire = lastClaimTime.get().plus(COOLDOWN);
                if (shutdown.isAfter(lastClaimTime.get()) && shutdown.isBefore(expire))
                {
                    lastClaimTime = java.util.Optional.empty();
                    knownOnCooldown = false;
                    lastKnownCooldownTime = java.util.Optional.empty();
                    safeClearConfig(LAST_CLAIM_KEY);
                    safeClearConfig(LAST_KNOWN_COOLDOWN_KEY);
                }
            }
            catch (NumberFormatException ignored) { /* Malformed timestamp is silently ignored */ }
        }
    }

    private void saveLastClaimTime()
    {
        if (lastClaimTime.isPresent())
        {
            configManager.setConfiguration(CONFIG_GROUP, LAST_CLAIM_KEY,
                String.valueOf(lastClaimTime.get().toEpochMilli()));
        }
    }

    private void addInfoBox()
    {
        try
        {
            removeInfoBox();
            BufferedImage icon = itemManager.getImage(558);
            infoBox = new TutorTimerInfoBox(icon, this);
            infoBoxManager.addInfoBox(infoBox);
        }
        catch (Exception ex)
        {
            log.error("Unable to create info box", ex);
        }
    }

    private void removeInfoBox()
    {
        if (infoBox != null)
        {
            infoBoxManager.removeInfoBox(infoBox);
            infoBox = null;
        }
    }

    // --- Event handlers ---

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.DIALOG
            && type != ChatMessageType.GAMEMESSAGE
            && type != ChatMessageType.SPAM
            && type != ChatMessageType.MESBOX) return;

        String msg = event.getMessage();

        if (msg.contains(MIKASI_GIVES) || msg.contains(NEMARTI_GIVES))
        {
            handleTutorClaim();
        }
        else if (msg.contains("I work with the Ranged Combat tutor")
              || msg.contains("I work with the Magic tutor"))
        {
            handleTutorIntro();
        }
        else if (msg.contains(COOLDOWN_REJECT))
        {
            handleCooldownRejection();
        }
    }

    private void handleTutorClaim()
    {
        lastClaimTime = java.util.Optional.of(Instant.now());
        knownOnCooldown = true;
        notifiedReady = false;
        saveLastClaimTime();
        lastKnownCooldownTime = java.util.Optional.empty();
        safeClearConfig(LAST_KNOWN_COOLDOWN_KEY);
    }

    private void handleTutorIntro()
    {
        clearStaleClaim();
        if (lastClaimTime.isEmpty())
        {
            lastKnownCooldownTime = java.util.Optional.of(Instant.now());
            knownOnCooldown = true;
            configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY,
                String.valueOf(lastKnownCooldownTime.get().toEpochMilli()));
        }
    }

    private void handleCooldownRejection()
    {
        clearStaleClaim();
        lastKnownCooldownTime = java.util.Optional.of(Instant.now());
        knownOnCooldown = true;
        configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_COOLDOWN_KEY,
            String.valueOf(lastKnownCooldownTime.get().toEpochMilli()));
    }

    // If the previous claim has expired, clear it so new tracking can start.
    private void clearStaleClaim()
    {
        if (lastClaimTime.isPresent() && isReady())
        {
            lastClaimTime = java.util.Optional.empty();
            knownOnCooldown = false;
            lastKnownCooldownTime = java.util.Optional.empty();
            safeClearConfig(LAST_CLAIM_KEY);
            safeClearConfig(LAST_KNOWN_COOLDOWN_KEY);
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (config.notifyOnReady() && !notifiedReady && isReady())
        {
            notifier.notify("Your free runes and arrows are ready to claim!");
            notifiedReady = true;
        }

        boolean shouldShow = config.showInfoBox() && (!config.showWhenReady() || isReady());
        if (!shouldShow) removeInfoBox();
        else if (infoBox == null) addInfoBox();

        // Throttled: clear persisted known-cooldown when it expires
        long now = System.currentTimeMillis();
        if (now - lastKnownCooldownExpiryCheck >= KNOWN_COOLDOWN_CHECK_INTERVAL_MS)
        {
            lastKnownCooldownExpiryCheck = now;
            if (lastClaimTime.isEmpty() && lastKnownCooldownTime.isPresent() && !isKnownCooldownActive())
            {
                lastKnownCooldownTime = java.util.Optional.empty();
                knownOnCooldown = false;
                safeClearConfig(LAST_KNOWN_COOLDOWN_KEY);
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (CONFIG_GROUP.equals(event.getGroup()))
        {
            removeInfoBox();
        }
    }

    // --- InfoBox API ---

    public String getTooltipText()
    {
        if (lastClaimTime.isEmpty() && !knownOnCooldown)
            return "Tutor Timer - Claim runes or arrows to start tracking";
        if (lastClaimTime.isEmpty())
            return "Tutor Timer - On cooldown, but unknown time remaining";
        if (isReady())
            return "Tutor Timer - Ready to claim!";
        return "Tutor Timer - " + getTimerText() + " remaining";
    }

    public String getTimerText()
    {
        if (lastClaimTime.isEmpty())
            return knownOnCooldown ? "< 30m" : "?";

        Duration elapsed = Duration.between(lastClaimTime.get(), Instant.now());
        Duration remaining = COOLDOWN.minus(elapsed);
        if (remaining.isNegative() || remaining.isZero()) return "Ready!";

        long minutes = remaining.toMinutes();
        long seconds = remaining.getSeconds() % 60;
        return config.showSeconds()
            ? String.format("%d:%02d", minutes, seconds)
            : String.format("%dm", minutes);
    }

    public boolean isReady()
    {
        return lastClaimTime.isPresent()
            && Duration.between(lastClaimTime.get(), Instant.now()).compareTo(COOLDOWN) >= 0;
    }

    public boolean isUnknown()
    {
        return lastClaimTime.isEmpty();
    }

    private boolean isKnownCooldownActive()
    {
        return lastKnownCooldownTime
            .map(t -> Instant.now().isBefore(t.plus(COOLDOWN)))
            .orElse(false);
    }
}
