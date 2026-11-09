package com.gh4a.utils;

import com.gh4a.utils.GitLabCompatInterceptor.Ctx;
import com.gh4a.utils.GitLabCompatInterceptor.Up;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.gh4a.utils.GitLabMapper.newObj;
import static com.gh4a.utils.GitLabMapper.put;

/**
 * Reaction counters for issues, merge requests and their notes. The REST API only lists award
 * emoji per object, GraphQL returns all of them in one request.
 */
final class GitLabReactions {
    private GitLabReactions() {
    }

    static final class Counts {
        final Map<String, Integer> byContent = new HashMap<>();

        JSONObject toJson() {
            JSONObject o = newObj();
            int total = 0;
            for (String k : new String[] {"+1", "-1", "laugh", "hooray", "confused", "heart",
                    "rocket", "eyes"}) {
                int v = byContent.containsKey(k) ? byContent.get(k) : 0;
                put(o, k, v);
                total += v;
            }
            put(o, "total_count", total);
            return o;
        }
    }

    static final class Result {
        Counts description = new Counts();
        final Map<Long, Counts> notes = new HashMap<>();
        final Map<Long, Counts> notesFlat = notes;
    }

    private static final Map<String, Result> CACHE = new HashMap<>();
    private static final Map<String, Long> CACHE_TIME = new HashMap<>();

    static JSONObject empty() {
        return new Counts().toJson();
    }

    static Result load(Ctx c, boolean mergeRequest, long iid) {
        String key = c.instance() + "|" + c.project() + "|" + mergeRequest + "|" + iid;
        synchronized (CACHE) {
            Long at = CACHE_TIME.get(key);
            if (at != null && System.currentTimeMillis() - at < 4_000) {
                return CACHE.get(key);
            }
        }
        Result result = new Result();
        try {
            String kind = mergeRequest ? "mergeRequest" : "issue";
            JSONObject body = newObj();
            put(body, "query", "query($p: ID!, $iid: String!) { project(fullPath: $p) {"
                    + " " + kind + "(iid: $iid) { awardEmoji { nodes { name } }"
                    + " notes(first: 100) { nodes { id awardEmoji { nodes { name } } } } } } }");
            JSONObject vars = newObj();
            put(vars, "p", c.p("owner") + "/" + c.p("repo"));
            put(vars, "iid", String.valueOf(iid));
            put(body, "variables", vars);
            Up up = c.call("POST", "/graphql", body);
            if (up.ok()) {
                JSONObject data = up.obj().optJSONObject("data");
                JSONObject project = data != null ? data.optJSONObject("project") : null;
                JSONObject target = project != null ? project.optJSONObject(kind) : null;
                if (target != null) {
                    result.description = counts(target.optJSONObject("awardEmoji"));
                    JSONObject notes = target.optJSONObject("notes");
                    JSONArray nodes = notes != null ? notes.optJSONArray("nodes") : null;
                    for (int i = 0; nodes != null && i < nodes.length(); i++) {
                        JSONObject n = nodes.optJSONObject(i);
                        String gid = n.optString("id");
                        long id = Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1));
                        result.notes.put(id, counts(n.optJSONObject("awardEmoji")));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // reactions are optional
        }
        synchronized (CACHE) {
            CACHE.put(key, result);
            CACHE_TIME.put(key, System.currentTimeMillis());
        }
        return result;
    }

    static void invalidate(Ctx c, boolean mergeRequest, long iid) {
        synchronized (CACHE) {
            CACHE_TIME.remove(c.instance() + "|" + c.project() + "|" + mergeRequest + "|" + iid);
        }
    }

    private static Counts counts(JSONObject awardEmoji) {
        Counts counts = new Counts();
        JSONArray nodes = awardEmoji != null ? awardEmoji.optJSONArray("nodes") : null;
        for (int i = 0; nodes != null && i < nodes.length(); i++) {
            String content = GitLabIssueRoutes.reactionContent(nodes.optJSONObject(i)
                    .optString("name"));
            if (content != null) {
                Integer old = counts.byContent.get(content);
                counts.byContent.put(content, old == null ? 1 : old + 1);
            }
        }
        return counts;
    }
}
