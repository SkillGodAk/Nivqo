/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings;

import java.util.Locale;

/**
 * Language used only by Hushfacebook's own injected UI and messages.
 * SYSTEM follows the phone, while FOLLOW_FACEBOOK leaves Facebook's own application configuration in charge.
 */
public enum HushLanguage {
    SYSTEM(null),
    FOLLOW_FACEBOOK(null),
    TRADITIONAL_CHINESE(Locale.forLanguageTag("zh-TW")),
    ENGLISH(Locale.ENGLISH);

    private final Locale locale;

    HushLanguage(Locale locale) {
        this.locale = locale;
    }

    public boolean followsSystem() {
        return this == SYSTEM;
    }

    public boolean followsFacebook() {
        return this == FOLLOW_FACEBOOK;
    }

    public Locale locale() {
        return locale;
    }
}
