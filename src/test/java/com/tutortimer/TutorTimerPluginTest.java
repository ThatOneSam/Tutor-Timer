package com.tutortimer;

import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;

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

    // --- Reflection helpers ---

    private static void setField(Object obj, String name, Object value) throws Exception
    {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static Object getField(Object obj, String name) throws Exception
    {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    // --- Basic sanity ---

    @Test
    public void pluginClassIsPresent()
    {
        assertNotNull(TutorTimerPlugin.class);
    }

    @Test
    public void pluginImplementsRuneLitePlugin()
    {
        assertTrue(Plugin.class.isAssignableFrom(TutorTimerPlugin.class));
    }

    // --- Display logic (InfoBox public API) ---

    @Test
    public void getTimerText_unknownAndKnownOnCooldown() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        assertEquals("?", plugin.getTimerText());

        setField(plugin, "knownOnCooldown", true);
        assertEquals("< 30m", plugin.getTimerText());
    }

    @Test
    public void isReady_logic() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        assertFalse(plugin.isReady());

        setField(plugin, "lastClaimTime", java.util.Optional.of(Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1)));
        assertTrue(plugin.isReady());

        setField(plugin, "lastClaimTime", java.util.Optional.of(Instant.now()));
        assertFalse(plugin.isReady());
    }

    @Test
    public void getTimerText_showsSecondsWhenConfigured() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        setField(plugin, "config", new TutorTimerConfig()
        {
            @Override
            public boolean showSeconds()
            {
                return true;
            }
        });
        setField(plugin, "lastClaimTime", java.util.Optional.of(Instant.now().minus(Duration.ofMinutes(29)).minusSeconds(30)));
        assertTrue(plugin.getTimerText().matches("\\d+:\\d{2}"));
    }

    @Test
    public void getTooltipText_variousStates() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        setField(plugin, "config", new TutorTimerConfig() { });

        assertEquals("Tutor Timer - claim runes or arrows to start tracking", plugin.getTooltipText());

        setField(plugin, "knownOnCooldown", true);
        assertEquals("Tutor Timer - on cooldown, but unknown time remaining", plugin.getTooltipText());

        setField(plugin, "lastClaimTime", java.util.Optional.of(Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1)));
        assertEquals("Tutor Timer - ready to claim!", plugin.getTooltipText());

        setField(plugin, "lastClaimTime", java.util.Optional.of(Instant.now().minus(Duration.ofMinutes(29)).minusSeconds(30)));
        String tooltip = plugin.getTooltipText();
        assertTrue(tooltip.startsWith("Tutor Timer - "));
        assertTrue(tooltip.endsWith(" remaining"));
    }

    // --- Chat message handling ---

    @Test
    public void onChatMessage_detectsMesboxClaim() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        setField(plugin, "configManager", mock(ConfigManager.class));

        ChatMessage ev = mock(ChatMessage.class);
        when(ev.getType()).thenReturn(ChatMessageType.MESBOX);
        when(ev.getMessage()).thenReturn("Mikasi gives you 30 mind runes and 30 air runes.");

        plugin.onChatMessage(ev);

        assertTrue((boolean) getField(plugin, "knownOnCooldown"));
        assertTrue(((java.util.Optional<?>) getField(plugin, "lastClaimTime")).isPresent());
    }

    @Test
    public void onChatMessage_introSetsKnownCooldown() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        ConfigManager cfg = mock(ConfigManager.class);
        setField(plugin, "configManager", cfg);

        ChatMessage ev = mock(ChatMessage.class);
        when(ev.getType()).thenReturn(ChatMessageType.DIALOG);
        when(ev.getMessage()).thenReturn(
            "Ranged combat tutor|I work with the Magic tutor to give out consumable items.");

        plugin.onChatMessage(ev);

        assertNotNull(getField(plugin, "lastKnownCooldownTime"));
        verify(cfg).setConfiguration(eq("tutortimer"), eq("lastKnownCooldown"), anyString());
    }

    @Test
    public void onChatMessage_cooldownRejectionClearsStaleClaim() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn("existing");
        setField(plugin, "configManager", cfg);
        setField(plugin, "lastClaimTime", java.util.Optional.of(Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1)));

        ChatMessage ev = mock(ChatMessage.class);
        when(ev.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
        when(ev.getMessage()).thenReturn("You can only get items every half an hour.");

        plugin.onChatMessage(ev);

        Object val1 = getField(plugin, "lastClaimTime");
        assertFalse(((java.util.Optional<?>) val1).isPresent());
        assertTrue((boolean) getField(plugin, "knownOnCooldown"));
    }

    @Test
    public void onChatMessage_tutorIntroClearsStaleClaim() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn("existing");
        setField(plugin, "configManager", cfg);
        setField(plugin, "lastClaimTime", java.util.Optional.of(Instant.now().minus(TutorTimerPlugin.COOLDOWN).minusSeconds(1)));

        ChatMessage ev = mock(ChatMessage.class);
        when(ev.getType()).thenReturn(ChatMessageType.DIALOG);
        when(ev.getMessage()).thenReturn("I work with the Magic tutor to give out items.");

        plugin.onChatMessage(ev);

        Object val2 = getField(plugin, "lastClaimTime");
        assertFalse(((java.util.Optional<?>) val2).isPresent());
        assertTrue((boolean) getField(plugin, "knownOnCooldown"));
    }

    // --- Config persistence ---

    @Test
    public void loadLastClaimTime_readsSavedValue() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long epoch = Instant.now().minus(Duration.ofMinutes(10)).toEpochMilli();

        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(String.valueOf(epoch));
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn(null);
        setField(plugin, "configManager", cfg);

        plugin.loadLastClaimTime();

        Object val = getField(plugin, "lastClaimTime");
        assertEquals(Instant.ofEpochMilli(epoch), ((java.util.Optional<?>) val).get());
    }

    @Test
    public void loadLastClaimTime_knownCooldownPersisted() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long epoch = Instant.now().minus(Duration.ofMinutes(10)).toEpochMilli();

        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(String.valueOf(epoch));
        setField(plugin, "configManager", cfg);

        plugin.loadLastClaimTime();

        assertTrue((boolean) getField(plugin, "knownOnCooldown"));
    }

    @Test
    public void loadLastClaimTime_expiredKnownCooldownIgnored() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long epoch = Instant.now().minus(Duration.ofMinutes(31)).toEpochMilli();

        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(String.valueOf(epoch));
        setField(plugin, "configManager", cfg);

        plugin.loadLastClaimTime();

        assertFalse((boolean) getField(plugin, "knownOnCooldown"));
    }

    @Test
    public void loadLastClaimTime_clearsStaleClaimFromShutdown() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long now = System.currentTimeMillis();
        long claim = now - Duration.ofMinutes(10).toMillis();
        long shutdown = now - Duration.ofMinutes(5).toMillis();

        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(String.valueOf(claim));
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn(String.valueOf(shutdown));
        setField(plugin, "configManager", cfg);

        plugin.loadLastClaimTime();

        Object val = getField(plugin, "lastClaimTime");
        assertFalse("stale claim should be cleared", ((java.util.Optional<?>) val).isPresent());
    }

    @Test
    public void loadLastClaimTime_doesNotClearClaimWhenShutdownOutsideWindow() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long now = System.currentTimeMillis();
        long claim = now - Duration.ofMinutes(10).toMillis();
        // shutdown either before the claim or after expiry
        long shutdown = now - Duration.ofMinutes(40).toMillis();

        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(String.valueOf(claim));
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn(String.valueOf(shutdown));
        setField(plugin, "configManager", cfg);

        plugin.loadLastClaimTime();

        Object val = getField(plugin, "lastClaimTime");
        assertTrue("claim should survive when shutdown outside cooldown", ((java.util.Optional<?>) val).isPresent());
    }

    @Test
    public void loadLastClaimTime_keepsClaimWhenNoShutdownKey() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long now = System.currentTimeMillis();
        long claim = now - Duration.ofMinutes(10).toMillis();
        long shutdown = -1; // not used

        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(String.valueOf(claim));
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn(null);
        setField(plugin, "configManager", cfg);

        plugin.loadLastClaimTime();

        Object val = getField(plugin, "lastClaimTime");
        assertTrue("claim should remain when shutdown key absent", ((java.util.Optional<?>) val).isPresent());
    }

    @Test
    public void loadLastClaimTime_handlesMalformedShutdown() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        long now = System.currentTimeMillis();
        long claim = now - Duration.ofMinutes(10).toMillis();

        ConfigManager cfg = mock(ConfigManager.class);
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(String.valueOf(claim));
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn("not-a-number");
        setField(plugin, "configManager", cfg);

        plugin.loadLastClaimTime();

        Object val = getField(plugin, "lastClaimTime");
        assertTrue("claim should survive when shutdown value malformed", ((java.util.Optional<?>) val).isPresent());
        // log warning is emitted, but not asserted here
    }

    // --- Startup resilience ---

    @Test
    public void shutDown_recordsShutdownTime() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        ConfigManager cfg = mock(ConfigManager.class);
        setField(plugin, "configManager", cfg);

        plugin.shutDown();

        verify(cfg).setConfiguration(eq("tutortimer"), eq("lastShutdown"), anyString());
    }

    @Test
    public void safeClearConfig_ignoresNullPointerOnClear() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        ConfigManager cfg = mock(ConfigManager.class);

        // simulate existing value, and throw NPE when attempting to clear it
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn("existing");
        doAnswer(invocation -> {
            Object value = invocation.getArgument(2);
            if (value == null)
            {
                throw new NullPointerException("value is marked non-null but is null");
            }
            return null;
        }).when(cfg).setConfiguration(anyString(), anyString(), anyString());

        setField(plugin, "configManager", cfg);

        // call the private helper via reflection; any exception would fail the test
        java.lang.reflect.Method m = TutorTimerPlugin.class.getDeclaredMethod("safeClearConfig", String.class);
        m.setAccessible(true);
        m.invoke(plugin, "lastClaim");

        // also verify we attempted to clear it once
        verify(cfg).setConfiguration("tutortimer", "lastClaim", null);
    }

    @Test
    public void startUp_survivesConfigExceptions() throws Exception
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        ConfigManager cfg = mock(ConfigManager.class);

        // make getConfiguration return some values so that loadLastClaimTime
        // performs a clear and triggers our mocked NPE above
        when(cfg.getConfiguration("tutortimer", "lastShutdown")).thenReturn("123");
        when(cfg.getConfiguration("tutortimer", "lastClaim")).thenReturn(null);
        when(cfg.getConfiguration("tutortimer", "lastKnownCooldown")).thenReturn(null);

        doThrow(new NullPointerException("value is marked non-null but is null"))
            .when(cfg).setConfiguration(eq("tutortimer"), eq("lastShutdown"), isNull());

        setField(plugin, "configManager", cfg);

        // should not propagate despite the exception bubbling out of the mock
        plugin.startUp();
    }

    @Test
    public void startUp_doesNotThrowOnNullDependencies()
    {
        TutorTimerPlugin plugin = new TutorTimerPlugin();
        plugin.startUp();
        assertNotNull(plugin);
    }
}



