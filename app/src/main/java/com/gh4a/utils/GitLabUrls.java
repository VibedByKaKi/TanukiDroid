package com.gh4a.utils;

import android.net.Uri;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts between real GitLab web URLs ({@code https://host/group/sub/project/-/merge_requests/3})
 * and the GitHub-style URLs ({@code https://github.com/group%2Fsub/project/pull/1000000003}) that
 * the rest of the app, built for GitHub, works with.
 */
public final class GitLabUrls {
    public static final String SYNTHETIC_HOST = "github.com";

    private static final Set<String> RESERVED_FIRST = new HashSet<>(Arrays.asList(
            "-", "admin", "api", "assets", "dashboard", "explore", "help", "import", "jwt",
            "oauth", "profile", "projects", "search", "snippets", "uploads", "users", "groups"));

    private GitLabUrls() {
    }

    /** Offset that merge request numbers get so that they differ from issue numbers. */
    public static int mrOffset() {
        return GitLabCompatInterceptor.MR_OFFSET;
    }

    public static boolean isMergeRequestNumber(long number) {
        return number >= GitLabCompatInterceptor.MR_OFFSET;
    }

    /** "#5" for an issue, "!5" for a merge request. */
    public static String formatNumber(long number) {
        return isMergeRequestNumber(number)
                ? "!" + (number - GitLabCompatInterceptor.MR_OFFSET) : "#" + number;
    }

    /** "5" for an issue, "!5" for a merge request. */
    public static String formatNumberCompact(long number) {
        return isMergeRequestNumber(number)
                ? "!" + (number - GitLabCompatInterceptor.MR_OFFSET) : String.valueOf(number);
    }

    /**
     * Turns a real GitLab URL into the GitHub style one, or returns null when the URL does not
     * point to something the app can show.
     */
    public static Uri toSynthetic(Uri uri) {
        List<String> segs = new ArrayList<>(uri.getPathSegments());
        if (segs.isEmpty()) {
            return null;
        }
        String first = segs.get(0);
        if ("groups".equals(first) && segs.size() >= 2) {
            // /groups/<path>[/-/...] is a group page.
            List<String> ns = new ArrayList<>();
            for (int i = 1; i < segs.size() && !"-".equals(segs.get(i)); i++) {
                ns.add(segs.get(i));
            }
            return new Uri.Builder().scheme("https").authority(SYNTHETIC_HOST)
                    .appendPath(TextUtils.join("/", ns)).build();
        }
        if (RESERVED_FIRST.contains(first) && !"-".equals(first)) {
            return null;
        }
        int dash = segs.indexOf("-");
        List<String> ns = dash >= 0 ? segs.subList(0, dash) : segs;
        List<String> rest = dash >= 0 ? segs.subList(dash + 1, segs.size())
                : new ArrayList<String>();
        if (dash == 0) {
            return null;
        }
        Uri.Builder b = new Uri.Builder().scheme("https").authority(SYNTHETIC_HOST);
        if (ns.size() == 1) {
            // A single segment is a user or a group.
            b.appendPath(ns.get(0));
        } else {
            b.appendPath(TextUtils.join("/", ns.subList(0, ns.size() - 1)))
                    .appendPath(ns.get(ns.size() - 1));
        }
        if (rest.isEmpty()) {
            return b.encodedQuery(uri.getEncodedQuery()).encodedFragment(uri.getEncodedFragment())
                    .build();
        }
        String kind = rest.get(0);
        switch (kind) {
            case "merge_requests":
                if (rest.size() >= 2 && rest.get(1).matches("\\d+")) {
                    long number = Long.parseLong(rest.get(1)) + mrOffset();
                    b.appendPath("pull").appendPath(String.valueOf(number));
                    if (rest.size() >= 3 && ("diffs".equals(rest.get(2))
                            || "changes".equals(rest.get(2)))) {
                        b.appendPath("files");
                    } else if (rest.size() >= 3 && "commits".equals(rest.get(2))) {
                        b.appendPath("commits");
                    }
                } else {
                    b.appendPath("pulls");
                }
                break;
            case "issues":
            case "work_items":
                b.appendPath("issues");
                appendRest(b, rest, 1);
                break;
            case "commit":
                b.appendPath("commit");
                appendRest(b, rest, 1);
                break;
            case "commits":
                b.appendPath("commits");
                appendRest(b, rest, 1);
                break;
            case "tree":
            case "blob":
            case "raw":
            case "compare":
                b.appendPath(kind);
                appendRest(b, rest, 1);
                break;
            case "tags":
                b.appendPath("releases").appendPath("tag");
                appendRest(b, rest, 1);
                break;
            case "releases":
                b.appendPath("releases");
                if (rest.size() >= 2) {
                    b.appendPath("tag");
                    appendRest(b, rest, 1);
                }
                break;
            case "wikis":
                b.appendPath("wiki");
                appendRest(b, rest, 1);
                break;
            default:
                return null;
        }
        return b.encodedQuery(uri.getEncodedQuery()).encodedFragment(uri.getEncodedFragment())
                .build();
    }

    private static void appendRest(Uri.Builder b, List<String> rest, int from) {
        for (int i = from; i < rest.size(); i++) {
            b.appendPath(rest.get(i));
        }
    }

    /**
     * Turns a GitHub style URL, as built by the app, into the real GitLab web URL for
     * {@code instanceUrl}. URLs that are already GitLab style are returned unchanged.
     */
    public static Uri toWeb(Uri uri, String instanceUrl) {
        if (uri == null || TextUtils.isEmpty(instanceUrl)) {
            return uri;
        }
        List<String> segs = uri.getPathSegments();
        if (segs.contains("-") || segs.isEmpty()) {
            return uri;
        }
        Uri base = Uri.parse(instanceUrl);
        if (base.getHost() == null || !base.getHost().equalsIgnoreCase(uri.getHost())) {
            if (!SYNTHETIC_HOST.equals(uri.getHost())) {
                return uri;
            }
        }
        Uri.Builder b = base.buildUpon().path(null).clearQuery().fragment(null);
        String owner = segs.get(0);
        for (String part : owner.split("/")) {
            b.appendPath(part);
        }
        if (segs.size() == 1) {
            return withQuery(b, uri).build();
        }
        b.appendPath(segs.get(1));
        if (segs.size() == 2) {
            return withQuery(b, uri).build();
        }
        String action = segs.get(2);
        b.appendPath("-");
        switch (action) {
            case "pull":
                b.appendPath("merge_requests");
                if (segs.size() >= 4) {
                    try {
                        b.appendPath(String.valueOf(Long.parseLong(segs.get(3)) - mrOffset()));
                    } catch (NumberFormatException e) {
                        b.appendPath(segs.get(3));
                    }
                    if (segs.size() >= 6 && "commit".equals(segs.get(4))) {
                        // A commit of a merge request is shown by its diffs page
                        b.appendPath("diffs").appendQueryParameter("commit_id", segs.get(5));
                    } else if (segs.size() >= 5) {
                        b.appendPath("files".equals(segs.get(4)) ? "diffs" : segs.get(4));
                    }
                }
                return withQuery(b, uri).build();
            case "pulls":
                b.appendPath("merge_requests");
                return withQuery(b, uri).build();
            case "wiki":
                b.appendPath("wikis");
                break;
            case "releases":
                if (segs.size() >= 4 && "tag".equals(segs.get(3))) {
                    b.appendPath("releases");
                    for (int i = 4; i < segs.size(); i++) {
                        b.appendPath(segs.get(i));
                    }
                    return withQuery(b, uri).build();
                }
                b.appendPath("releases");
                break;
            default:
                b.appendPath(action);
                break;
        }
        for (int i = 3; i < segs.size(); i++) {
            b.appendPath(segs.get(i));
        }
        return withQuery(b, uri).build();
    }

    private static Uri.Builder withQuery(Uri.Builder b, Uri original) {
        if (original.getEncodedQuery() != null) {
            b.encodedQuery(original.getEncodedQuery());
        }
        if (original.getEncodedFragment() != null) {
            b.encodedFragment(original.getEncodedFragment());
        }
        return b;
    }
}
