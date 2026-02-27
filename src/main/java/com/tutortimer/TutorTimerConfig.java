package com.tutortimer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("tutortimer")
public interface TutorTimerConfig extends Config
{
    @ConfigItem(
        keyName = "showInfoBox",
        name = "Show info box",
        description = "Togggle visibility of the info box timer",
        position = 0
    )
    default boolean showInfoBox()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showWhenReady",
        name = "Show info box only when ready",
        description = "Show the info box only when the timer is ready; disable to show it at all times",
        position = 1
    )
    default boolean showWhenReady()
    {
        return false;
    }

    @ConfigItem(
        keyName = "notifyOnReady",
        name = "Notify when ready",
        description = "Send a notification when the cooldown expires",
        position = 2
    )
    default boolean notifyOnReady()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showSeconds",
        name = "Show seconds",
        description = "Show seconds in the countdown, or just minutes",
        position = 3
    )
    default boolean showSeconds()
    {
        return true;
    }

    // ---------------------------------------------------------------------
    // Debug / support helpers
    // ---------------------------------------------------------------------
    @ConfigSection(
        name = "Debug",
        description = "Options for diagnosing issues",
        position = 50
    )
    String debugSection = "debug";

    @ConfigItem(
        section = "debug",
        keyName = "openLogFolder",
        name = "CLICK HERE TO OPEN DEBUG FOLDER",
        description = "Opens the Tutor Timer directory containing debug logs",
        position = 51
    )
    default boolean openLogFolder()
    {
        return false;
    }

    @ConfigItem(
        section = "debug",
        keyName = "enableDebugLog",
        name = "Enable debug log file",
        description = "Append extra Tutor‑Timer debug messages to ~/.runelite/tutor-timer/debug.log",
        position = 52
    )
    default boolean enableDebugLog()
    {
        return false;
    }

}
