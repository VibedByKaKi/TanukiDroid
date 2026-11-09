package com.gh4a.fragment;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.ServiceFactory;
import com.gh4a.utils.ApiHelpers;
import com.gh4a.utils.RxUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.meisolsson.githubsdk.model.User;
import com.meisolsson.githubsdk.service.users.UserService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class LoginModeChooserFragment extends DialogFragment {
    public interface ParentCallback {
        void onLoginFinished(String token, User user, String instanceBaseUrl);
        void onLoginFailed(Throwable error);
        void onLoginCanceled();
    }

    private static final String[] PRESET_HOSTS = {
            "gitlab.com",
            "gitlab.gnome.org",
            "invent.kde.org",
            "salsa.debian.org",
            "gitlab.freedesktop.org",
            "gitgud.io",
            "framagit.org",
            "jihulab.com",
            "gitlab.alpinelinux.org",
    };

    private static final String CUSTOM_LABEL = "Custom";
    private static final String TAG = "TanukiDroidLogin";
    private static final String TOKEN_PAGE_PATH = "/-/user_settings/personal_access_tokens";
    private static final String LEGACY_TOKEN_PAGE_PATH = "/-/profile/personal_access_tokens";

    public static LoginModeChooserFragment newInstance() {
        return new LoginModeChooserFragment();
    }

    private ParentCallback mCallback;
    private TextView mInstanceLabel;
    private AutoCompleteTextView mInstanceDropdown;
    private TextInputLayout mCustomUrlWrapper;
    private TextInputEditText mCustomUrl;
    private TextInputEditText mTokenName;
    private WebView mWebView;
    private ProgressBar mProgress;
    private String mSelectedInstanceLabel;

    private boolean mLoginInProgress;
    private boolean mCheckingSession;
    private boolean mOnTokenPage;
    private boolean mTokenRequested;
    private String mCurrentInstanceUrl;
    private String mLastLoadedUrl;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final CompositeDisposable mDisposables = new CompositeDisposable();

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (!(context instanceof ParentCallback)) {
            throw new IllegalArgumentException("Activity must implement ParentCallback");
        }
        mCallback = (ParentCallback) context;
    }

    @NonNull
    @Override
    @SuppressLint("SetJavaScriptEnabled")
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View view = inflater.inflate(R.layout.login_dialog, null);

        mInstanceLabel = view.findViewById(R.id.instance_label);
        applyInstanceLabelSize();

        mInstanceDropdown = view.findViewById(R.id.instance_dropdown);
        mCustomUrlWrapper = view.findViewById(R.id.custom_url_wrapper);
        mCustomUrl = view.findViewById(R.id.custom_url);
        mTokenName = view.findViewById(R.id.token_name);
        mWebView = view.findViewById(R.id.login_webview);
        mProgress = view.findViewById(R.id.login_progress);

        String[] labels = new String[PRESET_HOSTS.length + 1];
        System.arraycopy(PRESET_HOSTS, 0, labels, 0, PRESET_HOSTS.length);
        labels[PRESET_HOSTS.length] = CUSTOM_LABEL;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, labels);
        mInstanceDropdown.setAdapter(adapter);
        mSelectedInstanceLabel = PRESET_HOSTS[0];
        mInstanceDropdown.setText(mSelectedInstanceLabel, false);
        mInstanceDropdown.setOnItemClickListener((parent, itemView, position, id) -> {
            mSelectedInstanceLabel = labels[position];
            boolean custom = isCustomSelected();
            mCustomUrlWrapper.setVisibility(custom ? View.VISIBLE : View.GONE);
            reloadWebViewIfReady();
        });

        mCustomUrl.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (isCustomSelected()) {
                    scheduleReloadWebView();
                }
            }
        });

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        // Start signed out, so that adding a second account does not reuse the first one's session.
        cookieManager.removeAllCookies(null);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(mWebView, true);
        }

        mWebView.addJavascriptInterface(new TokenBridge(), "TanukiLogin");
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                mLastLoadedUrl = url;
                CookieManager.getInstance().flush();
                if (mOnTokenPage) {
                    requestTokenFromPage(url);
                } else {
                    maybeHandleSession();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.add_account)
                .setView(view)
                .setNegativeButton(R.string.cancel, (d, which) -> d.cancel())
                .create();

        dialog.setOnShowListener(d -> {
            applyInstanceLabelSize();
            Window window = dialog.getWindow();
            if (window != null && getActivity() != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
                window.setLayout(
                        (int) (metrics.widthPixels * 0.96f),
                        (int) (metrics.heightPixels * 0.92f));
            }
            if (mCurrentInstanceUrl == null) {
                reloadWebViewIfReady();
            }
        });

        return dialog;
    }

    private void applyInstanceLabelSize() {
        if (mInstanceLabel != null) {
            // AlertDialog themes re-apply a small text appearance after inflate.
            mInstanceLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        applyInstanceLabelSize();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacksAndMessages(null);
        mDisposables.clear();
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mCallback.onLoginCanceled();
    }

    private boolean isCustomSelected() {
        return CUSTOM_LABEL.equals(mSelectedInstanceLabel);
    }

    private String resolveInstanceBaseUrl() {
        if (isCustomSelected()) {
            Editable editable = mCustomUrl.getText();
            String raw = editable != null ? editable.toString().trim() : "";
            if (TextUtils.isEmpty(raw)) {
                return null;
            }
            if (!raw.contains("://")) {
                raw = "https://" + raw;
            }
            return Gh4Application.normalizeInstanceUrl(raw);
        }
        if (TextUtils.isEmpty(mSelectedInstanceLabel)) {
            return null;
        }
        return "https://" + mSelectedInstanceLabel;
    }

    private String getTokenName() {
        Editable editable = mTokenName.getText();
        String name = editable != null ? editable.toString().trim() : "";
        if (TextUtils.isEmpty(name)) {
            name = getString(R.string.login_token_name_default);
        }
        return name;
    }

    private void scheduleReloadWebView() {
        mHandler.removeCallbacks(mReloadRunnable);
        mHandler.postDelayed(mReloadRunnable, 600);
    }

    private final Runnable mReloadRunnable = this::reloadWebViewIfReady;

    private void reloadWebViewIfReady() {
        if (mWebView == null || mLoginInProgress) {
            return;
        }
        String instance = resolveInstanceBaseUrl();
        if (instance == null) {
            return;
        }
        if (instance.equals(mCurrentInstanceUrl)) {
            return;
        }
        Uri uri = Uri.parse(instance);
        if (TextUtils.isEmpty(uri.getHost())) {
            mCustomUrlWrapper.setError(getString(R.string.credentials_error_empty));
            return;
        }
        mCustomUrlWrapper.setErrorEnabled(false);
        mCurrentInstanceUrl = instance;
        mLastLoadedUrl = null;
        mLoginInProgress = false;
        mCheckingSession = false;
        mOnTokenPage = false;
        mTokenRequested = false;
        mWebView.loadUrl(instance + "/users/sign_in");
    }

    private void maybeHandleSession() {
        if (mLoginInProgress || mCheckingSession || mWebView == null) {
            return;
        }
        String instance = mCurrentInstanceUrl;
        if (instance == null) {
            return;
        }
        // Anonymous visits also get a session cookie; wait until the user leaves the login page.
        if (isAuthPage(mLastLoadedUrl)) {
            return;
        }
        if (!isOnInstance(mLastLoadedUrl, instance)) {
            return;
        }
        String cookies = CookieManager.getInstance().getCookie(instance);
        if (!hasSessionCookie(cookies)) {
            return;
        }
        mCheckingSession = true;
        mLoginInProgress = true;
        setBusy(true);
        Log.i(TAG, "Session detected on " + mLastLoadedUrl + "; creating token on the web UI");
        // The token is created by a script running in the page itself, so the request carries the
        // same cookies, user agent and browser checks as the user's own login.
        mOnTokenPage = true;
        mTokenRequested = false;
        mWebView.loadUrl(instance + TOKEN_PAGE_PATH);
    }

    private static boolean isOnInstance(String url, String instance) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        Uri page = Uri.parse(url);
        Uri base = Uri.parse(instance);
        return page.getHost() != null && page.getHost().equalsIgnoreCase(base.getHost());
    }

    private void requestTokenFromPage(String url) {
        if (mWebView == null || mTokenRequested || mCurrentInstanceUrl == null
                || !isOnInstance(url, mCurrentInstanceUrl)) {
            return;
        }
        if (isAuthPage(url)) {
            // Not signed in after all (anonymous session cookie). Wait for the real login.
            resetToLogin(false);
            return;
        }
        mTokenRequested = true;
        String script = TOKEN_SCRIPT.replace("__NAME__", JSONObject.quote(getTokenName()));
        mWebView.evaluateJavascript(script, null);
    }

    private void resetToLogin(boolean reload) {
        mOnTokenPage = false;
        mTokenRequested = false;
        mCheckingSession = false;
        mLoginInProgress = false;
        setBusy(false);
        if (reload && mWebView != null && mCurrentInstanceUrl != null) {
            mWebView.loadUrl(mCurrentInstanceUrl + "/users/sign_in");
        }
    }

    private static final String TOKEN_SCRIPT = ("(async function() {"
            + "  var name = __NAME__;"
            + "  function done(o) { TanukiLogin.onResult(JSON.stringify(o)); }"
            + "  try {"
            + "    var meta = document.querySelector('meta[name=\"csrf-token\"]');"
            + "    var csrf = meta ? meta.content : '';"
            + "    if (!csrf) { done({error: 'no_csrf'}); return; }"
            + "    var base = location.origin;"
            + "    var lastError = 'unknown error';"
            + "    var daysList = [364, 90, 30, 7, 1];"
            + "    for (var i = 0; i < daysList.length; i++) {"
            + "      var d = new Date(Date.now() + daysList[i] * 86400000).toISOString().slice(0, 10);"
            + "      var paths = ['" + TOKEN_PAGE_PATH + "', '" + LEGACY_TOKEN_PAGE_PATH + "'];"
            + "      for (var p = 0; p < paths.length; p++) {"
            // Current GitLab reads flat parameters, older versions nested ones. Send both.
            + "        var fields = {name: name, expires_at: d, scopes: ['api']};"
            + "        var payload = Object.assign({personal_access_token: fields}, fields);"
            + "        var r = await fetch(base + paths[p], {method: 'POST', credentials: 'same-origin',"
            + "          headers: {'X-CSRF-Token': csrf, 'Accept': 'application/json',"
            + "            'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest'},"
            + "          body: JSON.stringify(payload)});"
            + "        var text = await r.text();"
            + "        var j = null;"
            + "        try { j = JSON.parse(text); } catch (e) {}"
            + "        var found = j && (j.token || j.new_token);"
            + "        if (found) { done({token: found}); return; }"
            + "        if (r.status == 404) { continue; }"
            + "        lastError = 'HTTP ' + r.status + ' ' + (j && j.errors ? JSON.stringify(j.errors) : text.slice(0, 200));"
            + "        break;"
            + "      }"
            + "    }"
            + "    var q = 'mutation($input: PersonalAccessTokenCreateInput!) {'"
            + "      + ' personalAccessTokenCreate(input: $input) { token errors } }';"
            + "    for (var k = 0; k < daysList.length; k++) {"
            + "      var d2 = new Date(Date.now() + daysList[k] * 86400000).toISOString().slice(0, 10);"
            + "      var gr = await fetch(base + '/api/graphql', {method: 'POST', credentials: 'same-origin',"
            + "        headers: {'X-CSRF-Token': csrf, 'Content-Type': 'application/json',"
            + "          'Accept': 'application/json'},"
            + "        body: JSON.stringify({query: q, variables: {input: {name: name, scopes: ['API'],"
            + "          expiresAt: d2}}})});"
            + "      var gj = await gr.json();"
            + "      var res = gj && gj.data && gj.data.personalAccessTokenCreate;"
            + "      if (res && res.token) { done({token: res.token}); return; }"
            + "      lastError += ' | graphql: ' + JSON.stringify((res && res.errors) || gj.errors || gj);"
            + "    }"
            + "    done({error: lastError});"
            + "  } catch (e) { done({error: String(e)}); }"
            + "})();");

    private class TokenBridge {
        @JavascriptInterface
        public void onResult(String json) {
            mHandler.post(() -> handleTokenResult(json));
        }
    }

    private void handleTokenResult(String json) {
        if (mWebView == null || !mOnTokenPage) {
            return;
        }
        String token = null;
        String error = null;
        try {
            JSONObject obj = new JSONObject(json);
            token = obj.optString("token", null);
            error = obj.optString("error", null);
        } catch (JSONException e) {
            error = "Unreadable response: " + json;
        }
        if (!TextUtils.isEmpty(token)) {
            Log.i(TAG, "Token created, fetching user");
            mOnTokenPage = false;
            // The token replaces the web session.
            CookieManager.getInstance().removeAllCookies(null);
            finishWithToken(mCurrentInstanceUrl, token);
        } else if ("no_csrf".equals(error)) {
            // Landed on a page without a session (for example an interstitial); retry on login.
            resetToLogin(false);
        } else {
            failLogin(new IOException("Token creation failed on " + mCurrentInstanceUrl
                    + ": " + error));
        }
    }

    private static boolean isAuthPage(String url) {
        if (TextUtils.isEmpty(url)) {
            return true;
        }
        String path = Uri.parse(url).getPath();
        if (path == null) {
            return true;
        }
        return path.startsWith("/users/")
                || path.startsWith("/-/users/")
                || path.contains("/sign_in")
                || path.contains("/sign_up")
                || path.startsWith("/oauth")
                || path.startsWith("/-/google_api/auth")
                || path.startsWith("/users/auth");
    }

    private static boolean hasSessionCookie(String cookies) {
        if (TextUtils.isEmpty(cookies)) {
            return false;
        }
        for (String part : cookies.split(";")) {
            String name = part.trim();
            int eq = name.indexOf('=');
            if (eq > 0) {
                name = name.substring(0, eq);
            }
            // GitLab names its session cookie _gitlab_session unless the admin changed it.
            // Anonymous visitors get one too, so the caller also checks the page URL.
            if (name.toLowerCase(Locale.US).contains("session")) {
                return true;
            }
        }
        return false;
    }

    private void setBusy(boolean busy) {
        if (mProgress != null) {
            mProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (mWebView != null) {
            mWebView.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        }
        mInstanceDropdown.setEnabled(!busy);
        mCustomUrl.setEnabled(!busy);
        mTokenName.setEnabled(!busy);
    }

    private void finishWithToken(String instance, String token) {
        mDisposables.add(
                Single.fromCallable(() -> {
                            UserService userService = ServiceFactory.get(UserService.class, true,
                                    null, token, null, instance);
                            return userService.getUser()
                                    .map(ApiHelpers::throwOnFailure)
                                    .blockingGet();
                        })
                        .compose(RxUtils::doInBackground)
                        .subscribe(user -> {
                            mCallback.onLoginFinished(token, user, instance);
                            dismissAllowingStateLoss();
                        }, this::failLogin)
        );
    }

    private void failLogin(Throwable error) {
        mLoginInProgress = false;
        mCheckingSession = false;
        setBusy(false);
        mCallback.onLoginFailed(error);
        dismissAllowingStateLoss();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
