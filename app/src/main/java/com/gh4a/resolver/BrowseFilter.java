package com.gh4a.resolver;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.utils.IntentUtils;

public class BrowseFilter extends AppCompatActivity {
    private static final String EXTRA_INITIAL_COMMENT = "initial_comment";

    public static Intent makeRedirectionIntent(Context context, Uri uri,
            IntentUtils.InitialCommentMarker initialComment) {
        Intent intent = new Intent(context, BrowseFilter.class);
        intent.setData(uri);
        intent.putExtra(EXTRA_INITIAL_COMMENT, initialComment);
        return intent;
    }

    public void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.TransparentTheme);

        super.onCreate(savedInstanceState);

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        int flags = getIntent().getFlags() & ~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        if ((flags & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)) != 0) {
            flags |= Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        }
        IntentUtils.InitialCommentMarker initialComment =
                getIntent().getParcelableExtra(EXTRA_INITIAL_COMMENT);

        if (!selectAccountForHost(uri)) {
            IntentUtils.launchBrowser(this, uri, flags);
            finish();
            return;
        }

        LinkParser.ParseResult result = LinkParser.parseUri(this, uri, initialComment);
        if (result == null) {
            IntentUtils.launchBrowser(this, uri, flags);
            finish();
            return;
        }

        if (result.intent != null) {
            startActivity(result.intent.setFlags(flags));
            finish();
            return;
        }

        result.loadTask.setIntentFlags(flags);
        result.loadTask.setCompletionCallback(this::finish);
        result.loadTask.execute();
    }

    /**
     * Links to a GitLab instance are answered by the account of that instance. Returns false if
     * the link points to an instance without an account, which the browser has to open.
     */
    private boolean selectAccountForHost(Uri uri) {
        String host = uri.getHost();
        if (host == null || "github.com".equalsIgnoreCase(host)
                || "www.github.com".equalsIgnoreCase(host)) {
            // Links made by the app itself
            return true;
        }
        Gh4Application app = Gh4Application.get();
        String activeKey = app.getAuthAccountKey();
        String match = null;
        for (String key : app.getAccounts().keySet()) {
            String instance = app.getInstanceUrl(key);
            Uri instanceUri = instance != null ? Uri.parse(instance) : null;
            if (instanceUri == null || !host.equalsIgnoreCase(instanceUri.getHost())) {
                continue;
            }
            if (key.equals(activeKey)) {
                return true;
            }
            if (match == null) {
                match = key;
            }
        }
        if (match == null) {
            return false;
        }
        app.setActiveLogin(match);
        return true;
    }
}
