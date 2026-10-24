package personal.muse.recorder;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.webkit.WebView;

/**
 * Full-screen help page. Chinese users get the full Chinese page;
 * everyone else gets the English page (other locales fall back to it
 * until their own translations are added).
 */
public class HelpActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.help_title);
        setContentView(R.layout.activity_help);
        WebView webView = findViewById(R.id.helpWebView);
        String file = LocaleHelper.currentCode(this).equals("zh")
                ? "help.html" : "help_en.html";
        webView.loadUrl("file:///android_asset/" + file);
    }
}
