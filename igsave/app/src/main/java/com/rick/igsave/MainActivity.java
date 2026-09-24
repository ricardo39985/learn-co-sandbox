package com.rick.igsave;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

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

public class MainActivity extends Activity {
    private static final String FILE_PROVIDER_AUTHORITY = "com.rick.igsave.fileprovider";
    private static final String TIKTOK_US = "com.zhiliaoapp.musically";
    private static final String TIKTOK_INTL = "com.ss.android.ugc.trill";
    private static final String IFUNNY = "mobi.ifunny";
    private static final Pattern IG_URL = Pattern.compile(
            "https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/[^\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    private static final int BG = 0xFF151A1F;
    private static final int SURFACE = 0xFF23282D;
    private static final int SURFACE_HIGH = 0xFF2E3439;
    private static final int OUTLINE = 0xFF3E454B;
    private static final int TEXT = 0xFFF6F7F8;
    private static final int TEXT_MUTED = 0xFF9DA5AC;
    private static final int CYAN = 0xFF72D7F8;
    private static final int CYAN_DARK = 0xFF0D1B21;
    private static final int ERROR = 0xFFFF8A80;
    private static final int OVERLAY = 0xB3191D20;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<MediaFile> readyFiles = new ArrayList<>();

    private LinearLayout contentRoot;
    private EditText input;
    private ImageButton getButton;
    private LinearLayout statusRow;
    private TextView statusText;
    private View statusDot;
    private ProgressBar progress;

    private LinearLayout previewCard;
    private FrameLayout mediaFrame;
    private ImageView previewImage;
    private VideoView previewVideo;
    private ImageView playOverlay;
    private TextView durationChip;
    private TextView sizeChip;
    private Button shareButton;
    private Button downloadButton;
    private TextView bottomMeta;

    private String activePostUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        buildUi();
        consumeIntent(getIntent());

        contentRoot.setAlpha(0f);
        contentRoot.setTranslationY(dp(8));
        contentRoot.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
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
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().setNavigationBarDividerColor(BG);
        }
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
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
        contentRoot.setPadding(dp(20), dp(22), dp(20), dp(34));
        scroll.addView(contentRoot, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final int baseTop = dp(22);
        final int baseBottom = dp(34);
        contentRoot.setOnApplyWindowInsetsListener((view, insets) -> {
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
            view.setPadding(dp(20), baseTop + top, dp(20), baseBottom + bottom);
            return insets;
        });

        buildHeader();
        buildLinkBar();
        buildStatus();
        buildPreview();
    }

    private void buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        contentRoot.addView(row, matchWrap());

        FrameLayout appIcon = new FrameLayout(this);
        appIcon.setBackground(round(SURFACE_HIGH, 20, 1, OUTLINE));
        row.addView(appIcon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_download);
        icon.setImageTintList(ColorStateList.valueOf(TEXT));
        icon.setPadding(dp(15), dp(15), dp(15), dp(15));
        appIcon.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleGroupLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        titleGroupLp.leftMargin = dp(14);
        row.addView(titles, titleGroupLp);

        TextView title = label("IG Save", 23, TEXT, true);
        titles.addView(title);

        TextView subtitle = label("Save Instagram videos and photos", 14, TEXT_MUTED, false);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.topMargin = dp(2);
        titles.addView(subtitle, subLp);

