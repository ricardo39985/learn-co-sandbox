package com.rick.igsave;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class XTwitterResolver {
    static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";

    private XTwitterResolver() {}

    static List<String> resolve(String suppliedUrl) {
        ArrayList<String> out = new ArrayList<>();

        try {
            Uri uri = Uri.parse(suppliedUrl);
            List<String> parts = uri.getPathSegments();

            int statusIndex = -1;
            for (int i = 0; i < parts.size(); i++) {
                if ("status".equals(parts.get(i))) {
                    statusIndex = i;
                    break;
                }
            }

            if (statusIndex < 0 || statusIndex + 1 >= parts.size()) return out;

            String id = parts.get(statusIndex + 1).replaceAll("[^0-9]", "");
            if (id.isEmpty()) return out;

            JSONObject root = null;

            // Current maintained API.
            try {
                root = fetchJson("https://api.fxtwitter.com/2/status/" + id);
            } catch (Exception ignored) { }

            // Legacy endpoint remains as a fallback.
            if (root == null) {
                String username = statusIndex > 0 ? parts.get(statusIndex - 1) : "";
                if (!username.isEmpty()) {
                    try {
                        root = fetchJson(
                                "https://api.fxtwitter.com/"
                                        + username
                                        + "/status/"
                                        + id
                        );
                    } catch (Exception ignored) { }
                }
            }

            if (root == null) return out;

            JSONObject status = root.optJSONObject("status");
            if (status == null) status = root.optJSONObject("tweet");
            if (status == null) return out;

            String media = firstMediaUrl(status);

            if (media.isEmpty()) {
                JSONObject quote = status.optJSONObject("quote");
                if (quote != null) media = firstMediaUrl(quote);
            }

            if (!media.isEmpty()) out.add(media);
        } catch (Exception ignored) { }

        return out;
    }

    private static String firstMediaUrl(JSONObject status) {
        JSONObject media = status.optJSONObject("media");
        if (media == null) return "";

        // Prefer actual videos first so a thumbnail/photo never wins over the attached video.
        JSONArray videos = media.optJSONArray("videos");
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                String url = mediaItemUrl(videos.optJSONObject(i));
                if (!url.isEmpty()) return url;
            }
        }

        JSONArray all = media.optJSONArray("all");
        if (all != null) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item == null) continue;

                String type = item.optString("type", "").toLowerCase(Locale.US);
                if ("video".equals(type) || "gif".equals(type)) {
                    String url = mediaItemUrl(item);
                    if (!url.isEmpty()) return url;
                }
            }
        }

        JSONArray photos = media.optJSONArray("photos");
        if (photos != null) {
            for (int i = 0; i < photos.length(); i++) {
                JSONObject photo = photos.optJSONObject(i);
                if (photo == null) continue;
                String url = photo.optString("url", "");
                if (isHttp(url)) return url;
            }
        }

        if (all != null) {
            for (int i = 0; i < all.length(); i++) {
                String url = mediaItemUrl(all.optJSONObject(i));
                if (!url.isEmpty()) return url;
            }
        }

        JSONObject external = media.optJSONObject("external");
        if (external != null) {
            String url = external.optString("url", "");
            if (isHttp(url)) return url;
        }

        JSONObject mosaic = media.optJSONObject("mosaic");
        if (mosaic != null) {
            JSONObject formats = mosaic.optJSONObject("formats");
            if (formats != null) {
                String jpeg = formats.optString("jpeg", "");
                if (isHttp(jpeg)) return jpeg;
                String webp = formats.optString("webp", "");
                if (isHttp(webp)) return webp;
            }

            String url = mosaic.optString("url", "");
            if (isHttp(url)) return url;
        }

        return "";
    }

    private static String mediaItemUrl(JSONObject item) {
        if (item == null) return "";

        String type = item.optString("type", "").toLowerCase(Locale.US);

        if ("video".equals(type) || "gif".equals(type)) {
            JSONArray formats = item.optJSONArray("formats");

            long bestBitrate = Long.MIN_VALUE;
            long bestSize = Long.MIN_VALUE;
            String bestUrl = "";

            if (formats != null) {
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject format = formats.optJSONObject(i);
                    if (format == null) continue;

                    String container = format.optString("container", "").toLowerCase(Locale.US);
                    String url = format.optString("url", "");
                    if (!isHttp(url)) continue;

                    boolean mp4 = "mp4".equals(container)
                            || url.toLowerCase(Locale.US).contains(".mp4");

                    if (!mp4) continue;

                    long bitrate = format.optLong("bitrate", 0);
                    long size = format.optLong("size", 0);

                    if (bitrate > bestBitrate
                            || (bitrate == bestBitrate && size > bestSize)) {
                        bestBitrate = bitrate;
                        bestSize = size;
                        bestUrl = url;
                    }
                }
            }

            if (!bestUrl.isEmpty()) return bestUrl;

            String direct = item.optString("url", "");
            if (isHttp(direct) && direct.toLowerCase(Locale.US).contains(".mp4")) {
                return direct;
            }

            String transcode = item.optString("transcode_url", "");
            if (isHttp(transcode) && transcode.toLowerCase(Locale.US).contains(".mp4")) {
                return transcode;
            }

            return "";
        }

        if ("mosaic_photo".equals(type)) {
            JSONObject formats = item.optJSONObject("formats");
            if (formats != null) {
                String jpeg = formats.optString("jpeg", "");
                if (isHttp(jpeg)) return jpeg;
                String webp = formats.optString("webp", "");
                if (isHttp(webp)) return webp;
            }
        }

        String url = item.optString("url", "");
        return isHttp(url) ? url : "";
    }

    private static boolean isHttp(String value) {
        return value != null
                && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private static JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Pragma", "no-cache");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }

        String body;
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[32 * 1024];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 4 * 1024 * 1024) break;
            }

            body = out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }

        JSONObject json = new JSONObject(body);

        // FxTwitter mirrors status in the body. Fail fast on tombstones/errors.
        int apiCode = json.optInt("code", 200);
        if (apiCode < 200 || apiCode >= 300) {
            throw new IllegalStateException("API " + apiCode);
        }

        return json;
    }
}
