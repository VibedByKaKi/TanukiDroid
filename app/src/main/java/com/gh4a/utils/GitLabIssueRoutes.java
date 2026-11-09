package com.gh4a.utils;

import com.gh4a.utils.GitLabCompatInterceptor.Ctx;
import com.gh4a.utils.GitLabCompatInterceptor.Up;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

import static com.gh4a.utils.GitLabCompatInterceptor.route;
import static com.gh4a.utils.GitLabMapper.isMr;
import static com.gh4a.utils.GitLabMapper.newObj;
import static com.gh4a.utils.GitLabMapper.put;
import static com.gh4a.utils.GitLabMapper.str;

/** Route table for issues, merge requests, comments, labels, milestones, events and todos. */
final class GitLabIssueRoutes {
    private GitLabIssueRoutes() {
    }

    /** Where a note lives, so edits and reactions can address it. */
    static final class NoteRef {
        final boolean mergeRequest;
        final long iid;
        final String discussion;

        NoteRef(boolean mergeRequest, long iid, String discussion) {
            this.mergeRequest = mergeRequest;
            this.iid = iid;
            this.discussion = discussion;
        }
    }

    private static final Map<String, NoteRef> NOTES = new HashMap<>();

    private static String noteKey(Ctx c, long id) {
        return c.instance() + "|" + c.project() + "|" + id;
    }

    static void remember(Ctx c, long noteId, boolean mr, long iid, String discussion) {
        NOTES.put(noteKey(c, noteId), new NoteRef(mr, iid, discussion));
    }

    private static final String[][] REACTIONS = {
            {"+1", "thumbsup"}, {"-1", "thumbsdown"}, {"laugh", "laughing"}, {"hooray", "tada"},
            {"confused", "confused"}, {"heart", "heart"}, {"rocket", "rocket"}, {"eyes", "eyes"},
    };

    static void register() {
        registerIssues();
        registerComments();
        registerLabels();
        registerMilestones();
        registerReactions();
        GitLabPullRoutes.register();
        registerEvents();
        registerNotifications();
        registerMisc();
    }

    // ---------------------------------------------------------------- helpers

    static String projectPath(Ctx c) {
        return c.p("owner") + "/" + c.p("repo");
    }

    static long number(Ctx c) {
        String n = c.raw("number") != null ? c.raw("number") : c.raw("issue_number");
        return Long.parseLong(n);
    }

    /** GitLab path of the issue or merge request behind a GitHub issue number. */
    static String target(Ctx c, long number) {
        return "/projects/" + c.project() + (isMr(number) ? "/merge_requests/" : "/issues/")
                + GitLabMapper.iid(number);
    }

    private static final Map<String, JSONArray> LABELS = new HashMap<>();
    private static final Map<String, Long> LABELS_TIME = new HashMap<>();

    private static JSONArray projectLabels(Ctx c) throws IOException {
        String key = c.instance() + "|" + c.project();
        Long at = LABELS_TIME.get(key);
        if (at != null && System.currentTimeMillis() - at < 15_000) {
            return LABELS.get(key);
        }
        Up up = c.get("/projects/" + c.project() + "/labels", "per_page", "100",
                "include_ancestor_groups", "true");
        JSONArray a = up.ok() ? up.arr() : new JSONArray();
        LABELS.put(key, a);
        LABELS_TIME.put(key, System.currentTimeMillis());
        return a;
    }

    /** Single issue and merge request answers only carry label names; add colours. */
    static void fixLabels(Ctx c, JSONObject o) throws IOException {
        JSONArray labels = o.optJSONArray("labels");
        if (labels == null || labels.length() == 0) {
            return;
        }
        JSONArray known = projectLabels(c);
        for (int i = 0; i < labels.length(); i++) {
            JSONObject l = labels.optJSONObject(i);
            if (l == null || !"428bca".equals(l.optString("color"))) {
                continue;
            }
            for (int k = 0; k < known.length(); k++) {
                JSONObject kl = known.optJSONObject(k);
                if (kl.optString("name").equals(l.optString("name"))) {
                    GitLabMapper.setAt(labels, i, GitLabMapper.label(kl));
                    break;
                }
            }
        }
    }

    static void invalidateLabels(Ctx c) {
        LABELS_TIME.remove(c.instance() + "|" + c.project());
    }

    static JSONObject enrichRepository(Ctx c, JSONObject issue, long projectId)
            throws IOException {
        JSONObject p = GitLabRoutes.project(c, String.valueOf(projectId));
        if (p != null) {
            put(issue, "repository", GitLabMapper.project(p));
        }
        return issue;
    }

