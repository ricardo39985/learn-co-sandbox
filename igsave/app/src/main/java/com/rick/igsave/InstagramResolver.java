package com.rick.igsave;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InstagramResolver {
    static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";

    private static final String GOOGLEBOT =
            "Googlebot/2.1 (+http://www.google.com/bot.html)";
    private static final String FACEBOOK_CRAWLER =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";

    private static final String APP_ID = "936619743392459";
    private static final String POST_DOC_ID = "27128499623469141";
    private static final String SHORTCODE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Pattern DATA_SJS = Pattern.compile(
            "<script\\b[^>]*\\bdata-sjs(?:=[^>]*)?[^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CANONICAL = Pattern.compile(
            "<link[^>]+rel=[\\\"']canonical[\\\"'][^>]+href=[\\\"']([^\\\"']+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CANONICAL_REVERSED = Pattern.compile(
            "<link[^>]+href=[\\\"']([^\\\"']+)[\\\"'][^>]+rel=[\\\"']canonical[\\\"']",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VIDEO_JSON = Pattern.compile(
            "[\\\"](?:video_url|contentUrl|content_url)[\\\"]\\s*:\\s*[\\\"]([^\\\"]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_JSON = Pattern.compile(
            "[\\\"](?:display_url|thumbnail_url|image_url)[\\\"]\\s*:\\s*[\\\"]([^\\\"]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_VIDEO = Pattern.compile(
            "<meta[^>]+property=[\\\"]og:video(?::url)?[\\\"][^>]+content=[\\\"]([^\\\"]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_VIDEO_REV = Pattern.compile(
            "<meta[^>]+content=[\\\"]([^\\\"]+)[\\\"][^>]+property=[\\\"]og:video(?::url)?[\\\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+property=[\\\"]og:image[\\\"][^>]+content=[\\\"]([^\\\"]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE_REV = Pattern.compile(
            "<meta[^>]+content=[\\\"]([^\\\"]+)[\\\"][^>]+property=[\\\"]og:image[\\\"]",
            Pattern.CASE_INSENSITIVE);

    private InstagramResolver() {}

    static List<String> resolve(String suppliedUrl) {
        LinkedHashSet<String> media = new LinkedHashSet<>();

        String code = shortcode(suppliedUrl);
        if (code.isEmpty()) {
            try {
                Page first = fetchPage(suppliedUrl, USER_AGENT, null);
                String canonical = canonicalFrom(first, suppliedUrl);
                code = shortcode(canonical);
                if (code.isEmpty()) code = shortcode(first.finalUrl);
            } catch (Exception ignored) { }
        }

        if (code.isEmpty()) return new ArrayList<>();

        tryPublicPostPage(code, media);
        if (!media.isEmpty()) return new ArrayList<>(media);

        tryEmbedPages(code, media);
        if (!media.isEmpty()) return new ArrayList<>(media);

        tryGraphQl(code, "https://www.instagram.com/p/" + code + "/", media);
        return new ArrayList<>(media);
    }

    private static void tryPublicPostPage(String code, LinkedHashSet<String> out) {
        String expectedId = shortcodeToMediaId(code);

        String[] urls = new String[] {
                "https://www.instagram.com/p/" + code + "/",
                "https://www.instagram.com/reel/" + code + "/"
        };
        String[] agents = new String[] {
                GOOGLEBOT,
                FACEBOOK_CRAWLER,
                USER_AGENT
        };

        for (String pageUrl : urls) {
            for (String agent : agents) {
                if (!out.isEmpty()) return;
                try {
                    Page page = fetchPage(pageUrl, agent, null);
                    if (page.status == 429 || page.status == 401 || page.status == 403) {
                        continue;
                    }

                    if (page.status >= 200 && page.status < 400) {
                        JSONObject product = findPublicProductInScripts(page.body, expectedId);
                        if (product != null) {
                            collectProductMedia(product, out);
                            if (!out.isEmpty()) return;
                        }

                        collectFromHtml(page.body, out);
                    }
                } catch (Exception ignored) { }
            }
        }
    }

    private static JSONObject findPublicProductInScripts(String html, String expectedId) {
        if (html == null || html.isEmpty()) return null;

        Matcher matcher = DATA_SJS.matcher(html);
        while (matcher.find()) {
            String payload = matcher.group(1);
            if (payload == null) continue;

            payload = payload.trim();
            if (!payload.startsWith("{")) continue;

            try {
                JSONObject json = new JSONObject(payload);
                JSONObject found = findPublicProduct(json, expectedId);
                if (found != null) return found;
            } catch (Exception ignored) { }
        }

        return null;
    }

    private static JSONObject findPublicProduct(Object value, String expectedId) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findPublicProduct(array.opt(i), expectedId);
                if (found != null) return found;
            }
            return null;
        }

        if (!(value instanceof JSONObject)) return null;

        JSONObject obj = (JSONObject) value;

        JSONObject publicLoggedOut = obj.optJSONObject("if_not_gated_logged_out");
        if (publicLoggedOut != null && matchesId(publicLoggedOut, expectedId)) {
            return publicLoggedOut;
        }

        if (matchesId(obj, expectedId)
                && (obj.has("video_versions")
                || obj.has("carousel_media")
                || obj.has("image_versions2")
                || obj.has("display_url"))) {
            return obj;
        }

        JSONArray names = obj.names();
        if (names == null) return null;

        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            Object child = obj.opt(key);
            if (!(child instanceof JSONObject) && !(child instanceof JSONArray)) continue;

            JSONObject found = findPublicProduct(child, expectedId);
            if (found != null) return found;
        }

        return null;
    }

    private static boolean matchesId(JSONObject obj, String expectedId) {
        if (expectedId == null || expectedId.isEmpty()) return true;

        String pk = obj.optString("pk", "");
        String id = obj.optString("id", "");

        if (expectedId.equals(pk) || expectedId.equals(id)) return true;

        int underscore = id.indexOf('_');
        return underscore > 0 && expectedId.equals(id.substring(0, underscore));
    }

    private static void collectProductMedia(JSONObject product, LinkedHashSet<String> out) {
        JSONArray carousel = product.optJSONArray("carousel_media");
        if (carousel != null && carousel.length() > 0) {
            for (int i = 0; i < carousel.length(); i++) {
                JSONObject item = carousel.optJSONObject(i);
                if (item != null) addBestItemMedia(item, out);
            }
            return;
        }

        addBestItemMedia(product, out);
    }

    private static void addBestItemMedia(JSONObject item, LinkedHashSet<String> out) {
        JSONArray videos = item.optJSONArray("video_versions");
        String bestVideo = bestCandidate(videos);
        if (isMediaUrl(bestVideo)) {
            out.add(bestVideo);
            return;
        }

        String directVideo = decodeEscapedUrl(item.optString("video_url", ""));
        if (isMediaUrl(directVideo)) {
            out.add(directVideo);
            return;
        }

        JSONObject imageVersions = item.optJSONObject("image_versions2");
        if (imageVersions != null) {
            String bestImage = bestCandidate(imageVersions.optJSONArray("candidates"));
            if (isMediaUrl(bestImage)) {
                out.add(bestImage);
                return;
            }
        }

        String display = decodeEscapedUrl(item.optString("display_url", ""));
        if (isMediaUrl(display)) out.add(display);
    }

    private static void tryEmbedPages(String code, LinkedHashSet<String> out) {
        String[] paths = new String[]{
                "https://www.instagram.com/reel/" + code + "/embed/",
                "https://www.instagram.com/p/" + code + "/embed/",
                "https://www.instagram.com/reel/" + code + "/embed/captioned/",
                "https://www.instagram.com/p/" + code + "/embed/captioned/"
        };

        for (String url : paths) {
            if (!out.isEmpty()) return;

            try {
                Page p = fetchPage(url, USER_AGENT, null);
                if (p.status >= 200 && p.status < 400) {
                    collectFromHtml(p.body, out);
                }
            } catch (Exception ignored) { }
        }
    }

    private static void tryGraphQl(String code, String referer, LinkedHashSet<String> out) {
        try {
            Page root = fetchPage("https://www.instagram.com/", USER_AGENT, null);
            Map<String, String> cookies = root.cookies;
            String csrf = cookies.get("csrftoken");
            if (csrf == null) csrf = "";

            JSONObject vars = new JSONObject();
            vars.put("shortcode", code);
            vars.put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false);

            String form = "variables=" + URLEncoder.encode(vars.toString(), "UTF-8")
                    + "&doc_id=" + URLEncoder.encode(POST_DOC_ID, "UTF-8")
                    + "&server_timestamps=true";

            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://www.instagram.com/graphql/query").openConnection();

            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("x-ig-app-id", APP_ID);
            c.setRequestProperty("x-csrftoken", csrf);
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (!cookies.isEmpty()) {
                c.setRequestProperty("Cookie", cookieHeader(cookies));
            }

            byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }

            int status = c.getResponseCode();
            if (status >= 200 && status < 300) {
                String body = readFully(c.getInputStream(), 4 * 1024 * 1024);
                collectFromJson(new JSONObject(body), out);
            }

            c.disconnect();
        } catch (Exception ignored) { }
    }

    private static Page fetchPage(
            String url,
            String userAgent,
            Map<String, String> cookieJar
    ) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
        );
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");

        if (cookieJar != null && !cookieJar.isEmpty()) {
            c.setRequestProperty("Cookie", cookieHeader(cookieJar));
        }

        int status = c.getResponseCode();

        Map<String, String> cookies =
                cookieJar == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(cookieJar);

        List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
        if (setCookies == null) {
            setCookies = c.getHeaderFields().get("set-cookie");
        }

        if (setCookies != null) {
            for (String h : setCookies) {
                if (h == null) continue;

                int semi = h.indexOf(';');
                String pair = semi >= 0 ? h.substring(0, semi) : h;
                int eq = pair.indexOf('=');

                if (eq > 0) {
                    cookies.put(
                            pair.substring(0, eq).trim(),
                            pair.substring(eq + 1).trim()
                    );
                }
            }
        }

        InputStream raw =
                status >= 200 && status < 400
                        ? c.getInputStream()
                        : c.getErrorStream();

        String body =
                raw == null
                        ? ""
                        : readFully(raw, 6 * 1024 * 1024);

        String finalUrl = c.getURL().toString();
        c.disconnect();

        return new Page(status, finalUrl, body, cookies);
    }

    private static String canonicalFrom(Page page, String fallback) {
        String fromFinal =
                page.finalUrl == null || page.finalUrl.isEmpty()
                        ? fallback
                        : page.finalUrl;

        String body = page.body == null ? "" : page.body;

        Matcher m = CANONICAL.matcher(body);
        if (m.find()) return htmlDecode(m.group(1));

        m = CANONICAL_REVERSED.matcher(body);
        if (m.find()) return htmlDecode(m.group(1));

        return fromFinal;
    }

    private static void collectFromJson(Object value, LinkedHashSet<String> out) {
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.opt(i);
                if (v != null) collectFromJson(v, out);
            }
            return;
        }

        if (!(value instanceof JSONObject)) return;

        JSONObject obj = (JSONObject) value;

        JSONArray carousel = obj.optJSONArray("carousel_media");
        if (carousel != null && carousel.length() > 0) {
            for (int i = 0; i < carousel.length(); i++) {
                JSONObject item = carousel.optJSONObject(i);
                if (item != null) addBestItemMedia(item, out);
            }
            return;
        }

        JSONObject sidecar = obj.optJSONObject("edge_sidecar_to_children");
        if (sidecar != null) {
            JSONArray edges = sidecar.optJSONArray("edges");
            if (edges != null) {
                for (int i = 0; i < edges.length(); i++) {
                    JSONObject edge = edges.optJSONObject(i);
                    if (edge != null) collectFromJson(edge.opt("node"), out);
                }
                return;
            }
        }

        String bestVideo = bestCandidate(obj.optJSONArray("video_versions"));
        if (isMediaUrl(bestVideo)) {
            out.add(bestVideo);
            return;
        }

        String directVideo = decodeEscapedUrl(obj.optString("video_url", ""));
        if (isMediaUrl(directVideo)) {
            out.add(directVideo);
            return;
        }

        JSONObject imageVersions = obj.optJSONObject("image_versions2");
        if (imageVersions != null) {
            String bestImage = bestCandidate(imageVersions.optJSONArray("candidates"));
            if (isMediaUrl(bestImage)) {
                out.add(bestImage);
                return;
            }
        }

        String display = decodeEscapedUrl(obj.optString("display_url", ""));
        if (isMediaUrl(display)) {
            out.add(display);
            return;
        }

        JSONArray names = obj.names();
        if (names == null) return;

        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            Object child = obj.opt(key);
            if (child instanceof JSONObject || child instanceof JSONArray) {
                collectFromJson(child, out);
            }
        }
    }

    private static String bestCandidate(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";

        long bestArea = -1;
        String best = "";

        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;

            String url = decodeEscapedUrl(c.optString("url", ""));
            if (!isMediaUrl(url)) continue;

            long width = c.optLong("width", 0);
            long height = c.optLong("height", 0);
            long area = width > 0 && height > 0 ? width * height : i;

            if (area >= bestArea) {
                bestArea = area;
                best = url;
            }
        }

        return best;
    }

    private static void collectFromHtml(String html, LinkedHashSet<String> out) {
        if (html == null || html.isEmpty()) return;

        LinkedHashSet<String> videos = new LinkedHashSet<>();
        collectRegex(html, VIDEO_JSON, videos);
        collectRegex(html, OG_VIDEO, videos);
        collectRegex(html, OG_VIDEO_REV, videos);

        if (!videos.isEmpty()) {
            out.addAll(videos);
            return;
        }

        LinkedHashSet<String> images = new LinkedHashSet<>();
        collectRegex(html, IMAGE_JSON, images);
        collectRegex(html, OG_IMAGE, images);
        collectRegex(html, OG_IMAGE_REV, images);
        out.addAll(images);
    }

    private static void collectRegex(
            String html,
            Pattern pattern,
            LinkedHashSet<String> out
    ) {
        Matcher m = pattern.matcher(html);

        while (m.find() && out.size() < 12) {
            String u = decodeEscapedUrl(m.group(1));
            if (isMediaUrl(u)) out.add(u);
        }
    }

    private static String decodeEscapedUrl(String raw) {
        if (raw == null) return "";

        return htmlDecode(raw)
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u003D", "=")
                .replace("\\u002F", "/")
                .replace("\\/", "/");
    }

    private static String htmlDecode(String raw) {
        if (raw == null) return "";

        return raw
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static boolean isMediaUrl(String value) {
        if (value == null) return false;

        String s = value.trim();
        if (!(s.startsWith("https://") || s.startsWith("http://"))) {
            return false;
        }

        String lower = s.toLowerCase(Locale.US);

        return lower.contains("cdninstagram.com")
                || lower.contains("fbcdn.net")
                || lower.contains("instagram.com");
    }

    private static String shortcode(String url) {
        if (url == null) return "";

        try {
            List<String> parts = Uri.parse(url).getPathSegments();

            for (int i = 0; i + 1 < parts.size(); i++) {
                String p = parts.get(i);

                if ("p".equals(p)
                        || "reel".equals(p)
                        || "reels".equals(p)
                        || "tv".equals(p)) {
                    return parts.get(i + 1)
                            .replaceAll("[^A-Za-z0-9_-]", "");
                }
            }
        } catch (Exception ignored) { }

        return "";
    }

    private static String shortcodeToMediaId(String shortcode) {
        try {
            BigInteger value = BigInteger.ZERO;
            BigInteger base = BigInteger.valueOf(64);

            for (int i = 0; i < shortcode.length(); i++) {
                int digit = SHORTCODE_ALPHABET.indexOf(shortcode.charAt(i));
                if (digit < 0) return "";

                value = value
                        .multiply(base)
                        .add(BigInteger.valueOf(digit));
            }

            return value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String cookieHeader(Map<String, String> cookies) {
        StringBuilder b = new StringBuilder();

        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (b.length() > 0) b.append("; ");
            b.append(e.getKey()).append('=').append(e.getValue());
        }

        return b.toString();
    }

    private static String readFully(InputStream input, int maxBytes) throws Exception {
        try (InputStream in = new BufferedInputStream(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            byte[] buf = new byte[32 * 1024];
            int total = 0;
            int r;

            while ((r = in.read(buf)) != -1) {
                int remaining = maxBytes - total;
                if (remaining <= 0) break;

                int count = Math.min(r, remaining);
                out.write(buf, 0, count);
                total += count;

                if (count < r) break;
            }

            return out.toString("UTF-8");
        }
    }

    private static final class Page {
        final int status;
        final String finalUrl;
        final String body;
        final Map<String, String> cookies;

        Page(
                int status,
                String finalUrl,
                String body,
                Map<String, String> cookies
        ) {
            this.status = status;
            this.finalUrl = finalUrl;
            this.body = body;
            this.cookies = cookies;
        }
    }
}
