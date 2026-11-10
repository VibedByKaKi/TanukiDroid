package com.gh4a.utils;

import com.gh4a.utils.GitLabCompatInterceptor.Ctx;
import com.gh4a.utils.GitLabCompatInterceptor.Up;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

import static com.gh4a.utils.GitLabCompatInterceptor.route;
import static com.gh4a.utils.GitLabMapper.iid;
import static com.gh4a.utils.GitLabMapper.newObj;
import static com.gh4a.utils.GitLabMapper.put;
import static com.gh4a.utils.GitLabMapper.str;

/** Merge requests presented as GitHub pull requests, including reviews and diff comments. */
final class GitLabPullRoutes {
    private GitLabPullRoutes() {
    }

    private static long number(Ctx c) {
        return Long.parseLong(c.raw("number"));
    }

    private static String mr(Ctx c) {
        return "/projects/" + c.project() + "/merge_requests/" + iid(number(c));
    }

    static void register() {
        route("GET", "repos/{owner}/{repo}/pulls", c -> {
            String state = c.q("state");
            String glState = "open".equals(state) ? "opened" : "closed".equals(state) ? "all"
                    : state == null ? "opened" : state;
            List<String> q = new ArrayList<>();
            q.add("state");
            q.add(glState);
            q.add("scope");
            q.add("all");
            String base = c.q("base");
            if (base != null) {
                q.add("target_branch");
                q.add(base);
            }
            String head = c.q("head");
            if (head != null) {
                q.add("source_branch");
                q.add(head.contains(":") ? head.substring(head.indexOf(':') + 1) : head);
            }
            String sort = c.q("sort");
            q.add("order_by");
            q.add("created".equals(sort) ? "created_at" : "updated_at");
            q.add("sort");
            q.add(c.q("direction") != null ? c.q("direction") : "desc");
            q.add("with_labels_details");
            q.add("true");
            Up up = c.getPaged("/projects/" + c.project() + "/merge_requests",
                    q.toArray(new String[0]));
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject m = in.optJSONObject(i);
                if ("closed".equals(state) && "opened".equals(str(m, "state"))) {
                    continue;
                }
                out.put(pullWithRepos(c, m));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/pulls/{number}", c -> {
            Up up = c.get(mr(c), "include_diverged_commits_count", "true",
                    "with_labels_details", "true");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject pr = pullWithRepos(c, up.obj());
            Up commits = c.get(mr(c) + "/commits", "per_page", "1");
            put(pr, "commits", commits.total() >= 0 ? commits.total() : commits.arr().length());
            Up diffs = c.get(mr(c) + "/diffs", "per_page", "100");
            int additions = 0;
            int deletions = 0;
            JSONArray da = diffs.arr();
            for (int i = 0; i < da.length(); i++) {
                JSONObject f = GitLabMapper.diffFile(da.optJSONObject(i), c.instance(), "", null);
                additions += f.optInt("additions");
                deletions += f.optInt("deletions");
            }
            put(pr, "additions", additions);
            put(pr, "deletions", deletions);
            GitLabIssueRoutes.fixLabels(c, pr);
            put(pr, "reactions", GitLabReactions.load(c, true, iid(number(c))).description.toJson());
            GitLabHtml.attach(c, pr, c.p("owner") + "/" + c.p("repo"));
            return c.json(pr);
        });
        route("POST", "repos/{owner}/{repo}/pulls", c -> {
            JSONObject in = c.bodyJson();
            String head = str(in, "head");
            String sourceProject = c.project();
            String sourceBranch = head;
            if (head != null && head.contains(":")) {
                String owner = head.substring(0, head.indexOf(':'));
                sourceBranch = head.substring(head.indexOf(':') + 1);
                if (!owner.equals(c.p("owner"))) {
                    sourceProject = GitLabRoutes.enc(owner) + "%2F" + c.raw("repo");
                }
            }
            JSONObject body = newObj();
            put(body, "source_branch", sourceBranch);
            put(body, "target_branch", str(in, "base"));
            put(body, "title", str(in, "title"));
            put(body, "description", str(in, "body"));
            if (!sourceProject.equals(c.project())) {
                JSONObject target = GitLabRoutes.project(c, c.p("owner") + "/" + c.p("repo"));
                put(body, "target_project_id", target != null ? target.optLong("id") : 0);
            }
            Up up = c.call("POST", "/projects/" + sourceProject + "/merge_requests", body);
            return up.ok() ? c.json(pullWithRepos(c, up.obj())) : c.error(up);
        });
        route("PATCH", "repos/{owner}/{repo}/pulls/{number}", c -> {
            JSONObject in = c.bodyJson();
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
            if (in.has("base") && !in.isNull("base")) {
                put(body, "target_branch", str(in, "base"));
            }
            Up up = c.call("PUT", mr(c), body);
            return up.ok() ? c.json(pullWithRepos(c, up.obj())) : c.error(up);
        });
        route("GET", "repos/{owner}/{repo}/pulls/{number}/commits", c -> {
            Up up = c.getPaged(mr(c) + "/commits");
            if (!up.ok()) {
                return c.error(up);
            }
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = in.length() - 1; i >= 0; i--) {
                out.put(GitLabMapper.commit(in.optJSONObject(i), c.project(), c.instance()));
            }
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/pulls/{number}/files", c -> {
            Up mrUp = c.get(mr(c));
            String sha = mrUp.ok() ? str(mrUp.obj(), "sha") : null;
            Up up = c.getPaged(mr(c) + "/diffs");
            JSONArray in;
            if (up.ok()) {
                in = up.arr();
            } else {
                Up changes = c.get(mr(c) + "/changes");
                if (!changes.ok()) {
                    return c.error(up);
                }
                in = changes.obj().optJSONArray("changes");
            }
            JSONArray out = new JSONArray();
            String web = c.p("owner") + "/" + c.p("repo");
            for (int i = 0; in != null && i < in.length(); i++) {
                out.put(GitLabMapper.diffFile(in.optJSONObject(i), c.instance(), web, sha));
            }
            return c.jsonPaged(out, up.ok() ? up : mrUp);
        });
        route("GET", "repos/{owner}/{repo}/pulls/{number}/merge", c -> {
            Up up = c.get(mr(c));
            return up.ok() && "merged".equals(str(up.obj(), "state")) ? c.empty(204)
                    : c.notFound("Not merged");
        });
        route("PUT", "repos/{owner}/{repo}/pulls/{number}/merge", c -> {
            JSONObject in = c.bodyJson();
            JSONObject body = newObj();
            String method = str(in, "merge_method");
            String title = str(in, "commit_title");
            String message = str(in, "commit_message");
            String text = title != null && !title.isEmpty()
                    ? (message != null && !message.isEmpty() ? title + "\n\n" + message : title)
                    : message;
            if (text != null && text.trim().isEmpty()) {
                text = null;
            }
            if ("rebase".equals(method)) {
                Up rebase = c.call("PUT", mr(c) + "/rebase", null);
                if (!rebase.ok()) {
                    return c.error(rebase);
                }
                for (int i = 0; i < 20; i++) {
                    Up status = c.get(mr(c), "include_rebase_in_progress", "true");
                    if (!status.obj().optBoolean("rebase_in_progress")) {
                        break;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else if ("squash".equals(method)) {
                put(body, "squash", true);
                if (text != null) {
                    put(body, "squash_commit_message", text);
                }
            } else if (text != null) {
                put(body, "merge_commit_message", text);
            }
            if (in.has("sha") && !in.isNull("sha") && !"rebase".equals(method)) {
                put(body, "sha", str(in, "sha"));
            }
            Up up = c.call("PUT", mr(c) + "/merge", body);
            if (!up.ok()) {
                return c.error(up);
            }
            JSONObject res = newObj();
            put(res, "merged", "merged".equals(str(up.obj(), "state")));
            put(res, "sha", str(up.obj(), "merge_commit_sha"));
            put(res, "message", "Merged");
            return c.json(res);
        });
        route("GET", "repos/{owner}/{repo}/pulls/{number}/requested_reviewers", c -> {
            Up up = c.get(mr(c));
            JSONObject o = newObj();
            put(o, "users", up.ok() ? GitLabMapper.userList(up.obj().optJSONArray("reviewers"))
                    : new JSONArray());
            put(o, "teams", new JSONArray());
            return c.json(o);
        });
        registerReviews();
        registerReviewComments();
    }

    /** A merge request as pull request with full head and base repositories. */
    static JSONObject pullWithRepos(Ctx c, JSONObject m) throws IOException {
        JSONObject pr = GitLabMapper.pull(m, c.instance());
        fillMarker(c, pr.optJSONObject("head"), m.optLong("source_project_id"));
        fillMarker(c, pr.optJSONObject("base"), m.optLong("target_project_id"));
        return pr;
    }

    private static void fillMarker(Ctx c, JSONObject marker, long projectId) throws IOException {
        if (marker == null) {
            return;
        }
        JSONObject p = GitLabRoutes.project(c, String.valueOf(projectId));
        if (p != null) {
            JSONObject repo = GitLabMapper.project(p);
            put(marker, "repo", repo);
            put(marker, "user", repo.optJSONObject("owner"));
            put(marker, "label", repo.optJSONObject("owner").optString("login") + ":"
                    + marker.optString("ref"));
        }
    }

    // ---------------------------------------------------------------- reviews

    private static void registerReviews() {
        route("GET", "repos/{owner}/{repo}/pulls/{number}/reviews", c -> {
            Up up = c.get(mr(c) + "/notes", "sort", "asc", "order_by", "created_at",
                    "per_page", "100");
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            // Every diff discussion is shown as a review of its own, so that the app groups the
            // comments with their code like it does for GitHub reviews.
            Up discussions = c.get(mr(c) + "/discussions", "per_page", "100");
            JSONArray da = discussions.arr();
            for (int i = 0; i < da.length(); i++) {
                JSONArray dn = da.optJSONObject(i).optJSONArray("notes");
                JSONObject first = dn != null && dn.length() > 0 ? dn.optJSONObject(0) : null;
                if (first == null || first.optBoolean("system")
                        || first.optJSONObject("position") == null) {
                    continue;
                }
                JSONObject r = review(first, "COMMENTED");
                put(r, "commit_id", str(first.optJSONObject("position"), "head_sha"));
                out.put(r);
            }
            for (int i = 0; i < in.length(); i++) {
                JSONObject n = in.optJSONObject(i);
                String body = str(n, "body");
                if (!n.optBoolean("system") || body == null) {
                    continue;
                }
                String state = null;
                if (body.startsWith("approved this merge request")) {
                    state = "APPROVED";
                } else if (body.startsWith("unapproved this merge request")) {
                    state = "DISMISSED";
                }
                if (state != null) {
                    out.put(review(n, state));
                }
            }
            return c.json(out);
        });
        route("GET", "repos/{owner}/{repo}/pulls/{number}/reviews/{id}", c -> c.notFound("No review"));
        route("GET", "repos/{owner}/{repo}/pulls/{number}/reviews/{id}/comments",
                c -> c.json(new JSONArray()));
        route("POST", "repos/{owner}/{repo}/pulls/{number}/reviews", c -> {
            JSONObject in = c.bodyJson();
            String event = str(in, "event");
            String body = str(in, "body");
            long mrIid = iid(number(c));
            Up mrUp = c.get(mr(c));
            if (!mrUp.ok()) {
                return c.error(mrUp);
            }
            JSONArray comments = in.optJSONArray("comments");
            for (int i = 0; comments != null && i < comments.length(); i++) {
                JSONObject dc = comments.optJSONObject(i);
                Response r = createDiscussion(c, mrUp.obj(), str(dc, "body"), str(dc, "path"),
                        dc.optInt("position", 0), true);
                if (r.code() >= 300) {
                    return r;
                }
            }
            Up result;
            if ("APPROVE".equals(event)) {
                result = c.call("POST", mr(c) + "/approve", null);
                if (!result.ok()) {
                    return c.error(result);
                }
                if (body != null && !body.isEmpty()) {
                    JSONObject note = newObj();
                    put(note, "body", body);
                    c.call("POST", mr(c) + "/notes", note);
                }
            } else if (body != null && !body.isEmpty()) {
                String text = "REQUEST_CHANGES".equals(event) ? "**Changes requested**\n\n" + body
                        : body;
                JSONObject note = newObj();
                put(note, "body", text);
                result = c.call("POST", mr(c) + "/notes", note);
                if (!result.ok()) {
                    return c.error(result);
                }
                GitLabIssueRoutes.remember(c, result.obj().optLong("id"), true, mrIid, null);
            }
            JSONObject review = newObj();
            put(review, "id", System.currentTimeMillis());
            put(review, "state", "APPROVE".equals(event) ? "APPROVED"
                    : "REQUEST_CHANGES".equals(event) ? "CHANGES_REQUESTED" : "COMMENTED");
            put(review, "body", body);
            put(review, "submitted_at", GitLabMapper.time(java.time.Instant.now().toString()));
            return c.json(review);
        });
        route("POST", "repos/{owner}/{repo}/pulls/{number}/reviews/{id}/events", c -> {
            JSONObject review = newObj();
            put(review, "id", Long.parseLong(c.p("id")));
            put(review, "state", "COMMENTED");
            return c.json(review);
        });
        route("DELETE", "repos/{owner}/{repo}/pulls/{number}/reviews/{id}", c -> {
            Up up = c.call("POST", mr(c) + "/unapprove", null);
            return up.ok() ? c.json(newObj()) : c.error(up);
        });
        route("PUT", "repos/{owner}/{repo}/pulls/{number}/reviews/{id}/dismissals", c -> {
            Up up = c.call("POST", mr(c) + "/unapprove", null);
            return up.ok() ? c.json(newObj()) : c.error(up);
        });
    }

    private static JSONObject review(JSONObject note, String state) {
        JSONObject o = newObj();
        put(o, "id", note.optLong("id"));
        put(o, "user", GitLabMapper.user(note.optJSONObject("author")));
        put(o, "state", state);
        put(o, "body", "");
        put(o, "submitted_at", GitLabMapper.time(str(note, "created_at")));
        return o;
    }

    // ---------------------------------------------------------------- diff comments

    private static void registerReviewComments() {
        route("GET", "repos/{owner}/{repo}/pulls/{number}/comments", c -> {
            Up mrUp = c.get(mr(c));
            String sha = mrUp.ok() ? str(mrUp.obj(), "sha") : null;
            Up up = c.getPaged(mr(c) + "/discussions");
            if (!up.ok()) {
                return c.error(up);
            }
            long mrIid = iid(number(c));
            GitLabReactions.Result reactions = GitLabReactions.load(c, true, mrIid);
            JSONArray in = up.arr();
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject d = in.optJSONObject(i);
                JSONArray notes = d.optJSONArray("notes");
                if (notes == null || notes.length() == 0) {
                    continue;
                }
                JSONObject first = notes.optJSONObject(0);
                if (first.optBoolean("system") || first.optJSONObject("position") == null) {
                    continue;
                }
                for (int k = 0; k < notes.length(); k++) {
                    JSONObject n = notes.optJSONObject(k);
                    GitLabIssueRoutes.remember(c, n.optLong("id"), true, mrIid, str(d, "id"));
                    JSONObject rc = reviewComment(c, n, first, sha);
                    GitLabIssueRoutes.applyReactions(rc, reactions, n.optLong("id"));
                    out.put(rc);
                }
            }
            GitLabHtml.attach(c, out, c.p("owner") + "/" + c.p("repo"));
            return c.jsonPaged(out, up);
        });
        route("GET", "repos/{owner}/{repo}/pulls/comments", c -> c.json(new JSONArray()));
        route("GET", "repos/{owner}/{repo}/pulls/comments/{id}", c -> {
            long id = Long.parseLong(c.p("id"));
            GitLabIssueRoutes.NoteRef ref = GitLabIssueRoutes.noteRef(c, id);
            if (ref == null) {
                return c.notFound("Comment not found");
            }
            Up up = c.get(GitLabIssueRoutes.notePath(c, ref, id));
            return up.ok() ? c.json(reviewComment(c, up.obj(), up.obj(), null)) : c.error(up);
        });
        route("POST", "repos/{owner}/{repo}/pulls/{number}/comments", c -> {
            JSONObject in = c.bodyJson();
            Up mrUp = c.get(mr(c));
            if (!mrUp.ok()) {
                return c.error(mrUp);
            }
            if (in.has("in_reply_to") && !in.isNull("in_reply_to")) {
                long replyTo = in.optLong("in_reply_to");
                GitLabIssueRoutes.NoteRef ref = GitLabIssueRoutes.noteRef(c, replyTo);
                if (ref == null || ref.discussion == null) {
                    return c.notFound("Original comment not found; reload and try again");
                }
                JSONObject body = newObj();
                put(body, "body", str(in, "body"));
                Up up = c.call("POST", mr(c) + "/discussions/" + ref.discussion + "/notes", body);
                if (!up.ok()) {
                    return c.error(up);
                }
                GitLabIssueRoutes.remember(c, up.obj().optLong("id"), true, iid(number(c)),
                        ref.discussion);
                JSONObject o = reviewComment(c, up.obj(), up.obj(), str(mrUp.obj(), "sha"));
                put(o, "in_reply_to_id", replyTo);
                return c.json(o);
            }
            return createDiscussion(c, mrUp.obj(), str(in, "body"), str(in, "path"),
                    in.optInt("position", 0), false);
        });
        route("PATCH", "repos/{owner}/{repo}/pulls/comments/{id}", c -> GitLabIssueRoutes.editNote(c));
        route("DELETE", "repos/{owner}/{repo}/pulls/comments/{id}",
                c -> GitLabIssueRoutes.deleteNote(c));
    }

    private static Response createDiscussion(Ctx c, JSONObject mrObj, String text, String path,
            int position, boolean pending) throws IOException {
        JSONObject refs = mrObj.optJSONObject("diff_refs");
        JSONObject body = newObj();
        put(body, "body", text);
        if (path != null && refs != null) {
            JSONObject pos = newObj();
            put(pos, "position_type", "text");
            put(pos, "base_sha", str(refs, "base_sha"));
            put(pos, "start_sha", str(refs, "start_sha"));
            put(pos, "head_sha", str(refs, "head_sha"));
            put(pos, "new_path", path);
            put(pos, "old_path", path);
            int[] lines = linesForPosition(c, mrObj, path, position);
            if (lines[1] > 0) {
                put(pos, "new_line", lines[1]);
            }
            if (lines[0] > 0 && lines[1] <= 0) {
                put(pos, "old_line", lines[0]);
            }
            if (lines[0] > 0 && lines[1] > 0) {
                // Context line: both numbers are needed.
                put(pos, "old_line", lines[0]);
            }
            put(body, "position", pos);
        }
        Up up = c.call("POST", "/projects/" + c.project() + "/merge_requests/"
                + mrObj.optLong("iid") + "/discussions", body);
        if (!up.ok()) {
            return c.error(up);
        }
        JSONArray notes = up.obj().optJSONArray("notes");
        JSONObject first = notes != null && notes.length() > 0 ? notes.optJSONObject(0) : up.obj();
        GitLabIssueRoutes.remember(c, first.optLong("id"), true, mrObj.optLong("iid"),
                str(up.obj(), "id"));
        return c.json(reviewComment(c, first, first, str(mrObj, "sha")));
    }

    private static JSONObject reviewComment(Ctx c, JSONObject n, JSONObject first, String sha)
            throws IOException {
        JSONObject o = GitLabMapper.note(n, null);
        JSONObject position = first.optJSONObject("position");
        if (position != null) {
            String path = position.optString("new_path", position.optString("old_path"));
            put(o, "path", path);
            put(o, "commit_id", str(position, "head_sha"));
            put(o, "original_commit_id", str(position, "head_sha"));
            int newLine = position.optInt("new_line", 0);
            int oldLine = position.optInt("old_line", 0);
            put(o, "line", newLine > 0 ? newLine : oldLine);
            put(o, "original_line", newLine > 0 ? newLine : oldLine);
            put(o, "side", newLine > 0 ? "RIGHT" : "LEFT");
            put(o, "subject_type", "line");
            String patch = patchFor(c, first, path);
            if (patch != null) {
                int idx = positionForLines(patch, oldLine, newLine);
                put(o, "position", idx);
                put(o, "original_position", idx);
                put(o, "diff_hunk", hunkUntil(patch, idx));
            } else {
                put(o, "diff_hunk", "");
            }
        } else if (sha != null) {
            put(o, "commit_id", sha);
        }
        if (!first.equals(n) && first.optLong("id") != n.optLong("id")) {
            put(o, "in_reply_to_id", first.optLong("id"));
        }
        put(o, "pull_request_review_id", first.optLong("id"));
        return o;
    }

    // ---------------------------------------------------------------- diff positions

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private static String patchFor(Ctx c, JSONObject note, String path) throws IOException {
        long iid = note.optLong("noteable_iid");
        if (iid == 0 && c.raw("number") != null) {
            iid = GitLabMapper.iid(number(c));
        }
        if (iid == 0) {
            GitLabIssueRoutes.NoteRef ref = GitLabIssueRoutes.noteRef(c, note.optLong("id"));
            iid = ref != null ? ref.iid : 0;
        }
        if (iid == 0) {
            return null;
        }
        Up up = c.get("/projects/" + c.project() + "/merge_requests/" + iid + "/diffs",
                "per_page", "100");
        JSONArray a = up.arr();
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (path.equals(str(d, "new_path")) || path.equals(str(d, "old_path"))) {
                return str(d, "diff");
            }
        }
        return null;
    }

