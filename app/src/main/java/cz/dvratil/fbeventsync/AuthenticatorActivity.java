/*
    Copyright (C) 2017  Daniel Vrátil <me@dvratil.cz>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package cz.dvratil.fbeventsync;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.loopj.android.http.JsonHttpResponseHandler;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

import cz.msebera.android.httpclient.Header;

public class AuthenticatorActivity extends AccountAuthenticatorActivity {

    public static String ARG_AUTH_TOKEN_TYPE = "cz.dvratil.fbeventsync.AuthenticatorActivity.AuthType";
    public static String ARG_IS_ADDING_NEW_ACCOUNT = "cz.dvratil.fbeventsync.AuthenticatorActivity.IsAddingNewAccount";

    public static final String TOKEN_SCOPE = "me,user_events";

    private static final int PERMISSION_REQUEST_INTERNET = 1;

    private AccountManager mAccountManager = null;
    private String mAuthTokenType = null;
    private String mAccessToken = null;
    private String mICalKey = null;
    private String mICalUid = null;

    private WebView mWebView = null;
    private ProgressBar mProgressBar = null;
    private TextView mProgressLabel = null;

    private Logger mLogger = null;

    private boolean mEventsPageReached = false;

    private void debugICalUrl() {
        mProgressBar.setVisibility(View.GONE);
        mProgressLabel.setVisibility(View.GONE);
        mWebView.setVisibility(View.VISIBLE);
        findViewById(R.id.sendWebViewBtn).setVisibility(View.VISIBLE);
    }

    protected void onProceedDebugICal(View view) {

        final Context context = this;

        new AlertDialog.Builder(this)
                .setTitle("Proceed?")
                .setMessage("The HTML source code of the displayed page will be sent to the developer. " +
                            "The developer promises to not share the email and the data in it with anyone " +
                            "and that he will delete the email once he has obtain the necessary information.\n\n" +
                            "Do you still want to proceed?")
                .setPositiveButton("Yes (will open email client with the page attached)", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        File dir = new File(context.getFilesDir(), "debug");
                        if (!dir.exists()) {
                            if (!dir.mkdir()) {
                                Toast.makeText(context, "Error creating directory", Toast.LENGTH_SHORT).show();
                                finish();
                                return;
                            }
                        }
                        mWebView.saveWebArchive(getFilesDir() + "/debug/events.mhtml", false,
                                new ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String value) {
                                        Intent intent = new Intent(Intent.ACTION_SEND);
                                        intent.setType("message/rfc822");
                                        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{ "me@dvratil.cz" });
                                        intent.putExtra(Intent.EXTRA_SUBJECT, "FBEventSync events page");

                                        intent.putExtra(Intent.EXTRA_TEXT, "The events page source code is in attachment.");

                                        File file = new File(getFilesDir(), "debug/events.mhtml");
                                        if (!file.exists() || !file.canRead()) {
                                            Toast.makeText(context, "Couldn't save the events page. Please contact the developer", Toast.LENGTH_SHORT).show();
                                            finish();
                                            return;
                                        }

                                        Uri contentUri = FileProvider.getUriForFile(
                                                context, getString(R.string.fileprovider_authority), file);
                                        intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                                        startActivity(Intent.createChooser(intent, getResources().getString(R.string.log_send_email_action)));
                                        finish();
                                    }
                                });
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    protected void onBirthdayLinkExtracted(String s) {
        mLogger.debug("AUTH","Found bday URL");

        if (s.isEmpty()) {
            Toast.makeText(this, "Authentication error: failed to retrieve birthday calendar", Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }

        // Remove opening and trailing quotes that come from JavaScript
        final Uri icalUri = Uri.parse(s.substring(1, s.length() - 1));
        mICalKey = icalUri.getQueryParameter("key");
        mICalUid = icalUri.getQueryParameter("uid");
        if (mICalKey == null || mICalKey.isEmpty() || mICalUid == null || mICalUid.isEmpty()) {
            final Context context = this;
            new AlertDialog.Builder(this)
                    .setTitle("Error retrieving iCal URL")
                    .setMessage("There was an error parsing the Facebook URL. Would you be willing to " +
                                "share the Facebook webpage with the developer so he can fix the app?\n" +
                                "WARNING: This will leak some of your private data to the developer " +
                                "(some of you upcoming events and friends birthday events). You wil see " +
                                "all of it on the next page and you will still be able to decide not to send " +
                                "anything.")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mProgressBar.setVisibility(View.GONE);
                            mProgressLabel.setVisibility(View.GONE);
                            mWebView.setVisibility(View.VISIBLE);
                            findViewById(R.id.sendWebViewBtn).setVisibility(View.VISIBLE);

                            mWebView.saveWebArchive(getFilesDir() + "/events.mhtml");
                        }
                    })
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mLogger.error("AUTH", "Failed to retrieve iCal URL! The raw URL was " + icalUri.toString());
                            Toast.makeText(context, getString(R.string.auth_calendar_uri_error_toast), Toast.LENGTH_SHORT)
                                    .show();
                            finish();
                        }
                    })
                    .show();
            return;
        }

        mProgressLabel.setText(getString(R.string.auth_progress_retrieving_userinfo));
        fetchUserInfo(mAccessToken);
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().requestFeature(Window.FEATURE_PROGRESS);
        setContentView(R.layout.activity_authenticator);

        final AccountAuthenticatorActivity activity = this;

        mLogger = Logger.getInstance(this);
        mProgressBar = findViewById(R.id.authProgressBar);
        mProgressLabel = findViewById(R.id.authProgressString);
        mWebView = findViewById(R.id.webview);
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                activity.setProgress(newProgress);
            }
        });
        mWebView.setWebViewClient(new WebViewClient() {

            @Override
            @SuppressWarnings("deprecation") // Deprecated in API level 23
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(activity, "Authentication error: " + description, Toast.LENGTH_LONG)
                        .show();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Uri uri = Uri.parse(url);

                if (uri.getPath().contains("/login.php")) {
                    mLogger.debug("AUTH", "Reached login.php");
                    mWebView.setVisibility(View.VISIBLE);
                    mProgressBar.setVisibility(View.GONE);
                    mProgressLabel.setVisibility(View.GONE);
                } else if (uri.getPath().equals("/connect/login_success.html")) {
                    // TODO: Check if all privileges were granted
                    mLogger.debug("AUTH", "Reached login_success with token");
                    mWebView.setVisibility(View.GONE);
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressLabel.setVisibility(View.VISIBLE);
                    mProgressLabel.setText(getString(R.string.auth_progress_retrieving_calendars));

                    mAccessToken = Uri.parse("http://localhost/?" + uri.getFragment()).getQueryParameter("access_token");

                    // Use a desktop user-agent to make sure we get a desktop version - otherwise we
                    // won't be able to get to the birthday link
                    mWebView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:25.0) Gecko/20100101 Firefox/25.0");
                    mWebView.loadUrl("https://www.facebook.com/events");
                } else if (uri.getPath().equals("/events/")) {
                    if (mEventsPageReached) {
                        return;
                    }
                    mEventsPageReached = true;
                    mLogger.debug("AUTH","Reached /events/ page, extracting iCal link");
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                        class JSObject {
                            @JavascriptInterface
                            public void linkExtracted(String s) {
                                onBirthdayLinkExtracted(s);
                            }
                        }

                        view.addJavascriptInterface(new JSObject(), "android");
                        view.loadUrl(
                                "javascript:(function() { " +
                                        "  var elems = document.getElementByTagName(\"a\");" +
                                        "  for (var i = 0; i < elems.length; i++) {" +
                                        "    var link = elems[i];" +
                                        "    if (link.href.startsWith(\"webcal://www.facebook.com/ical/b.php\")) {" +
                                        "        android.linkExtracted(link.href); " +
                                        "        return;" +
                                        "    }" +
                                        "  }" +
                                        "  android.linkExtracted(\"\");" +
                                        "})();");
                    } else {
                        view.evaluateJavascript(
                                "(function() { " +
                                        "  var elems = document.getElementsByTagName(\"a\");" +
                                        "  for (var i = 0; i < elems.length; i++) {" +
                                        "    var link = elems[i];" +
                                        "    if (link.href.startsWith(\"webcal://www.facebook.com/ical/b.php\")) {" +
                                        "      return link.href;" +
                                        "    }" +
                                        "  }" +
                                        "})();",
                                new ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String s) {
                                        onBirthdayLinkExtracted(s);
                                    }
                                });
                    }
                }
            }
        });

        mAccountManager = AccountManager.get(getBaseContext());
        mAuthTokenType = getIntent().getStringExtra(ARG_AUTH_TOKEN_TYPE);

        // Once the check is finished it will call startLogin()
        checkInternetPermission();
    }


    private void startLogin() {
        Uri uri = new Uri.Builder()
                    .scheme("https")
                    .authority("www.facebook.com")
                    .path("/v2.9/dialog/oauth")
                    .appendQueryParameter("client_id", getString(R.string.facebook_app_id))
                    .appendQueryParameter("redirect_uri", "https://www.facebook.com/connect/login_success.html")
                    .appendQueryParameter("response_type", "token")
                    .appendQueryParameter("scopes", TOKEN_SCOPE)
                    .build();
        mWebView.loadUrl(uri.toString());
    }

    private void fetchUserInfo(final String accessToken) {
        final AccountAuthenticatorActivity activity = this;
        Graph.me(accessToken, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                try {
                    String accountName = response.getString("name");
                    createAccount(accessToken, accountName);
                } catch (org.json.JSONException e) {
                    mLogger.error("AUTH","JSON exception: %s", e.getMessage());
                    Toast.makeText(activity, getString(R.string.auth_account_creation_error_toast),
                            Toast.LENGTH_SHORT)
                            .show();
                    activity.finish();
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                if (errorResponse != null) {
                    try {
                        JSONObject err = errorResponse.getJSONObject("error");
                        int errCode = err.getInt("code");
                        if (errCode == 4) {
                            mLogger.error("AUTH", "FetchUserInfo: rate limiting error");
                            Toast.makeText(activity, getString(R.string.auth_rate_limiting_toast), Toast.LENGTH_SHORT)
                                    .show();
                            activity.finish();
                            return;
                        }
                    } catch (org.json.JSONException e) {
                        // pass
                    }
                    mLogger.error("AUTH","FetchUserInfo failure: %s", errorResponse.toString());
                } else {
                    mLogger.error("AUTH","FetchUserInfo failure: unknown error");
                }
                Toast.makeText(activity, getString(R.string.auth_account_creation_error_toast), Toast.LENGTH_SHORT)
                        .show();
                activity.finish();
            }
        });
    }

    private void createAccount(String accessToken, String accountName) {
        mLogger.debug("AUTH", "Creating account %s", accountName);
        Intent intent = getIntent();
        Account account = new Account(accountName, intent.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE));
        if (intent.getBooleanExtra(ARG_IS_ADDING_NEW_ACCOUNT, false)) {
            mAccountManager.addAccountExplicitly(account, null, null);

            ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true);
        }

        mAccountManager.setUserData(account, Authenticator.DATA_BDAY_URI, null); // clear the legacy storage
        mAccountManager.setAuthToken(account, Authenticator.FB_OAUTH_TOKEN, accessToken);
        mAccountManager.setAuthToken(account, Authenticator.FB_KEY_TOKEN, mICalKey);
        mAccountManager.setAuthToken(account, Authenticator.FB_UID_TOKEN, mICalUid);

        Intent result = new Intent();
        result.putExtra(AccountManager.KEY_ACCOUNT_NAME, accountName);
        result.putExtra(AccountManager.KEY_ACCOUNT_TYPE, intent.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE));
        String authTokenType = intent.getStringExtra(AuthenticatorActivity.ARG_AUTH_TOKEN_TYPE);
        if (authTokenType != null && authTokenType.equals(Authenticator.FB_KEY_TOKEN)) {
            result.putExtra(AccountManager.KEY_AUTHTOKEN, mICalKey);
        } else if (authTokenType != null && authTokenType.equals(Authenticator.FB_UID_TOKEN)) {
            result.putExtra(AccountManager.KEY_AUTHTOKEN, mICalUid);
        } else {
            result.putExtra(AccountManager.KEY_AUTHTOKEN, accessToken);
        }

        setAccountAuthenticatorResult(result.getExtras());
        setResult(RESULT_OK, result);

        CalendarSyncAdapter.updateSync(this);

        Toast.makeText(this, R.string.auth_account_creation_success_toast, Toast.LENGTH_SHORT)
                .show();
        finish();
    }

    private void checkInternetPermission() {
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.INTERNET },
                    PERMISSION_REQUEST_INTERNET);
        } else {
            startLogin();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSION_REQUEST_INTERNET:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLogin();
                } else {
                    // TODO: What to do when we don't get the permissions?
                    finish();
                }
                break;
        }
    }
}
