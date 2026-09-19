package com.rick.igsave;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Polished native Material 3 production UI
public class MainActivity extends Activity {
    private static final String AUTHORITY = "com.rick.igsave.files";
    private static final Pattern IG_URL = Pattern.compile(
            "https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/[^\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<MediaFile> readyFiles = new ArrayList<>();

    private Palette palette;
    private LinearLayout contentRoot;
    private EditText input;
    private LinearLayout statusRow;
    private TextView statusText;
    private View statusDot;
    private ProgressBar progress;
    private ImageButton getButton;

    private FrameLayout emptyCard;
    private LinearLayout previewCard;
    private FrameLayout mediaFrame;
    private ImageView previewImage;
    private VideoView previewVideo;
    private ImageView playOverlay;
    private TextView previewChip;
    private TextView previewMeta;
    private ImageButton shareButton;
    private ImageButton downloadButton;

    private String activePostUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = Palette.create(this);
        configureSystemBars();
        buildUi();
        consumeIntent(getIntent());

        contentRoot.setAlpha(0f);
        contentRoot.setTranslationY(dp(8));
        contentRoot.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start();
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
        executor.shutdownNow();
        super.onDestroy();
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().setNavigationBarDividerColor(palette.background);
        }

        int flags = 0;
        if (!palette.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(palette.background);
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.setPadding(dp(20), dp(22), dp(20), dp(30));
        scroll.addView(contentRoot, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final int baseTop = dp(22);
        final int baseBottom = dp(30);
        contentRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(20), baseTop + top, dp(20), baseBottom + bottom);
            return insets;
        });

        buildHeader();
        buildLinkCard();
        buildStatus();
        buildEmptyState();
        buildPreview();
        buildFooter();
    }

    private void buildHeader() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        contentRoot.addView(top, matchWrap());

        FrameLayout mark = new FrameLayout(this);
        mark.setBackground(round(palette.primaryContainer, 16, 0, Color.TRANSPARENT));
        top.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ImageView markIcon = new ImageView(this);
        markIcon.setImageResource(R.drawable.ic_download);
        markIcon.setImageTintList(ColorStateList.valueOf(palette.onPrimaryContainer));
        markIcon.setPadding(dp(11), dp(11), dp(11), dp(11));
        mark.addView(markIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView appName = label("IG Save", 22, palette.onSurface, true);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        nameLp.leftMargin = dp(13);
        top.addView(appName, nameLp);
    }

    private void buildLinkCard() {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(6), 0, dp(6), 0);
        field.setBackground(round(palette.surface, 24, 1, palette.outline));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
        );
        fieldLp.topMargin = dp(26);
        contentRoot.addView(field, fieldLp);

        ImageView linkIcon = new ImageView(this);
        linkIcon.setImageResource(R.drawable.ic_link);
        linkIcon.setImageTintList(ColorStateList.valueOf(palette.onSurfaceVariant));
        linkIcon.setPadding(dp(12), dp(20), dp(7), dp(20));
        field.addView(linkIcon, new LinearLayout.LayoutParams(dp(44), dp(64)));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(palette.onSurface);
        input.setHintTextColor(palette.onSurfaceVariant);
        input.setHint("Paste Instagram link");
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(0, 0, dp(5), 0);
        input.setSelectAllOnFocus(false);
        field.addView(input, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        ));

        ImageButton paste = iconButton(R.drawable.ic_paste, false);
        paste.setContentDescription("Paste");
        paste.setOnClickListener(v -> pasteClipboard());
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        pasteLp.rightMargin = dp(6);
        field.addView(paste, pasteLp);

        getButton = iconButton(R.drawable.ic_download, true);
        getButton.setContentDescription("Get media");
        getButton.setOnClickListener(v -> startResolve(input.getText().toString()));
        field.addView(getButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void buildStatus() {
        statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams rowLp = matchWrap();
        rowLp.topMargin = dp(14);
        contentRoot.addView(statusRow, rowLp);

        statusDot = new View(this);
        statusDot.setBackground(circle(palette.primary));
        statusRow.addView(statusDot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        statusText = label("", 12, palette.onSurfaceVariant, false);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        statusLp.leftMargin = dp(8);
        statusRow.addView(statusText, statusLp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
    }

    private void buildEmptyState() {
        emptyCard = new FrameLayout(this);
        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(170)
        );
        emptyLp.topMargin = dp(26);
        contentRoot.addView(emptyCard, emptyLp);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        emptyCard.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ImageView media = new ImageView(this);
        media.setImageResource(R.drawable.ic_media);
        media.setImageTintList(ColorStateList.valueOf(palette.onSurfaceVariant));
        media.setPadding(dp(19), dp(19), dp(19), dp(19));
        media.setBackground(round(palette.surfaceContainer, 22, 0, Color.TRANSPARENT));
        inner.addView(media, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView hint = label("Paste a link", 14, palette.onSurfaceVariant, false);
        LinearLayout.LayoutParams hintLp = matchWrap();
        hintLp.topMargin = dp(13);
        inner.addView(hint, hintLp);
    }

    private void buildPreview() {
        previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams cardLp = matchWrap();
        cardLp.topMargin = dp(22);
        contentRoot.addView(previewCard, cardLp);

        mediaFrame = new FrameLayout(this);
        mediaFrame.setBackground(round(Color.BLACK, 24, 0, Color.TRANSPARENT));
        mediaFrame.setClipToOutline(true);
        previewCard.addView(mediaFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)
        ));

        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImage.setVisibility(View.GONE);
        mediaFrame.addView(previewImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        previewVideo = new VideoView(this);
        previewVideo.setVisibility(View.GONE);
        mediaFrame.addView(previewVideo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        playOverlay = new ImageView(this);
        playOverlay.setImageResource(R.drawable.ic_play);
        playOverlay.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        playOverlay.setPadding(dp(16), dp(16), dp(16), dp(16));
        playOverlay.setBackground(circle(0xB31A1A1A));
        playOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(58), dp(58));
        playLp.gravity = Gravity.CENTER;
        mediaFrame.addView(playOverlay, playLp);

        previewChip = label("", 11, Color.WHITE, true);
        previewChip.setGravity(Gravity.CENTER);
        previewChip.setPadding(dp(11), 0, dp(11), 0);
        previewChip.setBackground(round(0xB31A1A1A, 14, 0, Color.TRANSPARENT));
        previewChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(30)
        );
        chipLp.gravity = Gravity.TOP | Gravity.END;
        chipLp.rightMargin = dp(12);
        chipLp.topMargin = dp(12);
        mediaFrame.addView(previewChip, chipLp);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarLp = matchWrap();
        toolbarLp.topMargin = dp(12);
        previewCard.addView(toolbar, toolbarLp);

        previewMeta = label("", 12, palette.onSurfaceVariant, false);
        previewMeta.setSingleLine(true);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        toolbar.addView(previewMeta, metaLp);

        shareButton = iconButton(R.drawable.ic_share, false);
        shareButton.setContentDescription("Share");
        shareButton.setOnClickListener(v -> shareReadyFiles());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        shareLp.leftMargin = dp(8);
        toolbar.addView(shareButton, shareLp);

        downloadButton = iconButton(R.drawable.ic_download, true);
        downloadButton.setContentDescription("Save");
        downloadButton.setOnClickListener(v -> saveReadyFiles());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        saveLp.leftMargin = dp(8);
        toolbar.addView(downloadButton, saveLp);

        mediaFrame.setOnClickListener(v -> startVideoPlayback());
        playOverlay.setOnClickListener(v -> startVideoPlayback());
    }

    private void buildFooter() {
        // Intentionally empty: content is the interface.
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

        CharSequence value = clip.getItemAt(0).coerceToText(this);
        if (value == null) return;

        String url = extractUrl(value.toString());
        input.setText(url == null ? value : url);
        input.setSelection(input.length());
    }

    private void startResolve(String raw) {
        String url = extractUrl(raw);
        if (url == null) {
            setStatus("Paste a valid Instagram post or reel link.", palette.error);
            return;
        }

        activePostUrl = url;
        input.setText(url);
        input.setSelection(input.length());
        readyFiles.clear();
        resetPreview();

        setBusy(true);
        setStatus("Resolving public post…", palette.primary);

        executor.submit(() -> {
            List<String> urls = InstagramResolver.resolve(url);
            if (!url.equals(activePostUrl)) return;

            if (urls.isEmpty()) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus(
                            "Couldn’t resolve this public post right now. Retry in a moment.",
                            palette.error
                    );
                });
                return;
            }

            runOnUiThread(() -> setStatus(
                    "Found " + urls.size() + " media item" + (urls.size() == 1 ? "" : "s") + ". Fetching…",
                    palette.primary
            ));
            downloadAll(urls);
        });
    }

    private void downloadAll(List<String> urls) {
        File dir = new File(getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();

        File[] old = dir.listFiles();
        if (old != null) {
            for (File file : old) file.delete();
        }

        List<MediaFile> downloaded = new ArrayList<>();
        int index = 0;

        for (String mediaUrl : urls) {
            index++;
            final int current = index;
            runOnUiThread(() -> setStatus(
                    "Fetching " + current + " of " + urls.size() + "…",
                    palette.primary
            ));

            try {
                MediaFile file = downloadOne(mediaUrl, dir, current);
                if (file != null && file.file.length() > 0) downloaded.add(file);
            } catch (Exception ignored) { }
        }

        if (downloaded.isEmpty()) {
            runOnUiThread(() -> setStatus("Refreshing media link…", palette.primary));
            List<String> refreshed = InstagramResolver.resolve(activePostUrl);

            if (!refreshed.isEmpty() && !refreshed.equals(urls)) {
                int retry = 0;
                for (String mediaUrl : refreshed) {
                    retry++;
                    try {
                        MediaFile file = downloadOne(mediaUrl, dir, retry);
                        if (file != null && file.file.length() > 0) downloaded.add(file);
                    } catch (Exception ignored) { }
                }
            }
        }

        runOnUiThread(() -> {
            readyFiles.clear();
            readyFiles.addAll(downloaded);
            setBusy(false);

            if (readyFiles.isEmpty()) {
                setStatus("Couldn’t fetch the media file. Try the post again.", palette.error);
                return;
            }

            setStatus("Media ready", palette.primary);
            showPreview();
        });
    }

    private MediaFile downloadOne(String mediaUrl, File dir, int index) throws Exception {
        URL url = new URL(mediaUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", InstagramResolver.USER_AGENT);
        connection.setRequestProperty("Referer", "https://www.instagram.com/");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("Connection", "close");

        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }

        String mime = connection.getContentType();
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

        String extension = extensionFor(mime, url.getPath());
        String codePart = shortcode(activePostUrl);
        String name = "Instagram_"
                + (codePart.isEmpty() ? System.currentTimeMillis() : codePart)
                + (index > 1 ? "_" + index : "")
                + extension;

        File out = new File(dir, name);

        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }

        return new MediaFile(out, mime);
    }

    private void showPreview() {
        if (readyFiles.isEmpty()) return;

        stopPreview();
        MediaFile first = readyFiles.get(0);
        boolean video = first.mime != null
                && first.mime.toLowerCase(Locale.US).startsWith("video/");

        Bitmap poster = video ? videoPoster(first.file) : decodePreview(first.file);
        previewImage.setImageBitmap(poster);
        previewImage.setVisibility(View.VISIBLE);
        previewVideo.setVisibility(View.GONE);
        playOverlay.setVisibility(video ? View.VISIBLE : View.GONE);

        if (readyFiles.size() > 1) {
            previewChip.setText("1 / " + readyFiles.size());
            previewChip.setVisibility(View.VISIBLE);
        } else {
            previewChip.setVisibility(View.GONE);
        }

        long total = 0;
        for (MediaFile file : readyFiles) total += file.file.length();

        String summary = compactMediaSummary(first);
        if (readyFiles.size() > 1) {
            summary = readyFiles.size() + " items  •  " + formatBytes(total);
        } else {
            summary = summary + "  •  " + formatBytes(total);
        }
        previewMeta.setText(summary);

        TransitionManager.beginDelayedTransition(
                contentRoot,
                new AutoTransition().setDuration(220)
        );
        emptyCard.setVisibility(View.GONE);
        previewCard.setVisibility(View.VISIBLE);
        hideStatus();

        previewCard.setAlpha(0f);
        previewCard.setTranslationY(dp(10));
        previewCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .start();

        int[] size = mediaSize(first);
        mediaFrame.post(() -> {
            int width = mediaFrame.getWidth();
            if (width <= 0) return;

            float ratio = 0.82f;
            if (size[0] > 0 && size[1] > 0) {
                ratio = (float) size[1] / (float) size[0];
            }
            ratio = Math.max(0.62f, Math.min(1.25f, ratio));

            ViewGroup.LayoutParams lp = mediaFrame.getLayoutParams();
            lp.height = Math.round(width * ratio);
            mediaFrame.setLayoutParams(lp);
        });
    }

    private void startVideoPlayback() {
        if (readyFiles.isEmpty()) return;
        MediaFile first = readyFiles.get(0);
        if (first.mime == null || !first.mime.toLowerCase(Locale.US).startsWith("video/")) {
            return;
        }

        if (previewVideo.getVisibility() == View.VISIBLE) {
            if (previewVideo.isPlaying()) {
                previewVideo.pause();
                playOverlay.setVisibility(View.VISIBLE);
            } else {
                previewVideo.start();
                playOverlay.setVisibility(View.GONE);
            }
            return;
        }

        previewImage.setVisibility(View.GONE);
        previewVideo.setVisibility(View.VISIBLE);
        playOverlay.setVisibility(View.GONE);
        previewVideo.setVideoURI(Uri.fromFile(first.file));
        previewVideo.setOnPreparedListener(player -> {
            player.setLooping(false);
            player.setVolume(1f, 1f);
            previewVideo.start();
        });
        previewVideo.setOnCompletionListener(player -> {
            previewVideo.setVisibility(View.GONE);
            previewImage.setVisibility(View.VISIBLE);
            playOverlay.setVisibility(View.VISIBLE);
        });
    }

    private Bitmap videoPoster(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) return frame;
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) { }
        }
        return null;
    }

    private int[] mediaSize(MediaFile media) {
        int width = 0;
        int height = 0;

        try {
            if (media.mime != null && media.mime.startsWith("video/")) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(media.file.getAbsolutePath());

                String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);

                if (w != null) width = Integer.parseInt(w);
                if (h != null) height = Integer.parseInt(h);
                if ("90".equals(rotation) || "270".equals(rotation)) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
                retriever.release();
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(media.file.getAbsolutePath(), options);
                width = options.outWidth;
                height = options.outHeight;
            }
        } catch (Exception ignored) { }

        return new int[]{width, height};
    }

    private String compactMediaSummary(MediaFile media) {
        try {
            if (media.mime != null && media.mime.startsWith("video/")) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(media.file.getAbsolutePath());
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                retriever.release();
                return duration == null ? "Video" : formatDuration(Long.parseLong(duration));
            }
        } catch (Exception ignored) { }

        return media.mime != null && media.mime.startsWith("video/") ? "Video" : "Photo";
    }

    private String mediaDetails(MediaFile media) {
        try {
            if (media.mime != null && media.mime.startsWith("video/")) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(media.file.getAbsolutePath());

                String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                retriever.release();

                StringBuilder b = new StringBuilder("Video");
                if (width != null && height != null) b.append("  •  ").append(width).append("×").append(height);
                if (duration != null) b.append("  •  ").append(formatDuration(Long.parseLong(duration)));
                return b.toString();
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(media.file.getAbsolutePath(), options);

            if (options.outWidth > 0 && options.outHeight > 0) {
                return "Photo  •  " + options.outWidth + "×" + options.outHeight;
            }
        } catch (Exception ignored) { }

        return media.mime != null && media.mime.startsWith("video/") ? "Video" : "Photo";
    }

    private Bitmap decodePreview(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > 1600) sample *= 2;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void resetPreview() {
        stopPreview();

        if (previewCard.getVisibility() == View.VISIBLE) {
            TransitionManager.beginDelayedTransition(
                    contentRoot,
                    new AutoTransition().setDuration(180)
            );
        }
        previewCard.setVisibility(View.GONE);
        emptyCard.setVisibility(View.VISIBLE);
    }

    private void stopPreview() {
        if (previewVideo != null) {
            try {
                previewVideo.stopPlayback();
            } catch (Exception ignored) { }
            previewVideo.setVisibility(View.GONE);
        }
        if (previewImage != null) {
            previewImage.setImageDrawable(null);
        }
        if (playOverlay != null) {
            playOverlay.setVisibility(View.GONE);
        }
    }

    private void shareReadyFiles() {
        if (readyFiles.isEmpty()) return;

        ArrayList<Uri> uris = new ArrayList<>();
        String commonType = readyFiles.get(0).mime;
        boolean mixed = false;

        for (MediaFile file : readyFiles) {
            if (!commonType.equals(file.mime)) mixed = true;
            uris.add(Uri.parse(
                    "content://" + AUTHORITY + "/share/" + Uri.encode(file.file.getName())
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
        setStatus("Saving to Downloads…", palette.primary);

        executor.submit(() -> {
            int saved = 0;

            for (MediaFile media : new ArrayList<>(readyFiles)) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, media.file.getName());
                    values.put(MediaStore.Downloads.MIME_TYPE, media.mime);
                    values.put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Instagram Saver"
                    );
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri uri = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values
                    );
                    if (uri == null) continue;

                    try (InputStream in = new FileInputStream(media.file);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) continue;

                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }

                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                    saved++;
                } catch (Exception ignored) { }
            }

            final int count = saved;
            runOnUiThread(() -> {
                setBusy(false);
                if (count > 0) {
                    setStatus(
                            "Saved " + count + " file" + (count == 1 ? "" : "s")
                                    + " to Downloads / Instagram Saver",
                            palette.primary
                    );
                } else {
                    setStatus("Couldn’t save the file.", palette.error);
                }
            });
        });
    }

    private String extractUrl(String raw) {
        if (raw == null) return null;

        String value = raw.trim();
        Matcher matcher = IG_URL.matcher(value);
        String candidate = matcher.find() ? matcher.group() : value;

        candidate = candidate.replaceAll("[)>.,]+$", "");
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "https://" + candidate;
        }

        try {
            Uri uri = Uri.parse(candidate);
            String host = uri.getHost();
            if (host == null) return null;

            host = host.toLowerCase(Locale.US);
            if (!(host.equals("instagram.com")
                    || host.endsWith(".instagram.com")
                    || host.equals("instagr.am")
                    || host.endsWith(".instagr.am"))) {
                return null;
            }
            return candidate;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String shortcode(String url) {
        try {
            List<String> parts = Uri.parse(url).getPathSegments();
            for (int i = 0; i + 1 < parts.size(); i++) {
                String part = parts.get(i);
                if (part.equals("p")
                        || part.equals("reel")
                        || part.equals("reels")
                        || part.equals("tv")) {
                    return parts.get(i + 1).replaceAll("[^A-Za-z0-9_-]", "");
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private String extensionFor(String mime, String path) {
        String value = mime.toLowerCase(Locale.US);

        if (value.contains("mp4")) return ".mp4";
        if (value.contains("webm")) return ".webm";
        if (value.contains("jpeg") || value.contains("jpg")) return ".jpg";
        if (value.contains("png")) return ".png";
        if (value.contains("webp")) return ".webp";

        String candidate = path == null ? "" : path.toLowerCase(Locale.US);
        for (String extension : new String[]{".mp4", ".webm", ".jpg", ".jpeg", ".png", ".webp"}) {
            if (candidate.endsWith(extension)) return extension;
        }

        return value.startsWith("video/") ? ".mp4" : ".jpg";
    }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            getButton.setEnabled(!busy);
            getButton.setAlpha(busy ? .45f : 1f);
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) statusRow.setVisibility(View.VISIBLE);
        });
    }

    private void setStatus(String text, int color) {
        runOnUiThread(() -> {
            statusRow.setVisibility(View.VISIBLE);
            statusText.setText(text);
            statusText.setTextColor(
                    color == palette.primary ? palette.onSurfaceVariant : color
            );
            statusDot.setBackground(circle(color));
        });
    }

    private void hideStatus() {
        statusRow.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private ImageButton iconButton(int iconRes, boolean primary) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setImageTintList(ColorStateList.valueOf(
                primary ? palette.onPrimary : palette.onSurface
        ));
        button.setBackground(ripple(
                primary ? palette.primary : palette.surfaceContainer,
                primary ? palette.rippleOnPrimary : palette.ripple,
                18,
                primary ? 0 : 1,
                primary ? Color.TRANSPARENT : palette.outline
        ));
        return button;
    }

    private Button filledButton(String text, int iconRes) {
        Button button = baseButton(text, iconRes);
        button.setTextColor(palette.onPrimary);
        button.setBackground(ripple(
                palette.primary,
                palette.rippleOnPrimary,
                20,
                0,
                Color.TRANSPARENT
        ));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(palette.onPrimary));
        return button;
    }

    private Button outlinedButton(String text, int iconRes) {
        Button button = baseButton(text, iconRes);
        button.setTextColor(palette.onSurface);
        button.setBackground(ripple(
                palette.surfaceContainer,
                palette.ripple,
                20,
                1,
                palette.outline
        ));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(palette.onSurface));
        return button;
    }

    private Button compactButton(String text, int iconRes) {
        Button button = baseButton(text, iconRes);
        button.setTextSize(13);
        button.setTextColor(palette.onSurface);
        button.setPadding(dp(8), 0, dp(10), 0);
        button.setCompoundDrawablePadding(dp(4));
        button.setBackground(ripple(
                palette.surfaceContainerHigh,
                palette.ripple,
                16,
                0,
                Color.TRANSPARENT
        ));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(palette.onSurface));
        return button;
    }

    private Button baseButton(String text, int iconRes) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setCompoundDrawablePadding(dp(8));

        Drawable icon = getDrawable(iconRes);
        if (icon != null) {
            icon.setBounds(0, 0, dp(20), dp(20));
            button.setCompoundDrawables(icon, null, null, null);
        }

        return button;
    }

    private RippleDrawable ripple(
            int fill,
            int ripple,
            int radiusDp,
            int strokeDp,
            int strokeColor
    ) {
        GradientDrawable content = round(fill, radiusDp, strokeDp, strokeColor);
        return new RippleDrawable(
                ColorStateList.valueOf(ripple),
                content,
                null
        );
    }

    private GradientDrawable round(
            int fill,
            int radiusDp,
            int strokeDp,
            int strokeColor
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
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

        DecimalFormat format = value >= 100
                ? new DecimalFormat("0")
                : new DecimalFormat("0.0");

        return format.format(value) + " " + units[unit];
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
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

    private static class Palette {
        final boolean dark;
        final int background;
        final int surface;
        final int surfaceContainer;
        final int surfaceContainerHigh;
        final int onSurface;
        final int onSurfaceVariant;
        final int outline;
        final int primary;
        final int onPrimary;
        final int primaryContainer;
        final int onPrimaryContainer;
        final int error;
        final int ripple;
        final int rippleOnPrimary;

        private Palette(
                boolean dark,
                int background,
                int surface,
                int surfaceContainer,
                int surfaceContainerHigh,
                int onSurface,
                int onSurfaceVariant,
                int outline,
                int primary,
                int onPrimary,
                int primaryContainer,
                int onPrimaryContainer,
                int error,
                int ripple,
                int rippleOnPrimary
        ) {
            this.dark = dark;
            this.background = background;
            this.surface = surface;
            this.surfaceContainer = surfaceContainer;
            this.surfaceContainerHigh = surfaceContainerHigh;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.outline = outline;
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.primaryContainer = primaryContainer;
            this.onPrimaryContainer = onPrimaryContainer;
            this.error = error;
            this.ripple = ripple;
            this.rippleOnPrimary = rippleOnPrimary;
        }

        static Palette create(Context context) {
            boolean dark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

            int dynamicPrimary = resolveSystemColor(
                    context,
                    dark ? "system_accent1_200" : "system_accent1_600",
                    dark ? 0xFFA9D9B8 : 0xFF356247
            );

            if (dark) {
                return new Palette(
                        true,
                        0xFF101210,
                        0xFF181A17,
                        0xFF20231F,
                        0xFF2A2E29,
                        0xFFF1F3EE,
                        0xFFADB3AA,
                        0xFF3A3F38,
                        dynamicPrimary,
                        0xFF102016,
                        0xFF23382A,
                        0xFFCDEBD5,
                        0xFFFFB4AB,
                        0x28FFFFFF,
                        0x22102016
                );
            }

            return new Palette(
                    false,
                    0xFFF7F7F4,
                    0xFFFFFFFF,
                    0xFFF0F1EC,
                    0xFFE7E9E3,
                    0xFF171916,
                    0xFF686D66,
                    0xFFD7DAD2,
                    dynamicPrimary,
                    Color.WHITE,
                    0xFFE0EFE4,
                    0xFF173A25,
                    0xFFB3261E,
                    0x18000000,
                    0x24FFFFFF
            );
        }

        private static int resolveSystemColor(
                Context context,
                String name,
                int fallback
        ) {
            if (Build.VERSION.SDK_INT < 31) return fallback;

            int id = context.getResources().getIdentifier(name, "color", "android");
            if (id == 0) return fallback;

            try {
                return context.getColor(id);
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }
}
