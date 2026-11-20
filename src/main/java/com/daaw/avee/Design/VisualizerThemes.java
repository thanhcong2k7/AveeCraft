package com.daaw.avee.Design;

import com.daaw.avee.comp.Visualizer.Elements.Composition;
import com.daaw.avee.comp.Visualizer.Elements.RootElement;

public class VisualizerThemes {
    private static final VisualizerThemes INSTANCE = new VisualizerThemes();

    public static VisualizerThemes s() {
        return INSTANCE;
    }

    public RootElement getThemeObjectForLoading2(int id, boolean reset, String str) {
        RootElement rootElement = new RootElement("10", id);
        rootElement.addChildAtEnd(new Composition(true));
        return rootElement;
    }
}