    static JSONArray issuesWithRepo(Ctx c, JSONArray in) throws IOException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.optJSONObject(i);
            JSONObject mapped = o.has("source_branch") ? GitLabMapper.mrAsIssue(o, c.instance())
                    : GitLabMapper.issue(o, null);
            enrichRepository(c, mapped, o.optLong("project_id"));
            out.put(mapped);
        }
        return out;
    }

    static List<String> issueFilterQuery(Ctx c) throws IOException {
        List<String> q = new ArrayList<>();
        String state = c.q("state");
        if (state != null) {
            q.add("state");
            q.add("open".equals(state) ? "opened" : state);
        }
        String labels = c.q("labels");
        if (labels != null && !labels.isEmpty()) {
            q.add("labels");
            q.add(labels);
        }
        String milestone = c.q("milestone");
        if (milestone != null && !milestone.isEmpty()) {
            q.add("milestone");
            if ("*".equals(milestone)) {
                q.add("Any");
            } else if ("none".equals(milestone)) {
                q.add("None");
            } else if (milestone.matches("\\d+")) {
                String title = milestoneTitle(c, Long.parseLong(milestone));
                q.add(title != null ? title : milestone);
            } else {
                q.add(milestone);
            }
        }
        String assignee = c.q("assignee");
        if (assignee != null && !assignee.isEmpty()) {
            if ("none".equals(assignee)) {
                q.add("assignee_id");
                q.add("None");
            } else if ("*".equals(assignee)) {
                q.add("assignee_id");
                q.add("Any");
            } else {
                q.add("assignee_username");
                q.add(assignee);
            }
        }
        String creator = c.q("creator");
        if (creator != null && !creator.isEmpty()) {
            q.add("author_username");
            q.add(creator);
        }
        String sort = c.q("sort");
        q.add("order_by");
        q.add("created".equals(sort) ? "created_at" : "updated_at");
        String direction = c.q("direction");
        q.add("sort");
        q.add(direction != null ? direction : "desc");
        String since = c.q("since");
        if (since != null) {
            q.add("updated_after");
            q.add(since);
        }
        q.add("with_labels_details");
        q.add("true");
        return q;
    }

    static String[] issueListQuery(Ctx c) throws IOException {
        List<String> q = issueFilterQuery(c);
        q.add("scope");
        q.add("all");
        return q.toArray(new String[0]);
    }

    private static final Map<String, JSONArray> MILESTONES = new HashMap<>();

    private static JSONArray milestoneList(Ctx c) throws IOException {
        Up up = c.get("/projects/" + c.project() + "/milestones", "per_page", "100");
        return up.arr();
    }

    static String milestoneTitle(Ctx c, long number) throws IOException {
        JSONArray a = milestoneList(c);
        for (int i = 0; i < a.length(); i++) {
            if (a.optJSONObject(i).optLong("iid") == number) {
                return a.optJSONObject(i).optString("title");
            }
        }
        return null;
    }

    static long milestoneId(Ctx c, long number) throws IOException {
        JSONArray a = milestoneList(c);
        for (int i = 0; i < a.length(); i++) {
            if (a.optJSONObject(i).optLong("iid") == number) {
                return a.optJSONObject(i).optLong("id");
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- issues

    private static void registerIssues() {
        route("GET", "repos/{owner}/{repo}/issues", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/issues", issueListQuery(c));
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                out.put(GitLabMapper.issue(in.optJSONObject(i), c.project()));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "issues", c -> {
            String filter = c.q("filter");
            String scope = "created".equals(filter) ? "created_by_me"
                    : "assigned".equals(filter) || filter == null ? "assigned_to_me" : "all";
            List<String> q = issueFilterQuery(c);
            q.add("scope");
            q.add(scope);
            Up up = c.getPaged("/issues", q.toArray(new String[0]));
            return up.ok() ? c.jsonPaged(issuesWithRepo(c, up.arr()), up) : c.error(up);
        });
        route("GET", "user/issues", c -> {
            List<String> q = issueFilterQuery(c);
            q.add("scope");
            q.add("all");
            Up up = c.getPaged("/issues", q.toArray(new String[0]));
            return up.ok() ? c.jsonPaged(issuesWithRepo(c, up.arr()), up) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/issues/{number}", c -> {
            long n = number(c);
            Up up = c.get(target(c, n), "with_labels_details", "true");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject i = up.obj();
            JSONObject mapped = isMr(n) ? GitLabMapper.mrAsIssue(i, c.instance())
                    : GitLabMapper.issue(i, c.project());
            enrichIssueExtras(c, n, mapped);
            fixLabels(c, mapped);
            put(mapped, "reactions", GitLabReactions.load(c, isMr(n), GitLabMapper.iid(n))
                    .description.toJson());
            GitLabHtml.attach(c, mapped, projectPath(c));
            return c.json(mapped);
        });
        route("POST", "repos/{owner}/{repo}/issues", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = issueBody(c, in, true);
            Up up = c.call("POST", "/projects/" + c.project() + "/issues", body);
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject created = GitLabMapper.issue(up.obj(), c.project());
            fixLabels(c, created);
            return c.json(created);
        });
        route("PATCH", "repos/{owner}/{repo}/issues/{number}", c -> {
            long n = number(c);
            JSONObject body = issueBody(c, c.bodyJson(), false);
            Up up = c.call("PUT", target(c, n), body, "with_labels_details", "true");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject edited = isMr(n) ? GitLabMapper.mrAsIssue(up.obj(), c.instance())
                    : GitLabMapper.issue(up.obj(), c.project());
            fixLabels(c, edited);
            return c.json(edited);
        });
        route("PUT", "repos/{owner}/{repo}/issues/{number}/lock", c -> {
            JSONObject body = newObj();
            put(body, "discussion_locked", true);
            Up up = c.call("PUT", target(c, number(c)), body);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/issues/{number}/lock", c -> {
            JSONObject body = newObj();
            put(body, "discussion_locked", false);
            Up up = c.call("PUT", target(c, number(c)), body);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/issues/{number}/assignees", c -> c.json(new JSONArray()));
        route("POST", "repos/{owner}/{repo}/issues/{number}/assignees", c -> changeAssignees(c, true));
        route("DELETE", "repos/{owner}/{repo}/issues/{number}/assignees", c -> changeAssignees(c, false));
        route("GET", "repos/{owner}/{repo}/issues/{number}/dependencies/blocked_by",
                c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/issues/{number}/dependencies/blocking",
                c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/issues/{number}/sub_issues", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/issues/{number}/parent", c -> c.notFound("No parent"));
    }

    private static void enrichIssueExtras(Ctx c, long n, JSONObject mapped) throws IOException {
        // Needed by the screens that list the repository an issue belongs to.
        JSONObject p = GitLabRoutes.project(c, c.p("owner") + "/" + c.p("repo"));
        if (p != null) {
            put(mapped, "repository", GitLabMapper.project(p));
        }
    }

    private static JSONObject issueBody(Ctx c, JSONObject in, boolean create) throws IOException {
        JSONObject body = newObj();
        if (in.has("title") && !in.isNull("title")) {
            put(body, "title", str(in, "title"));
        }
        if (in.has("body")) {
            put(body, "description", in.isNull("body") ? "" : str(in, "body"));
        }
        if (in.has("state") && !in.isNull("state")) {
            put(body, "state_event", "closed".equals(str(in, "state")) ? "close" : "reopen");
        }
        if (in.has("labels") && !in.isNull("labels")) {
            put(body, "labels", join(in.optJSONArray("labels")));
        }
        if (in.has("assignees") && !in.isNull("assignees")) {
            JSONArray ids = new JSONArray();
            JSONArray names = in.optJSONArray("assignees");
            for (int i = 0; names != null && i < names.length(); i++) {
                long id = GitLabRoutes.userId(c, names.optString(i));
                if (id > 0) {
                    ids.put(id);
                }
            }
            put(body, "assignee_ids", ids);
        }
        if (in.has("milestone")) {
            if (in.isNull("milestone")) {
                put(body, "milestone_id", 0);
            } else {
                long id = milestoneId(c, in.optLong("milestone"));
                if (id > 0) {
                    put(body, "milestone_id", id);
                }
            }
        }
        return body;
    }

    private static String join(JSONArray names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; names != null && i < names.length(); i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(names.optString(i));
        }
        return sb.toString();
    }

    private static Response changeAssignees(Ctx c, boolean add) throws IOException {
        long n = number(c);
        Up cur = c.get(target(c, n));
        if (!cur.ok()) {
            return c.error(cur);
        }
        JSONObject in = c.bodyJson();
        JSONArray names = in.optJSONArray("assignees");
        if (names == null) {
            names = c.bodyArray();
        }
        List<Long> ids = new ArrayList<>();
        JSONArray existing = cur.obj().optJSONArray("assignees");
        for (int i = 0; existing != null && i < existing.length(); i++) {
            ids.add(existing.optJSONObject(i).optLong("id"));
        }
        for (int i = 0; i < names.length(); i++) {
            long id = GitLabRoutes.userId(c, names.optString(i));
            if (add && !ids.contains(id)) {
                ids.add(id);
            } else if (!add) {
                ids.remove(Long.valueOf(id));
            }
        }
        JSONObject body = newObj();
        put(body, "assignee_ids", new JSONArray(ids));
        Up up = c.call("PUT", target(c, n), body);
        if (!up.ok()) {
            return c.error(up);
        }
        return c.json(isMr(n) ? GitLabMapper.mrAsIssue(up.obj(), c.instance())
                : GitLabMapper.issue(up.obj(), c.project()));
    }

    // ---------------------------------------------------------------- search

    private static final Pattern QUERY_TOKEN =
            Pattern.compile("[^\\s\"]+:\"[^\"]*\"|\"[^\"]*\"|\\S+");

    static Response searchIssues(Ctx c) throws IOException {
        String q = c.q("q") != null ? c.q("q") : "";
        Matcher m = QUERY_TOKEN.matcher(q);
        Map<String, List<String>> quals = new HashMap<>();
        StringBuilder text = new StringBuilder();
        while (m.find()) {
            String t = m.group();
            int colon = t.indexOf(':');
            if (colon > 0 && !t.startsWith("\"")) {
                String key = t.substring(0, colon).toLowerCase(Locale.US);
                String value = t.substring(colon + 1).replace("\"", "");
                if (!quals.containsKey(key)) {
                    quals.put(key, new ArrayList<>());
                }
                quals.get(key).add(value);
            } else {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(t.replace("\"", ""));
            }
        }
        boolean wantIssues = true;
        boolean wantMrs = true;
        String state = null;
        for (String v : list(quals, "is")) {
            if ("issue".equals(v)) {
                wantMrs = false;
            } else if ("pr".equals(v) || "pull-request".equals(v)) {
                wantIssues = false;
            } else if ("open".equals(v)) {
                state = "opened";
            } else if ("closed".equals(v)) {
                state = "closed";
            } else if ("merged".equals(v)) {
                state = "merged";
                wantIssues = false;
            }
        }
        for (String v : list(quals, "type")) {
            if ("issue".equals(v)) {
                wantMrs = false;
            } else if ("pr".equals(v)) {
                wantIssues = false;
            }
        }
        for (String v : list(quals, "state")) {
            state = "open".equals(v) ? "opened" : v;
        }

        List<String> common = new ArrayList<>();
        if (text.length() > 0) {
            common.add("search");
            common.add(text.toString());
        }
        for (String v : list(quals, "label")) {
            common.add("labels");
            common.add(v);
        }
        for (String v : list(quals, "milestone")) {
            common.add("milestone");
            common.add(v);
        }
        String sort = c.q("sort");
        common.add("order_by");
        common.add("created".equals(sort) ? "created_at" : "updated_at");
        common.add("sort");
        common.add("asc".equals(c.q("order")) ? "asc" : "desc");
        common.add("with_labels_details");
        common.add("true");

        List<List<String>> variants = new ArrayList<>();
        List<String> authors = list(quals, "author");
        List<String> assignees = list(quals, "assignee");
        List<String> involves = list(quals, "involves");
        List<String> mentions = list(quals, "mentions");
        boolean any = false;
        for (String v : authors) {
            variants.add(single("author_username", v));
            any = true;
        }
        for (String v : assignees) {
            variants.add(single("assignee_username", v));
            any = true;
        }
        for (String v : involves) {
            variants.add(single("author_username", v));
            variants.add(single("assignee_username", v));
            any = true;
        }
        for (String v : mentions) {
            variants.add(single("search", v));
            any = true;
        }
        if (!any) {
            variants.add(new ArrayList<>());
        }
        if (involves.size() > 0 && authors.size() > 0) {
            // "author:x involves:y" needs both; keep the narrower author filter.
            variants.clear();
            variants.add(single("author_username", authors.get(0)));
        }

        List<String> repos = list(quals, "repo");
        String scopeBase;
        if (!repos.isEmpty()) {
            scopeBase = "/projects/" + GitLabRoutes.enc(repos.get(0));
        } else if (!list(quals, "org").isEmpty()) {
            scopeBase = "/groups/" + GitLabRoutes.enc(list(quals, "org").get(0));
        } else if (!list(quals, "user").isEmpty()) {
            scopeBase = null;
        } else {
            scopeBase = "";
        }

        List<JSONObject> items = new ArrayList<>();
        boolean hasNext = false;
        int total = 0;
        Up last = null;
        for (List<String> variant : variants) {
            for (int kind = 0; kind < 2; kind++) {
                boolean issues = kind == 0;
                if (issues && !wantIssues || !issues && !wantMrs) {
                    continue;
                }
                List<String> query = new ArrayList<>(common);
                query.addAll(variant);
                String useState = state;
                if (issues && "merged".equals(useState)) {
                    continue;
                }
                if (useState != null) {
                    query.add("state");
                    query.add(useState);
                }
                if (scopeBase == null || scopeBase.isEmpty()) {
                    query.add("scope");
                    query.add("all");
                }
                String base = scopeBase == null ? "" : scopeBase;
                String path = (base.isEmpty() ? "" : base) + (issues ? "/issues" : "/merge_requests");
                Up up = c.getPaged(path, query.toArray(new String[0]));
                last = up;
                if (!up.ok()) {
                    if (up.code == 404 || up.code == 403) {
                        continue;
                    }
                    return c.error(up);
                }
                JSONArray a = up.arr();
                for (int i = 0; i < a.length(); i++) {
                    items.add(a.optJSONObject(i));
                }
                if (up.nextPage() > 0) {
                    hasNext = true;
                }
                total += Math.max(up.total(), a.length());
            }
        }

        // Several queries (issues + merge requests, several filters) are merged by update time.
        Collections.sort(items, (a, b) -> str(b, "updated_at").compareTo(str(a, "updated_at")));
        Map<Long, Boolean> seen = new HashMap<>();
        JSONArray out = new JSONArray();
        for (JSONObject o : items) {
            long key = o.optLong("id") * 2 + (o.has("source_branch") ? 1 : 0);
            if (seen.containsKey(key)) {
                continue;
            }
            seen.put(key, true);
            JSONObject mapped = o.has("source_branch") ? GitLabMapper.mrAsIssue(o, c.instance())
                    : GitLabMapper.issue(o, null);
            enrichRepository(c, mapped, o.optLong("project_id"));
            out.put(mapped);
        }
        JSONObject result = GitLabRoutes.searchResult(out, null);
        put(result, "total_count", Math.max(total, out.length()));
        Response r = c.json(result);
        if (hasNext && last != null) {
            r = r.newBuilder().header("Link", String.format(Locale.US, "<%s>; rel=\"next\"",
                    c.url.newBuilder().setQueryParameter("page", String.valueOf(c.page() + 1))
                            .build())).build();
        }
        return r;
    }

    private static List<String> single(String k, String v) {
        List<String> l = new ArrayList<>();
        l.add(k);
        l.add(v);
        return l;
    }

    private static List<String> list(Map<String, List<String>> m, String key) {
        List<String> l = m.get(key);
        return l != null ? l : new ArrayList<>();
    }

    // ---------------------------------------------------------------- comments

    private static void registerComments() {
        route("GET", "repos/{owner}/{repo}/issues/{number}/comments", c -> {
            long n = number(c);
            Up up = c.getPaged(target(c, n) + "/notes", "sort", "asc", "order_by", "created_at");
            if (!up.ok()) {
                return c.error(up);
            }
            GitLabReactions.Result reactions = GitLabReactions.load(c, isMr(n),
                    GitLabMapper.iid(n));
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject note = in.optJSONObject(i);
                if (note.optBoolean("system")) {
                    continue;
                }
                remember(c, note.optLong("id"), isMr(n), GitLabMapper.iid(n), null);
                JSONObject mappedNote = GitLabMapper.note(note, str(note, "web_url"));
                applyReactions(mappedNote, reactions, note.optLong("id"));
                out.put(mappedNote);
            }
            GitLabHtml.attach(c, out, projectPath(c));
            return c.jsonPaged(out, up);
        });
        route("POST", "repos/{owner}/{repo}/issues/{number}/comments", c -> {
            long n = number(c);
            JSONObject body = newObj();
            put(body, "body", str(c.bodyJson(), "body"));
            Up up = c.call("POST", target(c, n) + "/notes", body);
            if (!up.ok()) {
                return c.error(up);
            }
            remember(c, up.obj().optLong("id"), isMr(n), GitLabMapper.iid(n), null);
            JSONObject created = GitLabMapper.note(up.obj(), null);
            GitLabHtml.attach(c, created, projectPath(c));
            return c.json(created);
        });
        route("GET", "repos/{owner}/{repo}/issues/comments", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/issues/comments/{id}", c -> {
            NoteRef ref = NOTES.get(noteKey(c, Long.parseLong(c.p("id"))));
            if (ref == null) {
                return c.notFound("Comment not found");
            }
            Up up = c.get(notePath(c, ref, Long.parseLong(c.p("id"))));
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject single = GitLabMapper.note(up.obj(), null);
            GitLabHtml.attach(c, single, projectPath(c));
            return c.json(single);
        });
        route("PATCH", "repos/{owner}/{repo}/issues/comments/{id}", c -> editNote(c));
        route("DELETE", "repos/{owner}/{repo}/issues/comments/{id}", c -> deleteNote(c));
    }

    static void applyReactions(JSONObject o, GitLabReactions.Result reactions, long noteId) {
        GitLabReactions.Counts counts = reactions.notes.get(noteId);
        if (counts != null) {
            put(o, "reactions", counts.toJson());
        }
    }

    static String notePath(Ctx c, NoteRef ref, long noteId) {
        return "/projects/" + c.project() + (ref.mergeRequest ? "/merge_requests/" : "/issues/")
                + ref.iid + "/notes/" + noteId;
    }

    static Response editNote(Ctx c) throws IOException {
        long id = Long.parseLong(c.p("id"));
        NoteRef ref = NOTES.get(noteKey(c, id));
        if (ref == null) {
            return c.notFound("Comment not found; reload and try again");
        }
        JSONObject body = newObj();
        put(body, "body", str(c.bodyJson(), "body"));
        Up up = c.call("PUT", notePath(c, ref, id), body);
        if (!up.ok()) {
            return c.error(up);
        }
        JSONObject edited = GitLabMapper.note(up.obj(), null);
        GitLabHtml.attach(c, edited, projectPath(c));
        return c.json(edited);
    }

    static Response deleteNote(Ctx c) throws IOException {
        long id = Long.parseLong(c.p("id"));
        NoteRef ref = NOTES.get(noteKey(c, id));
        if (ref == null) {
            return c.notFound("Comment not found; reload and try again");
        }
        Up up = c.call("DELETE", notePath(c, ref, id), null);
        return up.ok() ? c.empty(204) : c.error(up);
    }

    static NoteRef noteRef(Ctx c, long id) {
        return NOTES.get(noteKey(c, id));
    }

    // ---------------------------------------------------------------- labels

    private static void registerLabels() {
        route("GET", "repos/{owner}/{repo}/labels", c -> {
            Up up = c.getPaged("/projects/" + c.project() + "/labels",
                    "with_counts", "false", "include_ancestor_groups", "true");
            if (!up.ok()) {
                return c.error(up);
            }
            return c.jsonPaged(GitLabMapper.labels(up.arr()), up);
        });
        route("GET", "repos/{owner}/{repo}/labels/{name}", c -> {
            Up up = c.get("/projects/" + c.project() + "/labels/" + GitLabRoutes.enc(c.p("name")));
            return up.ok() ? c.json(GitLabMapper.label(up.obj())) : c.error(up);
        });
        route("POST", "repos/{owner}/{repo}/labels", c -> {
            invalidateLabels(c);
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            put(body, "name", str(in, "name"));
            put(body, "color", "#" + GitLabMapper.color(str(in, "color")));
            if (in.has("description") && !in.isNull("description")) {
                put(body, "description", str(in, "description"));
            }
            Up up = c.call("POST", "/projects/" + c.project() + "/labels", body);
            return up.ok() ? c.json(GitLabMapper.label(up.obj())) : c.error(up);
        });
        route("PATCH", "repos/{owner}/{repo}/labels/{name}", c -> {
            invalidateLabels(c);
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            if (in.has("name") && !in.isNull("name")) {
                put(body, "new_name", str(in, "name"));
            }
            if (in.has("color") && !in.isNull("color")) {
                put(body, "color", "#" + GitLabMapper.color(str(in, "color")));
            }
            if (in.has("description")) {
                put(body, "description", in.isNull("description") ? "" : str(in, "description"));
            }
            Up up = c.call("PUT", "/projects/" + c.project() + "/labels/"
                    + GitLabRoutes.enc(c.p("name")), body);
            return up.ok() ? c.json(GitLabMapper.label(up.obj())) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/labels/{name}", c -> {
            invalidateLabels(c);
            Up up = c.call("DELETE", "/projects/" + c.project() + "/labels/"
                    + GitLabRoutes.enc(c.p("name")), null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/issues/{number}/labels", c -> {
            Up up = c.get(target(c, number(c)), "with_labels_details", "true");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject holder = newObj();
            put(holder, "labels", GitLabMapper.labels(up.obj().optJSONArray("labels")));
            fixLabels(c, holder);
            return c.json(holder.optJSONArray("labels"));
        });
        route("POST", "repos/{owner}/{repo}/issues/{number}/labels", c -> {
            JSONArray names = labelNames(c);
            JSONObject body = newObj();
            put(body, "add_labels", join(names));
            return updateLabels(c, body);
        });
        route("PUT", "repos/{owner}/{repo}/issues/{number}/labels", c -> {
            JSONObject body = newObj();
            put(body, "labels", join(labelNames(c)));
            return updateLabels(c, body);
        });
        route("DELETE", "repos/{owner}/{repo}/issues/{number}/labels/{name}", c -> {
            JSONObject body = newObj();
            put(body, "remove_labels", c.p("name"));
            return updateLabels(c, body);
        });
        route("DELETE", "repos/{owner}/{repo}/issues/{number}/labels", c -> {
            JSONObject body = newObj();
            put(body, "labels", "");
            return updateLabels(c, body);
        });
        route("GET", "repos/{owner}/{repo}/milestones/{number}/labels", c -> c.json(new JSONArray()));
    }

    private static JSONArray labelNames(Ctx c) throws IOException {
        JSONArray names = c.bodyArray();
        if (names.length() == 0) {
            names = c.bodyJson().optJSONArray("labels");
        }
        return names != null ? names : new JSONArray();
    }

    private static Response updateLabels(Ctx c, JSONObject body) throws IOException {
        Up up = c.call("PUT", target(c, number(c)), body, "with_labels_details", "true");
        if (!up.ok()) {
            return c.error(up);
        }
        JSONObject holder = newObj();
        put(holder, "labels", GitLabMapper.labels(up.obj().optJSONArray("labels")));
        fixLabels(c, holder);
        return c.json(holder.optJSONArray("labels"));
    }

    // ---------------------------------------------------------------- milestones

    private static void registerMilestones() {
        route("GET", "repos/{owner}/{repo}/milestones", c -> {
            String state = c.q("state");
            Up up = c.getPaged("/projects/" + c.project() + "/milestones",
                    "state", "open".equals(state) ? "active" : "closed".equals(state)
                            ? "closed" : null);
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                out.put(milestoneWithCounts(c, in.optJSONObject(i)));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/milestones/{number}", c -> {
            long id = milestoneId(c, Long.parseLong(c.p("number")));
            if (id < 0) {
                return c.notFound("Milestone not found");
            }
            Up up = c.get("/projects/" + c.project() + "/milestones/" + id);
            return up.ok() ? c.json(milestoneWithCounts(c, up.obj())) : c.error(up);
        });
        route("POST", "repos/{owner}/{repo}/milestones", c -> {
            Up up = c.call("POST", "/projects/" + c.project() + "/milestones",
                    milestoneBody(c.bodyJson()));
            return up.ok() ? c.json(GitLabMapper.milestone(up.obj())) : c.error(up);
        });
        route("PATCH", "repos/{owner}/{repo}/milestones/{number}", c -> {
            long id = milestoneId(c, Long.parseLong(c.p("number")));
            if (id < 0) {
                return c.notFound("Milestone not found");
            }
            JSONObject in = c.bodyJson();
            JSONObject body = milestoneBody(in);
            if (in.has("state")) {
                put(body, "state_event", "closed".equals(str(in, "state")) ? "close" : "activate");
            }
            Up up = c.call("PUT", "/projects/" + c.project() + "/milestones/" + id, body);
            return up.ok() ? c.json(milestoneWithCounts(c, up.obj())) : c.error(up);
        });
        route("DELETE", "repos/{owner}/{repo}/milestones/{number}", c -> {
            long id = milestoneId(c, Long.parseLong(c.p("number")));
            if (id < 0) {
                return c.notFound("Milestone not found");
            }
            Up up = c.call("DELETE", "/projects/" + c.project() + "/milestones/" + id, null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
    }

    private static JSONObject milestoneBody(JSONObject in) {
        JSONObject body = newObj();
        if (in.has("title") && !in.isNull("title")) {
            put(body, "title", str(in, "title"));
        }
        if (in.has("description")) {
            put(body, "description", in.isNull("description") ? "" : str(in, "description"));
        }
        if (in.has("due_on")) {
            String due = str(in, "due_on");
            put(body, "due_date", due == null ? "" : due.substring(0, Math.min(10, due.length())));
        }
        return body;
    }

    private static JSONObject milestoneWithCounts(Ctx c, JSONObject m) throws IOException {
        JSONObject o = GitLabMapper.milestone(m);
        long id = m.optLong("id");
        Up open = c.get("/projects/" + c.project() + "/milestones/" + id + "/issues",
                "state", "opened", "per_page", "1");
        Up closed = c.get("/projects/" + c.project() + "/milestones/" + id + "/issues",
                "state", "closed", "per_page", "1");
        put(o, "open_issues", open.total() >= 0 ? open.total() : open.arr().length());
        put(o, "closed_issues", closed.total() >= 0 ? closed.total() : closed.arr().length());
        return o;
    }

    // ---------------------------------------------------------------- reactions

    static String reactionName(String content) {
        for (String[] r : REACTIONS) {
            if (r[0].equals(content)) {
                return r[1];
            }
        }
        return content;
    }

    static String reactionContent(String name) {
        for (String[] r : REACTIONS) {
            if (r[1].equals(name)) {
                return r[0];
            }
        }
        if ("smile".equals(name) || "smiley".equals(name) || "grinning".equals(name)) {
            return "laugh";
        }
        return null;
    }

    private static void registerReactions() {
        route("GET", "repos/{owner}/{repo}/issues/{number}/reactions",
                c -> listReactions(c, target(c, number(c))));
        route("POST", "repos/{owner}/{repo}/issues/{number}/reactions",
                c -> addReaction(c, target(c, number(c))));
        route("DELETE", "repos/{owner}/{repo}/issues/{number}/reactions/{id}",
                c -> deleteReaction(c, target(c, number(c))));
        route("GET", "repos/{owner}/{repo}/issues/comments/{id}/reactions", c -> {
            String path = commentPath(c);
            return path == null ? c.json(new JSONArray()) : listReactions(c, path);
        });
        route("POST", "repos/{owner}/{repo}/issues/comments/{id}/reactions", c -> {
            String path = commentPath(c);
            return path == null ? c.notFound("Comment not found") : addReaction(c, path);
        });
        route("DELETE", "repos/{owner}/{repo}/issues/comments/{comment}/reactions/{id}", c -> {
            NoteRef ref = noteRef(c, Long.parseLong(c.p("comment")));
            if (ref == null) {
                return c.notFound("Comment not found");
            }
            return deleteReaction(c, notePath(c, ref, Long.parseLong(c.p("comment"))));
        });
        route("GET", "repos/{owner}/{repo}/pulls/comments/{id}/reactions", c -> {
            String path = commentPath(c);
            return path == null ? c.json(new JSONArray()) : listReactions(c, path);
        });
        route("POST", "repos/{owner}/{repo}/pulls/comments/{id}/reactions", c -> {
            String path = commentPath(c);
            return path == null ? c.notFound("Comment not found") : addReaction(c, path);
        });
        route("DELETE", "repos/{owner}/{repo}/pulls/comments/{comment}/reactions/{id}", c -> {
            NoteRef ref = noteRef(c, Long.parseLong(c.p("comment")));
            if (ref == null) {
                return c.notFound("Comment not found");
            }
            return deleteReaction(c, notePath(c, ref, Long.parseLong(c.p("comment"))));
        });
        route("GET", "repos/{owner}/{repo}/comments/{id}/reactions", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/releases/{id}/reactions", c -> c.json(new JSONArray()));
    }

    private static String commentPath(Ctx c) {
        long id = Long.parseLong(c.p("id"));
        NoteRef ref = noteRef(c, id);
        return ref == null ? null : notePath(c, ref, id);
    }

    private static Response listReactions(Ctx c, String path) throws IOException {
        Up up = c.getPaged(path + "/award_emoji");
        if (!up.ok()) {
            return c.error(up);
        }
        JSONArray in = up.arr();
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject a = in.optJSONObject(i);
            String content = reactionContent(str(a, "name"));
            if (content == null) {
                continue;
            }
            out.put(reaction(a, content));
        }
        return c.jsonPaged(out, up);
    }

    private static JSONObject reaction(JSONObject a, String content) {
        JSONObject o = newObj();
        put(o, "id", a.optLong("id"));
        put(o, "content", content);
        put(o, "user", GitLabMapper.user(a.optJSONObject("user")));
        put(o, "created_at", GitLabMapper.time(str(a, "created_at")));
        return o;
    }

    private static Response addReaction(Ctx c, String path) throws IOException {
        String content = str(c.bodyJson(), "content");
        JSONObject body = newObj();
        put(body, "name", reactionName(content));
        Up up = c.call("POST", path + "/award_emoji", body);
        GitLabReactions.invalidate(c, path.contains("/merge_requests/"),
                Long.parseLong(path.replaceAll(".*/(issues|merge_requests)/(\\d+).*", "$2")));
        if (!up.ok() && up.code == 404) {
            return c.error(up);
        }
        if (!up.ok()) {
            // Already reacted: return the existing one.
            Up list = c.get(path + "/award_emoji", "per_page", "100");
            JSONArray a = list.arr();
            for (int i = 0; i < a.length(); i++) {
                if (reactionName(content).equals(str(a.optJSONObject(i), "name"))) {
                    return c.json(reaction(a.optJSONObject(i), content));
                }
            }
            return c.error(up);
        }
        return c.reply(201, reaction(up.obj(), content).toString());
    }

    private static Response deleteReaction(Ctx c, String path) throws IOException {
        Up up = c.call("DELETE", path + "/award_emoji/" + c.p("id"), null);
        GitLabReactions.invalidate(c, path.contains("/merge_requests/"),
                Long.parseLong(path.replaceAll(".*/(issues|merge_requests)/(\\d+).*", "$2")));
        return up.ok() ? c.empty(204) : c.error(up);
    }

    // ---------------------------------------------------------------- events (feeds)

    private static void registerEvents() {
        route("GET", "events", c -> feed(c, "/events", "scope", "all"));
        route("GET", "users/{username}/received_events", c -> feed(c, "/events", "scope", "all"));
        route("GET", "users/{username}/received_events/public",
                c -> feed(c, "/events", "scope", "all"));
        route("GET", "users/{username}/events", c -> feed(c, "/users/" + c.raw("username")
                + "/events"));
        route("GET", "users/{username}/events/public", c -> feed(c, "/users/"
                + c.raw("username") + "/events"));
        route("GET", "users/{username}/events/orgs/{org}", c -> feed(c, "/events", "scope", "all"));
        route("GET", "repos/{owner}/{repo}/events", c -> feed(c,
                "/projects/" + c.project() + "/events"));
        route("GET", "networks/{owner}/{repo}/events", c -> feed(c,
                "/projects/" + c.project() + "/events"));
        route("GET", "orgs/{org}/events", c -> groupFeed(c));
        route("GET", "repos/{owner}/{repo}/issues/events", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/issues/{number}/events", c -> timeline(c));
        route("GET", "repos/{owner}/{repo}/issues/{number}/timeline", c -> timeline(c));
    }

    private static Response groupFeed(Ctx c) throws IOException {
        Up projects = c.get("/groups/" + c.raw("org") + "/projects", "order_by", "last_activity_at",
                "per_page", "5", "include_subgroups", "true");
        JSONArray ps = projects.arr();
        List<JSONObject> events = new ArrayList<>();
        for (int i = 0; i < ps.length(); i++) {
            Up ev = c.get("/projects/" + ps.optJSONObject(i).optLong("id") + "/events",
                    "per_page", "20");
            JSONArray a = ev.arr();
            for (int k = 0; k < a.length(); k++) {
                events.add(a.optJSONObject(k));
            }
        }
        Collections.sort(events, (a, b) -> str(b, "created_at").compareTo(str(a, "created_at")));
        JSONArray out = new JSONArray();
        for (JSONObject e : events) {
            JSONObject ge = toGitHubEvent(c, e);
            if (ge != null) {
                out.put(ge);
            }
        }
        return c.json(out);
    }

    private static Response feed(Ctx c, String path, String... query) throws IOException {
        Up up = c.getPaged(path, query);
        if (!up.ok()) {
            return c.error(up);
        }
        JSONArray in = up.arr();
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject e = toGitHubEvent(c, in.optJSONObject(i));
            if (e != null) {
                out.put(e);
            }
        }
        return c.jsonPaged(out, up);
    }

    private static JSONObject toGitHubEvent(Ctx c, JSONObject e) throws IOException {
        String action = str(e, "action_name");
        String targetType = str(e, "target_type");
        if (action == null) {
            return null;
        }
        JSONObject project = GitLabRoutes.project(c, String.valueOf(e.optLong("project_id")));
        if (project == null) {
            return null;
        }
        String fullName = project.optString("path_with_namespace");
        String web = project.optString("web_url");
        JSONObject actor = GitLabMapper.user(e.optJSONObject("author"));
        if (actor == null) {
            return null;
        }
        String type;
        JSONObject payload = newObj();
        JSONObject push = e.optJSONObject("push_data");
        JSONObject note = e.optJSONObject("note");
        long iid = e.optLong("target_iid");
        String title = str(e, "target_title");
        if (push != null && ("pushed to".equals(action) || "pushed new".equals(action)
                || "pushed".equals(action) || "deleted".equals(action))) {
            String refType = str(push, "ref_type");
            String ref = str(push, "ref");
            String pushAction = str(push, "action");
            if ("created".equals(pushAction) && "tag".equals(refType)
                    || "created".equals(pushAction) && push.optInt("commit_count") == 0) {
                type = "CreateEvent";
                put(payload, "ref_type", "tag".equals(refType) ? "tag" : "branch");
                put(payload, "ref", ref);
                put(payload, "master_branch", project.optString("default_branch"));
                put(payload, "description", str(project, "description"));
            } else if ("removed".equals(pushAction)) {
                type = "DeleteEvent";
                put(payload, "ref_type", "tag".equals(refType) ? "tag" : "branch");
                put(payload, "ref", ref);
            } else {
                type = "PushEvent";
                put(payload, "ref", "refs/" + ("tag".equals(refType) ? "tags/" : "heads/") + ref);
                put(payload, "head", str(push, "commit_to"));
                put(payload, "before", str(push, "commit_from"));
                JSONArray commits = new JSONArray();
                if (push.has("commit_title") && !push.isNull("commit_title")) {
                    JSONObject cm = newObj();
                    put(cm, "sha", str(push, "commit_to"));
                    put(cm, "message", str(push, "commit_title"));
                    JSONObject author = newObj();
                    put(author, "name", actor.optString("name"));
                    put(cm, "author", author);
                    put(cm, "distinct", true);
                    commits.put(cm);
                }
                put(payload, "commits", commits);
                put(payload, "size", push.optInt("commit_count"));
                put(payload, "distinct_size", push.optInt("commit_count"));
            }
        } else if ("Issue".equals(targetType)) {
            type = "IssuesEvent";
            put(payload, "action", "closed".equals(action) ? "closed"
                    : "reopened".equals(action) ? "reopened" : "opened");
            put(payload, "issue", minimalIssue(web, iid, title, actor, false));
        } else if ("MergeRequest".equals(targetType) && "approved".equals(action)) {
            type = "PullRequestReviewEvent";
            put(payload, "action", "created");
            JSONObject review = newObj();
            put(review, "id", e.optLong("id"));
            put(review, "user", actor);
            put(review, "state", "approved");
            put(review, "body", "");
            put(payload, "review", review);
            put(payload, "pull_request", minimalIssue(web, iid, title, actor, true));
        } else if ("MergeRequest".equals(targetType)) {
            type = "PullRequestEvent";
            boolean merged = "accepted".equals(action);
            put(payload, "action", merged ? "merged" : "closed".equals(action) ? "closed"
                    : "reopened".equals(action) ? "reopened" : "opened");
            put(payload, "number", GitLabMapper.mrNumber(iid));
            JSONObject pr = minimalIssue(web, iid, title, actor, true);
            put(pr, "merged", merged);
            put(payload, "pull_request", pr);
        } else if ("Note".equals(targetType) || "DiffNote".equals(targetType)
                || "DiscussionNote".equals(targetType)) {
            String noteable = note != null ? str(note, "noteable_type") : null;
            long noteableIid = note != null ? note.optLong("noteable_iid") : iid;
            if ("MergeRequest".equals(noteable) && "DiffNote".equals(targetType)) {
                type = "PullRequestReviewCommentEvent";
                put(payload, "action", "created");
                put(payload, "pull_request", minimalIssue(web, noteableIid, title, actor, true));
                JSONObject comment = newObj();
                put(comment, "id", note != null ? note.optLong("id") : e.optLong("target_id"));
                put(comment, "body", note != null ? str(note, "body") : "");
                put(comment, "user", actor);
                put(comment, "html_url", web + "/-/merge_requests/" + noteableIid + "#note_"
                        + (note != null ? note.optLong("id") : 0));
                put(payload, "comment", comment);
            } else if ("Commit".equals(noteable)) {
                type = "CommitCommentEvent";
                JSONObject comment = newObj();
                put(comment, "body", note != null ? str(note, "body") : "");
                put(comment, "commit_id", str(e, "target_title"));
                put(comment, "user", actor);
                put(comment, "html_url", web + "/-/commit/" + str(e, "target_title"));
                put(payload, "comment", comment);
            } else {
                boolean mr = "MergeRequest".equals(noteable);
                type = "IssueCommentEvent";
                put(payload, "action", "created");
                put(payload, "issue", minimalIssue(web, noteableIid, title, actor, mr));
                JSONObject comment = newObj();
                put(comment, "id", note != null ? note.optLong("id") : e.optLong("target_id"));
                put(comment, "body", note != null ? str(note, "body") : "");
                put(comment, "user", actor);
                put(comment, "html_url", web + "/-/" + (mr ? "merge_requests/" : "issues/")
                        + noteableIid + "#note_" + (note != null ? note.optLong("id") : 0));
                put(payload, "comment", comment);
            }
        } else if ("Project".equals(targetType) || targetType == null) {
            if ("created".equals(action)) {
                type = "CreateEvent";
                put(payload, "ref_type", "repository");
                put(payload, "ref", null);
                put(payload, "master_branch", project.optString("default_branch"));
                put(payload, "description", str(project, "description"));
            } else if ("joined".equals(action)) {
                type = "MemberEvent";
                put(payload, "action", "added");
                put(payload, "member", actor);
            } else if ("left".equals(action) || "destroyed".equals(action)
                    || "deleted".equals(action)) {
                type = "DeleteEvent";
                put(payload, "ref_type", "repository");
                put(payload, "ref", fullName);
            } else {
                return null;
            }
        } else if ("Milestone".equals(targetType) || "WikiPage::Meta".equals(targetType)) {
            if ("WikiPage::Meta".equals(targetType)) {
                type = "GollumEvent";
                JSONArray pages = new JSONArray();
                JSONObject pg = newObj();
                put(pg, "title", title);
                put(pg, "action", "created".equals(action) ? "created" : "edited");
                put(pg, "html_url", web + "/-/wikis/" + title);
                put(pg, "page_name", title);
                pages.put(pg);
                put(payload, "pages", pages);
            } else {
                return null;
            }
        } else {
            return null;
        }

        JSONObject repo = newObj();
        put(repo, "id", project.optLong("id"));
        put(repo, "name", fullName);
        put(repo, "url", web);
        JSONObject ev = newObj();
        put(ev, "id", String.valueOf(e.optLong("id")));
        put(ev, "type", type);
        put(ev, "actor", actor);
        put(ev, "repo", repo);
        put(ev, "payload", payload);
        put(ev, "public", "public".equals(project.optString("visibility")));
        put(ev, "created_at", GitLabMapper.time(str(e, "created_at")));
        return ev;
    }

    private static JSONObject minimalIssue(String web, long iid, String title, JSONObject actor,
            boolean mr) {
        JSONObject issue = newObj();
        put(issue, "number", mr ? GitLabMapper.mrNumber(iid) : (int) iid);
        put(issue, "title", title);
        put(issue, "state", "open");
        String html = web + "/-/" + (mr ? "merge_requests/" : "issues/") + iid;
        put(issue, "html_url", html);
        put(issue, "user", actor);
        if (mr) {
            JSONObject pr = newObj();
            put(pr, "html_url", html);
            put(issue, "pull_request", pr);
        }
        return issue;
    }

    // ---------------------------------------------------------------- timeline

    private static Response timeline(Ctx c) throws IOException {
        long n = number(c);
        boolean mr = isMr(n);
        String base = target(c, n);
        List<JSONObject> events = new ArrayList<>();

        Up notes = c.get(base + "/notes", "sort", "asc", "order_by", "created_at",
                "per_page", "100", "page", String.valueOf(c.page()));
        if (!notes.ok()) {
            return c.error(notes);
        }
        JSONArray na = notes.arr();
        for (int i = 0; i < na.length(); i++) {
            JSONObject note = na.optJSONObject(i);
            JSONObject ev = noteEvent(c, note, mr, GitLabMapper.iid(n));
            if (ev != null) {
                events.add(ev);
            }
        }
        // Label, state and milestone changes live in their own resources.
        addResourceEvents(c, base + "/resource_label_events", events, "label");
        addResourceEvents(c, base + "/resource_state_events", events, "state");
        addResourceEvents(c, base + "/resource_milestone_events", events, "milestone");

        Collections.sort(events, (a, b) -> str(a, "created_at").compareTo(str(b, "created_at")));
        GitLabReactions.Result reactions = GitLabReactions.load(c, mr, GitLabMapper.iid(n));
        for (JSONObject e : events) {
            if ("commented".equals(str(e, "event"))) {
                applyReactions(e, reactions, e.optLong("id"));
            }
        }
        GitLabHtml.attach(c, events, projectPath(c));
        JSONArray out = new JSONArray();
        for (JSONObject e : events) {
            out.put(e);
        }
        return c.jsonPaged(out, notes);
    }

    private static final Pattern RENAME = Pattern.compile(
            "^changed title from \\*\\*(.*)\\*\\* to \\*\\*(.*)\\*\\*$", Pattern.DOTALL);
    private static final Pattern ASSIGNED = Pattern.compile("^assigned to (.*)$");
    private static final Pattern UNASSIGNED = Pattern.compile("^unassigned (.*)$");
    private static final Pattern MENTIONED_IN = Pattern.compile("^mentioned in (.*)$");
    private static final Pattern REQUESTED_REVIEW =
            Pattern.compile("^requested review from @(\\S+)$");

    private static JSONObject noteEvent(Ctx c, JSONObject note, boolean mr, long iid)
            throws IOException {
        long id = note.optLong("id");
        JSONObject author = GitLabMapper.user(note.optJSONObject("author"));
        JSONObject ev = newObj();
        put(ev, "id", id);
        put(ev, "actor", author);
        put(ev, "created_at", GitLabMapper.time(str(note, "created_at")));
        put(ev, "updated_at", GitLabMapper.time(str(note, "updated_at")));
        if (!note.optBoolean("system")) {
            if ("DiffNote".equals(str(note, "type"))) {
                return null;
            }
            remember(c, id, mr, iid, null);
            put(ev, "event", "commented");
            put(ev, "body", str(note, "body"));
            put(ev, "author_association", "NONE");
            return ev;
        }
        String body = str(note, "body");
        if (body == null) {
            return null;
        }
        Matcher m;
        if ("closed".equals(body) || body.startsWith("closed via")) {
            put(ev, "event", "closed");
        } else if ("reopened".equals(body)) {
            put(ev, "event", "reopened");
        } else if (body.startsWith("merged")) {
            put(ev, "event", "merged");
        } else if ("locked".equals(body) || body.startsWith("locked this")) {
            put(ev, "event", "locked");
        } else if (body.startsWith("unlocked")) {
            put(ev, "event", "unlocked");
        } else if ((m = RENAME.matcher(body)).matches()) {
            put(ev, "event", "renamed");
            JSONObject rename = newObj();
            put(rename, "from", m.group(1));
            put(rename, "to", m.group(2));
            put(ev, "rename", rename);
        } else if ((m = ASSIGNED.matcher(body)).matches()) {
            put(ev, "event", "assigned");
            put(ev, "assignee", userFromMention(m.group(1)));
        } else if ((m = UNASSIGNED.matcher(body)).matches()) {
            put(ev, "event", "unassigned");
            put(ev, "assignee", userFromMention(m.group(1)));
        } else if ((m = REQUESTED_REVIEW.matcher(body)).matches()) {
            put(ev, "event", "review_requested");
            put(ev, "requested_reviewer", userFromMention("@" + m.group(1)));
            put(ev, "review_requester", author);
        } else if (body.startsWith("approved this merge request")) {
            put(ev, "event", "reviewed");
            put(ev, "state", "approved");
            put(ev, "body", "");
            put(ev, "submitted_at", GitLabMapper.time(str(note, "created_at")));
        } else if (body.startsWith("unapproved this merge request")) {
            put(ev, "event", "review_dismissed");
        } else if (body.startsWith("marked this merge request as **draft**")
                || body.startsWith("marked as a **draft**")) {
            put(ev, "event", "convert_to_draft");
        } else if (body.startsWith("marked this merge request as **ready**")
                || body.startsWith("marked as **ready**")) {
            put(ev, "event", "ready_for_review");
        } else if (body.startsWith("added ") && body.contains("commit")) {
            put(ev, "event", "committed");
            put(ev, "message", body);
            put(ev, "sha", "");
        } else if (body.startsWith("mentioned in")) {
            put(ev, "event", "referenced");
            put(ev, "commit_id", null);
        } else {
            return null;
        }
        return ev;
    }

    private static JSONObject userFromMention(String text) {
        Matcher m = Pattern.compile("@([\\w.\\-]+)").matcher(text);
        JSONObject u = newObj();
        put(u, "login", m.find() ? m.group(1) : text);
        put(u, "type", "User");
        put(u, "id", 0);
        return u;
    }

    private static void addResourceEvents(Ctx c, String path, List<JSONObject> events, String kind)
            throws IOException {
        Up up = c.get(path, "per_page", "100");
        if (!up.ok()) {
            return;
        }
        JSONArray a = up.arr();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            JSONObject ev = newObj();
            put(ev, "id", r.optLong("id") + 4_000_000_000_000L);
            put(ev, "actor", GitLabMapper.user(r.optJSONObject("user")));
            put(ev, "created_at", GitLabMapper.time(str(r, "created_at")));
            boolean add = "add".equals(str(r, "action"));
            if ("label".equals(kind)) {
                JSONObject label = r.optJSONObject("label");
                if (label == null) {
                    continue;
                }
                put(ev, "event", add ? "labeled" : "unlabeled");
                put(ev, "label", GitLabMapper.label(label));
            } else if ("milestone".equals(kind)) {
                JSONObject ms = r.optJSONObject("milestone");
                if (ms == null) {
                    continue;
                }
                put(ev, "event", add ? "milestoned" : "demilestoned");
                put(ev, "milestone", GitLabMapper.milestone(ms));
            } else {
                String state = str(r, "state");
                if ("closed".equals(state)) {
                    put(ev, "event", "closed");
                } else if ("reopened".equals(state)) {
                    put(ev, "event", "reopened");
                } else if ("merged".equals(state)) {
                    put(ev, "event", "merged");
                } else {
                    continue;
                }
            }
            events.add(ev);
        }
    }

    // ---------------------------------------------------------------- notifications (todos)

    private static void registerNotifications() {
        route("GET", "notifications", c -> {
            Up up = c.getPaged("/todos", "state", "all".equals(c.q("all")) || "true".equals(c.q("all"))
                    ? "done" : "pending");
            if ("true".equals(c.q("all"))) {
                up = c.getPaged("/todos");
            }
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject t = todo(c, in.optJSONObject(i));
                if (t != null) {
                    out.put(t);
                }
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/notifications", c -> c.json(new JSONArray()));
        route("PUT", "notifications", c -> {
            Up up = c.call("POST", "/todos/mark_as_done", null);
            return up.ok() ? c.empty(205) : c.error(up);
        });
        route("PUT", "repos/{owner}/{repo}/notifications", c -> {
            Up up = c.get("/todos", "project_id", String.valueOf(
                    GitLabRoutes.project(c, c.p("owner") + "/" + c.p("repo")).optLong("id")),
                    "per_page", "100");
            JSONArray a = up.arr();
            for (int i = 0; i < a.length(); i++) {
                c.call("POST", "/todos/" + a.optJSONObject(i).optLong("id") + "/mark_as_done", null);
            }
            return c.empty(205);
        });
        route("GET", "notifications/threads/{id}", c -> {
            Up up = c.get("/todos", "per_page", "100");
            JSONArray a = up.arr();
            for (int i = 0; i < a.length(); i++) {
                if (String.valueOf(a.optJSONObject(i).optLong("id")).equals(c.p("id"))) {
                    return c.json(todo(c, a.optJSONObject(i)));
                }
            }
            return c.notFound("Notification not found");
        });
        route("PATCH", "notifications/threads/{id}", c -> {
            Up up = c.call("POST", "/todos/" + c.p("id") + "/mark_as_done", null);
            return up.ok() ? c.empty(205) : c.error(up);
        });
        route("DELETE", "notifications/threads/{id}", c -> {
            Up up = c.call("POST", "/todos/" + c.p("id") + "/mark_as_done", null);
            return up.ok() ? c.empty(204) : c.error(up);
        });
        route("GET", "notifications/threads/{id}/subscription", c -> {
            JSONObject o = newObj();
            put(o, "subscribed", true);
            put(o, "ignored", false);
            return c.json(o);
        });
        route("PUT", "notifications/threads/{id}/subscription", c -> c.json(newObj()));
        route("DELETE", "notifications/threads/{id}/subscription", c -> c.empty(204));
    }

    private static JSONObject todo(Ctx c, JSONObject t) throws IOException {
        JSONObject project = GitLabRoutes.project(c, String.valueOf(
                t.optJSONObject("project") != null ? t.optJSONObject("project").optLong("id") : 0));
        JSONObject target = t.optJSONObject("target");
        if (project == null) {
            return null;
        }
        String targetType = str(t, "target_type");
        JSONObject subject = newObj();
        String type = "MergeRequest".equals(targetType) ? "PullRequest"
                : "Issue".equals(targetType) ? "Issue" : "Commit".equals(targetType) ? "Commit"
                : targetType;
        put(subject, "type", type);
        put(subject, "title", target != null ? str(target, "title") : str(t, "body"));
        String url = str(t, "target_url");
        if (url != null && url.contains("#note_")) {
            url = url.substring(0, url.indexOf("#note_"));
        }
        put(subject, "url", url);
        put(subject, "latest_comment_url", null);
        String action = str(t, "action_name");
        String reason = "mention";
        if ("assigned".equals(action)) {
            reason = "assign";
        } else if ("review_requested".equals(action) || "approval_required".equals(action)) {
            reason = "review_requested";
        } else if ("build_failed".equals(action) || "unmergeable".equals(action)) {
            reason = "ci_activity";
        } else if ("marked".equals(action)) {
            reason = "manual";
        } else if ("review_submitted".equals(action)) {
            reason = "comment";
        }
        JSONObject o = newObj();
        put(o, "id", String.valueOf(t.optLong("id")));
        put(o, "unread", "pending".equals(str(t, "state")));
        put(o, "reason", reason);
        put(o, "updated_at", GitLabMapper.time(str(t, "updated_at") != null
                ? str(t, "updated_at") : str(t, "created_at")));
        put(o, "subject", subject);
        put(o, "repository", GitLabMapper.project(project));
        put(o, "url", url);
        return o;
    }

    // ---------------------------------------------------------------- misc

    private static void registerMisc() {
        route("POST", "markdown", c -> {
            JSONObject in = c.bodyJson();
            String html = GitLabHtml.render(c, str(in, "text"), str(in, "context"));
            return c.raw(new Up(200, html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    okhttp3.Headers.of()), "text/html; charset=utf-8");
        });
        route("GET", "gists", c -> c.json(new JSONArray()));
        route("GET", "gists/public", c -> c.json(new JSONArray()));
        route("GET", "gists/starred", c -> c.json(new JSONArray()));
        route("GET", "users/{username}/gists", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/pulls/{number}/reactions", c -> c.json(new JSONArray()));
    }
}
