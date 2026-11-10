package com.gh4a.resolver;

import android.content.Intent;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import android.net.Uri;
import android.util.Pair;

import com.gh4a.ApiRequestException;
import com.gh4a.ServiceFactory;
import com.gh4a.activities.FileViewerActivity;
import com.gh4a.activities.RepositoryActivity;
import com.meisolsson.githubsdk.service.repositories.RepositoryBranchService;
import com.meisolsson.githubsdk.service.repositories.RepositoryCommitService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import io.reactivex.Single;

public class RefPathDisambiguationTask extends UrlLoadTask {
    private static final int MAX_REF_SEGMENTS = 4;
    private static final Pattern SHA1_PATTERN = Pattern.compile("[a-z0-9]{40}");

    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final String mRefAndPath;
    @VisibleForTesting
    protected final int mInitialPage;
    @VisibleForTesting
    protected final String mFragment;
    @VisibleForTesting
    protected final boolean mGoToFileViewer;

    public RefPathDisambiguationTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, String refAndPath, int initialPage) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mRefAndPath = refAndPath;
        mInitialPage = initialPage;
        mFragment = null;
        mGoToFileViewer = false;
    }

    public RefPathDisambiguationTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, String refAndPath, String fragment) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mRefAndPath = refAndPath;
        mFragment = fragment;
        mInitialPage = -1;
        mGoToFileViewer = true;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        return resolveRefAndPath()
                .map(refAndPathOpt -> {
                    if (!refAndPathOpt.isPresent()) {
                        return Optional.empty();
                    }
                    Pair<String, String> refAndPath = refAndPathOpt.get();
                    if (mGoToFileViewer && refAndPath.second != null) {
                        // parse line numbers from fragment
                        int highlightStart = -1, highlightEnd = -1;
                        // Line numbers are encoded either in the form #L12 or #L12-14
                        if (mFragment != null && mFragment.startsWith("L")) {
                            try {
                                int dashPos = mFragment.indexOf("-L");
                                if (dashPos > 0) {
                                    highlightStart = Integer.valueOf(mFragment.substring(1, dashPos));
                                    highlightEnd = Integer.valueOf(mFragment.substring(dashPos + 2));
                                } else {
                                    highlightStart = Integer.valueOf(mFragment.substring(1));
                                }
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                        return Optional.of(FileViewerActivity.makeIntentWithHighlight(mActivity,
                                mRepoOwner, mRepoName, refAndPath.first, refAndPath.second,
                                highlightStart, highlightEnd));
                    } else if (!mGoToFileViewer) {
                        return Optional.of(RepositoryActivity.makeIntent(mActivity,
                                mRepoOwner, mRepoName, refAndPath.first,
                                refAndPath.second, mInitialPage));
                    }
                    return Optional.empty();
                });
    }

    // returns ref, path
    private Single<Optional<Pair<String, String>>> resolveRefAndPath() throws ApiRequestException {
        // first check whether the path redirects to HEAD
        if (mRefAndPath.startsWith("HEAD")) {
            return Single.just(Optional.of(Pair.create("HEAD",
                    mRefAndPath.startsWith("HEAD/") ? mRefAndPath.substring(5) : null)));
        }
        // or whether the ref is a commit SHA-1
        int slashPos = mRefAndPath.indexOf('/');
        String potentialSha = slashPos > 0 ? mRefAndPath.substring(0, slashPos) : mRefAndPath;
        if (SHA1_PATTERN.matcher(potentialSha).matches()) {
            return Single.just(Optional.of(Pair.create(potentialSha,
                    slashPos > 0 ? mRefAndPath.substring(slashPos + 1) : "")));
        }

        var branchService = ServiceFactory.getForFullPagedLists(RepositoryBranchService.class, false);

        var commitService = ServiceFactory.get(RepositoryCommitService.class, false);

        // Ask for each candidate ref name directly instead of paging through every branch and
        // tag, which takes minutes on a big project. Branches win over tags.
        List<String> candidates = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        String[] parts = mRefAndPath.split("/");
        for (int i = 0; i < parts.length && i < MAX_REF_SEGMENTS; i++) {
            name.append(i > 0 ? "/" : "").append(parts[i]);
            candidates.add(name.toString());
        }
        Single<Optional<String>> result = Single.just(Optional.empty());
        for (String candidate : candidates) {
            result = result.flatMap(found -> found.isPresent() ? Single.just(found)
                    : branchService.getBranch(mRepoOwner, mRepoName, candidate)
                            .map(response -> response.isSuccessful()
                                    ? Optional.of(candidate) : Optional.<String>empty())
                            .onErrorReturnItem(Optional.empty()));
        }
        // Anything else that resolves to a commit is a tag (or an abbreviated commit id).
        for (String candidate : candidates) {
            result = result.flatMap(found -> found.isPresent() ? Single.just(found)
                    : commitService.getCommit(mRepoOwner, mRepoName, candidate)
                            .map(response -> response.isSuccessful()
                                    ? Optional.of(candidate) : Optional.<String>empty())
                            .onErrorReturnItem(Optional.empty()));
        }
        return result.map(this::determineRefAndPathFromFoundRef);
    }

    private Optional<Pair<String, String>> determineRefAndPathFromFoundRef(Optional<String> foundRef) {
        return foundRef.map(ref -> {
            if (mRefAndPath.equals(ref)) {
                return Pair.create(ref, null);
            } else {
                String refNameWithSlash = ref + "/";
                return Pair.create(ref, mRefAndPath.substring(refNameWithSlash.length()));
            }
        });
    }
}
