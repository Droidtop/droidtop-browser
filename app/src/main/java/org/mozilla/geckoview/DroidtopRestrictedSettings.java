package org.mozilla.geckoview;

import java.util.Map;

/**
 * Source-pinned Gecko 160 adapter. Add owned-runtime restrictions to the exact startup
 * preference map, before GeckoThread/native initialization. The optional YAML loader can
 * ignore errors, so it is not the enforcement path. No reflection or ordinary runtime use.
 */
public final class DroidtopRestrictedSettings {
    private DroidtopRestrictedSettings() {}

    public static GeckoRuntimeSettings apply(
            GeckoRuntimeSettings settings, Map<String, Object> required) {
        for (Map.Entry<String, Object> entry : required.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (value instanceof Boolean) {
                settings.new Pref<Boolean>(key, (Boolean) value);
            } else if (value instanceof Integer) {
                settings.new Pref<Integer>(key, (Integer) value);
            } else if (value instanceof String) {
                settings.new Pref<String>(key, (String) value);
            } else {
                throw new IllegalArgumentException("Unsupported restricted preference type");
            }
        }
        final Map<String, Object> startup = settings.getPrefsMap();
        for (Map.Entry<String, Object> entry : required.entrySet()) {
            if (!entry.getValue().equals(startup.get(entry.getKey()))) {
                throw new IllegalStateException("Restricted startup preference was not retained");
            }
        }
        return settings;
    }
}
