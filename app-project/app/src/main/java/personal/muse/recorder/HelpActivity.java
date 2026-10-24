package personal.muse.recorder;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

/** Full-screen help page, rendered from assets/help.html. */
public class HelpActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("使用帮助");
        setContentView(R.layout.activity_help);
        WebView webView = findViewById(R.id.helpWebView);
        webView.loadUrl("file:///android_asset/help.html");
    }
}
