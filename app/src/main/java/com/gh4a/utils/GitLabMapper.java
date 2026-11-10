package com.gh4a.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import okhttp3.HttpUrl;

/** Converts GitLab API v4 objects into the GitHub JSON shape the SDK models parse. */
final class GitLabMapper {
    private GitLabMapper() {
    }

    static String instanceOf(HttpUrl url) {
        StringBuilder sb = new StringBuilder(url.scheme()).append("://").append(url.host());
        if (url.port() != HttpUrl.defaultPort(url.scheme())) {
            sb.append(':').append(url.port());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- small helpers

    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) {
            return null;
        }
        return o.optString(key, null);
    }

    static JSONObject obj(JSONObject o, String key) {
        return o != null ? o.optJSONObject(key) : null;
    }

    static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setAt(JSONArray a, int index, Object value) {
        try {
            a.put(index, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject newObj() {
        return new JSONObject();
    }

    /** ISO timestamp in UTC without fraction digits, as the SDK date adapter expects. */
    static String time(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) {
            return null;
        }
        try {
            if (s.length() == 10) {
                return s + "T00:00:00Z";
            }
            return OffsetDateTime.parse(s).toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        } catch (DateTimeParseException e) {
            // GitLab also uses "2024-05-01 10:00:00 UTC".
            try {
                return OffsetDateTime.parse(s.replace(" UTC", "Z").replace(' ', 'T'))
                        .toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    static String color(String c) {
        if (c == null) {
            return "ededed";
        }
        return c.startsWith("#") ? c.substring(1) : c;
    }

    /** Stable positive id for things that GitLab identifies by name (tags, releases). */
    static long nameId(String name) {
        CRC32 crc = new CRC32();
        crc.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue() + 1;
    }

    static boolean isMr(long number) {
        return number >= GitLabCompatInterceptor.MR_OFFSET;
    }

    static long iid(long number) {
        return isMr(number) ? number - GitLabCompatInterceptor.MR_OFFSET : number;
    }

    static int mrNumber(long iid) {
        return (int) (iid + GitLabCompatInterceptor.MR_OFFSET);
    }

    // ---------------------------------------------------------------- users and groups

    static JSONObject user(JSONObject u) {
        if (u == null) {
            return null;
        }
        JSONObject o = newObj();
        put(o, "login", str(u, "username"));
        put(o, "id", u.optLong("id"));
        put(o, "avatar_url", avatar(str(u, "avatar_url"), str(u, "web_url")));
        put(o, "html_url", str(u, "web_url"));
        put(o, "url", str(u, "web_url"));
        put(o, "name", str(u, "name"));
        put(o, "type", "User");
        String email = str(u, "public_email");
        if (email == null) {
            email = str(u, "email");
        }
        put(o, "email", email == null || email.isEmpty() ? null : email);
        put(o, "bio", str(u, "bio"));
        put(o, "location", str(u, "location"));
        put(o, "company", str(u, "organization"));
        put(o, "blog", str(u, "website_url"));
        put(o, "created_at", time(str(u, "created_at")));
        if (u.has("followers")) {
            put(o, "followers", u.optInt("followers"));
            put(o, "following", u.optInt("following"));
        }
        put(o, "site_admin", u.optBoolean("is_admin", false));
        return o;
    }

    private static String avatar(String avatar, String webUrl) {
        if (avatar == null || avatar.isEmpty()) {
            return null;
        }
        if (avatar.startsWith("/") && webUrl != null) {
            Matcher m = Pattern.compile("^(https?://[^/]+)").matcher(webUrl);
            if (m.find()) {
                return m.group(1) + avatar;
            }
        }
        return avatar;
    }

    static JSONObject group(JSONObject g) {
        JSONObject o = newObj();
        put(o, "login", str(g, "full_path"));
        put(o, "id", g.optLong("id"));
        put(o, "avatar_url", avatar(str(g, "avatar_url"), str(g, "web_url")));
        put(o, "html_url", str(g, "web_url"));
        put(o, "url", str(g, "web_url"));
        put(o, "name", str(g, "name"));
        put(o, "description", str(g, "description"));
        put(o, "type", "Organization");
        put(o, "created_at", time(str(g, "created_at")));
        return o;
    }

    /** A project namespace as GitHub-style owner. */
    static JSONObject namespace(JSONObject ns, String webUrl) {
        JSONObject o = newObj();
        if (ns == null) {
            return o;
        }
        boolean isUser = "user".equals(str(ns, "kind"));
        put(o, "login", str(ns, "full_path"));
        put(o, "id", ns.optLong("id"));
        put(o, "avatar_url", avatar(str(ns, "avatar_url"), webUrl));
        String web = str(ns, "web_url");
        put(o, "html_url", web);
        put(o, "type", isUser ? "User" : "Organization");
        return o;
    }

    // ---------------------------------------------------------------- repositories

    static JSONObject project(JSONObject p) {
        JSONObject o = newObj();
        String path = str(p, "path_with_namespace");
        String webUrl = str(p, "web_url");
        put(o, "id", p.optLong("id"));
        put(o, "name", str(p, "path"));
        put(o, "full_name", path);
        put(o, "description", str(p, "description"));
        put(o, "owner", ownerOf(p));
        put(o, "private", !"public".equals(str(p, "visibility")));
        put(o, "fork", p.has("forked_from_project") && !p.isNull("forked_from_project"));
        put(o, "html_url", webUrl);
        put(o, "url", webUrl);
        put(o, "clone_url", str(p, "http_url_to_repo"));
        put(o, "ssh_url", str(p, "ssh_url_to_repo"));
        put(o, "homepage", null);
        put(o, "stargazers_count", p.optInt("star_count"));
        put(o, "watchers_count", p.optInt("star_count"));
        put(o, "forks_count", p.optInt("forks_count"));
        put(o, "open_issues_count", p.optInt("open_issues_count"));
        put(o, "default_branch", str(p, "default_branch"));
        put(o, "archived", p.optBoolean("archived"));
        put(o, "created_at", time(str(p, "created_at")));
        String activity = time(str(p, "last_activity_at"));
        put(o, "updated_at", activity);
        put(o, "pushed_at", activity);
        put(o, "has_issues", p.optBoolean("issues_enabled", true));
        put(o, "has_wiki", p.optBoolean("wiki_enabled", false));
        put(o, "has_pages", false);
        put(o, "has_downloads", true);
        put(o, "has_projects", false);
        put(o, "has_discussions", false);
        JSONObject stats = obj(p, "statistics");
        if (stats != null) {
            put(o, "size", (int) (stats.optLong("repository_size") / 1024));
        }
        JSONArray topics = p.optJSONArray("topics");
        if (topics == null) {
            topics = p.optJSONArray("tag_list");
        }
        put(o, "topics", topics != null ? topics : new JSONArray());
        JSONObject perms = obj(p, "permissions");
        if (perms != null) {
            int access = Math.max(
                    accessLevel(obj(perms, "project_access")),
                    accessLevel(obj(perms, "group_access")));
            JSONObject pm = newObj();
            put(pm, "admin", access >= 40);
            put(pm, "push", access >= 30);
            put(pm, "pull", access >= 10);
            put(o, "permissions", pm);
        }
        JSONObject parent = obj(p, "forked_from_project");
        if (parent != null) {
            put(o, "parent", project(parent));
        }
        return o;
    }

    private static int accessLevel(JSONObject access) {
        return access != null ? access.optInt("access_level") : 0;
    }

    private static JSONObject ownerOf(JSONObject p) {
        JSONObject ns = obj(p, "namespace");
        JSONObject owner = namespace(ns, str(p, "web_url"));
        if (owner.length() == 0) {
            String path = str(p, "path_with_namespace");
            put(owner, "login", path != null && path.contains("/")
                    ? path.substring(0, path.lastIndexOf('/')) : path);
            put(owner, "type", "User");
        }
        return owner;
    }

    // ---------------------------------------------------------------- labels, milestones

    static JSONObject label(Object labelOrName) {
        JSONObject o = newObj();
        if (labelOrName instanceof JSONObject) {
            JSONObject l = (JSONObject) labelOrName;
            put(o, "id", l.optLong("id"));
            put(o, "name", str(l, "name"));
            put(o, "color", color(str(l, "color")));
            put(o, "description", str(l, "description"));
            put(o, "default", false);
        } else {
            put(o, "id", nameId(String.valueOf(labelOrName)));
            put(o, "name", String.valueOf(labelOrName));
            put(o, "color", "428bca");
        }
        return o;
    }

    static JSONArray labels(JSONArray in) {
        JSONArray out = new JSONArray();
        if (in != null) {
            for (int i = 0; i < in.length(); i++) {
                out.put(label(in.opt(i)));
            }
        }
        return out;
    }

    static JSONObject milestone(JSONObject m) {
        if (m == null) {
            return null;
        }
        JSONObject o = newObj();
        put(o, "id", m.optLong("id"));
        put(o, "number", m.optInt("iid"));
        put(o, "title", str(m, "title"));
        put(o, "description", str(m, "description"));
        put(o, "state", "closed".equals(str(m, "state")) ? "closed" : "open");
        put(o, "html_url", str(m, "web_url"));
        put(o, "due_on", time(str(m, "due_date")));
        put(o, "created_at", time(str(m, "created_at")));
        put(o, "updated_at", time(str(m, "updated_at")));
        put(o, "open_issues", m.optInt("open_issues"));
        put(o, "closed_issues", m.optInt("closed_issues"));
        return o;
    }

    // ---------------------------------------------------------------- issues and merge requests

    static JSONArray userList(JSONArray users) {
        JSONArray out = new JSONArray();
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u != null) {
                    out.put(user(u));
                }
            }
        }
        return out;
    }

    static JSONObject issue(JSONObject i, String project) {
        JSONObject o = newObj();
        put(o, "id", i.optLong("id"));
        put(o, "number", i.optInt("iid"));
        put(o, "title", str(i, "title"));
        put(o, "body", str(i, "description"));
        put(o, "state", "closed".equals(str(i, "state")) ? "closed" : "open");
        put(o, "locked", i.optBoolean("discussion_locked", false));
        put(o, "user", user(obj(i, "author")));
        JSONArray assignees = userList(i.optJSONArray("assignees"));
        put(o, "assignees", assignees);
        put(o, "assignee", assignees.length() > 0 ? assignees.opt(0) : null);
        put(o, "labels", labels(i.optJSONArray("labels")));
        put(o, "milestone", milestone(obj(i, "milestone")));
        put(o, "comments", i.optInt("user_notes_count"));
        put(o, "html_url", str(i, "web_url"));
        put(o, "url", str(i, "web_url"));
        put(o, "created_at", time(str(i, "created_at")));
        put(o, "updated_at", time(str(i, "updated_at")));
        put(o, "closed_at", time(str(i, "closed_at")));
        put(o, "closed_by", user(obj(i, "closed_by")));
        put(o, "author_association", "NONE");
        put(o, "reactions", reactionCounts(i));
        return o;
    }

    /** The SDK requires every reaction counter to be present. */
    static JSONObject reactionCounts(JSONObject i) {
        JSONObject r = newObj();
        put(r, "+1", i.optInt("upvotes"));
        put(r, "-1", i.optInt("downvotes"));
        for (String k : new String[] {"laugh", "hooray", "confused", "heart", "rocket", "eyes"}) {
            put(r, k, 0);
        }
        return r;
    }

    private static String prState(JSONObject m) {
        return "opened".equals(str(m, "state")) ? "open" : "closed";
    }

    /** A merge request seen as a GitHub pull request. */
    static JSONObject pull(JSONObject m, String instance) {
        JSONObject o = newObj();
        long iid = m.optLong("iid");
        put(o, "id", m.optLong("id"));
        put(o, "number", mrNumber(iid));
        put(o, "title", str(m, "title"));
        put(o, "body", str(m, "description"));
        put(o, "state", prState(m));
        boolean merged = "merged".equals(str(m, "state"));
        put(o, "merged", merged);
        put(o, "merged_at", time(str(m, "merged_at")));
        put(o, "merged_by", user(obj(m, "merged_by") != null ? obj(m, "merged_by")
                : obj(m, "merge_user")));
        put(o, "draft", m.optBoolean("draft") || m.optBoolean("work_in_progress"));
        put(o, "locked", m.optBoolean("discussion_locked", false));
        put(o, "user", user(obj(m, "author")));
        JSONArray assignees = userList(m.optJSONArray("assignees"));
        put(o, "assignee", assignees.length() > 0 ? assignees.opt(0) : null);
        put(o, "requested_reviewers", userList(m.optJSONArray("reviewers")));
        put(o, "labels", labels(m.optJSONArray("labels")));
        put(o, "milestone", milestone(obj(m, "milestone")));
        put(o, "comments", m.optInt("user_notes_count"));
        put(o, "review_comments", 0);
        put(o, "html_url", str(m, "web_url"));
        put(o, "url", str(m, "web_url"));
        put(o, "diff_url", str(m, "web_url") + ".diff");
        put(o, "patch_url", str(m, "web_url") + ".patch");
        put(o, "created_at", time(str(m, "created_at")));
        put(o, "updated_at", time(str(m, "updated_at")));
        put(o, "closed_at", time(str(m, "closed_at")));
        put(o, "closed_by", user(obj(m, "closed_by")));
        put(o, "merge_commit_sha", str(m, "merge_commit_sha"));
        put(o, "author_association", "NONE");
        String detailed = str(m, "detailed_merge_status");
        String mergeStatus = str(m, "merge_status");
        boolean canMerge = "mergeable".equals(detailed) || ("can_be_merged".equals(mergeStatus)
                && detailed == null);
        put(o, "mergeable", merged ? null : (Object) canMerge);
        put(o, "mergeable_state", mergeableState(m, merged));
        put(o, "rebaseable", canMerge);
        put(o, "maintainer_can_modify", m.optBoolean("allow_maintainer_to_push", false));
        String changes = str(m, "changes_count");
        int changed = 0;
        try {
            changed = changes != null ? Integer.parseInt(changes.replace("+", "")) : 0;
        } catch (NumberFormatException ignored) {
        }
        put(o, "changed_files", changed);

        JSONObject refs = obj(m, "diff_refs");
        long sourceProject = m.optLong("source_project_id");
        long targetProject = m.optLong("target_project_id");
        put(o, "head", marker(str(m, "source_branch"), str(m, "sha"), sourceProject,
                refs == null ? null : refs.optString("head_sha", null), instance, m));
        put(o, "base", marker(str(m, "target_branch"),
                refs != null ? refs.optString("base_sha", null) : null, targetProject, null,
                instance, m));
        return o;
    }

    private static JSONObject marker(String ref, String sha, long projectId, String fallbackSha,
            String instance, JSONObject mr) {
        JSONObject o = newObj();
        put(o, "ref", ref);
        // Old merge requests have no diff refs. The app needs a non-null base, so use the branch.
        put(o, "sha", sha != null ? sha : fallbackSha != null ? fallbackSha : ref);
        put(o, "label", ref);
        JSONObject repo = newObj();
        put(repo, "id", projectId);
        // The exact repository is resolved lazily by the route that serves the pull request.
        put(o, "repo", repo);
        return o;
    }

    private static String mergeableState(JSONObject m, boolean merged) {
        if (merged) {
            return "clean";
        }
        String s = str(m, "detailed_merge_status");
        if (s == null) {
            return "can_be_merged".equals(str(m, "merge_status")) ? "clean" : "unknown";
        }
        switch (s) {
            case "mergeable":
                return "clean";
            case "broken_status":
            case "ci_must_pass":
            case "ci_still_running":
            case "not_approved":
            case "requested_changes":
            case "discussions_not_resolved":
            case "draft_status":
            case "blocked_status":
            case "jira_association_missing":
            case "locked_paths":
            case "locked_lfs_files":
            case "merge_request_blocked":
            case "security_policy_violations":
            case "title_regex":
            case "not_open":
            case "external_status_checks":
                return "blocked";
            case "need_rebase":
            case "conflict":
                return "dirty";
            case "checking":
            case "unchecked":
            case "preparing":
            default:
                return "unknown";
        }
    }

    /** A merge request seen as a GitHub issue (for the issue endpoints and search). */
    static JSONObject mrAsIssue(JSONObject m, String instance) {
        JSONObject o = newObj();
        put(o, "id", m.optLong("id"));
        put(o, "number", mrNumber(m.optLong("iid")));
        put(o, "title", str(m, "title"));
        put(o, "body", str(m, "description"));
        put(o, "state", prState(m));
        put(o, "locked", m.optBoolean("discussion_locked", false));
        put(o, "draft", m.optBoolean("draft"));
        put(o, "user", user(obj(m, "author")));
        JSONArray assignees = userList(m.optJSONArray("assignees"));
        put(o, "assignees", assignees);
        put(o, "assignee", assignees.length() > 0 ? assignees.opt(0) : null);
        put(o, "labels", labels(m.optJSONArray("labels")));
        put(o, "milestone", milestone(obj(m, "milestone")));
        put(o, "comments", m.optInt("user_notes_count"));
        put(o, "html_url", str(m, "web_url"));
        put(o, "url", str(m, "web_url"));
        put(o, "created_at", time(str(m, "created_at")));
        put(o, "updated_at", time(str(m, "updated_at")));
        put(o, "closed_at", time(str(m, "closed_at")));
        put(o, "closed_by", user(obj(m, "closed_by")));
        put(o, "author_association", "NONE");
        JSONObject pr = newObj();
        put(pr, "merged_at", time(str(m, "merged_at")));
        put(pr, "html_url", str(m, "web_url"));
        put(o, "pull_request", pr);
        put(o, "reactions", reactionCounts(m));
        return o;
    }

    // ---------------------------------------------------------------- notes

    static JSONObject note(JSONObject n, String webBase) {
        JSONObject o = newObj();
        put(o, "id", n.optLong("id"));
        put(o, "body", str(n, "body"));
        put(o, "user", user(obj(n, "author")));
        put(o, "created_at", time(str(n, "created_at")));
        put(o, "updated_at", time(str(n, "updated_at")));
        put(o, "html_url", webBase != null ? webBase + "#note_" + n.optLong("id") : null);
        put(o, "author_association", "NONE");
        put(o, "reactions", reactionCounts(newObj()));
        return o;
    }

    // ---------------------------------------------------------------- commits and files

    static JSONObject gitUser(String name, String email, String date) {
        JSONObject o = newObj();
        put(o, "name", name);
        put(o, "email", email);
        put(o, "date", time(date));
        return o;
    }

    static JSONObject commit(JSONObject c, String project, String instance) {
        JSONObject o = newObj();
        String sha = str(c, "id");
        put(o, "sha", sha);
        JSONObject git = newObj();
        put(git, "message", str(c, "message") != null ? str(c, "message") : str(c, "title"));
        put(git, "author", gitUser(str(c, "author_name"), str(c, "author_email"),
                str(c, "authored_date")));
        put(git, "committer", gitUser(str(c, "committer_name"), str(c, "committer_email"),
                str(c, "committed_date")));
        put(git, "url", str(c, "web_url"));
        JSONObject tree = newObj();
        put(tree, "sha", sha);
        put(git, "tree", tree);
        put(o, "commit", git);
        put(o, "html_url", str(c, "web_url"));
        put(o, "url", str(c, "web_url"));
        JSONArray parents = new JSONArray();
        JSONArray ids = c.optJSONArray("parent_ids");
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                JSONObject p = newObj();
                put(p, "sha", ids.optString(i));
                parents.put(p);
            }
        }
        put(o, "parents", parents);
        JSONObject stats = obj(c, "stats");
        if (stats != null) {
            JSONObject s = newObj();
            put(s, "additions", stats.optInt("additions"));
            put(s, "deletions", stats.optInt("deletions"));
            put(s, "total", stats.optInt("total"));
            put(o, "stats", s);
        }
        return o;
    }

