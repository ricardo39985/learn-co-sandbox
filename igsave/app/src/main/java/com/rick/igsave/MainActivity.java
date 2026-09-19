package com.rick.igsave;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String AUTHORITY = "com.rick.igsave.files";
    private static final Pattern IG_URL = Pattern.compile("https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/[^\\s]+", Pattern.CASE_INSENSITIVE);

    private EditText input;
    private TextView status;
    private ProgressBar progress;
    private Button getButton, shareButton, saveButton;
    private WebView resolver;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> capturedVideos = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, Long> capturedImages = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<MediaFile> readyFiles = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean finishingResolve = new AtomicBoolean(false);
    private int inspectRound = 0;
    private String activePostUrl = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        configureResolver();
        consumeIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    @Override protected void onDestroy() {
        if (resolver != null) resolver.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(250,250,250));
        getWindow().setNavigationBarColor(Color.rgb(250,250,250));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(250,250,250));
        setContentView(frame);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(22));
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("IG Save", 30, true, Color.rgb(20,20,20));
        root.addView(title);

        TextView subtitle = text("Paste or share an Instagram post or reel.", 15, false, Color.rgb(92,92,92));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        root.addView(subtitle, subLp);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(4), 0, dp(4), 0);
        inputRow.setBackground(roundRect(Color.WHITE, 18, Color.rgb(225,225,225), 1));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        rowLp.topMargin = dp(26);
        root.addView(inputRow, rowLp);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setHint("https://www.instagram.com/reel/…");
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(12), 0, dp(8), 0);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button paste = smallButton("Paste");
        paste.setOnClickListener(v -> pasteClipboard());
        inputRow.addView(paste, new LinearLayout.LayoutParams(dp(78), dp(44)));

        getButton = primaryButton("Get media");
        LinearLayout.LayoutParams getLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        getLp.topMargin = dp(14);
        root.addView(getButton, getLp);
        getButton.setOnClickListener(v -> startResolve(input.getText().toString()));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progLp.topMargin = dp(20);
        root.addView(progress, progLp);

        status = text("Ready.", 15, false, Color.rgb(75,75,75));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(16);
        root.addView(status, statusLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        actionsLp.topMargin = dp(22);
        root.addView(actions, actionsLp);

        shareButton = secondaryButton("Share file");
        saveButton = secondaryButton("Download");
        shareButton.setEnabled(false);
        saveButton.setEnabled(false);
        shareButton.setAlpha(.45f);
        saveButton.setAlpha(.45f);

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        half.rightMargin = dp(6);
        actions.addView(shareButton, half);

        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        half2.leftMargin = dp(6);
        actions.addView(saveButton, half2);

        shareButton.setOnClickListener(v -> shareReadyFiles());
        saveButton.setOnClickListener(v -> saveReadyFiles());

        TextView foot = text("Public posts should work without signing in. Private or restricted posts are not supported.", 12, false, Color.rgb(125,125,125));
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(18);
        root.addView(foot, footLp);

        resolver = new WebView(this);
        resolver.setAlpha(0.01f);
        FrameLayout.LayoutParams hidden = new FrameLayout.LayoutParams(2, 2);
        hidden.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        frame.addView(resolver, hidden);
    }

    private void configureResolver() {
        WebSettings s = resolver.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(resolver, true);

        resolver.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!finishingResolve.get()) {
                    status("Reading the post…");
                    scheduleInspections();
                }
            }

            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                String lower = u.toLowerCase(Locale.US);
                if ((lower.contains("cdninstagram.com") || lower.contains("fbcdn.net")) &&
                        (lower.contains(".mp4") || lower.contains("/video/") || lower.contains("/v/t2/"))) {
                    capturedVideos.add(u);
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    private void consumeIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        String url = extractUrl(text.toString());
        if (url != null) {
            input.setText(url);
            startResolve(url);
        }
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        if (t != null) input.setText(t.toString());
    }

    private void startResolve(String raw) {
        String url = extractUrl(raw);
        if (url == null) {
            status("Paste a valid Instagram post or reel link.");
            return;
        }
        activePostUrl = url;
        input.setText(url);
        capturedVideos.clear();
        capturedImages.clear();
        readyFiles.clear();
        finishingResolve.set(false);
        inspectRound = 0;
        setBusy(true);
        shareButton.setEnabled(false); shareButton.setAlpha(.45f);
        saveButton.setEnabled(false); saveButton.setAlpha(.45f);
        status("Resolving public post…");
        executor.submit(() -> {
            List<String> urls = InstagramResolver.resolve(url);
            if (!activePostUrl.equals(url)) return;

            if (!urls.isEmpty()) {
                finishingResolve.set(true);
                runOnUiThread(() -> status("Found " + urls.size() + " media item" + (urls.size() == 1 ? "" : "s") + ". Fetching file…"));
                downloadAll(urls);
            } else {
                runOnUiThread(() -> {
                    if (!activePostUrl.equals(url)) return;
                    finishingResolve.set(true);
                    setBusy(false);
                    status("Couldn’t resolve this public post right now. Retry in a moment — signing in should not be required.");
                });
            }
        });
    }

    private void scheduleInspections() {
        long[] delays = new long[]{1200, 3200, 6000, 9000};
        for (long delay : delays) resolver.postDelayed(this::inspectPage, delay);
    }

    private void inspectPage() {
        if (finishingResolve.get()) return;
        inspectRound++;
        String js = "(function(){"
                + "const scope=document.querySelector('article')||document.querySelector('main')||document;"
                + "const vids=[];scope.querySelectorAll('video').forEach(v=>{const u=v.currentSrc||v.src;if(u)vids.push(u)});"
                + "document.querySelectorAll('meta[property=\\\"og:video\\\"],meta[property=\\\"og:video:url\\\"]').forEach(m=>{if(m.content)vids.push(m.content)});"
                + "const imgs=[];scope.querySelectorAll('img').forEach(i=>{const u=i.currentSrc||i.src;const a=(i.naturalWidth||0)*(i.naturalHeight||0);if(u&&a>=160000)imgs.push({u:u,a:a})});"
                + "const og=document.querySelector('meta[property=\\\"og:image\\\"]');if(og&&og.content)imgs.push({u:og.content,a:999999});"
                + "return JSON.stringify({href:location.href,videos:[...new Set(vids)],images:imgs});"
                + "})()";

        resolver.evaluateJavascript(js, value -> {
            try {
                Object parsed = new JSONTokener(value).nextValue();
                if (parsed instanceof String) {
                    JSONObject obj = new JSONObject((String) parsed);
                    JSONArray videos = obj.optJSONArray("videos");
                    if (videos != null) {
                        for (int i = 0; i < videos.length(); i++) addVideoCandidate(videos.optString(i));
                    }
                    JSONArray images = obj.optJSONArray("images");
                    if (images != null) {
                        for (int i = 0; i < images.length(); i++) {
                            JSONObject im = images.optJSONObject(i);
                            if (im != null) addImageCandidate(im.optString("u"), im.optLong("a", 0));
                        }
                    }
                }
            } catch (Exception ignored) { }

            if (!capturedVideos.isEmpty() && inspectRound >= 2) finishResolve();
            else if (inspectRound >= 4) finishResolve();
        });
    }

    private void addVideoCandidate(String u) {
        if (isHttpMedia(u)) capturedVideos.add(u);
    }

    private void addImageCandidate(String u, long area) {
        if (!isHttpMedia(u)) return;
        String lower = u.toLowerCase(Locale.US);
        if (!(lower.contains("cdninstagram.com") || lower.contains("fbcdn.net") || lower.contains("instagram.com"))) return;
        Long old = capturedImages.get(u);
        if (old == null || area > old) capturedImages.put(u, area);
    }

    private boolean isHttpMedia(String u) {
        return u != null && (u.startsWith("https://") || u.startsWith("http://")) && !u.startsWith("blob:");
    }

    private void finishResolve() {
        if (!finishingResolve.compareAndSet(false, true)) return;
        List<String> urls = new ArrayList<>();
        synchronized (capturedVideos) { urls.addAll(capturedVideos); }

        if (urls.isEmpty()) {
            List<Map.Entry<String, Long>> imgs;
            synchronized (capturedImages) { imgs = new ArrayList<>(capturedImages.entrySet()); }
            imgs.sort((a,b) -> Long.compare(b.getValue(), a.getValue()));
            long max = imgs.isEmpty() ? 0 : imgs.get(0).getValue();
            for (Map.Entry<String, Long> e : imgs) {
                if (urls.size() >= 10) break;
                if (max == 0 || e.getValue() >= Math.max(160000, (long)(max * .60))) urls.add(e.getKey());
            }
        }

        if (urls.isEmpty()) {
            setBusy(false);
            status("Couldn’t resolve media from this post. It may be private, restricted, expired, or temporarily blocked by Instagram.");
            return;
        }

        List<String> selected = urls;
        status("Found " + selected.size() + " media item" + (selected.size() == 1 ? "" : "s") + ". Fetching file…");
        executor.submit(() -> downloadAll(selected));
    }

    private void downloadAll(List<String> urls) {
        File dir = new File(getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();

        List<MediaFile> downloaded = new ArrayList<>();
        int n = 0;
        for (String mediaUrl : urls) {
            n++;
            final int index = n;
            runOnUiThread(() -> status("Fetching " + index + " of " + urls.size() + "…"));
            try {
                MediaFile mf = downloadOne(mediaUrl, dir, index);
                if (mf != null && mf.file.length() > 0) downloaded.add(mf);
            } catch (Exception ignored) { }
        }

        if (downloaded.isEmpty()) {
            runOnUiThread(() -> status("Refreshing media link…"));
            List<String> refreshed = InstagramResolver.resolve(activePostUrl);
            if (!refreshed.isEmpty() && !refreshed.equals(urls)) {
                int retryIndex = 0;
                for (String mediaUrl : refreshed) {
                    retryIndex++;
                    try {
                        MediaFile mf = downloadOne(mediaUrl, dir, retryIndex);
                        if (mf != null && mf.file.length() > 0) downloaded.add(mf);
                    } catch (Exception ignored) { }
                }
            }
        }

        runOnUiThread(() -> {
            readyFiles.clear();
            readyFiles.addAll(downloaded);
            setBusy(false);
            if (readyFiles.isEmpty()) {
                status("Couldn’t fetch the media file. Retry the post — signing in should not be required.");
            } else {
                status("Ready — " + readyFiles.size() + " file" + (readyFiles.size() == 1 ? "" : "s") + ".");
                shareButton.setEnabled(true); shareButton.setAlpha(1f);
                saveButton.setEnabled(true); saveButton.setAlpha(1f);
            }
        });
    }

    private MediaFile downloadOne(String mediaUrl, File dir, int index) throws Exception {
        URL url = new URL(mediaUrl);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", InstagramResolver.USER_AGENT);
        c.setRequestProperty("Referer", "https://www.instagram.com/");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        c.setRequestProperty("Connection", "close");
        c.connect();

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

        String mime = c.getContentType();
        if (mime != null) {
            int semi = mime.indexOf(';');
            if (semi > -1) mime = mime.substring(0, semi).trim();
        }
        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
            mime = URLConnection.guessContentTypeFromName(url.getPath());
        }
        if (mime == null) mime = mediaUrl.toLowerCase(Locale.US).contains(".mp4") ? "video/mp4" : "image/jpeg";

        String ext = extensionFor(mime, url.getPath());
        String codePart = shortcode(activePostUrl);
        String name = "Instagram_" + (codePart.isEmpty() ? System.currentTimeMillis() : codePart) + (index > 1 ? "_" + index : "") + ext;
        File out = new File(dir, name);

        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
        } finally {
            c.disconnect();
        }
        return new MediaFile(out, mime);
    }

    private void shareReadyFiles() {
        if (readyFiles.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        String commonType = readyFiles.get(0).mime;
        boolean mixed = false;

        for (MediaFile mf : readyFiles) {
            if (!commonType.equals(mf.mime)) mixed = true;
            uris.add(Uri.parse("content://" + AUTHORITY + "/share/" + Uri.encode(mf.file.getName())));
        }

        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(mixed ? "*/*" : commonType);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share media"));
    }

    private void saveReadyFiles() {
        if (readyFiles.isEmpty()) return;
        setBusy(true);

        executor.submit(() -> {
            int saved = 0;
            for (MediaFile mf : new ArrayList<>(readyFiles)) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, mf.file.getName());
                    v.put(MediaStore.Downloads.MIME_TYPE, mf.mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Instagram Saver");
                    v.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) continue;

                    try (InputStream in = new FileInputStream(mf.file);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) continue;
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    }

                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                    saved++;
                } catch (Exception ignored) { }
            }

            int count = saved;
            runOnUiThread(() -> {
                setBusy(false);
                if (count > 0) {
                    status("Saved " + count + " file" + (count == 1 ? "" : "s") + " to Downloads/Instagram Saver.");
                    Toast.makeText(this, "Saved to Downloads/Instagram Saver", Toast.LENGTH_SHORT).show();
                } else {
                    status("Could not save the file.");
                }
            });
        });
    }

    private void openInstagramSignIn() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);

        Button done = primaryButton("Done — retry post");
        box.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        box.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(box);
        dialog.setOnDismissListener(d -> web.destroy());
        done.setOnClickListener(v -> {
            CookieManager.getInstance().flush();
            dialog.dismiss();
            if (!activePostUrl.isEmpty()) startResolve(activePostUrl);
        });
        dialog.show();
        web.loadUrl("https://www.instagram.com/accounts/login/");
    }

    private String extractUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Matcher m = IG_URL.matcher(s);
        String candidate = m.find() ? m.group() : s;
        candidate = candidate.replaceAll("[)>.,]+$", "");
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) candidate = "https://" + candidate;

        try {
            Uri u = Uri.parse(candidate);
            String h = u.getHost();
            if (h == null) return null;
            h = h.toLowerCase(Locale.US);
            if (!(h.equals("instagram.com") || h.endsWith(".instagram.com") || h.equals("instagr.am") || h.endsWith(".instagr.am"))) return null;
            return candidate;
        } catch (Exception e) {
            return null;
        }
    }

    private String shortcode(String url) {
        try {
            List<String> parts = Uri.parse(url).getPathSegments();
            for (int i = 0; i + 1 < parts.size(); i++) {
                String p = parts.get(i);
                if (p.equals("p") || p.equals("reel") || p.equals("reels") || p.equals("tv")) {
                    return parts.get(i + 1).replaceAll("[^A-Za-z0-9_-]", "");
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private String extensionFor(String mime, String path) {
        String m = mime.toLowerCase(Locale.US);
        if (m.contains("mp4")) return ".mp4";
        if (m.contains("webm")) return ".webm";
        if (m.contains("jpeg") || m.contains("jpg")) return ".jpg";
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";

        String p = path == null ? "" : path.toLowerCase(Locale.US);
        for (String e : new String[]{".mp4", ".webm", ".jpg", ".jpeg", ".png", ".webp"}) {
            if (p.endsWith(e)) return e;
        }
        return m.startsWith("video/") ? ".mp4" : ".jpg";
    }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            getButton.setEnabled(!busy);
            getButton.setAlpha(busy ? .65f : 1f);
        });
    }

    private void status(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundRect(Color.rgb(18,18,18), 18, Color.TRANSPARENT, 0));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(25,25,25));
        b.setBackground(roundRect(Color.WHITE, 16, Color.rgb(218,218,218), 1));
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(35,35,35));
        b.setBackground(roundRect(Color.rgb(244,244,244), 14, Color.TRANSPARENT, 0));
        return b;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class MediaFile {
        final File file;
        final String mime;
        MediaFile(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }
    }
}
