package org.xmlpull.v1;

import java.io.Reader;

/**
 * Desktop stand-in so {@code MuseCloudManualTest} can compile {@code S3.java}
 * on a JDK. The APK uses Android's real {@code org.xmlpull.v1} (build-apk.sh
 * compiles {@code src/main} only). These types are never packaged.
 */
public interface XmlPullParser {
    int START_TAG = 2;
    int END_TAG = 3;
    int END_DOCUMENT = 1;

    void setInput(Reader reader);

    int next();

    String getName();

    String nextText();
}