        ImageButton more = iconButton(R.drawable.ic_more, false, 18);
        more.setContentDescription("More");
        more.setOnClickListener(this::showOverflow);
        row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void buildLinkBar() {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(8), 0, dp(8), 0);
        field.setBackground(round(SURFACE, 28, 1, OUTLINE));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(74)
        );
        fieldLp.topMargin = dp(30);
        contentRoot.addView(field, fieldLp);

        ImageView link = new ImageView(this);
        link.setImageResource(R.drawable.ic_link);
        link.setImageTintList(ColorStateList.valueOf(TEXT));
        link.setPadding(dp(11), dp(23), dp(6), dp(23));
        field.addView(link, new LinearLayout.LayoutParams(dp(46), dp(74)));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(TEXT);
        input.setHintTextColor(TEXT_MUTED);
        input.setHint("Paste Instagram link");
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(0, 0, dp(6), 0);
        input.setSelectAllOnFocus(false);
        field.addView(input, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        ));

        ImageButton paste = iconButton(R.drawable.ic_paste, false, 18);
        paste.setContentDescription("Paste");
        paste.setOnClickListener(v -> pasteClipboard());
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(dp(50), dp(50));
        pasteLp.rightMargin = dp(8);
        field.addView(paste, pasteLp);

        getButton = iconButton(R.drawable.ic_download, true, 18);
        getButton.setContentDescription("Download media");
        getButton.setOnClickListener(v -> startResolve(input.getText().toString()));
        field.addView(getButton, new LinearLayout.LayoutParams(dp(54), dp(54)));
    }

    private void buildStatus() {
        statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(13);
        contentRoot.addView(statusRow, lp);

        statusDot = new View(this);
        statusDot.setBackground(circle(CYAN));
        statusRow.addView(statusDot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        statusText = label("", 12, TEXT_MUTED, false);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        textLp.leftMargin = dp(8);
        statusRow.addView(statusText, textLp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(CYAN));
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
    }

    private void buildPreview() {
        previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewLp = matchWrap();
        previewLp.topMargin = dp(28);
        contentRoot.addView(previewCard, previewLp);

        mediaFrame = new FrameLayout(this);
        mediaFrame.setBackground(round(Color.BLACK, 26, 1, OUTLINE));
        mediaFrame.setClipToOutline(true);
        previewCard.addView(mediaFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(390)
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

        durationChip = overlayChip("");
        FrameLayout.LayoutParams durationLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        durationLp.gravity = Gravity.TOP | Gravity.START;
        durationLp.leftMargin = dp(14);
        durationLp.topMargin = dp(14);
        mediaFrame.addView(durationChip, durationLp);

        sizeChip = overlayChip("");
        FrameLayout.LayoutParams sizeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        sizeLp.gravity = Gravity.TOP | Gravity.END;
        sizeLp.rightMargin = dp(14);
        sizeLp.topMargin = dp(14);
        mediaFrame.addView(sizeChip, sizeLp);

        playOverlay = new ImageView(this);
        playOverlay.setImageResource(R.drawable.ic_play);
        playOverlay.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        playOverlay.setPadding(dp(18), dp(18), dp(18), dp(18));
        playOverlay.setBackground(circle(OVERLAY));
        playOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(70), dp(70));
        playLp.gravity = Gravity.CENTER;
        mediaFrame.addView(playOverlay, playLp);

        mediaFrame.setOnClickListener(v -> startVideoPlayback());
        playOverlay.setOnClickListener(v -> startVideoPlayback());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowLp = matchWrap();
        actionRowLp.topMargin = dp(18);
        previewCard.addView(actionRow, actionRowLp);

        shareButton = actionButton("Share", R.drawable.ic_share, false);
        shareButton.setOnClickListener(this::showShareTargets);
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        shareLp.rightMargin = dp(8);
        actionRow.addView(shareButton, shareLp);

        downloadButton = actionButton("Download", R.drawable.ic_download, true);
        downloadButton.setOnClickListener(v -> saveReadyFiles());
        LinearLayout.LayoutParams downloadLp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        downloadLp.leftMargin = dp(8);
        actionRow.addView(downloadButton, downloadLp);

        bottomMeta = label("", 12, TEXT_MUTED, false);
        LinearLayout.LayoutParams metaLp = matchWrap();
        metaLp.topMargin = dp(18);
        metaLp.leftMargin = dp(4);
        previewCard.addView(bottomMeta, metaLp);
    }

    private TextView overlayChip(String value) {
        TextView chip = label(value, 12, Color.WHITE, false);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(13), 0, dp(13), 0);
        chip.setBackground(round(OVERLAY, 18, 0, Color.TRANSPARENT));
        return chip;
    }

    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Clear");
        menu.setOnMenuItemClickListener(item -> {
            clearCurrent();
            return true;
        });
        menu.show();
    }

    private void clearCurrent() {
        activePostUrl = "";
        input.setText("");
        readyFiles.clear();
        clearPrivateShareCache();
        stopPreview();
        previewCard.setVisibility(View.GONE);
        hideStatus();
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
            setStatus("Paste a valid Instagram link.", ERROR);
            return;
        }

        activePostUrl = url;
        input.setText(url);
        input.setSelection(input.length());

        // A new request replaces the previous temporary share file.
        clearPrivateShareCache();
        readyFiles.clear();
        stopPreview();
        previewCard.setVisibility(View.GONE);

        setBusy(true);
        setStatus("Fetching media…", CYAN);

        executor.submit(() -> {
            List<String> urls = InstagramResolver.resolve(url);
            if (!url.equals(activePostUrl)) return;

            if (urls.isEmpty()) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Couldn’t resolve this post.", ERROR);
                });
                return;
            }

            downloadAll(urls);
        });
    }

    private void downloadAll(List<String> urls) {
        File dir = privateShareDir();
        ensureNoMediaMarker(dir);

        MediaFile downloaded = null;
        int candidate = 0;

        // Strict single-file cache: keep the first valid media candidate only.
        for (String mediaUrl : urls) {
            candidate++;
            final int current = candidate;

            runOnUiThread(() -> setStatus(
                    urls.size() > 1 ? "Fetching media…" : "Fetching media…",
                    CYAN
            ));

            try {
                MediaFile file = downloadOne(mediaUrl, dir, current);
                if (file != null && file.file.length() > 0) {
                    downloaded = file;
                    break;
                }
            } catch (Exception ignored) { }
        }

        if (downloaded == null) {
            runOnUiThread(() -> setStatus("Refreshing link…", CYAN));
            List<String> refreshed = InstagramResolver.resolve(activePostUrl);

            if (!refreshed.isEmpty()) {
                int retry = 0;
                for (String mediaUrl : refreshed) {
                    retry++;
                    try {
                        MediaFile file = downloadOne(mediaUrl, dir, retry);
                        if (file != null && file.file.length() > 0) {
                            downloaded = file;
                            break;
                        }
                    } catch (Exception ignored) { }
                }
            }
        }

        final MediaFile latest = downloaded;
        runOnUiThread(() -> {
            readyFiles.clear();
            setBusy(false);

            if (latest == null) {
                clearPrivateShareCache();
                setStatus("Couldn’t fetch the media file.", ERROR);
                return;
            }

            // Remove any partial/alternate candidates. Only the latest successful file survives.
            keepOnly(latest.file);
            readyFiles.add(latest);
            hideStatus();
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
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

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
            while ((read = in.read(buffer)) != -1) os.write(buffer, 0, read);
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

        long total = 0;
        for (MediaFile file : readyFiles) total += file.file.length();

        String duration = video ? videoDuration(first.file) : "Photo";
        durationChip.setText(duration);
        sizeChip.setText(formatBytes(total));

        if (readyFiles.size() > 1) {
            bottomMeta.setText(readyFiles.size() + " items  •  " + formatBytes(total));
        } else {
            bottomMeta.setText(duration + "  •  " + formatBytes(total));
        }

        TransitionManager.beginDelayedTransition(
                contentRoot,
                new AutoTransition().setDuration(220)
        );
        previewCard.setVisibility(View.VISIBLE);

        previewCard.setAlpha(0f);
        previewCard.setTranslationY(dp(10));
        previewCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .start();

        mediaFrame.post(() -> {
            int width = mediaFrame.getWidth();
            if (width <= 0) return;
            ViewGroup.LayoutParams lp = mediaFrame.getLayoutParams();
            lp.height = Math.round(width * 1.06f);
            mediaFrame.setLayoutParams(lp);
        });
    }

    private void startVideoPlayback() {
        if (readyFiles.isEmpty()) return;
        MediaFile first = readyFiles.get(0);
        if (first.mime == null || !first.mime.toLowerCase(Locale.US).startsWith("video/")) return;

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

    private String videoDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? "Video" : formatDuration(Long.parseLong(value));
        } catch (Exception ignored) {
            return "Video";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) { }
        }
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

    private void stopPreview() {
        if (previewVideo != null) {
            try {
                previewVideo.stopPlayback();
            } catch (Exception ignored) { }
            previewVideo.setVisibility(View.GONE);
        }
        if (previewImage != null) previewImage.setImageDrawable(null);
        if (playOverlay != null) playOverlay.setVisibility(View.GONE);
    }

    private void showShareTargets(View anchor) {
        if (readyFiles.isEmpty()) return;

        PopupMenu menu = new PopupMenu(this, anchor);
        int nextId = 1;

        String tiktok = firstInstalledPackage(TIKTOK_US, TIKTOK_INTL);
        if (tiktok != null) {
            menu.getMenu().add(0, nextId++, 0, "TikTok");
        }

        boolean hasIFunny = isPackageInstalled(IFUNNY);
        if (hasIFunny) {
            menu.getMenu().add(0, nextId++, 1, "iFunny");
        }

        menu.getMenu().add(0, 100, 99, "Other…");

        final String tiktokPackage = tiktok;
        final boolean ifunnyInstalled = hasIFunny;

        menu.setOnMenuItemClickListener(item -> {
            CharSequence title = item.getTitle();
            if ("TikTok".contentEquals(title) && tiktokPackage != null) {
                shareToPackage(tiktokPackage);
                return true;
            }
            if ("iFunny".contentEquals(title) && ifunnyInstalled) {
                shareToPackage(IFUNNY);
                return true;
            }
            shareViaChooser();
            return true;
        });
        menu.show();
    }

    private void shareToPackage(String packageName) {
        if (readyFiles.isEmpty()) return;

        MediaFile media = readyFiles.get(0);
        Uri uri = privateShareUri(media);

        Intent send = baseShareIntent(media, uri);
        send.setPackage(packageName);

        try {
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(send);
        } catch (Exception e) {
            shareViaChooser();
        }
    }

    private void shareViaChooser() {
        if (readyFiles.isEmpty()) return;

        MediaFile media = readyFiles.get(0);
        Uri uri = privateShareUri(media);
        Intent send = baseShareIntent(media, uri);

        startActivity(Intent.createChooser(send, "Share"));
    }

    private Intent baseShareIntent(MediaFile media, Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        String mime = media.mime == null ? "application/octet-stream" : media.mime;

        if (mime.startsWith("video/")) {
            send.setType("video/*");
        } else if (mime.startsWith("image/")) {
            send.setType("image/*");
        } else {
            send.setType(mime);
        }

        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri(media.file.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }

    private Uri privateShareUri(MediaFile media) {
        return FileProvider.getUriForFile(
                this,
                FILE_PROVIDER_AUTHORITY,
                media.file
        );
    }

    private String firstInstalledPackage(String... packages) {
        for (String packageName : packages) {
            if (isPackageInstalled(packageName)) return packageName;
        }
        return null;
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveReadyFiles() {
        if (readyFiles.isEmpty()) return;

        setBusy(true);
        setStatus("Saving…", CYAN);

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
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
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
                    setStatus("Saved to Downloads", CYAN);
                    statusRow.postDelayed(this::hideStatus, 1600);
                } else {
                    setStatus("Couldn’t save the file.", ERROR);
                }
            });
        });
    }

    private File privateShareDir() {
        File dir = new File(getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void clearPrivateShareCache() {
        File dir = privateShareDir();
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (".nomedia".equals(file.getName())) continue;
            try {
                file.delete();
            } catch (Exception ignored) { }
        }
    }

    private void keepOnly(File keep) {
        File dir = privateShareDir();
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (".nomedia".equals(file.getName())) continue;
            if (file.equals(keep)) continue;
            try {
                file.delete();
            } catch (Exception ignored) { }
        }
        ensureNoMediaMarker(dir);
    }

    private void ensureNoMediaMarker(File dir) {
        File marker = new File(dir, ".nomedia");
        if (marker.exists()) return;
        try {
            marker.createNewFile();
        } catch (Exception ignored) { }
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
            statusText.setTextColor(color == CYAN ? TEXT_MUTED : color);
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
        view.setTypeface(Typeface.create(
                bold ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL
        ));
        return view;
    }

    private ImageButton iconButton(int iconRes, boolean primary, int radius) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setImageTintList(ColorStateList.valueOf(primary ? CYAN_DARK : TEXT));
        button.setBackground(ripple(
                primary ? CYAN : SURFACE_HIGH,
                primary ? 0x33000000 : 0x22FFFFFF,
                radius,
                primary ? 0 : 1,
                primary ? Color.TRANSPARENT : OUTLINE
        ));
        return button;
    }

    private Button actionButton(String text, int iconRes, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(primary ? CYAN_DARK : TEXT);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setCompoundDrawablePadding(dp(10));

        Drawable icon = getDrawable(iconRes);
        if (icon != null) {
            icon.setBounds(0, 0, dp(21), dp(21));
            button.setCompoundDrawables(icon, null, null, null);
            button.setCompoundDrawableTintList(ColorStateList.valueOf(primary ? CYAN_DARK : TEXT));
        }

        button.setBackground(ripple(
                primary ? CYAN : SURFACE,
                primary ? 0x33000000 : 0x22FFFFFF,
                24,
                primary ? 0 : 1,
                primary ? Color.TRANSPARENT : OUTLINE
        ));
        return button;
    }

    private RippleDrawable ripple(
            int fill,
            int ripple,
            int radiusDp,
            int strokeDp,
            int strokeColor
    ) {
        return new RippleDrawable(
                ColorStateList.valueOf(ripple),
                round(fill, radiusDp, strokeDp, strokeColor),
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
}
