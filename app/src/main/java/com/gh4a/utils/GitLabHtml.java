package com.gh4a.utils;

import com.gh4a.utils.GitLabCompatInterceptor.Ctx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Renders markdown through GitLab and cleans the HTML up for the app's HTML view. */
final class GitLabHtml {
    private GitLabHtml() {
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(64, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 200;
        }
    };

    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_SRC = Pattern.compile("\\sdata-src=\"([^\"]*)\"");
    private static final Pattern SRC = Pattern.compile("\\ssrc=\"[^\"]*\"");
    private static final Pattern EMOJI = Pattern.compile(
            "<gl-emoji\\b[^>]*>(.*?)</gl-emoji>", Pattern.DOTALL);
    private static final Pattern URL_ATTR = Pattern.compile("\\b(href|src)=\"(/[^\"/][^\"]*)\"");

    private static OkHttpClient sClient;

    private static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = com.gh4a.ServiceFactory.getHttpClientBuilder().build();
        }
        return sClient;
    }

    /** Rendered, cleaned HTML for {@code text} in the context of {@code project}. */
    static String render(Ctx c, String text, String project) throws IOException {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String key = c.instance() + "|" + project + "|" + text;
        synchronized (CACHE) {
            String cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        JSONObject body = new JSONObject();
        GitLabMapper.put(body, "text", text);
        GitLabMapper.put(body, "gfm", true);
        if (project != null) {
            GitLabMapper.put(body, "project", project);
        }
        String html = null;
        // A separate client: an interceptor chain must not be used from several threads at once.
        Request request = new Request.Builder()
                .url(c.glUrl("/markdown").build())
                .header("PRIVATE-TOKEN", String.valueOf(c.request.header("PRIVATE-TOKEN")))
                .header("Accept", "application/json")
                .post(RequestBody.create(GitLabCompatInterceptor.JSON, body.toString()))
                .build();
        try (Response response = client().newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                html = GitLabMapper.str(new JSONObject(response.body().string()), "html");
            }
        } catch (org.json.JSONException e) {
            html = null;
        }
        if (html == null) {
            // Fall back to the plain text, escaped, so that the screen still shows something.
            html = "<p>" + android.text.TextUtils.htmlEncode(text).replace("\n", "<br>") + "</p>";
        } else {
            html = clean(html, c.instance());
        }
        synchronized (CACHE) {
            CACHE.put(key, html);
        }
        return html;
    }

    /** Sets {@code body_html} on every object of {@code items} that has a {@code body}. */
    static void attach(Ctx c, List<JSONObject> items, String project) throws IOException {
        List<Future<String>> futures = new ArrayList<>();
        for (JSONObject o : items) {
            final String text = o.isNull("body") ? null : o.optString("body", null);
            if (text == null || text.isEmpty()) {
                futures.add(null);
                continue;
            }
            futures.add(POOL.submit(() -> render(c, text, project)));
        }
        for (int i = 0; i < items.size(); i++) {
            Future<String> f = futures.get(i);
            if (f == null) {
                continue;
            }
            try {
                GitLabMapper.put(items.get(i), "body_html", f.get());
            } catch (Exception e) {
                throw new IOException("Rendering failed: " + e.getMessage(), e);
            }
        }
    }

    static void attach(Ctx c, JSONArray items, String project) throws IOException {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) {
                list.add(o);
            }
        }
        attach(c, list, project);
    }

    static void attach(Ctx c, JSONObject item, String project) throws IOException {
        List<JSONObject> list = new ArrayList<>();
        list.add(item);
        attach(c, list, project);
    }

    static String clean(String html, String instance) {
        // GitLab lazy loads images; the real address is in data-src.
        Matcher m = IMG.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            Matcher d = DATA_SRC.matcher(tag);
            if (d.find()) {
                String url = absolute(d.group(1).replace("&amp;", "&"), instance)
                        .replace("/-/blob/", "/-/raw/");
                tag = SRC.matcher(tag).replaceFirst(Matcher.quoteReplacement(" src=\"" + url + "\""));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(tag));
        }
        m.appendTail(sb);
        html = sb.toString();
        html = EMOJI.matcher(html).replaceAll("$1");
        html = html.replace("<copy-code></copy-code>", "")
                .replace("<insert-code-snippet></insert-code-snippet>", "");
        Matcher u = URL_ATTR.matcher(html);
        sb = new StringBuffer();
        while (u.find()) {
            u.appendReplacement(sb, Matcher.quoteReplacement(
                    u.group(1) + "=\"" + instance + u.group(2) + "\""));
        }
        u.appendTail(sb);
        return sb.toString();
    }

    private static String absolute(String url, String instance) {
        if (url.startsWith("/")) {
            return instance + url;
        }
        return url;
    }
}
