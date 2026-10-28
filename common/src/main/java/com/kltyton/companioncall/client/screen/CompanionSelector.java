package com.kltyton.companioncall.client.screen;

import io.github.kltyton.kltytonui.screen.KltytonScreen;

public final class CompanionSelector extends KltytonScreen {
    public static final String PATH = "companion_call/selection.html";
    public CompanionSelector() {
        super(PATH);
        setPauseGame(false);
    }
}
