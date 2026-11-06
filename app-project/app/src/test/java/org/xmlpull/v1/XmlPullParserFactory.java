package org.xmlpull.v1;

/** See {@link XmlPullParser}. Enough of the factory for {@code S3.java} to compile. */
public class XmlPullParserFactory {
    public static XmlPullParserFactory newInstance() {
        return new XmlPullParserFactory();
    }

    public void setNamespaceAware(boolean aware) {}

    public XmlPullParser newPullParser() {
        return new XmlPullParser() {
            @Override public void setInput(java.io.Reader reader) {}
            @Override public int next() { return END_DOCUMENT; }
            @Override public String getName() { return ""; }
            @Override public String nextText() { return ""; }
        };
    }
}
