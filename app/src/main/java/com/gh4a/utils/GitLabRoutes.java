package com.gh4a.utils;

import android.net.Uri;

import com.gh4a.utils.GitLabCompatInterceptor.Ctx;
import com.gh4a.utils.GitLabCompatInterceptor.Up;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

import static com.gh4a.utils.GitLabCompatInterceptor.route;
import static com.gh4a.utils.GitLabMapper.newObj;
import static com.gh4a.utils.GitLabMapper.put;
import static com.gh4a.utils.GitLabMapper.str;

/** Route table for users, groups, repositories, files, commits, branches, tags and releases. */
final class GitLabRoutes {
    private GitLabRoutes() {
    }

    private static final Map<String, JSONObject> PROJECTS = new HashMap<>();
    private static final Map<String, Long> USER_IDS = new HashMap<>();
    private static final Map<String, Long> MY_IDS = new HashMap<>();

    static void register() {
        registerUsers();
        registerGroups();
        registerRepositories();
        registerSearch();
        registerStarring();
        registerContents();
        registerCommits();
        registerRefs();
        registerReleases();
        GitLabIssueRoutes.register();
    }

    // ---------------------------------------------------------------- shared lookups

    static String enc(String s) {
        return Uri.encode(s);
    }

    /** Project JSON by numeric id or path, cached per instance. */
    static JSONObject project(Ctx c, String idOrPath) throws IOException {
        String key = c.instance() + "|" + idOrPath;
        JSONObject cached = PROJECTS.get(key);
        if (cached != null) {
            return cached;
        }
        Up up = c.get("/projects/" + (idOrPath.matches("\\d+") ? idOrPath : enc(idOrPath)));
        if (!up.ok()) {
            return null;
        }
        JSONObject p = up.obj();
        PROJECTS.put(key, p);
        PROJECTS.put(c.instance() + "|" + p.optLong("id"), p);
        return p;
    }

    static long userId(Ctx c, String username) throws IOException {
        String key = c.instance() + "|" + username;
        Long cached = USER_IDS.get(key);
        if (cached != null) {
            return cached;
        }
        Up up = c.get("/users", "username", username);
        JSONArray a = up.arr();
        if (a.length() == 0) {
            return -1;
        }
        long id = a.optJSONObject(0).optLong("id");
        USER_IDS.put(key, id);
        return id;
    }

    static long myId(Ctx c) throws IOException {
        String key = c.instance() + "|" + c.request.header("PRIVATE-TOKEN");
        Long cached = MY_IDS.get(key);
        if (cached != null) {
            return cached;
        }
        Up up = c.get("/user");
        if (!up.ok()) {
            return -1;
        }
        long id = up.obj().optLong("id");
        MY_IDS.put(key, id);
        return id;
    }

    static JSONArray mapUsers(JSONArray in) {
        return GitLabMapper.userList(in);
    }

