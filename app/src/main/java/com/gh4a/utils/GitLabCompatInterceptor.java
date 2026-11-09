package com.gh4a.utils;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Lets the GitHub-shaped SDK talk to a GitLab instance. Each SDK request is matched against a
 * route table, sent to the GitLab REST API v4 and the answer is converted back into the JSON
 * the SDK models expect (see {@link GitLabRoutes} and {@link GitLabMapper}).
 */
public class GitLabCompatInterceptor implements Interceptor {
    static final String TAG = "GitLabCompat";
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Merge requests share the issue endpoints of the SDK, so they get a number range of their own. */
    public static final int MR_OFFSET = 1_000_000_000;

    interface Handler {
        Response handle(Ctx ctx) throws IOException;
    }

    private static final class Route {
        final String method;
        final Pattern pattern;
        final List<String> names;
        final Handler handler;

        Route(String method, String template, Handler handler) {
            this.method = method;
            this.handler = handler;
            this.names = new ArrayList<>();
            StringBuilder regex = new StringBuilder("^");
            Matcher m = Pattern.compile("\\{(\\w+)(\\*)?\\}").matcher(template);
            int last = 0;
            while (m.find()) {
                regex.append(Pattern.quote(template.substring(last, m.start())));
                names.add(m.group(1));
                regex.append(m.group(2) != null ? "(.*)" : "([^/]+)");
                last = m.end();
            }
            regex.append(Pattern.quote(template.substring(last))).append("$");
            this.pattern = Pattern.compile(regex.toString());
        }
    }

    private static final List<Route> ROUTES = new ArrayList<>();

    static void route(String method, String template, Handler handler) {
        ROUTES.add(new Route(method, template, handler));
    }

    static {
        GitLabRoutes.register();
    }

    /** Result of an upstream GitLab call. */
    static final class Up {
        final int code;
        final String body;
        final byte[] bytes;
        final okhttp3.Headers headers;

        Up(int code, byte[] bytes, okhttp3.Headers headers) {
            this.code = code;
            this.bytes = bytes;
            this.body = new String(bytes, StandardCharsets.UTF_8);
            this.headers = headers;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }

        JSONObject obj() {
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        JSONArray arr() {
            try {
                return new JSONArray(body);
            } catch (JSONException e) {
                return new JSONArray();
            }
        }

        int total() {
            return headerInt("X-Total", -1);
        }

        int totalPages() {
            return headerInt("X-Total-Pages", -1);
        }

        int nextPage() {
            return headerInt("X-Next-Page", -1);
        }

        private int headerInt(String name, int def) {
            String v = headers.get(name);
            if (v == null || v.trim().isEmpty()) {
                return def;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    /** Per-request state handed to the route handlers. */
    static final class Ctx {
        final Chain chain;
        final Request request;
        final HttpUrl url;
        private final Map<String, String> params = new LinkedHashMap<>();

        Ctx(Chain chain, Request request, Route route, Matcher m) {
            this.chain = chain;
            this.request = request;
            this.url = request.url();
            for (int i = 0; i < route.names.size(); i++) {
                params.put(route.names.get(i), m.group(i + 1));
            }
        }

        /** Raw (still percent-encoded) path parameter. */
        String raw(String name) {
            return params.get(name);
        }

        /** Decoded path parameter. */
        String p(String name) {
            return android.net.Uri.decode(params.get(name));
        }

        /** The GitLab project id (url encoded {@code namespace/path}) of this request. */
        String project() {
            return params.get("owner") + "%2F" + params.get("repo");
        }

        String q(String name) {
            return url.queryParameter(name);
        }

        String accept() {
            String a = request.header("Accept");
            return a != null ? a : "";
        }

        int page() {
            String v = q("page");
            try {
                return v != null ? Integer.parseInt(v) : 1;
            } catch (NumberFormatException e) {
                return 1;
            }
        }

        String bodyString() throws IOException {
            RequestBody body = request.body();
            if (body == null) {
                return "";
            }
            okio.Buffer buffer = new okio.Buffer();
            body.writeTo(buffer);
            return buffer.readUtf8();
        }

        JSONObject bodyJson() throws IOException {
            String s = bodyString();
            try {
                return s.trim().startsWith("{") ? new JSONObject(s) : new JSONObject();
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        JSONArray bodyArray() throws IOException {
            String s = bodyString();
            try {
                return s.trim().startsWith("[") ? new JSONArray(s) : new JSONArray();
            } catch (JSONException e) {
                return new JSONArray();
            }
        }

        /** Base of the instance, e.g. {@code https://gitlab.com}. */
        String instance() {
            return GitLabMapper.instanceOf(url);
        }

        HttpUrl.Builder glUrl(String glPath) {
            HttpUrl.Builder b = new HttpUrl.Builder()
                    .scheme(url.scheme())
                    .host(url.host());
            if (url.port() != HttpUrl.defaultPort(url.scheme())) {
                b.port(url.port());
            }
            if ("/graphql".equals(glPath)) {
                b.encodedPath("/api/graphql");
            } else {
                b.encodedPath("/api/v4" + (glPath.startsWith("/") ? glPath : "/" + glPath));
            }
            return b;
        }

        Up get(String glPath, String... query) throws IOException {
            return call("GET", glPath, null, query);
        }

        Up call(String method, String glPath, JSONObject body, String... query)
                throws IOException {
            HttpUrl.Builder b = glUrl(glPath);
            for (int i = 0; i + 1 < query.length; i += 2) {
                if (query[i + 1] != null) {
                    b.addQueryParameter(query[i], query[i + 1]);
                }
            }
            return exec(method, b.build(), body);
        }

        Up exec(String method, HttpUrl target, JSONObject body) throws IOException {
            Request.Builder rb = request.newBuilder().url(target);
            RequestBody rbody = null;
            if (body != null) {
                rbody = RequestBody.create(JSON, body.toString());
            } else if (!"GET".equals(method) && !"HEAD".equals(method) && !"DELETE".equals(method)) {
                rbody = RequestBody.create(JSON, "{}");
            }
            rb.method(method, rbody);
            rb.header("Accept", "application/json");
            Response r = chain.proceed(rb.build());
            try (ResponseBody rbod = r.body()) {
                byte[] bytes = rbod != null ? rbod.bytes() : new byte[0];
                return new Up(r.code(), bytes, r.headers());
            }
        }

        /** Same as {@link #get} but with the page parameters of the current request. */
        Up getPaged(String glPath, String... query) throws IOException {
            List<String> all = new ArrayList<>();
            for (String s : query) {
                all.add(s);
            }
            all.add("page");
            all.add(String.valueOf(page()));
            String per = q("per_page");
            if (per != null) {
                all.add("per_page");
                all.add(per);
            }
            return get(glPath, all.toArray(new String[0]));
        }

        Response json(Object json) {
            return reply(200, json.toString());
        }

        Response jsonPaged(JSONArray items, Up up) {
            Response r = reply(up.ok() ? 200 : up.code, items.toString());
            return withPagination(r, up);
        }

        Response reply(int code, String body) {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(code < 300 ? "OK" : "Error")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(ResponseBody.create(JSON, body))
                    .build();
        }

        Response raw(Up up, String contentType) {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(up.code)
                    .message("OK")
                    .header("Content-Type", contentType)
                    .body(ResponseBody.create(MediaType.parse(contentType), up.bytes))
                    .build();
        }

        Response empty(int code) {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(ResponseBody.create(JSON, ""))
                    .build();
        }

        Response error(Up up) {
            String message = up.body;
            try {
                JSONObject o = new JSONObject(up.body);
                Object m = o.opt("message");
                if (m == null) {
                    m = o.opt("error");
                }
                if (m != null) {
                    message = m.toString();
                }
            } catch (JSONException ignored) {
            }
            JSONObject o = new JSONObject();
            try {
                o.put("message", message);
            } catch (JSONException ignored) {
            }
            int code = up.code;
            String auth = up.headers.get("WWW-Authenticate");
            if (code == 401 && request.header("PRIVATE-TOKEN") != null
                    && (auth == null || !auth.contains("invalid_token"))) {
                // GitLab answers 401 when a valid token lacks the right for an action. The app
                // treats 401 as "log in again", so report it as the 403 it really is.
                code = 403;
            }
            return reply(code, o.toString());
        }

        Response notFound(String message) {
            return reply(404, "{\"message\":" + JSONObject.quote(message) + "}");
        }

        /** Convert an upstream answer with the usual list handling. */
        Response withPagination(Response r, Up up) {
            int next = up.nextPage();
            int totalPages = up.totalPages();
            if (up.total() >= 0) {
                r = r.newBuilder().header("X-Total-Count", String.valueOf(up.total())).build();
            }
            if (next < 0 && totalPages < 0) {
                return r;
            }
            StringBuilder link = new StringBuilder();
            if (next > 0) {
                link.append(String.format(Locale.US, "<%s>; rel=\"next\"",
                        url.newBuilder().setQueryParameter("page", String.valueOf(next)).build()));
            }
            if (totalPages > 0) {
                if (link.length() > 0) {
                    link.append(", ");
                }
                link.append(String.format(Locale.US, "<%s>; rel=\"last\"",
                        url.newBuilder().setQueryParameter("page", String.valueOf(totalPages))
                                .build()));
            }
            return r.newBuilder().header("Link", link.toString()).build();
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String path = request.url().encodedPath();
        if (path.startsWith("/api/")) {
            return chain.proceed(request);
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String method = request.method();
        for (Route route : ROUTES) {
            if (!route.method.equals(method)) {
                continue;
            }
            Matcher m = route.pattern.matcher(normalized);
            if (m.matches()) {
                Ctx ctx = new Ctx(chain, request, route, m);
                if (!"GET".equals(method) && Log.isLoggable(TAG, Log.DEBUG)) {
                    try {
                        Log.d(TAG, method + " " + path + " body " + ctx.bodyString());
                    } catch (IOException ignored) {
                        // only for logging
                    }
                }
                try {
                    return route.handler.handle(ctx);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Conversion failed for " + method + " " + request.url(), e);
                    throw new IOException("Unexpected GitLab answer: " + e.getMessage(), e);
                }
            }
        }
        Log.w(TAG, "No GitLab mapping for " + method + " " + path);
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not supported")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(ResponseBody.create(JSON,
                        "{\"message\":\"This action is not available on GitLab.\"}"))
                .build();
    }
}
