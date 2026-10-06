package com.onemillioncrops.util;

import java.util.ArrayList;
import java.util.List;

/** Builds the coloured console banner printed when the plugin enables. */
public final class StartupBanner {
    private static final String GRADIENT = "#FF4B4B:#FF8B2D:#FFD031:#DCFF39:#6DFF45:#3BFFC7:#58D0FF:#5489FF";
    private static final List<String> ART = List.of(
            " ██╗ ███╗   ███╗      ██████╗ ██████╗   ██████╗  ██████╗  ███████╗",
            "███║ ████╗ ████║     ██╔════╝ ██╔══██╗ ██╔═══██╗ ██╔══██╗ ██╔════╝",
            "╚██║ ██╔████╔██║     ██║      ██████╔╝ ██║   ██║ ██████╔╝ ███████╗",
            " ██║ ██║╚██╔╝██║     ██║      ██╔══██╗ ██║   ██║ ██╔═══╝  ╚════██║",
            " ██║ ██║ ╚═╝ ██║     ╚██████╗ ██║  ██║ ╚██████╔╝ ██║      ███████║",
            " ╚═╝ ╚═╝     ╚═╝      ╚═════╝ ╚═╝  ╚═╝  ╚═════╝  ╚═╝      ╚══════╝"
    );

    private StartupBanner() {
    }

    /** Returns MiniMessage lines; every art row shares one horizontal gradient so the colours line up. */
    public static List<String> lines(String version, int crops, int completed, long target) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        for (String row : ART) {
            lines.add("<bold><gradient:" + GRADIENT + ">" + row + "</gradient></bold>");
        }
        lines.add("");
        lines.add("  <gradient:#8CE99A:#FFD166><bold>One Million Crops</bold></gradient> <dark_gray>•</dark_gray> "
                + "<gray>v" + Text.escape(version) + "</gray>");
        lines.add("  <white>" + crops + "</white> <gray>crops</gray> <dark_gray>•</dark_gray> "
                + "<#8CE99A>" + completed + "</#8CE99A> <gray>complete</gray> <dark_gray>•</dark_gray> "
                + "<gray>target</gray> <#FFD166>" + Text.number(target) + "</#FFD166> <gray>each</gray>");
        lines.add("");
        return lines;
    }
}