    static Response projects(Ctx c, Up up) {
        if (!up.ok()) {
            return c.error(up);
        }
        JSONArray in = up.arr();
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            out.put(GitLabMapper.project(in.optJSONObject(i)));
        }
        return c.jsonPaged(out, up);
    }

    /** Translates the GitHub repository list options to GitLab project list options. */
    static String[] projectListQuery(Ctx c, String... extra) {
        java.util.List<String> q = new java.util.ArrayList<>();
        for (String s : extra) {
            q.add(s);
        }
        String type = c.q("type");
        if ("owner".equals(type)) {
            q.add("owned");
            q.add("true");
        } else if ("member".equals(type)) {
            q.add("membership");
            q.add("true");
        } else if ("public".equals(type) || "private".equals(type)) {
            q.add("visibility");
            q.add(type);
            q.add("membership");
            q.add("true");
        }
        String sort = c.q("sort");
        String orderBy = "last_activity_at";
        if ("created".equals(sort)) {
            orderBy = "created_at";
        } else if ("full_name".equals(sort)) {
            orderBy = "path";
        } else if ("updated".equals(sort) || "pushed".equals(sort)) {
            orderBy = "last_activity_at";
        }
        q.add("statistics");
        q.add("true");
        q.add("order_by");
        q.add(orderBy);
        String dir = c.q("direction");
        q.add("sort");
        q.add(dir != null ? dir : ("full_name".equals(sort) ? "asc" : "desc"));
        return q.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- users

    private static void registerUsers() {
        route("GET", "user", c -> {
            Up up = c.get("/user");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject me = up.obj();
            // /user lacks the follower counters that /users/:id has.
            Up pub = c.get("/users/" + me.optLong("id"));
            if (pub.ok()) {
                JSONObject p = pub.obj();
                put(me, "followers", p.optInt("followers"));
                put(me, "following", p.optInt("following"));
            }
            return c.json(GitLabMapper.user(me));
        });
        route("GET", "users/{username}", c -> {
            String name = c.p("username");
            Up lookup = c.get("/users", "username", name);
            if (lookup.code == 401 || lookup.code == 403) {
                return c.error(lookup);
            }
            JSONArray found = lookup.arr();
            if (found.length() > 0) {
                long id = found.optJSONObject(0).optLong("id");
                Up full = c.get("/users/" + id);
                JSONObject u = full.ok() ? full.obj() : found.optJSONObject(0);
                return c.json(GitLabMapper.user(u));
            }
            Up g = c.get("/groups/" + enc(name));
            if (g.ok()) {
                return c.json(GitLabMapper.group(g.obj()));
            }
            return c.notFound("User not found");
        });
        route("GET", "users", c -> {
            Up up = c.getPaged("/users", "active", "true");
            return up.ok() ? c.jsonPaged(mapUsers(up.arr()), up) : c.error(up);
        });
        route("PATCH", "user", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            copy(in, body, "name", "name");
            copy(in, body, "bio", "bio");
            copy(in, body, "location", "location");
            copy(in, body, "company", "organization");
            copy(in, body, "blog", "website_url");
            copy(in, body, "email", "public_email");
            Up up = c.call("PUT", "/user", body);
            if (!up.ok()) {
                // PUT /user needs admin rights; only fail when something was to be changed.
                return c.error(up);
            }
            return c.json(GitLabMapper.user(up.obj()));
        });
        route("GET", "users/{username}/followers", c -> followList(c, "followers"));
        route("GET", "users/{username}/following", c -> followList(c, "following"));
        route("GET", "user/followers", c -> {
            long me = myId(c);
            Up up = c.getPaged("/users/" + me + "/followers");
            return up.ok() ? c.jsonPaged(mapUsers(up.arr()), up) : c.error(up);
        });
        route("GET", "user/following", c -> {
            long me = myId(c);
            Up up = c.getPaged("/users/" + me + "/following");
            return up.ok() ? c.jsonPaged(mapUsers(up.arr()), up) : c.error(up);
        });
        route("GET", "user/following/{username}", c -> {
            long me = myId(c);
            long target = userId(c, c.p("username"));
            if (target < 0) {
                return c.notFound("User not found");
            }
            for (int page = 1; page <= 20; page++) {
                Up up = c.get("/users/" + me + "/following", "per_page", "100", "page",
                        String.valueOf(page));
                JSONArray a = up.arr();
                for (int i = 0; i < a.length(); i++) {
                    if (a.optJSONObject(i).optLong("id") == target) {
                        return c.empty(204);
                    }
                }
                if (a.length() < 100) {
                    break;
                }
            }
            return c.notFound("Not following");
        });
        route("PUT", "user/following/{username}", c -> {
            long target = userId(c, c.p("username"));
            Up up = c.call("POST", "/users/" + target + "/follow", null);
            return up.ok() || up.code == 304 ? c.empty(204) : c.error(up);
        });
        route("DELETE", "user/following/{username}", c -> {
            long target = userId(c, c.p("username"));
            Up up = c.call("POST", "/users/" + target + "/unfollow", null);
            return up.ok() || up.code == 304 ? c.empty(204) : c.error(up);
        });
    }

    private static Response followList(Ctx c, String kind) throws IOException {
        long id = userId(c, c.p("username"));
        if (id < 0) {
            return c.notFound("User not found");
        }
        Up up = c.getPaged("/users/" + id + "/" + kind);
        return up.ok() ? c.jsonPaged(mapUsers(up.arr()), up) : c.error(up);
    }

    private static void copy(JSONObject from, JSONObject to, String key, String toKey) {
        if (from.has(key) && !from.isNull(key)) {
            put(to, toKey, from.opt(key));
        }
    }

    // ---------------------------------------------------------------- groups

    private static void registerGroups() {
        route("GET", "orgs/{org}", c -> {
            Up up = c.get("/groups/" + c.raw("org"));
            return up.ok() ? c.json(GitLabMapper.group(up.obj())) : c.error(up);
        });
        route("PATCH", "orgs/{org}", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            copy(in, body, "name", "name");
            copy(in, body, "description", "description");
            Up up = c.call("PUT", "/groups/" + c.raw("org"), body);
            return up.ok() ? c.json(GitLabMapper.group(up.obj())) : c.error(up);
        });
        route("GET", "user/orgs", c -> groupList(c, "/groups", "min_access_level", "10"));
        route("GET", "organizations", c -> groupList(c, "/groups", "all_available", "true"));
        route("GET", "users/{username}/orgs", c -> c.json(new JSONArray()));
        route("GET", "orgs/{org}/members", c -> groupMembers(c));
        route("GET", "orgs/{org}/public_members", c -> groupMembers(c));
        route("GET", "orgs/{org}/members/{username}", c -> {
            long id = userId(c, c.p("username"));
            Up up = c.get("/groups/" + c.raw("org") + "/members/all/" + id);
            return up.ok() ? c.empty(204) : c.notFound("Not a member");
        });
        route("GET", "orgs/{org}/public_members/{username}", c -> {
            long id = userId(c, c.p("username"));
            Up up = c.get("/groups/" + c.raw("org") + "/members/all/" + id);
            return up.ok() ? c.empty(204) : c.notFound("Not a member");
        });
        route("GET", "orgs/{org}/repos", c -> projects(c, c.getPaged(
                "/groups/" + c.raw("org") + "/projects",
                projectListQuery(c, "include_subgroups", "false"))));
        route("GET", "orgs/{org}/teams", c -> c.json(new JSONArray()));
        route("GET", "user/teams", c -> c.json(new JSONArray()));
        route("GET", "user/memberships/orgs", c -> c.json(new JSONArray()));
        route("GET", "orgs/{org}/issue-types", c -> c.json(new JSONArray()));
        route("GET", "orgs/{org}/issues", c -> {
            Up up = c.getPaged("/groups/" + c.raw("org") + "/issues",
                    GitLabIssueRoutes.issueListQuery(c));
            return up.ok() ? c.jsonPaged(GitLabIssueRoutes.issuesWithRepo(c, up.arr()), up)
                    : c.error(up);
        });
    }

    private static Response groupList(Ctx c, String path, String... query) throws IOException {
        Up up = c.getPaged(path, query);
        if (!up.ok()) {
            return c.error(up);
        }
        JSONArray in = up.arr();
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            out.put(GitLabMapper.group(in.optJSONObject(i)));
        }
        return c.jsonPaged(out, up);
    }

    private static Response groupMembers(Ctx c) throws IOException {
        Up up = c.getPaged("/groups/" + c.raw("org") + "/members");
        return up.ok() ? c.jsonPaged(mapUsers(up.arr()), up) : c.error(up);
    }

    // ---------------------------------------------------------------- repositories

    private static void registerRepositories() {
        route("GET", "user/repos", c -> projects(c, c.getPaged("/projects",
                projectListQuery(c, "membership", "true", "simple", "false"))));
        route("GET", "repositories", c -> projects(c, c.getPaged("/projects",
                projectListQuery(c, "visibility", "public"))));
        route("GET", "users/{username}/repos", c -> projects(c, c.getPaged(
                "/users/" + c.raw("username") + "/projects", projectListQuery(c))));
        route("GET", "repos/{owner}/{repo}", c -> {
            Up up = c.get("/projects/" + c.project(), "license", "true");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject p = up.obj();
            if (!p.has("open_issues_count") && p.optBoolean("issues_enabled", true)) {
                // Anonymous requests get a reduced project without the issue counter.
                Up issues = c.get("/projects/" + c.project() + "/issues", "state", "opened",
                        "per_page", "1");
                if (issues.ok() && issues.total() >= 0) {
                    put(p, "open_issues_count", issues.total());
                }
            }
            PROJECTS.put(c.instance() + "|" + p.optLong("id"), p);
            return c.json(GitLabMapper.project(p));
        });
        route("POST", "user/repos", c -> {
            JSONObject in = c.bodyJson();
            Up up = c.call("POST", "/projects", createProjectBody(in));
            return up.ok() ? c.json(GitLabMapper.project(up.obj())) : c.error(up);
        });
        route("POST", "orgs/{org}/repos", c -> {
            Up group = c.get("/groups/" + c.raw("org"));
            JSONObject body = createProjectBody(c.bodyJson());
            put(body, "namespace_id", group.obj().optLong("id"));
            Up up = c.call("POST", "/projects", body);
            return up.ok() ? c.json(GitLabMapper.project(up.obj())) : c.error(up);
        });
        route("PATCH", "repos/{owner}/{repo}", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            copy(in, body, "name", "name");
            copy(in, body, "description", "description");
            copy(in, body, "default_branch", "default_branch");
            copy(in, body, "has_issues", "issues_enabled");
            copy(in, body, "has_wiki", "wiki_enabled");
            if (in.has("private")) {
                put(body, "visibility", in.optBoolean("private") ? "private" : "public");
            }
            Up up = c.call("PUT", "/projects/" + c.project(), body);
            if (up.ok() && in.has("archived")) {
                up = c.call("POST", "/projects/" + c.project()
                        + (in.optBoolean("archived") ? "/archive" : "/unarchive"), null);
            }
            PROJECTS.clear();
            return up.ok() ? c.json(GitLabMapper.project(up.obj())) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}", c -> {
            Up up = c.call("DELETE", "/projects/" + c.project(), null);
            PROJECTS.clear();
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/contributors", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/repository/contributors");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject ct = in.optJSONObject(i);
                JSONObject u = newObj();
                put(u, "login", str(ct, "name"));
                put(u, "id", 0);
                put(u, "avatar_url", null);
                put(u, "type", "User");
                put(u, "contributions", ct.optInt("commits"));
                out.put(u);
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/languages", c -> {
            Up up = c.get("/projects/" + c.project() + "/languages");
            JSONObject in = up.obj();
            JSONObject out = newObj();
            java.util.Iterator<String> keys = in.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                put(out, k, (long) (in.optDouble(k) * 1000));
            }
            return c.json(out);
        });
        route("GET", "repos/{owner}/{repo}/teams", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/forks", c -> projects(c,
                c.getPaged("/projects/" + c.project() + "/forks")));
        route("POST", "repos/{owner}/{repo}/forks", c -> {
            Up up = c.call("POST", "/projects/" + c.project() + "/fork", null);
            PROJECTS.clear();
            return up.ok() ? c.reply(202, GitLabMapper.project(up.obj()).toString())
                    : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/stargazers", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/starrers");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                out.put(GitLabMapper.user(in.optJSONObject(i).optJSONObject("user")));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/subscribers", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/assignees", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/users");
            return up.ok() ? c.jsonPaged(mapUsers(up.arr()), up) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/assignees/{assignee}", c -> {
            Up up = c.get("/projects/" + c.project() + "/users", "search", c.p("assignee"));
            JSONArray a = up.arr();
            for (int i = 0; i < a.length(); i++) {
                if (c.p("assignee").equalsIgnoreCase(a.optJSONObject(i).optString("username"))) {
                    return c.empty(204);
                }
            }
            return c.notFound("Not assignable");
        });
        route("GET", "repos/{owner}/{repo}/collaborators", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/members/all");
            return up.ok() ? c.jsonPaged(mapUsers(up.arr()), up) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/collaborators/{username}", c -> {
            long id = userId(c, c.p("username"));
            Up up = c.get("/projects/" + c.project() + "/members/all/" + id);
            return up.ok() ? c.empty(204) : c.notFound("Not a collaborator");
        });
        route("PUT", "repos/{owner}/{repo}/collaborators/{username}", c -> {
            long id = userId(c, c.p("username"));
            JSONObject body = newObj();
            put(body, "user_id", id);
            put(body, "access_level", 30);
            Up up = c.call("POST", "/projects/" + c.project() + "/members", body);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/collaborators/{username}", c -> {
            long id = userId(c, c.p("username"));
            Up up = c.call("DELETE", "/projects/" + c.project() + "/members/" + id, null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/pages", c -> c.notFound("No pages"));
        route("GET", "repos/{owner}/{repo}/deployments", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/keys", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/deploy_keys");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject k = in.optJSONObject(i);
                JSONObject o = newObj();
                put(o, "id", k.optLong("id"));
                put(o, "title", str(k, "title"));
                put(o, "key", str(k, "key"));
                put(o, "read_only", !k.optBoolean("can_push"));
                put(o, "created_at", GitLabMapper.time(str(k, "created_at")));
                out.put(o);
            }
            return c.jsonPaged(out, up);
        });
        route("POST", "repos/{owner}/{repo}/keys", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            put(body, "title", str(in, "title"));
            put(body, "key", str(in, "key"));
            put(body, "can_push", !in.optBoolean("read_only", true));
            Up up = c.call("POST", "/projects/" + c.project() + "/deploy_keys", body);
            return up.ok() ? c.json(up.obj()) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/keys/{id}", c -> {
            Up up = c.call("DELETE", "/projects/" + c.project() + "/deploy_keys/" + c.p("id"),
                    null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/subscription", c -> {
            Up up = c.get("/projects/" + c.project() + "/notification_settings");
            String level = up.ok() ? str(up.obj(), "level") : "global";
            JSONObject o = newObj();
            put(o, "subscribed", "watch".equals(level) || "participating".equals(level)
                    || "custom".equals(level) || "mention".equals(level));
            put(o, "ignored", "disabled".equals(level));
            return c.json(o);
        });
        route("PUT", "repos/{owner}/{repo}/subscription", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            put(body, "level", in.optBoolean("ignored") ? "disabled"
                    : in.optBoolean("subscribed") ? "watch" : "global");
            Up up = c.call("PUT", "/projects/" + c.project() + "/notification_settings", body);
            JSONObject o = newObj();
            put(o, "subscribed", "watch".equals(str(up.obj(), "level")));
            put(o, "ignored", "disabled".equals(str(up.obj(), "level")));
            return up.ok() ? c.json(o) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/subscription", c -> {
            JSONObject body = newObj();
            put(body, "level", "global");
            Up up = c.call("PUT", "/projects/" + c.project() + "/notification_settings", body);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "user/subscriptions", c -> c.json(new JSONArray()));
        route("GET", "users/{username}/subscriptions", c -> c.json(new JSONArray()));
    }

    private static JSONObject createProjectBody(JSONObject in) {
        JSONObject body = newObj();
        put(body, "name", str(in, "name"));
        put(body, "path", str(in, "name"));
        copy(in, body, "description", "description");
        put(body, "visibility", in.optBoolean("private") ? "private" : "public");
        if (in.optBoolean("auto_init")) {
            put(body, "initialize_with_readme", true);
        }
        return body;
    }

    // ---------------------------------------------------------------- search

    private static void registerSearch() {
        route("GET", "search/repositories", c -> {
            // GitHub qualifiers such as "fork:true" or "user:x" mean nothing to GitLab search.
            String q = c.q("q") == null ? null
                    : c.q("q").replaceAll("(^|\\s)[A-Za-z-]+:\\S*", " ").trim();
            String sort = c.q("sort");
            String order = "stars".equals(sort) ? "star_count" : "forks".equals(sort)
                    ? "forks_count" : "updated".equals(sort) ? "last_activity_at" : "similarity";
            Up up;
            if ("similarity".equals(order) && q != null && !q.isEmpty()) {
                // Ordering by similarity and searching namespaces makes gitlab.com time out
                // (500, or an empty list), the plain search answers in about a second.
                up = c.getPaged("/projects", "search", q);
            } else {
                up = c.getPaged("/projects", "search", q == null ? "" : q, "order_by",
                        "similarity".equals(order) ? "last_activity_at" : order,
                        "sort", "asc".equals(c.q("order")) ? "asc" : "desc");
            }
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray items = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                items.put(GitLabMapper.project(in.optJSONObject(i)));
            }
            return c.withPagination(c.json(searchResult(items, up)), up);
        });
        route("GET", "search/users", c -> {
            String q = c.q("q");
            Up up = c.getPaged("/users", "search", q == null ? "" : q);
            JSONArray items = up.ok() ? mapUsers(up.arr()) : new JSONArray();
            if (c.page() == 1) {
                Up groups = c.get("/groups", "search", q == null ? "" : q, "per_page", "10");
                JSONArray g = groups.arr();
                for (int i = 0; i < g.length(); i++) {
                    items.put(GitLabMapper.group(g.optJSONObject(i)));
                }
            }
            return c.withPagination(c.json(searchResult(items, up)), up);
        });
        route("GET", "search/code", c -> c.json(searchResult(new JSONArray(), null)));
        route("GET", "search/issues", c -> GitLabIssueRoutes.searchIssues(c));
    }

    /** Commit comment id to {sha, discussion id}, needed to edit or delete it later. */
    private static final Map<Long, String[]> COMMIT_NOTES = new HashMap<>();

    private static JSONObject commitComment(JSONObject n, String sha, String discussionId) {
        long id = n.optLong("id");
        COMMIT_NOTES.put(id, new String[] {sha, discussionId});
        JSONObject o = newObj();
        put(o, "id", id);
        put(o, "body", str(n, "body"));
        put(o, "user", GitLabMapper.user(n.optJSONObject("author")));
        JSONObject pos = n.optJSONObject("position");
        if (pos != null && !pos.isNull("new_path")) {
            put(o, "path", str(pos, "new_path"));
            put(o, "line", pos.optInt("new_line"));
            put(o, "position", pos.optInt("new_line"));
        }
        put(o, "commit_id", sha);
        put(o, "created_at", GitLabMapper.time(str(n, "created_at")));
        put(o, "updated_at", GitLabMapper.time(str(n, "updated_at")));
        put(o, "author_association", "NONE");
        put(o, "reactions", GitLabMapper.reactionCounts(newObj()));
        return o;
    }

    static JSONObject searchResult(JSONArray items, Up up) {
        JSONObject o = newObj();
        int total = up != null && up.total() >= 0 ? up.total() : items.length();
        put(o, "total_count", total);
        put(o, "incomplete_results", false);
        put(o, "items", items);
        return o;
    }

    // ---------------------------------------------------------------- starring

    private static void registerStarring() {
        route("GET", "user/starred", c -> starred(c, myId(c)));
        route("GET", "users/{username}/starred", c -> {
            long id = userId(c, c.p("username"));
            return id < 0 ? c.notFound("User not found") : starred(c, id);
        });
        route("GET", "user/starred/{owner}/{repo}", c -> {
            long me = myId(c);
            JSONObject p = project(c, c.p("owner") + "/" + c.p("repo"));
            if (p == null) {
                return c.notFound("Not found");
            }
            Up up = c.get("/users/" + me + "/starred_projects", "search", p.optString("path"),
                    "simple", "true");
            JSONArray a = up.arr();
            for (int i = 0; i < a.length(); i++) {
                if (a.optJSONObject(i).optLong("id") == p.optLong("id")) {
                    return c.empty(204);
                }
            }
            return c.notFound("Not starred");
        });
        route("PUT", "user/starred/{owner}/{repo}", c -> {
            Up up = c.call("POST", "/projects/" + c.project() + "/star", null);
            PROJECTS.clear();
            return up.ok() || up.code == 304 ? c.empty(204) : c.error(up);
        });
        route("DELETE", "user/starred/{owner}/{repo}", c -> {
            Up up = c.call("POST", "/projects/" + c.project() + "/unstar", null);
            PROJECTS.clear();
            return up.ok() || up.code == 304 ? c.empty(204) : c.error(up);
        });
    }

    private static Response starred(Ctx c, long userId) throws IOException {
        return projects(c, c.getPaged("/users/" + userId + "/starred_projects",
                projectListQuery(c)));
    }

    // ---------------------------------------------------------------- contents

    private static final Pattern README = Pattern.compile("(?i)^readme(\\.(md|markdown|rst|txt|adoc|org))?$");

    private static void registerContents() {
        route("GET", "repos/{owner}/{repo}/readme", c -> readme(c));
        route("HEAD", "repos/{owner}/{repo}/readme", c -> {
            JSONObject f = findReadme(c);
            return f != null ? c.empty(200) : c.notFound("No readme");
        });
        route("GET", "repos/{owner}/{repo}/contents/{path*}", c -> contents(c));
        route("GET", "repos/{owner}/{repo}/contents", c -> contents(c));
        route("PUT", "repos/{owner}/{repo}/contents/{path*}", c -> writeFile(c, false));
        route("DELETE", "repos/{owner}/{repo}/contents/{path*}", c -> writeFile(c, true));
    }

    private static String refOf(Ctx c) throws IOException {
        String ref = c.q("ref");
        if (ref != null && !ref.isEmpty()) {
            return ref;
        }
        JSONObject p = project(c, c.p("owner") + "/" + c.p("repo"));
        return p != null ? p.optString("default_branch", "HEAD") : "HEAD";
    }

    private static JSONObject findReadme(Ctx c) throws IOException {
        Up up = c.get("/projects/" + c.project() + "/repository/tree", "ref", refOf(c),
                "per_page", "100");
        JSONArray a = up.arr();
        JSONObject best = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if ("blob".equals(str(e, "type")) && README.matcher(str(e, "name")).matches()) {
                if (best == null || str(e, "name").toLowerCase(Locale.US).endsWith(".md")) {
                    best = e;
                }
            }
        }
        return best;
    }

    private static Response readme(Ctx c) throws IOException {
        JSONObject f = findReadme(c);
        if (f == null) {
            return c.notFound("No readme");
        }
        return fileResponse(c, str(f, "path"), refOf(c));
    }

    private static Response contents(Ctx c) throws IOException {
        String path = c.p("path");
        String ref = refOf(c);
        if (path == null) {
            path = "";
        }
        path = path.replaceFirst("^/+", "");
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // Raw requests always target files.
        String accept = c.accept();
        boolean wantsDir = !accept.contains("raw") && !accept.contains("html");
        if (path.isEmpty() || wantsDir) {
            Up tree = c.getPaged("/projects/" + c.project() + "/repository/tree", "ref", ref,
                    "path", path, "per_page", "100");
            if (tree.ok() && tree.arr().length() > 0) {
                JSONArray in = tree.arr();
                JSONArray out = new JSONArray();
                Map<String, Integer> sizes = blobSizes(c, ref, in);
                String web = c.instance() + "/" + c.p("owner") + "/" + c.p("repo");
                for (int i = 0; i < in.length(); i++) {
                    JSONObject e = in.optJSONObject(i);
                    boolean dir = "tree".equals(str(e, "type"));
                    JSONObject o = newObj();
                    put(o, "name", str(e, "name"));
                    put(o, "path", str(e, "path"));
                    put(o, "sha", str(e, "id"));
                    put(o, "type", dir ? "dir" : "file");
                    Integer size = sizes.get(str(e, "path"));
                    put(o, "size", size != null ? size : 0);
                    put(o, "html_url", web + "/-/" + (dir ? "tree/" : "blob/") + ref + "/"
                            + str(e, "path"));
                    put(o, "download_url", dir ? null : web + "/-/raw/" + ref + "/"
                            + str(e, "path"));
                    out.put(o);
                }
                return c.jsonPaged(out, tree);
            }
            if (path.isEmpty()) {
                return tree.ok() ? c.json(new JSONArray()) : c.error(tree);
            }
        }
        return fileResponse(c, path, ref);
    }

    /** The tree API has no file sizes, GraphQL does. Failures only mean that sizes stay unknown. */
    private static Map<String, Integer> blobSizes(Ctx c, String ref, JSONArray tree) {
        Map<String, Integer> sizes = new HashMap<>();
        try {
            JSONArray paths = new JSONArray();
            for (int i = 0; i < tree.length(); i++) {
                JSONObject e = tree.optJSONObject(i);
                if (e != null && "blob".equals(str(e, "type"))) {
                    paths.put(str(e, "path"));
                }
            }
            if (paths.length() == 0) {
                return sizes;
            }
            JSONObject body = newObj();
            put(body, "query", "query($p: ID!, $paths: [String!]!, $ref: String) {"
                    + " project(fullPath: $p) { repository { blobs(paths: $paths, ref: $ref) {"
                    + " nodes { path rawSize } } } } }");
            JSONObject vars = newObj();
            put(vars, "p", c.p("owner") + "/" + c.p("repo"));
            put(vars, "paths", paths);
            put(vars, "ref", ref);
            put(body, "variables", vars);
            Up up = c.call("POST", "/graphql", body);
            if (!up.ok()) {
                return sizes;
            }
            JSONObject data = up.obj().optJSONObject("data");
            JSONObject project = data != null ? data.optJSONObject("project") : null;
            JSONObject repo = project != null ? project.optJSONObject("repository") : null;
            JSONObject blobs = repo != null ? repo.optJSONObject("blobs") : null;
            JSONArray nodes = blobs != null ? blobs.optJSONArray("nodes") : null;
            for (int i = 0; nodes != null && i < nodes.length(); i++) {
                JSONObject n = nodes.optJSONObject(i);
                sizes.put(str(n, "path"), (int) Math.min(Integer.MAX_VALUE,
                        Long.parseLong(n.optString("rawSize", "0"))));
            }
        } catch (IOException | NumberFormatException ignored) {
            // keep what we have
        }
        return sizes;
    }

    private static Response fileResponse(Ctx c, String path, String ref) throws IOException {
        String filePath = "/projects/" + c.project() + "/repository/files/" + enc(path);
        String accept = c.accept();
        if (accept.contains("raw")) {
            Up raw = c.get(filePath + "/raw", "ref", ref);
            if (!raw.ok()) {
                return c.error(raw);
            }
            return c.raw(raw, "application/octet-stream");
        }
        if (accept.contains("html")) {
            Up raw = c.get(filePath + "/raw", "ref", ref);
            if (!raw.ok()) {
                return c.error(raw);
            }
            return renderMarkdown(c, raw.body, path);
        }
        Up up = c.get(filePath, "ref", ref);
        if (!up.ok()) {
            return c.error(up);
        }
        JSONObject f = up.obj();
        JSONObject o = newObj();
        String web = c.instance() + "/" + c.p("owner") + "/" + c.p("repo");
        put(o, "name", str(f, "file_name"));
        put(o, "path", str(f, "file_path"));
        put(o, "sha", str(f, "blob_id"));
        put(o, "size", f.optInt("size"));
        put(o, "type", "file");
        put(o, "encoding", str(f, "encoding"));
        put(o, "content", str(f, "content"));
        put(o, "html_url", web + "/-/blob/" + ref + "/" + path);
        put(o, "download_url", web + "/-/raw/" + ref + "/" + path);
        return c.json(o);
    }

    static Response renderMarkdown(Ctx c, String text, String path) throws IOException {
        String html = GitLabHtml.render(c, text, c.p("owner") + "/" + c.p("repo"));
        return c.raw(new Up(200, html.getBytes(StandardCharsets.UTF_8), okhttp3.Headers.of()),
                "text/html; charset=utf-8");
    }

    private static Response writeFile(Ctx c, boolean delete) throws IOException {
        JSONObject in = c.bodyJson();
        String path = c.p("path");
        JSONObject body = newObj();
        put(body, "branch", in.has("branch") ? str(in, "branch") : refOf(c));
        put(body, "commit_message", str(in, "message"));
        String filePath = "/projects/" + c.project() + "/repository/files/" + enc(path);
        Up up;
        if (delete) {
            up = c.call("DELETE", filePath, body);
        } else {
            put(body, "content", str(in, "content"));
            put(body, "encoding", "base64");
            boolean exists = in.has("sha") && !in.isNull("sha");
            up = c.call(exists ? "PUT" : "POST", filePath, body);
        }
        if (!up.ok()) {
            return c.error(up);
        }
        JSONObject res = newObj();
        JSONObject content = newObj();
        put(content, "path", path);
        put(res, "content", delete ? null : content);
        put(res, "commit", newObj());
        return c.json(res);
    }

    // ---------------------------------------------------------------- commits

    private static void registerCommits() {
        route("GET", "repos/{owner}/{repo}/commits", c -> {
            String sha = c.q("sha");
            if (sha == null) {
                sha = c.q("ref_name");
            }
            Up up = c.getPaged("/projects/" + c.project() + "/repository/commits",
                    "ref_name", sha, "path", c.q("path"), "since", c.q("since"),
                    "until", c.q("until"), "author", null, "with_stats", "false");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                out.put(GitLabMapper.commit(in.optJSONObject(i), c.project(), c.instance()));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/commits/{sha}", c -> {
            String sha = c.p("sha");
            Up up = c.get("/projects/" + c.project() + "/repository/commits/" + enc(sha),
                    "stats", "true");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject commit = GitLabMapper.commit(up.obj(), c.project(), c.instance());
            Up diff = c.get("/projects/" + c.project() + "/repository/commits/" + enc(sha)
                    + "/diff", "per_page", "100");
            JSONArray files = new JSONArray();
            JSONArray d = diff.arr();
            String web = c.p("owner") + "/" + c.p("repo");
            for (int i = 0; i < d.length(); i++) {
                files.put(GitLabMapper.diffFile(d.optJSONObject(i), c.instance(), web,
                        up.obj().optString("id")));
            }
            put(commit, "files", files);
            return c.json(commit);
        });
        route("GET", "repos/{owner}/{repo}/compare/{spec*}", c -> {
            String spec = c.p("spec");
            int idx = spec.indexOf("...");
            String base = idx > 0 ? spec.substring(0, idx) : spec;
            String head = idx > 0 ? spec.substring(idx + 3) : "HEAD";
            Up up = c.get("/projects/" + c.project() + "/repository/compare", "from", base,
                    "to", head);
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject in = up.obj();
            JSONObject o = newObj();
            JSONArray commits = new JSONArray();
            JSONArray cin = in.optJSONArray("commits");
            for (int i = 0; cin != null && i < cin.length(); i++) {
                commits.put(GitLabMapper.commit(cin.optJSONObject(i), c.project(), c.instance()));
            }
            JSONArray files = new JSONArray();
            JSONArray din = in.optJSONArray("diffs");
            String web = c.p("owner") + "/" + c.p("repo");
            for (int i = 0; din != null && i < din.length(); i++) {
                files.put(GitLabMapper.diffFile(din.optJSONObject(i), c.instance(), web, head));
            }
            put(o, "commits", commits);
            put(o, "files", files);
            put(o, "total_commits", commits.length());
            put(o, "ahead_by", commits.length());
            put(o, "behind_by", 0);
            put(o, "status", commits.length() == 0 ? "identical" : "ahead");
            if (in.optJSONObject("commit") != null) {
                put(o, "merge_base_commit", GitLabMapper.commit(in.optJSONObject("commit"),
                        c.project(), c.instance()));
            }
            put(o, "html_url", c.instance() + "/" + web + "/-/compare/" + base + "..." + head);
            return c.json(o);
        });
        route("GET", "repos/{owner}/{repo}/commits/{sha}/comments", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/repository/commits/"
                    + enc(c.p("sha")) + "/discussions");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject d = in.optJSONObject(i);
                JSONArray notes = d.optJSONArray("notes");
                for (int j = 0; notes != null && j < notes.length(); j++) {
                    JSONObject n = notes.optJSONObject(j);
                    if (n.optBoolean("system")) {
                        continue;
                    }
                    out.put(commitComment(n, c.p("sha"), str(d, "id")));
                }
            }
            GitLabHtml.attach(c, out, c.p("owner") + "/" + c.p("repo"));
            return c.jsonPaged(out, up);
        });
        route("POST", "repos/{owner}/{repo}/commits/{sha}/comments", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            put(body, "body", str(in, "body"));
            Up up;
            if (in.has("path") && !in.isNull("path")) {
                // Inline comments only exist in the legacy endpoint, which has no note ids.
                JSONObject legacy = newObj();
                put(legacy, "note", str(in, "body"));
                put(legacy, "path", str(in, "path"));
                put(legacy, "line", in.optInt("line", in.optInt("position", 1)));
                put(legacy, "line_type", "new");
                up = c.call("POST", "/projects/" + c.project() + "/repository/commits/"
                        + enc(c.p("sha")) + "/comments", legacy);
                if (!up.ok()) {
                    return c.error(up);
                }
                JSONObject n = up.obj();
                JSONObject o = newObj();
                put(o, "id", System.currentTimeMillis());
                put(o, "body", str(n, "note"));
                put(o, "user", GitLabMapper.user(n.optJSONObject("author")));
                put(o, "path", str(in, "path"));
                put(o, "commit_id", c.p("sha"));
                put(o, "created_at", GitLabMapper.time(str(n, "created_at")));
                put(o, "updated_at", GitLabMapper.time(str(n, "created_at")));
                put(o, "author_association", "NONE");
                put(o, "reactions", GitLabMapper.reactionCounts(newObj()));
                GitLabHtml.attach(c, o, c.p("owner") + "/" + c.p("repo"));
                return c.json(o);
            }
            up = c.call("POST", "/projects/" + c.project() + "/repository/commits/"
                    + enc(c.p("sha")) + "/discussions", body);
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject d = up.obj();
            JSONArray notes = d.optJSONArray("notes");
            JSONObject o = commitComment(notes.optJSONObject(0), c.p("sha"), str(d, "id"));
            GitLabHtml.attach(c, o, c.p("owner") + "/" + c.p("repo"));
            return c.json(o);
        });
        route("PATCH", "repos/{owner}/{repo}/comments/{id}", c -> {
            String[] where = COMMIT_NOTES.get(Long.parseLong(c.raw("id")));
            if (where == null) {
                return c.notFound("Reload the comments first.");
            }
            JSONObject body = newObj();
            put(body, "body", str(c.bodyJson(), "body"));
            Up up = c.call("PUT", "/projects/" + c.project() + "/repository/commits/"
                    + enc(where[0]) + "/discussions/" + where[1] + "/notes/" + c.raw("id"), body);
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject o = commitComment(up.obj(), where[0], where[1]);
            GitLabHtml.attach(c, o, c.p("owner") + "/" + c.p("repo"));
            return c.json(o);
        });
        route("DELETE", "repos/{owner}/{repo}/comments/{id}", c -> {
            String[] where = COMMIT_NOTES.get(Long.parseLong(c.raw("id")));
            if (where == null) {
                return c.notFound("Reload the comments first.");
            }
            Up up = c.call("DELETE", "/projects/" + c.project() + "/repository/commits/"
                    + enc(where[0]) + "/discussions/" + where[1] + "/notes/" + c.raw("id"), null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/comments", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/commits/{sha}/statuses", c -> statuses(c));
        route("GET", "repos/{owner}/{repo}/commits/{sha}/status", c -> statuses(c));
        route("GET", "repos/{owner}/{repo}/commits/{ref}/check-runs", c -> {
            JSONObject o = newObj();
            put(o, "total_count", 0);
            put(o, "check_runs", new JSONArray());
            return c.json(o);
        });
        route("POST", "repos/{owner}/{repo}/statuses/{sha}", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            String state = str(in, "state");
            put(body, "state", "failure".equals(state) ? "failed" : "error".equals(state)
                    ? "failed" : state);
            put(body, "name", str(in, "context"));
            put(body, "description", str(in, "description"));
            put(body, "target_url", str(in, "target_url"));
            Up up = c.call("POST", "/projects/" + c.project() + "/statuses/" + c.p("sha"), body);
            return up.ok() ? c.json(newObj()) : c.error(up);
        });
    }

    private static Response statuses(Ctx c) throws IOException {
        Up up = c.getPaged("/projects/" + c.project() + "/repository/commits/"
                + enc(c.p("sha")) + "/statuses");
        if (!up.ok()) {
            return c.error(up);
        }
        JSONArray in = up.arr();
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.optJSONObject(i);
            String state = GitLabMapper.statusState(str(s, "status"));
            if ("skipped".equals(state)) {
                continue;
            }
            JSONObject o = newObj();
            put(o, "id", s.optLong("id"));
            put(o, "state", state);
            put(o, "context", str(s, "name"));
            put(o, "description", str(s, "description"));
            put(o, "target_url", str(s, "target_url"));
            put(o, "created_at", GitLabMapper.time(str(s, "created_at")));
            put(o, "updated_at", GitLabMapper.time(str(s, "finished_at") != null
                    ? str(s, "finished_at") : str(s, "created_at")));
            put(o, "creator", GitLabMapper.user(s.optJSONObject("author")));
            out.put(o);
        }
        return c.jsonPaged(out, up);
    }

    // ---------------------------------------------------------------- branches and tags

    private static void registerRefs() {
        route("GET", "repos/{owner}/{repo}/branches", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/repository/branches");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                out.put(branch(in.optJSONObject(i)));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/branches/{branch*}", c -> {
            Up up = c.get("/projects/" + c.project() + "/repository/branches/"
                    + enc(c.p("branch")));
            return up.ok() ? c.json(branch(up.obj())) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/branches/{branch*}", c -> {
            Up up = c.call("DELETE", "/projects/" + c.project() + "/repository/branches/"
                    + enc(c.p("branch")), null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/tags", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/repository/tags");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject t = in.optJSONObject(i);
                JSONObject o = newObj();
                put(o, "name", str(t, "name"));
                JSONObject commit = newObj();
                put(commit, "sha", t.optJSONObject("commit") != null
                        ? t.optJSONObject("commit").optString("id") : str(t, "target"));
                put(o, "commit", commit);
                String web = c.instance() + "/" + c.p("owner") + "/" + c.p("repo");
                put(o, "zipball_url", web + "/-/archive/" + str(t, "name") + "/archive.zip");
                put(o, "tarball_url", web + "/-/archive/" + str(t, "name") + "/archive.tar.gz");
                out.put(o);
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/git/refs", c -> refs(c, "heads", "tags"));
        route("GET", "repos/{owner}/{repo}/git/refs/{type}", c -> refs(c, c.p("type")));
        route("GET", "repos/{owner}/{repo}/git/matching-refs/{ref*}", c -> {
            String ref = c.p("ref");
            Up up = c.getPaged("/projects/" + c.project() + "/repository/"
                    + (ref.startsWith("tags") ? "tags" : "branches"));
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            String name = ref.replaceFirst("^(heads|tags)/?", "");
            for (int i = 0; i < in.length(); i++) {
                JSONObject b = in.optJSONObject(i);
                if (b.optString("name").startsWith(name)) {
                    out.put(refObject(ref.startsWith("tags") ? "tags" : "heads",
                            b.optString("name"), commitSha(b)));
                }
            }
            return c.json(out);
        });
        route("GET", "repos/{owner}/{repo}/git/refs/heads/{branch*}", c -> {
            Up up = c.get("/projects/" + c.project() + "/repository/branches/"
                    + enc(c.p("branch")));
            return up.ok() ? c.json(refObject("heads", up.obj().optString("name"),
                    commitSha(up.obj()))) : c.error(up);
        });
        route("POST", "repos/{owner}/{repo}/git/refs", c -> {
            JSONObject in = c.bodyJson();
            String ref = str(in, "ref");
            boolean tag = ref != null && ref.startsWith("refs/tags/");
            String name = ref == null ? "" : ref.replaceFirst("^refs/(heads|tags)/", "");
            Up up = c.call("POST", "/projects/" + c.project() + "/repository/"
                    + (tag ? "tags" : "branches"), null, tag ? "tag_name" : "branch", name,
                    "ref", str(in, "sha"));
            return up.ok() ? c.json(refObject(tag ? "tags" : "heads", name, str(in, "sha")))
                    : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/git/refs/{kind}/{name*}", c -> {
            boolean tag = "tags".equals(c.p("kind"));
            Up up = c.call("DELETE", "/projects/" + c.project() + "/repository/"
                    + (tag ? "tags/" : "branches/") + enc(c.p("name")), null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/git/trees/{sha*}", c -> {
            String sha = c.p("sha");
            Up up = c.getPaged("/projects/" + c.project() + "/repository/tree", "ref", sha,
                    "per_page", "100", "recursive", c.q("recursive") != null ? "true" : "false");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray tree = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject e = in.optJSONObject(i);
                JSONObject o = newObj();
                put(o, "path", str(e, "path"));
                put(o, "mode", str(e, "mode"));
                put(o, "type", "tree".equals(str(e, "type")) ? "tree" : "blob");
                put(o, "sha", str(e, "id"));
                tree.put(o);
            }
            JSONObject res = newObj();
            put(res, "sha", sha);
            put(res, "tree", tree);
            return c.json(res);
        });
        route("GET", "repos/{owner}/{repo}/git/commits/{sha}", c -> {
            Up up = c.get("/projects/" + c.project() + "/repository/commits/" + enc(c.p("sha")));
            return up.ok() ? c.json(GitLabMapper.commit(up.obj(), c.project(), c.instance())
                    .optJSONObject("commit")) : c.error(up);
        });
    }

    private static Response refs(Ctx c, String... kinds) throws IOException {
        JSONArray out = new JSONArray();
        for (String kind : kinds) {
            boolean tag = "tags".equals(kind);
            Up up = c.get("/projects/" + c.project() + "/repository/" + (tag ? "tags" : "branches"),
                    "per_page", "100");
            JSONArray in = up.arr();
            for (int i = 0; i < in.length(); i++) {
                JSONObject b = in.optJSONObject(i);
                out.put(refObject(kind, b.optString("name"), commitSha(b)));
            }
        }
        return c.json(out);
    }

    private static String commitSha(JSONObject b) {
        JSONObject commit = b.optJSONObject("commit");
        return commit != null ? commit.optString("id", commit.optString("sha")) : str(b, "target");
    }

    private static JSONObject refObject(String kind, String name, String sha) {
        JSONObject o = newObj();
        put(o, "ref", "refs/" + kind + "/" + name);
        JSONObject obj = newObj();
        put(obj, "sha", sha);
        put(obj, "type", "commit");
        put(o, "object", obj);
        return o;
    }

    private static JSONObject branch(JSONObject b) {
        JSONObject o = newObj();
        put(o, "name", str(b, "name"));
        JSONObject commit = newObj();
        put(commit, "sha", commitSha(b));
        put(o, "commit", commit);
        put(o, "protected", b.optBoolean("protected"));
        return o;
    }

    // ---------------------------------------------------------------- releases

    private static void registerReleases() {
        route("GET", "repos/{owner}/{repo}/releases", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/releases");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                out.put(release(c, in.optJSONObject(i)));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/releases/latest", c -> {
            Up up = c.get("/projects/" + c.project() + "/releases/permalink/latest");
            return up.ok() ? c.json(htmlRelease(c, up.obj())) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/releases/tags/{tag*}", c -> {
            Up up = c.get("/projects/" + c.project() + "/releases/" + enc(c.p("tag")));
            return up.ok() ? c.json(htmlRelease(c, up.obj())) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/releases/{id}", c -> {
            JSONObject r = releaseById(c, Long.parseLong(c.p("id")));
            return r != null ? c.json(htmlRelease(c, r)) : c.notFound("Release not found");
        });
        route("GET", "repos/{owner}/{repo}/releases/{id}/assets", c -> {
            JSONObject r = releaseById(c, Long.parseLong(c.p("id")));
            return r != null ? c.json(release(c, r).optJSONArray("assets"))
                    : c.notFound("Release not found");
        });
        route("POST", "repos/{owner}/{repo}/releases", c -> {
            Up up = c.call("POST", "/projects/" + c.project() + "/releases",
                    releaseBody(c.bodyJson(), true));
            return up.ok() ? c.json(release(c, up.obj())) : c.error(up);
        });
        route("PATCH", "repos/{owner}/{repo}/releases/{id}", c -> {
            JSONObject r = releaseById(c, Long.parseLong(c.p("id")));
            if (r == null) {
                return c.notFound("Release not found");
            }
            Up up = c.call("PUT", "/projects/" + c.project() + "/releases/"
                    + enc(r.optString("tag_name")), releaseBody(c.bodyJson(), false));
            return up.ok() ? c.json(release(c, up.obj())) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/releases/{id}", c -> {
            JSONObject r = releaseById(c, Long.parseLong(c.p("id")));
            if (r == null) {
                return c.notFound("Release not found");
            }
            Up up = c.call("DELETE", "/projects/" + c.project() + "/releases/"
                    + enc(r.optString("tag_name")), null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
    }

    private static JSONObject releaseBody(JSONObject in, boolean create) {
        JSONObject body = newObj();
        if (create) {
            put(body, "tag_name", str(in, "tag_name"));
            put(body, "ref", in.has("target_commitish") && !in.isNull("target_commitish")
                    ? str(in, "target_commitish") : "HEAD");
        }
        copy(in, body, "name", "name");
        copy(in, body, "body", "description");
        return body;
    }

    private static JSONObject releaseById(Ctx c, long id) throws IOException {
        for (int page = 1; page <= 10; page++) {
            Up up = c.get("/projects/" + c.project() + "/releases", "per_page", "100", "page",
                    String.valueOf(page));
            JSONArray a = up.arr();
            for (int i = 0; i < a.length(); i++) {
                if (GitLabMapper.nameId(a.optJSONObject(i).optString("tag_name")) == id) {
                    return a.optJSONObject(i);
                }
            }
            if (a.length() < 100) {
                break;
            }
        }
        return null;
    }

    private static JSONObject htmlRelease(Ctx c, JSONObject r) throws IOException {
        JSONObject o = release(c, r);
        GitLabHtml.attach(c, o, c.p("owner") + "/" + c.p("repo"));
        return o;
    }

    static JSONObject release(Ctx c, JSONObject r) {
        JSONObject o = newObj();
        String tag = str(r, "tag_name");
        put(o, "id", GitLabMapper.nameId(tag));
        put(o, "tag_name", tag);
        put(o, "name", str(r, "name"));
        put(o, "body", str(r, "description"));
        put(o, "draft", false);
        put(o, "prerelease", r.optBoolean("upcoming_release"));
        put(o, "author", GitLabMapper.user(r.optJSONObject("author")));
        put(o, "created_at", GitLabMapper.time(str(r, "created_at")));
        put(o, "published_at", GitLabMapper.time(str(r, "released_at")));
        JSONObject links = r.optJSONObject("_links");
        put(o, "html_url", links != null ? str(links, "self") : null);
        put(o, "target_commitish", r.optJSONObject("commit") != null
                ? r.optJSONObject("commit").optString("id") : null);
        JSONObject assetsIn = r.optJSONObject("assets");
        JSONArray assets = new JSONArray();
        if (assetsIn != null) {
            JSONArray l = assetsIn.optJSONArray("links");
            for (int i = 0; l != null && i < l.length(); i++) {
                JSONObject a = l.optJSONObject(i);
                JSONObject ao = newObj();
                put(ao, "id", a.optLong("id"));
                put(ao, "name", str(a, "name"));
                put(ao, "size", 0);
                put(ao, "download_count", 0);
                put(ao, "created_at", GitLabMapper.time(str(r, "created_at")));
                put(ao, "updated_at", GitLabMapper.time(str(r, "created_at")));
                put(ao, "content_type", "application/octet-stream");
                String url = str(a, "direct_asset_url");
                put(ao, "browser_download_url", url != null ? url : str(a, "url"));
                assets.put(ao);
            }
            JSONArray s = assetsIn.optJSONArray("sources");
            for (int i = 0; s != null && i < s.length(); i++) {
                JSONObject a = s.optJSONObject(i);
                String format = str(a, "format");
                if ("zip".equals(format)) {
                    put(o, "zipball_url", str(a, "url"));
                } else if ("tar.gz".equals(format)) {
                    put(o, "tarball_url", str(a, "url"));
                }
                if ("zip".equals(format) || "tar.gz".equals(format)) {
                    JSONObject src = newObj();
                    put(src, "id", GitLabMapper.nameId(str(a, "url")));
                    put(src, "name", "Source code (" + format + ")");
                    put(src, "size", 0);
                    put(src, "download_count", 0);
                    put(src, "created_at", GitLabMapper.time(str(r, "created_at")));
                    put(src, "updated_at", GitLabMapper.time(str(r, "created_at")));
                    put(src, "content_type", "zip".equals(format) ? "application/zip"
                            : "application/gzip");
                    put(src, "browser_download_url", str(a, "url"));
                    assets.put(src);
                }
            }
        }
        put(o, "assets", assets);
        return o;
    }
}
