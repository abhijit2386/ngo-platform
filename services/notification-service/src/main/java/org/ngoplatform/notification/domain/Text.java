package org.ngoplatform.notification.domain;

final class Text {
    private Text() {}
    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