    static JSONObject diffFile(JSONObject d, String instance, String project, String ref) {
        JSONObject o = newObj();
        String newPath = str(d, "new_path");
        String oldPath = str(d, "old_path");
        String diff = str(d, "diff");
        boolean added = d.optBoolean("new_file");
        boolean deleted = d.optBoolean("deleted_file");
        boolean renamed = d.optBoolean("renamed_file");
        put(o, "filename", newPath);
        if (renamed) {
            put(o, "previous_filename", oldPath);
        }
        put(o, "status", added ? "added" : deleted ? "removed" : renamed ? "renamed" : "modified");
        int add = 0;
        int del = 0;
        if (diff != null) {
            for (String line : diff.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    add++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    del++;
                }
            }
        }
        put(o, "additions", add);
        put(o, "deletions", del);
        put(o, "changes", add + del);
        put(o, "patch", diff == null || diff.isEmpty() ? null : diff);
        put(o, "sha", d.optString("b_mode", ""));
        if (ref != null) {
            put(o, "raw_url", instance + "/" + project + "/-/raw/" + ref + "/" + newPath);
            put(o, "blob_url", instance + "/" + project + "/-/blob/" + ref + "/" + newPath);
        }
        return o;
    }

    static String statusState(String s) {
        if (s == null) {
            return "pending";
        }
        switch (s) {
            case "success":
            case "passed":
                return "success";
            case "failed":
                return "failure";
            case "canceled":
            case "cancelled":
                return "error";
            case "skipped":
                return "skipped";
            case "warning":
                return "warning";
            default:
                return "pending";
        }
    }
}
