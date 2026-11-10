package com.gh4a.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import com.meisolsson.githubsdk.core.ServiceGenerator;
import com.meisolsson.githubsdk.model.GitHubComment;
import com.meisolsson.githubsdk.model.Issue;
import com.meisolsson.githubsdk.model.IssueEvent;
import com.meisolsson.githubsdk.model.IssueState;
import com.meisolsson.githubsdk.model.Label;
import com.meisolsson.githubsdk.model.Milestone;
import com.meisolsson.githubsdk.model.PullRequest;
import com.meisolsson.githubsdk.model.Release;
import com.meisolsson.githubsdk.model.Repository;
import com.meisolsson.githubsdk.model.Status;
import com.meisolsson.githubsdk.model.User;
import com.squareup.moshi.JsonAdapter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Files;

/**
 * Runs recorded GitLab answers (from gitlab.com and from a local GitLab 19.5) through the
 * mappers and parses the result with the SDK models, like the app does.
 */
@RunWith(RobolectricTestRunner.class)
public class GitLabMappingTest {
    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/test/resources/gitlab/" + name
                + ".json").toPath()), "UTF-8");
    }

    private static JSONArray array(String name) throws Exception {
        return new JSONArray(read(name));
    }

    private static <T> T parse(Class<T> type, JSONObject json) throws Exception {
        JsonAdapter<T> adapter = ServiceGenerator.moshi.adapter(type);
        T result = adapter.fromJson(json.toString());
        assertNotNull(result);
        return result;
    }

    @Test
    public void projectsBecomeRepositories() throws Exception {
        for (String name : new String[] {"gl_project", "lc_project"}) {
            Repository repo = parse(Repository.class, GitLabMapper.project(new JSONObject(read(name))));
            assertNotNull(name, repo.fullName());
            assertNotNull(name, repo.owner().login());
            assertNotNull(name, repo.defaultBranch());
            assertTrue(name, repo.fullName().endsWith("/" + repo.name()));
        }
        Repository hello = parse(Repository.class,
                GitLabMapper.project(new JSONObject(read("lc_project"))));
        assertEquals("root/hello", hello.fullName());
        assertEquals("root", hello.owner().login());
        assertFalse(hello.isPrivate());
        assertEquals(Integer.valueOf(1), hello.stargazersCount());
    }

    @Test
    public void nestedGroupsKeepTheirFullPathAsOwner() throws Exception {
        JSONObject project = new JSONObject(read("lc_project"));
        project.put("path_with_namespace", "tanuki-org/sub/deep");
        project.put("path", "deep");
        JSONObject ns = new JSONObject().put("id", 5).put("kind", "group")
                .put("full_path", "tanuki-org/sub").put("web_url", "http://x/groups/tanuki-org/sub");
        project.put("namespace", ns);
        Repository repo = parse(Repository.class, GitLabMapper.project(project));
        assertEquals("tanuki-org/sub", repo.owner().login());
        assertEquals("tanuki-org/sub/deep", repo.fullName());
    }

    @Test
    public void issuesBecomeIssuesWithColouredLabels() throws Exception {
        for (String name : new String[] {"gl_issues", "lc_issues"}) {
            JSONArray in = array(name);
            for (int i = 0; i < in.length(); i++) {
                Issue issue = parse(Issue.class, GitLabMapper.issue(in.getJSONObject(i), "x/y"));
                assertNotNull(issue.title());
                assertNotNull(issue.number());
                assertNotNull(issue.user().login());
                assertNotNull(issue.state());
                for (Label label : issue.labels()) {
                    assertNotNull(label.color());
                    assertFalse(label.color().startsWith("#"));
                }
            }
        }
    }

    @Test
    public void mergeRequestsBecomePullRequestsWithShiftedNumbers() throws Exception {
        for (String name : new String[] {"gl_mrs", "lc_mr1"}) {
            JSONArray in = name.equals("lc_mr1") ? new JSONArray().put(new JSONObject(read(name)))
                    : array(name);
            for (int i = 0; i < in.length(); i++) {
                JSONObject mr = in.getJSONObject(i);
                PullRequest pr = parse(PullRequest.class, GitLabMapper.pull(mr, "https://x"));
                assertEquals(Integer.valueOf(GitLabMapper.mrNumber(mr.getLong("iid"))), pr.number());
                assertTrue(GitLabUrls.isMergeRequestNumber(pr.number()));
                assertEquals(mr.getString("source_branch"), pr.head().ref());
                assertEquals(mr.getString("target_branch"), pr.base().ref());
                Issue asIssue = parse(Issue.class, GitLabMapper.mrAsIssue(mr, "https://x"));
                assertNotNull(asIssue.pullRequest());
                assertEquals(pr.number(), asIssue.number());
            }
        }
    }

    @Test
    public void mergedMergeRequestsAreClosedAndMerged() throws Exception {
        JSONObject mr = array("gl_mrs").getJSONObject(0);
        PullRequest pr = parse(PullRequest.class, GitLabMapper.pull(mr, "https://x"));
        assertEquals(IssueState.Closed, pr.state());
        assertTrue(pr.merged());
    }

    @Test
    public void notesBecomeComments() throws Exception {
        JSONArray notes = array("lc_notes1");
        for (int i = 0; i < notes.length(); i++) {
            GitHubComment c = parse(GitHubComment.class, GitLabMapper.note(notes.getJSONObject(i), null));
            assertNotNull(c.body());
            assertNotNull(c.reactions());
        }
    }

    @Test
    public void usersGroupsMilestonesAndLabelsParse() throws Exception {
        parse(User.class, GitLabMapper.user(new JSONObject(read("lc_user"))));
        parse(User.class, GitLabMapper.user(array("gl_user").getJSONObject(0)));
        User group = parse(User.class, GitLabMapper.group(new JSONObject(read("lc_group"))));
        assertEquals("tanuki-org", group.login());
        JSONArray milestones = array("lc_milestones");
        for (int i = 0; i < milestones.length(); i++) {
            Milestone m = parse(Milestone.class, GitLabMapper.milestone(milestones.getJSONObject(i)));
            assertNotNull(m.title());
            assertNotNull(m.number());
        }
        JSONArray labels = array("lc_labels");
        for (int i = 0; i < labels.length(); i++) {
            parse(Label.class, GitLabMapper.label(labels.getJSONObject(i)));
        }
    }

    @Test
    public void commitsAndDiffsParse() throws Exception {
        for (String name : new String[] {"gl_commits", "lc_branches"}) {
            if (!name.startsWith("gl_")) {
                continue;
            }
            JSONArray in = array(name);
            for (int i = 0; i < in.length(); i++) {
                JSONObject c = GitLabMapper.commit(in.getJSONObject(i), "a%2Fb", "https://x");
                parse(com.meisolsson.githubsdk.model.Commit.class, c);
            }
        }
        JSONObject commit = GitLabMapper.commit(new JSONObject(read("lc_commit")), "a", "https://x");
        JSONArray files = new JSONArray();
        JSONArray diffs = array("lc_commit_diff");
        for (int i = 0; i < diffs.length(); i++) {
            files.put(GitLabMapper.diffFile(diffs.getJSONObject(i), "https://x", "a/b", "main"));
        }
        commit.put("files", files);
        com.meisolsson.githubsdk.model.Commit parsed =
                parse(com.meisolsson.githubsdk.model.Commit.class, commit);
        assertNotNull(parsed.commit().message());
        assertFalse(parsed.files().isEmpty());
        assertTrue(parsed.files().get(0).additions() >= 0);
    }

    @Test
    public void statusesMapToGitHubStates() throws Exception {
        assertEquals("success", GitLabMapper.statusState("success"));
        assertEquals("failure", GitLabMapper.statusState("failed"));
        assertEquals("pending", GitLabMapper.statusState("running"));
        assertEquals("pending", GitLabMapper.statusState("created"));
        assertEquals("error", GitLabMapper.statusState("canceled"));
        JSONArray in = array("lc_statuses");
        for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.getJSONObject(i);
            JSONObject o = new JSONObject().put("id", s.getLong("id"))
                    .put("state", GitLabMapper.statusState(s.getString("status")))
                    .put("context", s.getString("name"));
            Status status = parse(Status.class, o);
            assertNotNull(status.state());
        }
    }

    @Test
    public void releasesParse() throws Exception {
        JSONObject r = GitLabRoutes.release(null, new JSONObject(read("lc_release")));
        Release release = parse(Release.class, r);
        assertEquals("v1.0", release.tagName());
        assertFalse(release.assets().isEmpty());
        JSONArray gl = array("gl_releases");
        for (int i = 0; i < gl.length(); i++) {
            parse(Release.class, GitLabRoutes.release(null, gl.getJSONObject(i)));
        }
    }

    @Test
    public void reactionCountsAreComplete() throws Exception {
        JSONObject counts = GitLabReactions.empty();
        for (String key : new String[] {"+1", "-1", "laugh", "hooray", "confused", "heart",
                "rocket", "eyes"}) {
            assertTrue(key, counts.has(key));
        }
    }

    @Test
    public void timesUseUtcWithoutFraction() {
        assertEquals("2026-11-09T22:10:09Z", GitLabMapper.time("2026-11-09T22:10:09.819Z"));
        assertEquals("2026-11-09T21:10:09Z", GitLabMapper.time("2026-11-09T22:10:09.819+01:00"));
        assertEquals("2027-01-01T00:00:00Z", GitLabMapper.time("2027-01-01"));
        assertNull(GitLabMapper.time(null));
    }

    @Test
    public void htmlCleanupFixesImagesAndEmoji() {
        String html = "<p><a href=\"http://h/p/-/blob/main/a.png\"><img src=\"data:image/gif;base64,AAA\""
                + " data-src=\"/p/-/blob/main/a.png\" alt=\"a\"></a> <gl-emoji data-name=\"tada\">T</gl-emoji>"
                + "<copy-code></copy-code></p><a href=\"/u/alice\">x</a>";
        String out = GitLabHtml.clean(html, "http://h");
        assertTrue(out, out.contains("src=\"http://h/p/-/raw/main/a.png\""));
        assertFalse(out, out.contains("gl-emoji"));
        assertFalse(out, out.contains("copy-code"));
        assertTrue(out, out.contains("href=\"http://h/u/alice\""));
    }

    @Test
    public void webUrlsConvertToAppUrlsAndBack() {
        Uri mr = GitLabUrls.toSynthetic(Uri.parse("https://gitlab.com/group/sub/proj/-/merge_requests/7"));
        assertEquals("github.com", mr.getHost());
        assertEquals("group/sub", mr.getPathSegments().get(0));
        assertEquals("proj", mr.getPathSegments().get(1));
        assertEquals("pull", mr.getPathSegments().get(2));
        assertEquals(String.valueOf(7 + GitLabCompatInterceptor.MR_OFFSET), mr.getPathSegments().get(3));

        Uri issue = GitLabUrls.toSynthetic(Uri.parse("https://gitlab.com/a/b/-/work_items/12"));
        assertEquals("issues", issue.getPathSegments().get(2));
        assertEquals("12", issue.getPathSegments().get(3));

        Uri blob = GitLabUrls.toSynthetic(Uri.parse("https://gitlab.com/a/b/-/blob/main/src/x.java"));
        assertEquals("blob", blob.getPathSegments().get(2));
        assertEquals("src", blob.getPathSegments().get(4));

        Uri project = GitLabUrls.toSynthetic(Uri.parse("https://gitlab.com/a/b"));
        assertEquals(2, project.getPathSegments().size());
        Uri user = GitLabUrls.toSynthetic(Uri.parse("https://gitlab.com/alice"));
        assertEquals(1, user.getPathSegments().size());
        assertNull(GitLabUrls.toSynthetic(Uri.parse("https://gitlab.com/explore")));

        Uri back = GitLabUrls.toWeb(mr, "https://gitlab.com");
        assertEquals("https://gitlab.com/group/sub/proj/-/merge_requests/7", back.toString());
        Uri issueBack = GitLabUrls.toWeb(issue, "https://gitlab.com");
        assertEquals("https://gitlab.com/a/b/-/issues/12", issueBack.toString());
    }

    @Test
    public void numbersShowTheMergeRequestMarker() {
        assertEquals("#5", GitLabUrls.formatNumber(5));
        assertEquals("!5", GitLabUrls.formatNumber(GitLabCompatInterceptor.MR_OFFSET + 5));
        assertEquals("5", GitLabUrls.formatNumberCompact(5));
    }

    @Test
    public void issueEventsParse() throws Exception {
        JSONObject ev = new JSONObject().put("id", 1).put("event", "renamed")
                .put("created_at", "2026-11-09T22:10:09Z")
                .put("rename", new JSONObject().put("from", "a").put("to", "b"));
        IssueEvent parsed = parse(IssueEvent.class, ev);
        assertEquals("a", parsed.rename().from());
    }
}
