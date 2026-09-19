package com.rick.igsave;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
import java.text.DecimalFormat;
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

public class MainActivity extends AppCompatActivity {
    private static final String AUTHORITY = "com.rick.igsave.files";
    private static final Pattern IG_URL = Pattern.compile(
            "https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/[^\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    private TextInputEditText input;
    private TextView status;
    private LinearProgressIndicator progress;
    private MaterialButton getButton;
    private MaterialButton shareButton;
    private MaterialButton saveButton;

    private LinearLayout contentRoot;
    private MaterialCardView emptyCard;
    private MaterialCardView previewCard;
    private FrameLayout previewFrame;
    private ImageView previewImage;
    private VideoView previewVideo;
    private Chip previewChip;
    private TextView previewTitle;
    private TextView previewMeta;

    private WebView resolver;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> capturedVideos = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, Long> capturedImages = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<MediaFile> readyFiles = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean finishingResolve = new AtomicBoolean(false);

    private int inspectRound = 0;
    private String activePostUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        configureResolver();
        consumeIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        if (resolver != null) resolver.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        final int surface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE);
        final int onSurface = color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        final int onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY);
        final int surfaceContainer = color(com.google.android.material.R.attr.colorSurfaceContainer, 0xFFF2F2F2);
        final int surfaceContainerHigh = color(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFEAEAEA);
        final int outlineVariant = color(com.google.android.material.R.attr.colorOutlineVariant, 0xFFD0D0D0);
        final int primary = color(com.google.android.material.R.attr.colorPrimary, 0xFF6750A4);
        final int onPrimary = color(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(surface);
        setContentView(rootFrame);

        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        rootFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(contentRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(22), dp(24) + bars.top, dp(22), dp(28) + bars.bottom);
            return insets;
        });

        TextView eyebrow = new TextView(this);
        eyebrow.setText("INSTAGRAM MEDIA");
        eyebrow.setTextSize(12);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setTextColor(onSurfaceVariant);
        eyebrow.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        contentRoot.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText("Save what you want.");
        title.setTextSize(32);
        title.setTextColor(onSurface);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(8);
        contentRoot.addView(title, titleLp);

        TextView subtitle = new TextView(this);
        subtitle.setText("Paste or share a public Instagram post or reel. Preview the real file, then download or share it.");
        subtitle.setTextSize(16);
        subtitle.setLineSpacing(0f, 1.14f);
        subtitle.setTextColor(onSurfaceVariant);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.topMargin = dp(8);
        contentRoot.addView(subtitle, subLp);

        MaterialCardView inputCard = new MaterialCardView(this);
        inputCard.setRadius(dp(28));
        inputCard.setCardElevation(0);
        inputCard.setCardBackgroundColor(surfaceContainer);
        inputCard.setStrokeColor(outlineVariant);
        inputCard.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardLp = matchWrap();
        cardLp.topMargin = dp(26);
        contentRoot.addView(inputCard, cardLp);

        LinearLayout inputContent = new LinearLayout(this);
        inputContent.setOrientation(LinearLayout.VERTICAL);
        inputContent.setPadding(dp(16), dp(16), dp(16), dp(16));
        inputCard.addView(inputContent);

        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setHint("Instagram link");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setBoxCornerRadii(dp(20), dp(20), dp(20), dp(20));
        inputLayout.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        inputLayout.setBoxBackgroundColor(surface);
        inputContent.addView(inputLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        input = new TextInputEditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("instagram.com/reel/…");
        input.setMinHeight(dp(58));
        inputLayout.addView(input, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout inputActions = new LinearLayout(this);
        inputActions.setOrientation(LinearLayout.HORIZONTAL);
        inputActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputActionsLp = matchWrap();
        inputActionsLp.topMargin = dp(12);
        inputContent.addView(inputActions, inputActionsLp);

        MaterialButton pasteButton = new MaterialButton(this);
        pasteButton.setText("Paste link");
        pasteButton.setAllCaps(false);
        pasteButton.setCornerRadius(dp(18));
        pasteButton.setInsetTop(0);
        pasteButton.setInsetBottom(0);
        pasteButton.setMinHeight(dp(52));
        pasteButton.setBackgroundTintList(ColorStateList.valueOf(surfaceContainerHigh));
        pasteButton.setTextColor(onSurface);
        pasteButton.setOnClickListener(v -> pasteClipboard());
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(0, dp(52), 0.36f);
        pasteLp.rightMargin = dp(8);
        inputActions.addView(pasteButton, pasteLp);

        getButton = new MaterialButton(this);
        getButton.setText("Get media");
        getButton.setAllCaps(false);
        getButton.setCornerRadius(dp(18));
        getButton.setInsetTop(0);
        getButton.setInsetBottom(0);
        getButton.setMinHeight(dp(52));
        getButton.setBackgroundTintList(ColorStateList.valueOf(primary));
        getButton.setTextColor(onPrimary);
        getButton.setOnClickListener(v -> startResolve(String.valueOf(input.getText())));
        LinearLayout.LayoutParams getLp = new LinearLayout.LayoutParams(0, dp(52), 0.64f);
        getLp.leftMargin = dp(8);
        inputActions.addView(getButton, getLp);

        progress = new LinearProgressIndicator(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        progress.setTrackCornerRadius(dp(3));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
        );
        progressLp.topMargin = dp(18);
        contentRoot.addView(progress, progressLp);

        status = new TextView(this);
        status.setText("Ready.");
        status.setTextSize(14);
        status.setTextColor(onSurfaceVariant);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.topMargin = dp(12);
        contentRoot.addView(status, statusLp);

        emptyCard = new MaterialCardView(this);
        emptyCard.setRadius(dp(28));
        emptyCard.setCardElevation(0);
        emptyCard.setCardBackgroundColor(surfaceContainer);
        emptyCard.setStrokeColor(outlineVariant);
        emptyCard.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams emptyLp = matchWrap();
        emptyLp.topMargin = dp(22);
        contentRoot.addView(emptyCard, emptyLp);

        LinearLayout emptyContent = new LinearLayout(this);
        emptyContent.setOrientation(LinearLayout.VERTICAL);
        emptyContent.setGravity(Gravity.CENTER);
        emptyContent.setPadding(dp(24), dp(34), dp(24), dp(34));
        emptyCard.addView(emptyContent);

        TextView emptyIcon = new TextView(this);
        emptyIcon.setText("↓");
        emptyIcon.setTextSize(34);
        emptyIcon.setGravity(Gravity.CENTER);
        emptyIcon.setTextColor(primary);
        emptyIcon.setBackground(circleDrawable(surfaceContainerHigh));
        emptyContent.addView(emptyIcon, new LinearLayout.LayoutParams(dp(68), dp(68)));

        TextView emptyTitle = new TextView(this);
        emptyTitle.setText("Your preview will appear here");
        emptyTitle.setTextSize(19);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setTextColor(onSurface);
        emptyTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams emptyTitleLp = matchWrap();
        emptyTitleLp.topMargin = dp(18);
        emptyContent.addView(emptyTitle, emptyTitleLp);

        TextView emptyBody = new TextView(this);
        emptyBody.setText("Nothing is saved automatically. Choose Download or Share after the media is ready.");
        emptyBody.setTextSize(14);
        emptyBody.setGravity(Gravity.CENTER);
        emptyBody.setTextColor(onSurfaceVariant);
        emptyBody.setLineSpacing(0f, 1.12f);
        LinearLayout.LayoutParams emptyBodyLp = matchWrap();
        emptyBodyLp.topMargin = dp(7);
        emptyContent.addView(emptyBody, emptyBodyLp);

        previewCard = new MaterialCardView(this);
        previewCard.setRadius(dp(30));
        previewCard.setCardElevation(dp(1));
        previewCard.setCardBackgroundColor(surfaceContainer);
        previewCard.setStrokeColor(outlineVariant);
        previewCard.setStrokeWidth(dp(1));
        previewCard.setVisibility(View.GONE);
        previewCard.setClipToOutline(true);
        LinearLayout.LayoutParams previewLp = matchWrap();
        previewLp.topMargin = dp(22);
        contentRoot.addView(previewCard, previewLp);

        LinearLayout previewContent = new LinearLayout(this);
        previewContent.setOrientation(LinearLayout.VERTICAL);
        previewCard.addView(previewContent);

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        previewContent.addView(previewFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(390)
        ));

        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImage.setVisibility(View.GONE);
        previewFrame.addView(previewImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        previewVideo = new VideoView(this);
        previewVideo.setVisibility(View.GONE);
        previewVideo.setOnClickListener(v -> {
            if (previewVideo.isPlaying()) previewVideo.pause();
            else previewVideo.start();
        });
        previewFrame.addView(previewVideo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        previewChip = new Chip(this);
        previewChip.setText("READY");
        previewChip.setCheckable(false);
        previewChip.setClickable(false);
        previewChip.setTextSize(12);
        previewChip.setChipBackgroundColor(ColorStateList.valueOf(0xE61A1A1A));
        previewChip.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
        );
        chipLp.gravity = Gravity.TOP | Gravity.START;
        chipLp.leftMargin = dp(14);
        chipLp.topMargin = dp(14);
        previewFrame.addView(previewChip, chipLp);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(18), dp(18), dp(18), dp(18));
        previewContent.addView(details);

        previewTitle = new TextView(this);
        previewTitle.setText("Ready to share");
        previewTitle.setTextSize(21);
        previewTitle.setTextColor(onSurface);
        previewTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        details.addView(previewTitle);

        previewMeta = new TextView(this);
        previewMeta.setTextSize(14);
        previewMeta.setTextColor(onSurfaceVariant);
        LinearLayout.LayoutParams metaLp = matchWrap();
        metaLp.topMargin = dp(5);
        details.addView(previewMeta, metaLp);

        LinearLayout previewActions = new LinearLayout(this);
        previewActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowLp = matchWrap();
        actionRowLp.topMargin = dp(16);
        details.addView(previewActions, actionRowLp);

        shareButton = new MaterialButton(this);
        shareButton.setText("Share file");
        shareButton.setAllCaps(false);
        shareButton.setCornerRadius(dp(18));
        shareButton.setInsetTop(0);
        shareButton.setInsetBottom(0);
        shareButton.setBackgroundTintList(ColorStateList.valueOf(surfaceContainerHigh));
        shareButton.setTextColor(onSurface);
        shareButton.setOnClickListener(v -> shareReadyFiles());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(54), 1f);
        half.rightMargin = dp(7);
        previewActions.addView(shareButton, half);

        saveButton = new MaterialButton(this);
        saveButton.setText("Download");
        saveButton.setAllCaps(false);
        saveButton.setCornerRadius(dp(18));
        saveButton.setInsetTop(0);
        saveButton.setInsetBottom(0);
        saveButton.setBackgroundTintList(ColorStateList.valueOf(primary));
        saveButton.setTextColor(onPrimary);
        saveButton.setOnClickListener(v -> saveReadyFiles());
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(54), 1f);
        half2.leftMargin = dp(7);
        previewActions.addView(saveButton, half2);

        TextView privacy = new TextView(this);
        privacy.setText("Public posts only • Files stay on your device unless you share them");
        privacy.setTextSize(12);
        privacy.setTextColor(onSurfaceVariant);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyLp = matchWrap();
        privacyLp.topMargin = dp(20);
        contentRoot.addView(privacy, privacyLp);

        resolver = new WebView(this);
        resolver.setAlpha(0.01f);
        FrameLayout.LayoutParams hidden = new FrameLayout.LayoutParams(2, 2);
        hidden.gravity = Gravity.BOTTOM | Gravity.END;
        rootFrame.addView(resolver, hidden);
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
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!finishingResolve.get()) {
                    status("Reading the post…");
                    scheduleInspections();
                }
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request
            ) {
                String u = request.getUrl().toString();
                String lower = u.toLowerCase(Locale.US);
                if ((lower.contains("cdninstagram.com") || lower.contains("fbcdn.net"))
                        && (lower.contains(".mp4")
                        || lower.contains("/video/")
                        || lower.contains("/v/t2/"))) {
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
        if (t == null) return;

        String url = extractUrl(t.toString());
        input.setText(url == null ? t : url);
    }

    private void startResolve(String raw) {
        String url = extractUrl(raw);
        if (url == null) {
            status("Paste a valid Instagram post or reel link.");
            return;
        }

        activePostUrl = url;
        input.setText(url);

        stopPreview();
        TransitionManager.beginDelayedTransition(contentRoot, new AutoTransition().setDuration(220));
        previewCard.setVisibility(View.GONE);
        emptyCard.setVisibility(View.VISIBLE);

        capturedVideos.clear();
        capturedImages.clear();
        readyFiles.clear();
        finishingResolve.set(false);
        inspectRound = 0;

        setBusy(true);
        status("Resolving public post…");

        executor.submit(() -> {
            List<String> urls = InstagramResolver.resolve(url);
            if (!activePostUrl.equals(url)) return;

            if (!urls.isEmpty()) {
                finishingResolve.set(true);
                runOnUiThread(() -> status(
                        "Found " + urls.size() + " media item" + (urls.size() == 1 ? "" : "s") + ". Fetching file…"
                ));
                downloadAll(urls);
            } else {
                runOnUiThread(() -> {
                    if (!activePostUrl.equals(url)) return;
                    status("Direct lookup failed. Trying browser fallback…");
                    resolver.stopLoading();
                    resolver.loadUrl(url);
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
                        for (int i = 0; i < videos.length(); i++) {
                            addVideoCandidate(videos.optString(i));
                        }
                    }

                    JSONArray images = obj.optJSONArray("images");
                    if (images != null) {
                        for (int i = 0; i < images.length(); i++) {
                            JSONObject im = images.optJSONObject(i);
                            if (im != null) {
                                addImageCandidate(im.optString("u"), im.optLong("a", 0));
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }

            if (!capturedVideos.isEmpty() && inspectRound >= 2) {
                finishResolve();
            } else if (inspectRound >= 4) {
                finishResolve();
            }
        });
    }

    private void addVideoCandidate(String u) {
        if (isHttpMedia(u)) capturedVideos.add(u);
    }

    private void addImageCandidate(String u, long area) {
        if (!isHttpMedia(u)) return;

        String lower = u.toLowerCase(Locale.US);
        if (!(lower.contains("cdninstagram.com")
                || lower.contains("fbcdn.net")
                || lower.contains("instagram.com"))) {
            return;
        }

        Long old = capturedImages.get(u);
        if (old == null || area > old) capturedImages.put(u, area);
    }

    private boolean isHttpMedia(String u) {
        return u != null
                && (u.startsWith("https://") || u.startsWith("http://"))
                && !u.startsWith("blob:");
    }

    private void finishResolve() {
        if (!finishingResolve.compareAndSet(false, true)) return;

        List<String> urls = new ArrayList<>();
        synchronized (capturedVideos) {
            urls.addAll(capturedVideos);
        }

        if (urls.isEmpty()) {
            List<Map.Entry<String, Long>> imgs;
            synchronized (capturedImages) {
                imgs = new ArrayList<>(capturedImages.entrySet());
            }

            imgs.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            long max = imgs.isEmpty() ? 0 : imgs.get(0).getValue();

            for (Map.Entry<String, Long> e : imgs) {
                if (urls.size() >= 10) break;
                if (max == 0 || e.getValue() >= Math.max(160000, (long) (max * .60))) {
                    urls.add(e.getKey());
                }
            }
        }

        if (urls.isEmpty()) {
            setBusy(false);
            status("Couldn’t resolve media from this post. It may be private, restricted, expired, or temporarily blocked by Instagram.");
            return;
        }

        status("Found " + urls.size() + " media item" + (urls.size() == 1 ? "" : "s") + ". Fetching file…");
        executor.submit(() -> downloadAll(urls));
    }

    private void downloadAll(List<String> urls) {
        File dir = new File(getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();

        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) f.delete();
        }

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

        runOnUiThread(() -> {
            readyFiles.clear();
            readyFiles.addAll(downloaded);
            setBusy(false);

            if (readyFiles.isEmpty()) {
                status("The media link was found, but the file download failed. Retry the post.");
                return;
            }

            status("Ready.");
            showPreview();
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

        String cookie = CookieManager.getInstance().getCookie(mediaUrl);
        if (cookie != null && !cookie.isEmpty()) {
            c.setRequestProperty("Cookie", cookie);
        }

        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }

        String mime = c.getContentType();
        if (mime != null) {
            int semi = mime.indexOf(';');
            if (semi > -1) mime = mime.substring(0, semi).trim();
        }

        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
            mime = URLConnection.guessContentTypeFromName(url.getPath());
        }

        if (mime == null) {
            mime = mediaUrl.toLowerCase(Locale.US).contains(".mp4")
                    ? "video/mp4"
                    : "image/jpeg";
        }

        String ext = extensionFor(mime, url.getPath());
        String codePart = shortcode(activePostUrl);
        String name = "Instagram_"
                + (codePart.isEmpty() ? System.currentTimeMillis() : codePart)
                + (index > 1 ? "_" + index : "")
                + ext;

        File out = new File(dir, name);

        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                os.write(buf, 0, r);
            }
        } finally {
            c.disconnect();
        }

        return new MediaFile(out, mime);
    }

    private void showPreview() {
        if (readyFiles.isEmpty()) return;

        stopPreview();
        MediaFile first = readyFiles.get(0);

        boolean video = first.mime != null && first.mime.toLowerCase(Locale.US).startsWith("video/");
        previewChip.setText(video ? "VIDEO" : "IMAGE");

        if (video) {
            previewImage.setVisibility(View.GONE);
            previewVideo.setVisibility(View.VISIBLE);
            previewVideo.setVideoURI(Uri.fromFile(first.file));
            previewVideo.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                previewVideo.start();
            });
        } else {
            previewVideo.setVisibility(View.GONE);
            Bitmap bitmap = BitmapFactory.decodeFile(first.file.getAbsolutePath());
            previewImage.setImageBitmap(bitmap);
            previewImage.setVisibility(View.VISIBLE);
        }

        long total = 0;
        for (MediaFile mf : readyFiles) total += mf.file.length();

        if (readyFiles.size() == 1) {
            previewTitle.setText("Ready to share");
            previewMeta.setText(
                    (video ? "Video" : "Image")
                            + " • " + formatBytes(total)
                            + " • " + first.file.getName()
            );
        } else {
            previewTitle.setText(readyFiles.size() + " files ready");
            previewMeta.setText(
                    "Previewing 1 of " + readyFiles.size()
                            + " • " + formatBytes(total) + " total"
            );
        }

        TransitionManager.beginDelayedTransition(contentRoot, new AutoTransition().setDuration(260));
        emptyCard.setVisibility(View.GONE);
        previewCard.setVisibility(View.VISIBLE);

        previewCard.setAlpha(0f);
        previewCard.setTranslationY(dp(14));
        previewCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .start();
    }

    private void stopPreview() {
        if (previewVideo != null) {
            try {
                previewVideo.stopPlayback();
            } catch (Exception ignored) { }
        }
        if (previewImage != null) {
            previewImage.setImageDrawable(null);
        }
    }

    private void shareReadyFiles() {
        if (readyFiles.isEmpty()) return;

        ArrayList<Uri> uris = new ArrayList<>();
        String commonType = readyFiles.get(0).mime;
        boolean mixed = false;

        for (MediaFile mf : readyFiles) {
            if (!commonType.equals(mf.mime)) mixed = true;
            uris.add(Uri.parse(
                    "content://" + AUTHORITY + "/share/" + Uri.encode(mf.file.getName())
            ));
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
        status("Saving to Downloads…");

        executor.submit(() -> {
            int saved = 0;

            for (MediaFile mf : new ArrayList<>(readyFiles)) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, mf.file.getName());
                    v.put(MediaStore.Downloads.MIME_TYPE, mf.mime);
                    v.put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Instagram Saver"
                    );
                    v.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri uri = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            v
                    );
                    if (uri == null) continue;

                    try (InputStream in = new FileInputStream(mf.file);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) continue;

                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            out.write(buf, 0, r);
                        }
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
                } else {
                    status("Could not save the file.");
                }
            });
        });
    }

    private String extractUrl(String raw) {
        if (raw == null) return null;

        String s = raw.trim();
        Matcher m = IG_URL.matcher(s);
        String candidate = m.find() ? m.group() : s;

        candidate = candidate.replaceAll("[)>.,]+$", "");
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "https://" + candidate;
        }

        try {
            Uri u = Uri.parse(candidate);
            String h = u.getHost();
            if (h == null) return null;

            h = h.toLowerCase(Locale.US);
            if (!(h.equals("instagram.com")
                    || h.endsWith(".instagram.com")
                    || h.equals("instagr.am")
                    || h.endsWith(".instagr.am"))) {
                return null;
            }

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
                if (p.equals("p")
                        || p.equals("reel")
                        || p.equals("reels")
                        || p.equals("tv")) {
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
            getButton.setAlpha(busy ? .68f : 1f);
        });
    }

    private void status(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private int color(int attr, int fallback) {
        return MaterialColors.getColor(this, attr, fallback);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private android.graphics.drawable.GradientDrawable circleDrawable(int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";

        double value = bytes;
        String[] units = new String[]{"KB", "MB", "GB"};
        int unit = -1;

        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < units.length - 1);

        DecimalFormat format = value >= 100 ? new DecimalFormat("0") : new DecimalFormat("0.0");
        return format.format(value) + " " + units[unit];
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
