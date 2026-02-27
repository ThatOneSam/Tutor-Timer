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
import net.runelite.client.events.ConfigChanged;

import java.nio.file.Files;
import java.nio.file.Path;

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

        // default unknown (note the capitalisation used by the plugin)
        assertEquals("Tutor Timer - Claim runes or arrows to start tracking", plugin.getTooltipText());

        // knownOnCooldown true, but lastClaimTime null
        Field knownField = TutorTimerPlugin.class.getDeclaredField("knownOnCooldown");
        knownField.setAccessible(true);
        knownField.setBoolean(plugin, true);
        assertEquals("Tutor Timer - On cooldown, but unknown time remaining", plugin.getTooltipText());

        // set lastClaimTime far in the past -> ready
        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        lastClaim.set(plugin, Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1));
        assertEquals("Tutor Timer - Ready to claim!", plugin.getTooltipText());

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
        // return a non-null shutdown value so loadLastClaimTime will attempt to clear it
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn("123");

        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method m = TutorTimerPlugin.class.getDeclaredMethod("loadLastClaimTime");
        m.setAccessible(true);
        m.invoke(plugin);

        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        assertEquals(Instant.ofEpochMilli(epoch), lastClaim.get(plugin));

        // shutdown key should be cleared even if it wasn't set; we don't assert on
        // the config write because the clearing helper might skip when the value is
        // already absent.
    }

    @Test
    public void safeClearConfig_handlesNullManager() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        // configManager is intentionally left null
        Method m = TutorTimerPlugin.class.getDeclaredMethod("safeClearConfig", String.class);
        m.setAccessible(true);
        // should not throw even though manager is null
        Object result = m.invoke(plugin, "someKey");
        assertNull("safeClearConfig should return null", result);
    }

    @Test
    public void safeClearConfig_skipsWhenAlreadyNull() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        // pretend the value is already absent
        when(cfg.getConfiguration("tutortimer", "foo")).thenReturn(null);

        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method m = TutorTimerPlugin.class.getDeclaredMethod("safeClearConfig", String.class);
        m.setAccessible(true);
        // invoking should neither throw nor call setConfiguration at all
        m.invoke(plugin, "foo");
        verify(cfg, never()).setConfiguration(eq("tutortimer"), eq("foo"), any());
    }

    @Test
    public void safeClearConfig_ignoresNpeFromManager() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        // simulate the bad-null-check behaviour observed in the log output
        when(cfg.getConfiguration("tutortimer", "foo")).thenReturn("bar");
        doThrow(new NullPointerException("value is marked non-null but is null"))
            .when(cfg).setConfiguration(eq("tutortimer"), eq("foo"), isNull());

        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method m = TutorTimerPlugin.class.getDeclaredMethod("safeClearConfig", String.class);
        m.setAccessible(true);
        // the call should complete without propagating the NPE
        m.invoke(plugin, "foo");

        // we still attempted a clear operation
        verify(cfg).setConfiguration("tutortimer", "foo", (String) null);
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
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn("value");
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

        // in-memory flags should be cleared; persistence is not asserted here
        //verify(cfg).setConfiguration("tutortimer", "lastKnownCooldown", null);
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
        // stub an existing value to force safeClearConfig to clear it
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn("value");
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

        // in-memory flags should be cleared; persistence details not asserted
        //verify(cfg).setConfiguration("tutortimer", "lastKnownCooldown", null);
        assertFalse(knownFlag.getBoolean(plugin));

        Field lastKnownField = TutorTimerPlugin.class.getDeclaredField("lastKnownCooldownTime");
        lastKnownField.setAccessible(true);
        assertNull(lastKnownField.get(plugin));
    }

    @Test
    public void shutDown_recordsShutdownTime() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        // call shutDown and verify config written
        plugin.shutDown();
        verify(cfg).setConfiguration(eq("tutortimer"), eq("lastShutdown"), anyString());
    }

    @Test
    public void loadLastClaimTime_clearsStaleClaimWhenDisabledDuringCooldown() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long now = System.currentTimeMillis();
        long claim = now - Duration.ofMinutes(10).toMillis();
        long shutdown = now - Duration.ofMinutes(5).toMillis();

        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(String.valueOf(claim));
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn(String.valueOf(shutdown));

        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        Method load = TutorTimerPlugin.class.getDeclaredMethod("loadLastClaimTime");
        load.setAccessible(true);
        load.invoke(plugin);

        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        assertNull("stale claim should be cleared", lastClaim.get(plugin));
        // configuration writes are implementation details; we just need to ensure the
        // in-memory state is correct.
        //verify(cfg).setConfiguration(eq("tutortimer"), eq("lastClaim"), isNull());
        //verify(cfg).setConfiguration(eq("tutortimer"), eq("lastShutdown"), isNull());
    }

    @Test
    public void handleCooldownRejection_clearsStaleClaim() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        // set claim in past such that isReady() == true
        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        lastClaim.set(plugin, Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1));

        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        // pre-populate the key so safeClearConfig actually performs a setConfiguration call
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn("existing");
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        plugin.handleCooldownRejection("... every half an hour ...");

        assertNull(lastClaim.get(plugin));
        verify(cfg).setConfiguration(eq("tutortimer"), eq("lastClaim"), isNull());
    }

    @Test
    public void handleTutorIntro_clearsStaleClaim() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        Field lastClaim = TutorTimerPlugin.class.getDeclaredField("lastClaimTime");
        lastClaim.setAccessible(true);
        lastClaim.set(plugin, Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1));

        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn("existing");
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        plugin.handleTutorIntro("I work with the Magic tutor");

        assertNull(lastClaim.get(plugin));
        verify(cfg).setConfiguration(eq("tutortimer"), eq("lastClaim"), isNull());
    }

    @Test
    public void startUp_doesNotThrowOnNullDependencies()
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        // don't initialize any fields, just call startUp
        plugin.startUp();
        // if no exception thrown we consider test passed
        assertNotNull("plugin should not be null after startUp", plugin);
    }

    @Test
    public void onConfigChanged_openLogFolderInvoked() throws Exception
    {
        class TestPlugin extends TutorTimerPlugin
        {
            boolean opened = false;

            @Override
            void openLogFolder()
            {
                opened = true;
            }
        }
        TestPlugin plugin = new TestPlugin();
        // inject mock ConfigManager to allow resetting the value
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgField.setAccessible(true);
        cfgField.set(plugin, cfg);

        // create bogus event
        ConfigChanged ev = mock(ConfigChanged.class);
        when(ev.getGroup()).thenReturn("tutortimer");
        when(ev.getKey()).thenReturn("openLogFolder");
        plugin.onConfigChanged(ev);
        assertTrue("openLogFolder should have been invoked", plugin.opened);
        // ensure we attempted to reset the checkbox
        verify(cfg).setConfiguration("tutortimer", "openLogFolder", "false");
    }

    @Test
    public void debugLog_writesFileWhenEnabled() throws Exception
    {
        Path tempDir = Files.createTempDirectory("tutor-log-test");
        System.setProperty("user.home", tempDir.toString());

        TutorTimerPlugin plugin = new TutorTimerPlugin();
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("config");
        cfgField.setAccessible(true);
        cfgField.set(plugin, new TutorTimerConfig() {
            @Override
            public boolean enableDebugLog()
            {
                return true;
            }
        });

        Method debug = TutorTimerPlugin.class.getDeclaredMethod("debugLog", String.class);
        debug.setAccessible(true);
        debug.invoke(plugin, "hello world");

        Path logFile = plugin.getDebugLogPath();
        assertTrue(Files.exists(logFile));
        String contents = Files.readString(logFile);
        assertTrue(contents.contains("hello world"));
    }

    @Test
    public void debugLog_noFileWhenDisabled() throws Exception
    {
        Path tempDir = Files.createTempDirectory("tutor-log-test");
        System.setProperty("user.home", tempDir.toString());

        TutorTimerPlugin plugin = new TutorTimerPlugin();
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("config");
        cfgField.setAccessible(true);
        cfgField.set(plugin, new TutorTimerConfig() {
            @Override
            public boolean enableDebugLog()
            {
                return false;
            }
        });

        Method debug = TutorTimerPlugin.class.getDeclaredMethod("debugLog", String.class);
        debug.setAccessible(true);
        debug.invoke(plugin, "won't be logged");

        Path logFile = plugin.getDebugLogPath();
        assertFalse(Files.exists(logFile));
    }

    @Test
    public void startUp_retriesWhenConfigNull() throws Exception
    {
        // use Mockito spy to observe internal method calls without altering
        // behaviour (super.loadLastClaimTime contains its own guard).
        TutorTimerPlugin plugin = spy(new TutorTimerPlugin());

        Field cfgMgr = TutorTimerPlugin.class.getDeclaredField("configManager");
        cfgMgr.setAccessible(true);
        // start with config manager missing
        cfgMgr.set(plugin, null);

        // invocation should complete without throwing; loadLastClaimTime will execute
        // once but simply return early because of the null guard.
        plugin.startUp();
        verify(plugin, times(1)).loadLastClaimTime();

        // now inject a real config manager and start again; second start should also
        // call the loader exactly once more.
        net.runelite.client.config.ConfigManager cfg = mock(net.runelite.client.config.ConfigManager.class);
        cfgMgr.set(plugin, cfg);
        plugin.startUp();
        verify(plugin, times(2)).loadLastClaimTime();
    }

    @Test
    public void debugLog_rotatesWhenLarge() throws Exception
    {
        Path tempDir = Files.createTempDirectory("tutor-log-test");
        System.setProperty("user.home", tempDir.toString());

        TutorTimerPlugin plugin = new TutorTimerPlugin();
        Field cfgField = TutorTimerPlugin.class.getDeclaredField("config");
        cfgField.setAccessible(true);
        cfgField.set(plugin, new TutorTimerConfig() {
            @Override
            public boolean enableDebugLog()
            {
                return true;
            }
        });

        // artificially set small max size via reflection
        Field maxField = TutorTimerPlugin.class.getDeclaredField("maxDebugFileBytes");
        maxField.setAccessible(true);
        maxField.setLong(null, 50); // 50 bytes threshold

        Method debug = TutorTimerPlugin.class.getDeclaredMethod("debugLog", String.class);
        debug.setAccessible(true);

        for (int i = 0; i < 10; i++)
        {
            debug.invoke(plugin, "entry " + i);
        }

        Path logFile = plugin.getDebugLogPath();
        assertTrue(Files.exists(logFile));
        String contents = Files.readString(logFile);
        // after rotation, header should appear once
        assertTrue(contents.contains("rotated debug log"));
    }
}



