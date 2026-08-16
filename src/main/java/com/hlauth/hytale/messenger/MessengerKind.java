package com.hlauth.hytale.messenger;

/** External 2FA / account-link channel. */
public enum MessengerKind {
    TELEGRAM("telegram", "Telegram"),
    DISCORD("discord", "Discord");

    private final String id;
    private final String display;

    MessengerKind(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public static MessengerKind fromInput(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if ("telegram".equals(v) || "tg".equals(v) || "телеграм".equals(v)) {
            return TELEGRAM;
        }
        if ("discord".equals(v) || "ds".equals(v) || "дискорд".equals(v)) {
            return DISCORD;
        }
        return null;
    }
}
