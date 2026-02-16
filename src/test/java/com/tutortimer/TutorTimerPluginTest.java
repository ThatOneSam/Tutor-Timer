package com.tutortimer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;

import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TutorTimerPluginTest
{
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(TutorTimerPlugin.class);
        RuneLite.main(args);
    }

    @Test
    public void pluginClassIsPresent()
    {
        // compile-time reference - fails fast if the class/name is wrong
        assertNotNull(TutorTimerPlugin.class);
    }

    @Test
    public void pluginImplementsRuneLitePlugin()
    {
        assertTrue("TutorTimerPlugin should implement net.runelite.client.plugins.Plugin",
            Plugin.class.isAssignableFrom(TutorTimerPlugin.class));
    }

    @Test
    public void getTimerText_unknownAndKnownOnCooldown() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // default state - unknown
        assertEquals("?", plugin.getTimerText());

        // ensure lastClaimTime is null and flip knownOnCooldown to true via reflection
        Field lastClaimField = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaimField.setAccessible(true);
        lastClaimField.set(plugin, null);
        assertNull(lastClaimField.get(plugin));

        Field knownField = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        knownField.setAccessible(true);
        knownField.setBoolean(plugin, true);

        // sanity-check the reflection write actually took effect
        assertTrue("knownOnCooldown should be true after reflection", knownField.getBoolean(plugin));

        assertEquals("< 30m", plugin.getTimerText());
    }

    @Test
    public void isReady_logic() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);

        // null -> not ready
        lastClaim.set(plugin, null);
        assertFalse(plugin.isReady());

        // far in the past -> ready
        lastClaim.set(plugin, Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1));
        assertTrue(plugin.isReady());

        // just now -> not ready
        lastClaim.set(plugin, Instant.now());
        assertFalse(plugin.isReady());
    }

    @Test
    public void getTimerText_showsSecondsWhenConfigured() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // install a config that enables seconds
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("config");
        cfgField.setAccessible(true);
        cfgField.set(plugin, new TutorTimerConfig() { });

        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);

        // set lastClaim so remaining time includes seconds (not a full minute)
        lastClaim.set(plugin, Instant.now().minus(Duration.ofMinutes(29)).minusSeconds(30));

        String txt = plugin.getTimerText();
        // expect a mm:ss style string when seconds are shown
        assertTrue(txt.matches("\\d+:\\d{2}"));
    }

    @Test
    public void getTooltipText_variousStates() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // install a basic config for timer formatting (used later by getTimerText())
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("config");
        cfgField.setAccessible(true);
        cfgField.set(plugin, new TutorTimerConfig() { });

        // default unknown
        assertEquals("Tutor Timer - claim runes or arrows to start tracking", plugin.getTooltipText());

        // knownOnCooldown true, but lastClaimTime null
        Field knownField = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        knownField.setAccessible(true);
        knownField.setBoolean(plugin, true);
        assertEquals("Tutor Timer - on cooldown, but unknown time remaining", plugin.getTooltipText());

        // set lastClaimTime far in the past -> ready
        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        lastClaim.set(plugin, Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1));
        assertEquals("Tutor Timer - ready to claim!", plugin.getTooltipText());

        // set recent lastClaimTime -> not ready
        lastClaim.set(plugin, Instant.now().minus(Duration.ofMinutes(29)).minusSeconds(30));
        String tooltip = plugin.getTooltipText();
        // tooltip should be in the form "Tutor Timer - <mm:ss> remaining"
        assertTrue(tooltip.startsWith("Tutor Timer - "));
        assertTrue(tooltip.endsWith(" remaining"));
        assertTrue(tooltip.matches("Tutor Timer - \\d+:\\d{2} remaining"));
    }

    @Test
    public void onChatMessage_detectsMesboxClaim() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // inject a mock ConfigManager so saveLastClaimTime() doesn't NPE
        Field cfgMgr = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgMgr.setAccessible(true);
        cfgMgr.set(plugin, mock(net.runelite.client.config.ConfigManager.class));

        // create a fake MESBOX chat message
        ChatMessage ev = mock(ChatMessage.class);
        when(ev.getType()).thenReturn(ChatMessageType.MESBOX);
        when(ev.getMessage()).thenReturn("Mikasi gives you 30 mind runes and 30 air runes.");

        // call handler
        plugin.onChatMessage(ev);

        // verify state changed
        Field known = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        known.setAccessible(true);
        assertTrue(known.getBoolean(plugin));

        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        assertNotNull(lastClaim.get(plugin));
    }

    @Test
    public void onChatMessage_introLineSetsKnownCooldown() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // inject a mock ConfigManager so clearing will attempt to persist lastKnownCooldown
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        // simulate the Ranged tutor introductory dialog (some clients only emit this)
        ChatMessage ev = mock(ChatMessage.class);
        when(ev.getType()).thenReturn(ChatMessageType.DIALOG);
        when(ev.getMessage()).thenReturn("Ranged combat tutor|I work with the Magic tutor to give out consumable items that you may need for combat such as arrows and runes. However, we've had some cheeky people try to take both!");

        // call handler
        plugin.onChatMessage(ev);

        // verify known-cooldown set and persisted
        Field lastKnown = TutorTimerPlugin.class.getDeclaredField("lastKnownCooldownTime");
        lastKnown.setAccessible(true);
        assertNotNull(lastKnown.get(plugin));
        verify(cfg).setConfiguration(eq("tutortimer"), eq("lastKnownCooldown"), anyString());
    }

    @Test
    public void loadLastClaimTime_readsSavedValue() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        long epoch = Instant.now().minus(Duration.ofMinutes(10)).toEpochMilli();

        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(String.valueOf(epoch));
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);

        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method m = TutorTimerPlugin.class.getDeclaredMethod("loadLastClaimTime");
        m.setAccessible(true);
        m.invoke(plugin);

        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        assertEquals(Instant.ofEpochMilli(epoch), lastClaim.get(plugin));
    }

    @Test
    public void saveLastClaimTime_writesConfig() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        long epoch = Instant.now().toEpochMilli();
        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        lastClaim.set(plugin, Instant.ofEpochMilli(epoch));

        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method save = TutorTimerPlugin.class.getDeclaredMethod("saveLastClaimTime");
        save.setAccessible(true);
        save.invoke(plugin);

        verify(cfg).setConfiguration("tutortimer", "lastClaim", String.valueOf(epoch));
    }

    @Test
    public void knownCooldown_persistedAndLoaded() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        long epoch = Instant.now().minus(Duration.ofMinutes(10)).toEpochMilli();

        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(String.valueOf(epoch));

        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method load = TutorTimerPlugin.class.getDeclaredMethod("loadLastClaimTime");
        load.setAccessible(true);
        load.invoke(plugin);

        Field known = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        known.setAccessible(true);
        assertTrue(known.getBoolean(plugin));
    }

    @Test
    public void knownCooldown_expiredIsIgnored() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        long epoch = Instant.now().minus(Duration.ofMinutes(31)).toEpochMilli();

        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(String.valueOf(epoch));

        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method load = TutorTimerPlugin.class.getDeclaredMethod("loadLastClaimTime");
        load.setAccessible(true);
        load.invoke(plugin);

        Field known = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        known.setAccessible(true);
        assertFalse(known.getBoolean(plugin));

        // expired value should be cleared
        verify(cfg).setConfiguration(eq("tutortimer"), eq("lastKnownCooldown"), isNull());
    }

    @Test
    public void knownCooldown_helpers_nullAndExpiredAndActive()
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // null -> not active, zero remaining
        plugin.setLastKnownCooldownTime(null);
        assertFalse(plugin.isKnownCooldownActive());
        assertEquals(Duration.ZERO, plugin.getKnownCooldownRemaining());

        // expired -> not active, zero remaining
        plugin.setLastKnownCooldownTime(Instant.now().minus(Duration.ofMinutes(31)));
        assertFalse(plugin.isKnownCooldownActive());
        assertEquals(Duration.ZERO, plugin.getKnownCooldownRemaining());

        // active -> true and remaining > 0 and <= COOLDOWN
        plugin.setLastKnownCooldownTime(Instant.now().minus(Duration.ofMinutes(10)));
        assertTrue(plugin.isKnownCooldownActive());
        Duration rem = plugin.getKnownCooldownRemaining();
        assertTrue(rem.compareTo(Duration.ZERO) > 0);
        assertTrue(rem.compareTo(TutorTimerPlugin.COOLDOWN) <= 0);
    }

    @Test
    public void knownCooldown_expiryClearedOnGameTick() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // set expired known-cooldown and mark as known
        plugin.setLastKnownCooldownTime(Instant.now().minus(Duration.ofMinutes(31)));

        Field knownFlag = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        knownFlag.setAccessible(true);
        knownFlag.setBoolean(plugin, true);

        // ensure no real claim exists
        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        lastClaim.set(plugin, null);

        // inject mock ConfigManager
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        // ensure expiry check will run (throttle reset)
        Field checkField = TutorTimerPlugin.class.getDeclaredField("lastKnownCooldownExpiryCheck");
        checkField.setAccessible(true);
        checkField.setLong(plugin, 0L);

        // install a basic config so onGameTick can read settings
        Field cfgField2 = TutorTimerPlugin.class.getDeclaredField("config");
        cfgField2.setAccessible(true);
        cfgField2.set(plugin, new TutorTimerConfig() {
            @Override
            public boolean showInfoBox()
            {
                return false;
            }
        });

        // inject mock ItemManager and InfoBoxManager to avoid NPE when InfoBox is created
        net.runelite.client.game.ItemManager itemManager = mock(net.runelite.client.game.ItemManager.class);
        when(itemManager.getImage(anyInt())).thenAnswer(invocation -> null);
        Field itemField = TutorTimerPlugin.class.getDeclaredField("itemManager");
        itemField.setAccessible(true);
        itemField.set(plugin, itemManager);

        net.runelite.client.ui.overlay.infobox.InfoBoxManager infoBoxManager = mock(net.runelite.client.ui.overlay.infobox.InfoBoxManager.class);
        Field infoBoxField = TutorTimerPlugin.class.getDeclaredField("infoBoxManager");
        infoBoxField.setAccessible(true);
        infoBoxField.set(plugin, infoBoxManager);

        // call onGameTick which should perform the throttled check and clear expired known cooldown
        plugin.onGameTick(new net.runelite.api.events.GameTick());

        // verify persisted value cleared and in-memory flags cleared
        verify(cfg).setConfiguration("tutortimer", "lastKnownCooldown", null);
        assertFalse(knownFlag.getBoolean(plugin));

        Field lastKnownField = TutorTimerPlugin.class.getDeclaredField("lastKnownCooldownTime");
        lastKnownField.setAccessible(true);
        assertNull(lastKnownField.get(plugin));
    }

    @Test
    public void knownCooldown_expiryClearedInSession() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();

        // set expired known-cooldown and mark as known
        plugin.setLastKnownCooldownTime(Instant.now().minus(Duration.ofMinutes(31)));

        Field knownFlag = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        knownFlag.setAccessible(true);
        knownFlag.setBoolean(plugin, true);

        // ensure no real claim exists
        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        lastClaim.set(plugin, null);

        // inject mock ConfigManager
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        // ensure expiry check will run (throttle reset)
        Field checkField = TutorTimerPlugin.class.getDeclaredField("lastKnownCooldownExpiryCheck");
        checkField.setAccessible(true);
        checkField.setLong(plugin, 0L);

        // install a basic config so onGameTick can read settings
        Field cfgField2 = TutorTimerPlugin.class.getDeclaredField("config");
        cfgField2.setAccessible(true);
        cfgField2.set(plugin, new TutorTimerConfig() {
            @Override
            public boolean showInfoBox()
            {
                return false;
            }
        });

        // inject mock ItemManager and InfoBoxManager to avoid NPE when InfoBox is created
        net.runelite.client.game.ItemManager itemManager = mock(net.runelite.client.game.ItemManager.class);
        when(itemManager.getImage(anyInt())).thenAnswer(invocation -> null);
        Field itemField = TutorTimerPlugin.class.getDeclaredField("itemManager");
        itemField.setAccessible(true);
        itemField.set(plugin, itemManager);

        net.runelite.client.ui.overlay.infobox.InfoBoxManager infoBoxManager = mock(net.runelite.client.ui.overlay.infobox.InfoBoxManager.class);
        Field infoBoxField = TutorTimerPlugin.class.getDeclaredField("infoBoxManager");
        infoBoxField.setAccessible(true);
        infoBoxField.set(plugin, infoBoxManager);

        // do NOT create a TutorTimerInfoBox instance here (it can have side-effects); rely on config to prevent UI creation

        // perform the expiry check directly (avoids triggering UI creation in onGameTick)
        plugin.checkAndClearExpiredKnownCooldown();

        // verify persisted value cleared and in-memory flags cleared
        verify(cfg).setConfiguration("tutortimer", "lastKnownCooldown", null);
        assertFalse(knownFlag.getBoolean(plugin));

        Field lastKnownField = TutorTimerPlugin.class.getDeclaredField("lastKnownCooldownTime");
        lastKnownField.setAccessible(true);
        assertNull(lastKnownField.get(plugin));
    }
}


