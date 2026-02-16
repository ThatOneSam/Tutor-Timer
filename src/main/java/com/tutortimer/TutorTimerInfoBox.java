package com.tutortimer;

import net.runelite.client.ui.overlay.infobox.InfoBox;
import javax.annotation.Nonnull;

import java.awt.*;
import java.awt.image.BufferedImage;

public class TutorTimerInfoBox extends InfoBox
{
    private final TutorTimerPlugin plugin;

    public TutorTimerInfoBox(BufferedImage image, @Nonnull TutorTimerPlugin plugin)
    {
        super(image, plugin);
        this.plugin = plugin;
    }

    @Override
    public String getTooltip()
    {
        return plugin.getTooltipText();
    }

    @Override
    public String getText()
    {
        return plugin.getTimerText();
    }

    @Override
    public Color getTextColor()
    {
        if (plugin.isReady()) return Color.GREEN;
        if (plugin.isUnknown()) return Color.YELLOW;
        return Color.WHITE;
    }
}
