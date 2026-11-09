package com.gh4a.fragment;

import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.ServiceFactory;
import com.gh4a.activities.WikiActivity;
import com.gh4a.adapter.CommonFeedAdapter;
import com.gh4a.adapter.RootAdapter;
import com.gh4a.model.Feed;
import com.gh4a.utils.RxUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.reactivex.Single;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WikiListFragment extends ListDataBaseFragment<Feed> implements
        RootAdapter.OnItemClickListener<Feed> {
    private String mUserLogin;
    private String mRepoName;
    private String mInitialPage;

    public static WikiListFragment newInstance(String owner, String repo, String initialPage) {
        WikiListFragment f = new WikiListFragment();
        Bundle args = new Bundle();
        args.putString("owner", owner);
        args.putString("repo", repo);
        args.putString("initial_page", initialPage);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUserLogin = getArguments().getString("owner");
        mRepoName = getArguments().getString("repo");
        mInitialPage = getArguments().getString("initial_page");
        getArguments().remove("initial_page");
    }

    @Override
    protected Single<List<Feed>> onCreateDataSingle(boolean bypassCache) {
        return Single.fromCallable(this::loadWikiPages)
                .compose(RxUtils::doInBackground);
    }

    /** Wiki pages come from the GitLab API; there is no feed to read as on GitHub. */
    private List<Feed> loadWikiPages() throws IOException, JSONException {
        List<Feed> pages = new ArrayList<>();
        String instance = Gh4Application.get().getAuthInstanceUrl();
        String token = Gh4Application.get().getAuthToken();
        if (instance == null) {
            return pages;
        }
        String project = Uri.encode(mUserLogin + "/" + mRepoName);
        OkHttpClient client = ServiceFactory.getHttpClientBuilder().build();
        Request request = new Request.Builder()
                .url(instance + "/api/v4/projects/" + project + "/wikis")
                .header("PRIVATE-TOKEN", token != null ? token : "")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == HttpURLConnection.HTTP_NOT_FOUND
                    || response.code() == HttpURLConnection.HTTP_FORBIDDEN) {
                return pages;
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            JSONArray array = new JSONArray(response.body().string());
            for (int i = 0; i < array.length() && i < 50; i++) {
                JSONObject page = array.getJSONObject(i);
                String slug = page.optString("slug");
                // The list has no rendered HTML, a single page does.
                Request detail = new Request.Builder()
                        .url(instance + "/api/v4/projects/" + project + "/wikis/"
                                + Uri.encode(slug) + "?render_html=1")
                        .header("PRIVATE-TOKEN", token != null ? token : "")
                        .build();
                String html = page.optString("content");
                try (Response rendered = client.newCall(detail).execute()) {
                    if (rendered.isSuccessful() && rendered.body() != null) {
                        html = new JSONObject(rendered.body().string()).optString("content", html)
                                .replace("&#x000A;", "\n");
                    }
                }
                pages.add(Feed.create(slug, page.optString("title", slug), html,
                        instance + "/" + mUserLogin + "/" + mRepoName + "/-/wikis/" + slug));
            }
        }
        return pages;
    }

    @Override
    protected RootAdapter<Feed, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        CommonFeedAdapter adapter = new CommonFeedAdapter(getActivity(), false);
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_wiki_updates_found;
    }

    @Override
    public void onItemClick(Feed feed) {
        openViewer(feed);
    }

    @Override
    protected void onAddData(RootAdapter<Feed, ?> adapter, List<Feed> data) {
        super.onAddData(adapter, data);

        if (mInitialPage != null) {
            for (Feed feed : data) {
                if (mInitialPage.equals(feed.getId())) {
                    openViewer(feed);
                    break;
                }
            }
            mInitialPage = null;
        }
    }

    private void openViewer(Feed feed) {
        startActivity(WikiActivity.makeIntent(getActivity(), mUserLogin, mRepoName, feed));
    }
}
