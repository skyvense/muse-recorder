package personal.muse.recorder;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Markdown -> Spannable renderer for the Reports tab. The daily
 * summaries and window analyses stored in Supabase are Markdown files
 * (generated from the .md reports), and showing them raw left ##, ** and
 * - markers all over the detail page.
 *
 * This is deliberately NOT a general Markdown engine — it covers exactly
 * what the generated texts use, plus a few cheap defensive cases:
 *   # / ## / ### headings, **bold**, *italic*, `code`, - bullets (with
 *   2-space nesting), 1. numbered items, > quotes, --- rules, emoji and
 *   plain paragraphs as-is, [text](url) as accent + underline (not
 *   clickable — no movement method, so the surrounding ScrollView keeps
 *   its gestures), and pipe tables as monospace lines.
 * NOT supported: __bold__/_italic_ (underscores appear in file names in
 * these texts, so treating them as markers would corrupt them), images,
 * nested inline markup inside code spans, HTML.
 * All colors/sizes come from the Ui palette, so light and dark match.
 */
public final class Markdown {
    private Markdown() {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern RULE =
            Pattern.compile("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern BULLET = Pattern.compile("^( *)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED =
            Pattern.compile("^( *)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern TABLE_SEP =
            Pattern.compile("^\\|?[\\s:|-]+\\|?$");

    private static final int SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
    private static final String DIVIDER = "────────────────────";

    /**
     * Render Markdown text for a TextView whose text size is textSizePx
     * (used to measure the hanging indent of numbered items).
     */
    public static SpannableStringBuilder render(Context ctx, String md,
                                                Ui.Palette pal, float textSizePx) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (md == null) return sb;
        float density = ctx.getResources().getDisplayMetrics().density;
        int stepPx = Math.round(20 * density);
        Paint measure = new Paint();
        measure.setTextSize(textSizePx);
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", -1);
        boolean firstBlock = true;
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty()) { i++; continue; }

            // Pipe table: consecutive | lines become monospace rows;
            // the |---|---| separator row is dropped.
            if (line.startsWith("|")) {
                if (!firstBlock) sb.append("\n");
                firstBlock = false;
                boolean firstRow = true;
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    String t = lines[i].trim();
                    i++;
                    if (TABLE_SEP.matcher(t).matches() && t.contains("-")) continue;
                    if (!firstRow) sb.append("\n");
                    firstRow = false;
                    String row = t.replaceAll("^\\||\\|$", "");
                    String[] cells = row.split("\\|", -1);
                    StringBuilder joined = new StringBuilder();
                    for (int c = 0; c < cells.length; c++) {
                        if (c > 0) joined.append("  ·  ");
                        joined.append(cells[c].trim());
                    }
                    int rs = sb.length();
                    appendInline(sb, joined.toString(), pal);
                    sb.setSpan(new TypefaceSpan("monospace"), rs, sb.length(), SPAN);
                }
                continue;
            }

            if (!firstBlock) sb.append("\n");
            firstBlock = false;
            Matcher m;
            if (RULE.matcher(line).matches()) {
                int s = sb.length();
                sb.append(DIVIDER);
                sb.setSpan(new ForegroundColorSpan(pal.sub), s, sb.length(), SPAN);
                sb.append("\n");
            } else if ((m = HEADING.matcher(line)).matches()) {
                int level = m.group(1).length();
                int s = sb.length();
                appendInline(sb, m.group(2).trim(), pal);
                int e = sb.length();
                float scale = level == 1 ? 1.5f : level == 2 ? 1.3f
                        : level == 3 ? 1.16f : 1.05f;
                sb.setSpan(new RelativeSizeSpan(scale), s, e, SPAN);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, e, SPAN);
                sb.setSpan(new ForegroundColorSpan(pal.text), s, e, SPAN);
                sb.append("\n"); // blank line after a heading
            } else if ((m = QUOTE.matcher(line)).matches()) {
                int s = sb.length();
                appendInline(sb, m.group(1), pal);
                int e = sb.length();
                sb.setSpan(new ForegroundColorSpan(pal.sub), s, e, SPAN);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), s, e, SPAN);
                sb.setSpan(new LeadingMarginSpan.Standard(stepPx, stepPx), s, e, SPAN);
            } else if ((m = BULLET.matcher(raw)).matches()) {
                int level = m.group(1).length() / 2;
                int s = sb.length();
                appendInline(sb, m.group(2), pal);
                int e = sb.length();
                if (level > 0) {
                    sb.setSpan(new LeadingMarginSpan.Standard(
                            level * stepPx, level * stepPx), s, e, SPAN);
                }
                BulletSpan bs = Build.VERSION.SDK_INT >= 28
                        ? new BulletSpan(stepPx / 2, pal.sub)
                        : new BulletSpan(stepPx / 2);
                sb.setSpan(bs, s, e, SPAN);
            } else if ((m = NUMBERED.matcher(raw)).matches()) {
                int level = m.group(1).length() / 2;
                String prefix = m.group(2) + ". ";
                int s = sb.length();
                sb.append(prefix);
                appendInline(sb, m.group(3), pal);
                int e = sb.length();
                int first = level * stepPx;
                int rest = first + Math.round(measure.measureText(prefix));
                sb.setSpan(new LeadingMarginSpan.Standard(first, rest), s, e, SPAN);
            } else {
                appendInline(sb, line, pal);
            }
            i++;
        }
        return sb;
    }

    /**
     * Inline markup: **bold**, *italic*, `code`, [text](url).
     * Bold content is rendered recursively so **a `b` c** still works.
     * Unmatched markers are left as literal text rather than swallowed.
     */
    private static void appendInline(SpannableStringBuilder sb, String s,
                                     Ui.Palette pal) {
        int i = 0, n = s.length();
        while (i < n) {
            if (s.startsWith("**", i)) {
                int end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    appendInline(sb, s.substring(i + 2, end), pal);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), SPAN);
                    i = end + 2;
                    continue;
                }
            }
            char c = s.charAt(i);
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = sb.length();
                    sb.append(s, i + 1, end);
                    sb.setSpan(new TypefaceSpan("monospace"), st, sb.length(), SPAN);
                    sb.setSpan(new BackgroundColorSpan(pal.card2), st, sb.length(), SPAN);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = s.indexOf("](", i);
                if (close > i + 1) {
                    int endParen = s.indexOf(')', close + 2);
                    if (endParen > close + 2) {
                        int st = sb.length();
                        sb.append(s, i + 1, close);
                        sb.setSpan(new ForegroundColorSpan(pal.accent), st, sb.length(), SPAN);
                        sb.setSpan(new UnderlineSpan(), st, sb.length(), SPAN);
                        i = endParen + 1;
                        continue;
                    }
                }
            }
            if (c == '*' && !s.startsWith("**", i)) {
                int end = s.indexOf('*', i + 1);
                if (end > i + 1) {
                    int st = sb.length();
                    appendInline(sb, s.substring(i + 1, end), pal);
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), st, sb.length(), SPAN);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
    }

    /**
     * One clean plain-text line for list previews: first content line of
     * the text with heading/bullet/quote/number markers and inline
     * **, `, link syntax removed, so cards never show ## or **.
     */
    public static String stripToPlain(String text) {
        if (text == null) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", -1);
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (RULE.matcher(t).matches()) continue;
            t = t.replaceFirst("^#{1,6}\\s+", "");
            t = t.replaceFirst("^>\\s?", "");
            t = t.replaceFirst("^[-*+]\\s+", "");
            t = t.replaceFirst("^\\d+[.)]\\s+", "");
            t = stripInline(t);
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String stripInline(String s) {
        s = s.replace("**", "").replace("`", "");
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replace("*", "");
        return s.trim();
    }
}
