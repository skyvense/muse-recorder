package personal.muse.recorder;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Applies the user's in-app language choice ("system" = follow the device).
 * Activities and the service wrap their base context with {@link #wrap};
 * background string building (Uploader) wraps the context it is given so
 * result texts follow the same language.
 */
public class LocaleHelper {
    public static final String[] CODES =
            {"system", "en", "zh", "ja", "ko", "es", "fr", "de", "pt", "ru"};

    public static String savedCode(Context ctx) {
        return Prefs.get(ctx).getString(Prefs.KEY_APP_LANGUAGE, "system");
    }

    public static Locale localeFor(String code) {
        switch (code) {
            case "zh": return Locale.SIMPLIFIED_CHINESE;
            case "ja": return Locale.JAPANESE;
            case "ko": return Locale.KOREAN;
            case "es": return new Locale("es");
            case "fr": return Locale.FRENCH;
            case "de": return Locale.GERMAN;
            case "pt": return new Locale("pt");
            case "ru": return new Locale("ru");
            case "en": return Locale.ENGLISH;
            default: return null;
        }
    }

    /** The language the UI should actually use, resolving "system". */
    public static String currentCode(Context ctx) {
        String saved = savedCode(ctx);
        if (!"system".equals(saved)) return saved;
        String lang = Locale.getDefault().getLanguage();
        for (String c : CODES) {
            if (c.equals(lang)) return lang;
        }
        return "en";
    }

    public static Context wrap(Context base) {
        Locale locale = localeFor(savedCode(base));
        if (locale == null) return base;
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