    /** Diff position (index of the line in the patch, header lines included) to line numbers. */
    private static int[] linesForPosition(Ctx c, JSONObject mrObj, String path, int position)
            throws IOException {
        Up up = c.get("/projects/" + c.project() + "/merge_requests/" + mrObj.optLong("iid")
                + "/diffs", "per_page", "100");
        JSONArray a = up.arr();
        String patch = null;
        for (int i = 0; i < a.length(); i++) {
            if (path.equals(str(a.optJSONObject(i), "new_path"))) {
                patch = str(a.optJSONObject(i), "diff");
            }
        }
        if (patch == null) {
            return new int[] {0, Math.max(position, 1)};
        }
        String[] lines = patch.split("\n", -1);
        int oldLine = 0;
        int newLine = 0;
        for (int i = 0; i < lines.length && i <= position; i++) {
            String line = lines[i];
            Matcher m = HUNK.matcher(line);
            if (m.find()) {
                oldLine = Integer.parseInt(m.group(1)) - 1;
                newLine = Integer.parseInt(m.group(2)) - 1;
                if (i == position) {
                    return new int[] {0, 0};
                }
                continue;
            }
            char ch = line.isEmpty() ? ' ' : line.charAt(0);
            if (ch == '-') {
                oldLine++;
            } else if (ch == '+') {
                newLine++;
            } else if (ch == ' ') {
                oldLine++;
                newLine++;
            }
            if (i == position) {
                if (ch == '-') {
                    return new int[] {oldLine, 0};
                }
                if (ch == '+') {
                    return new int[] {0, newLine};
                }
                return new int[] {oldLine, newLine};
            }
        }
        return new int[] {0, Math.max(newLine, 1)};
    }

    private static int positionForLines(String patch, int oldTarget, int newTarget) {
        String[] lines = patch.split("\n", -1);
        int oldLine = 0;
        int newLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = HUNK.matcher(line);
            if (m.find()) {
                oldLine = Integer.parseInt(m.group(1)) - 1;
                newLine = Integer.parseInt(m.group(2)) - 1;
                continue;
            }
            char ch = line.isEmpty() ? ' ' : line.charAt(0);
            if (ch == '-') {
                oldLine++;
            } else if (ch == '+') {
                newLine++;
            } else if (ch == ' ') {
                oldLine++;
                newLine++;
            }
            if (newTarget > 0 && newLine == newTarget && ch != '-') {
                return i;
            }
            if (newTarget <= 0 && oldTarget > 0 && oldLine == oldTarget && ch != '+') {
                return i;
            }
        }
        return 1;
    }

    private static String hunkUntil(String patch, int position) {
        String[] lines = patch.split("\n", -1);
        int start = 0;
        for (int i = 0; i <= position && i < lines.length; i++) {
            if (lines[i].startsWith("@@")) {
                start = i;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= position && i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
