package com.example.fetch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fetch — a Downloader-style app for Fire TV: JS-enabled WebView browser,
 * direct APK downloads to the shared Downloads folder, a Downloads
 * browser with install support, full-screen remote mouse cursor with
 * hold-to-long-press menus, speed-dial favorites, ad-host blocking,
 * settings tab, and delete-APK-after-install.
 * Framework APIs only, minSdk/target 25 (Fire OS 6 / Android 7.1.1).
 *
 * v1.1: cursor overlay covers the whole window (tabs/toolbar included)
 * and taps are dispatched to the window root; holding the center button
 * long-presses (link menu on links, app menu elsewhere); PgUp/PgDn
 * buttons + PAGE / FF / REW keys + cursor edge-scrolling fix page
 * scrolling; new Settings tab (ad-block, JavaScript, desktop UA,
 * delete-after-install, cursor speed, clear cache).
 */
public class MainActivity extends Activity {

    static final String PREFS = "fetch_prefs";
    static final String KEY_FAVS = "favs";
    static final String KEY_DELETE_AFTER = "delete_after_install";
    static final String KEY_LAST_FILE = "last_installed_file";
    static final String KEY_LAUNCH_TIME = "install_launch_time";
    private static final String KEY_ADBLOCK = "adblock";
    private static final String KEY_JS = "javascript";
    private static final String KEY_DESKTOP_UA = "desktop_ua";
    private static final String KEY_CURSOR_STEP = "cursor_step";
    private static final String HOME_URL = "about:blank";
    private static final int REQ_STORAGE = 42;
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int BG = Color.parseColor("#1B2226");
    private static final int ACCENT = Color.parseColor("#FF7043");
    private static final int TEXT = Color.parseColor("#ECEFF1");
    private static final int DIM = Color.parseColor("#90A4AE");

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout windowRoot;
    private Button tabBrowser, tabDownloads, tabFavs, tabSettings;
    private View browserPane, downloadsPane, favsPane, settingsPane;

    private EditText urlBar;
    private WebView webView;
    private ProgressBar pageProgress;
    private Button btnBack, btnFwd, btnMouse;
    private CursorView cursorView;
    private boolean mouseMode = false;
    private float cursorX = 480f, cursorY = 270f;
    private long centerDownAt = 0;
    private long lastMenuShownAt = 0;

    private LinearLayout dlRow;
    private ProgressBar dlProgress;
    private TextView dlText;
    private volatile DownloadTask currentTask;

    private ListView dlList;
    private final List<File> dlFiles = new ArrayList<>();
    private DownloadsAdapter dlAdapter;

    private GridView favGrid;
    private final List<String[]> favs = new ArrayList<>(); // [name, url]
    private FavsAdapter favAdapter;

    private Button setAdblockBtn, setJsBtn, setUaBtn, setDeleteBtn, setCursorBtn;

    private boolean warnedInstallBlocked = false;

    static File downloadDir() {
        return Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
    }

    private static boolean isApk(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".apk");
    }

    // ---------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        downloadDir().mkdirs();
        buildUi();
        loadFavs();
        setupWebView();
        refreshSettingsLabels();
        showPane(0);
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
        checkSideloadEnabled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDownloads();
    }

    @Override
    public void onBackPressed() {
        if (mouseMode) {
            setMouseMode(false);
        } else if (webView != null && webView.canGoBack()
                && browserPane.getVisibility() == View.VISIBLE) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void checkSideloadEnabled() {
        try {
            int v = Settings.Secure.getInt(getContentResolver(),
                    "install_non_market_apps", 1);
            if (v == 0 && !warnedInstallBlocked) {
                warnedInstallBlocked = true;
                new AlertDialog.Builder(this)
                        .setTitle("Apps from unknown sources are off")
                        .setMessage("Fire TV is blocking installs from Fetch. Enable "
                                + "\"Apps from Unknown Sources\" in Settings > My Fire TV > "
                                + "Developer options (Fire OS 6), then come back.")
                        .setPositiveButton("Open settings", (d, w) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                            } catch (Exception e) {
                                Toast.makeText(this, "Open Settings manually",
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Later", null)
                        .show();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------- UI build

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        return b;
    }

    private void buildUi() {
        windowRoot = new FrameLayout(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        windowRoot.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(8), dp(6), dp(8), dp(2));
        tabBrowser = makeButton("Browser");
        tabDownloads = makeButton("Downloads");
        tabFavs = makeButton("Favorites");
        tabSettings = makeButton("Settings");
        tabBrowser.setOnClickListener(v -> showPane(0));
        tabDownloads.setOnClickListener(v -> showPane(1));
        tabFavs.setOnClickListener(v -> showPane(2));
        tabSettings.setOnClickListener(v -> showPane(3));
        tabs.addView(tabBrowser);
        tabs.addView(tabDownloads);
        tabs.addView(tabFavs);
        tabs.addView(tabSettings);
        root.addView(tabs);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        browserPane = buildBrowserPane();
        downloadsPane = buildDownloadsPane();
        favsPane = buildFavsPane();
        settingsPane = buildSettingsPane();
        content.addView(browserPane);
        content.addView(downloadsPane);
        content.addView(favsPane);
        content.addView(settingsPane);

        // Full-window cursor overlay: sits above tabs, toolbar and page,
        // so the cursor works across the entire app UI. Not clickable /
        // not focusable, so real touches pass through to views below.
        cursorView = new CursorView(this);
        cursorView.setVisibility(View.GONE);
        windowRoot.addView(cursorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(windowRoot);
    }

    private void styleTabs(int active) {
        Button[] bs = {tabBrowser, tabDownloads, tabFavs, tabSettings};
        for (int i = 0; i < bs.length; i++) {
            bs[i].setTextColor(i == active ? ACCENT : TEXT);
        }
    }

    private void showPane(int which) {
        browserPane.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        downloadsPane.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        favsPane.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
        settingsPane.setVisibility(which == 3 ? View.VISIBLE : View.GONE);
        styleTabs(which);
        if (which == 1) refreshDownloads();
        if (which == 3) refreshSettingsLabels();
    }

    private View buildBrowserPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setPadding(dp(8), dp(4), dp(8), dp(2));
        urlBar = new EditText(this);
        urlBar.setHint("Enter URL, Downloader code, or search");
        urlBar.setTextColor(TEXT);
        urlBar.setHintTextColor(DIM);
        urlBar.setSingleLine(true);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        Button go = makeButton("Go");
        go.setOnClickListener(v -> navigate(urlBar.getText().toString()));
        urlRow.addView(urlBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        urlRow.addView(go);
        pane.addView(urlRow);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(dp(8), 0, dp(8), dp(2));
        btnBack = makeButton("\u25C0 Back");
        btnFwd = makeButton("Fwd \u25B6");
        Button btnReload = makeButton("Reload");
        Button btnHome = makeButton("Home");
        btnMouse = makeButton("Mouse: off");
        Button btnStar = makeButton("\u2605 Add page");
        Button btnPgUp = makeButton("PgUp");
        Button btnPgDn = makeButton("PgDn");
        Button btnMenu = makeButton("\u2261 Menu");
        btnBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnFwd.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnReload.setOnClickListener(v -> webView.reload());
        btnHome.setOnClickListener(v -> showPane(2));
        btnMouse.setOnClickListener(v -> setMouseMode(!mouseMode));
        btnStar.setOnClickListener(v -> addFavorite(
                webView.getTitle() != null ? webView.getTitle() : "Page",
                webView.getUrl()));
        btnPgUp.setOnClickListener(v -> webView.pageUp(false));
        btnPgDn.setOnClickListener(v -> webView.pageDown(false));
        btnMenu.setOnClickListener(v -> showPageMenu());
        tools.addView(btnBack); tools.addView(btnFwd); tools.addView(btnReload);
        tools.addView(btnHome); tools.addView(btnMouse); tools.addView(btnStar);
        tools.addView(btnPgUp); tools.addView(btnPgDn); tools.addView(btnMenu);
        pane.addView(tools);

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setVisibility(View.GONE);
        pane.addView(pageProgress);

        webView = new WebView(this);
        pane.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dlRow = new LinearLayout(this);
        dlRow.setOrientation(LinearLayout.HORIZONTAL);
        dlRow.setPadding(dp(8), dp(4), dp(8), dp(6));
        dlRow.setVisibility(View.GONE);
        dlProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlProgress.setMax(100);
        dlProgress.setIndeterminate(true);
        dlText = new TextView(this);
        dlText.setTextColor(TEXT);
        dlText.setPadding(dp(8), 0, dp(8), 0);
        Button dlCancel = makeButton("Cancel");
        dlCancel.setOnClickListener(v -> {
            DownloadTask t = currentTask;
            if (t != null) t.cancelled = true;
        });
        dlRow.addView(dlProgress, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dlRow.addView(dlText);
        dlRow.addView(dlCancel);
        pane.addView(dlRow);

        return pane;
    }

    private View buildDownloadsPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(8), dp(4), dp(8), dp(4));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView path = new TextView(this);
        path.setText("Folder: " + downloadDir().getAbsolutePath()
                + "  (tap an APK to install, long-press for options)");
        path.setTextColor(DIM);
        Button refresh = makeButton("Refresh");
        refresh.setOnClickListener(v -> refreshDownloads());
        head.addView(path, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(refresh);
        pane.addView(head);

        dlAdapter = new DownloadsAdapter();
        dlList = new ListView(this);
        dlList.setAdapter(dlAdapter);
        dlList.setOnItemClickListener((parent, view, pos, id) -> {
            File f = dlFiles.get(pos);
            if (isApk(f.getName())) {
                installApk(f, prefs.getBoolean(KEY_DELETE_AFTER, false));
            } else {
                Toast.makeText(this, "Only .apk files can be installed",
                        Toast.LENGTH_SHORT).show();
            }
        });
        dlList.setOnItemLongClickListener((parent, view, pos, id) -> {
            showFileOptions(dlFiles.get(pos));
            return true;
        });
        pane.addView(dlList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return pane;
    }

    private View buildFavsPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView hint = new TextView(this);
        hint.setText("Tap a site to open it. Long-press a tile to remove it.");
        hint.setTextColor(DIM);
        pane.addView(hint);
        favAdapter = new FavsAdapter();
        favGrid = new GridView(this);
        favGrid.setNumColumns(3);
        favGrid.setHorizontalSpacing(dp(10));
        favGrid.setVerticalSpacing(dp(10));
        favGrid.setAdapter(favAdapter);
        favGrid.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos == favs.size()) {
                showAddFavoriteDialog();
            } else {
                showPane(0);
                loadUrl(favs.get(pos)[1]);
            }
        });
        favGrid.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos < favs.size()) {
                String name = favs.get(pos)[0];
                new AlertDialog.Builder(this)
                        .setTitle("Remove favorite?")
                        .setMessage(name)
                        .setPositiveButton("Remove", (d, w) -> {
                            favs.remove(pos);
                            saveFavs();
                            favAdapter.notifyDataSetChanged();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
            return false;
        });
        pane.addView(favGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return pane;
    }

    // ---------------------------------------------------------- settings pane

    private View buildSettingsPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(16), dp(10), dp(16), dp(10));

        setAdblockBtn = settingRow(pane, "Ad blocking (H)",
                v -> {
                    prefs.edit().putBoolean(KEY_ADBLOCK,
                            !prefs.getBoolean(KEY_ADBLOCK, true)).apply();
                    applyWebSettings();
                    refreshSettingsLabels();
                });
        setJsBtn = settingRow(pane, "JavaScript",
                v -> {
                    prefs.edit().putBoolean(KEY_JS,
                            !prefs.getBoolean(KEY_JS, true)).apply();
                    applyWebSettings();
                    webView.reload();
                    refreshSettingsLabels();
                });
        setUaBtn = settingRow(pane, "Desktop mode (desktop user agent)",
                v -> {
                    prefs.edit().putBoolean(KEY_DESKTOP_UA,
                            !prefs.getBoolean(KEY_DESKTOP_UA, false)).apply();
                    applyWebSettings();
                    webView.reload();
                    refreshSettingsLabels();
                });
        setDeleteBtn = settingRow(pane, "Delete APK after install (default) (J)",
                v -> {
                    prefs.edit().putBoolean(KEY_DELETE_AFTER,
                            !prefs.getBoolean(KEY_DELETE_AFTER, false)).apply();
                    refreshSettingsLabels();
                });
        setCursorBtn = settingRow(pane, "Mouse cursor speed",
                v -> {
                    int cur = prefs.getInt(KEY_CURSOR_STEP, 52);
                    int next = cur == 24 ? 52 : cur == 52 ? 96 : 24;
                    prefs.edit().putInt(KEY_CURSOR_STEP, next).apply();
                    refreshSettingsLabels();
                });

        Button clear = makeButton("Clear browser cache + history");
        clear.setOnClickListener(v -> {
            webView.clearCache(true);
            webView.clearHistory();
            Toast.makeText(this, "Cache and history cleared",
                    Toast.LENGTH_SHORT).show();
        });
        pane.addView(clear);
        return pane;
    }

    private Button settingRow(LinearLayout parent, String label,
                              View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(16);
        Button b = makeButton("");
        b.setOnClickListener(toggle);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(b);
        parent.addView(row);
        return b;
    }

    private void refreshSettingsLabels() {
        if (setAdblockBtn == null) return;
        setAdblockBtn.setText(prefs.getBoolean(KEY_ADBLOCK, true) ? "ON" : "OFF");
        setJsBtn.setText(prefs.getBoolean(KEY_JS, true) ? "ON" : "OFF");
        setUaBtn.setText(prefs.getBoolean(KEY_DESKTOP_UA, false) ? "ON" : "OFF");
        setDeleteBtn.setText(prefs.getBoolean(KEY_DELETE_AFTER, false) ? "ON" : "OFF");
        int step = prefs.getInt(KEY_CURSOR_STEP, 52);
        setCursorBtn.setText(step == 24 ? "Slow" : step == 96 ? "Fast" : "Normal");
    }

    private void applyWebSettings() {
        if (webView == null) return;
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(prefs.getBoolean(KEY_JS, true));
        s.setUserAgentString(prefs.getBoolean(KEY_DESKTOP_UA, false)
                ? DESKTOP_UA : null);
        AdBlock.enabled = prefs.getBoolean(KEY_ADBLOCK, true);
    }

    // ---------------------------------------------------------- browser

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(true);
        webView.setVerticalScrollBarEnabled(true);
        webView.setScrollbarFadingEnabled(false);
        applyWebSettings();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                pageProgress.setProgress(progress);
                pageProgress.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest req) {
                if (AdBlock.isAd(req.getUrl().toString())) return AdBlock.empty();
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (AdBlock.isAd(url)) return AdBlock.empty();
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageProgress.setVisibility(View.VISIBLE);
                urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageProgress.setVisibility(View.GONE);
                urlBar.setText(url);
                btnBack.setEnabled(view.canGoBack());
                btnFwd.setEnabled(view.canGoForward());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        WebResourceError err) {
                if (req.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                            "Page error: " + err.getDescription(),
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                confirmDownload(url, filenameFrom(url, contentDisposition), contentLength);
            }
        });

        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult r = webView.getHitTestResult();
            if (r != null && r.getExtra() != null
                    && (r.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE
                            || r.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                showLinkDialog(r.getExtra());
            } else {
                // Long-press anywhere else: quick access to app features.
                showPageMenu();
            }
            return true;
        });

        webView.loadUrl(HOME_URL);
    }

    /** Returns true if the URL was handled (download), false to load it. */
    private boolean handleUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String path = Uri.parse(url).getPath();
            if (path != null && isApk(path)) {
                confirmDownload(url, filenameFrom(url, null), -1);
                return true;
            }
            return false;
        }
        // tel:, mailto:, intent:, market: — hand to the system if possible.
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open link: " + url, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void showLinkDialog(final String url) {
        lastMenuShownAt = System.currentTimeMillis();
        new AlertDialog.Builder(this)
                .setTitle(url)
                .setItems(new CharSequence[]{"Open link", "Download link", "Copy link",
                                "\u2605 Add page to favorites"},
                        (d, which) -> {
                            if (which == 0) {
                                if (!handleUrl(url)) loadUrl(url);
                            } else if (which == 1) {
                                confirmDownload(url, filenameFrom(url, null), -1);
                            } else if (which == 2) {
                                ClipboardManager cm = (ClipboardManager)
                                        getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("url", url));
                                    Toast.makeText(this, "Link copied",
                                            Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                addFavorite(
                                        webView.getTitle() != null
                                                ? webView.getTitle() : "Page",
                                        webView.getUrl());
                            }
                        })
                .show();
    }

    /**
     * App menu shown on long-press when the cursor/finger is NOT on a
     * link, from the toolbar Menu button, or with the remote's Menu key:
     * favorites, app features and tab shortcuts in one place.
     */
    private void showPageMenu() {
        lastMenuShownAt = System.currentTimeMillis();
        final String url = webView != null ? webView.getUrl() : null;
        String title = webView != null && webView.getTitle() != null
                ? webView.getTitle() : "";
        CharSequence[] items = {
                "\u2605 Add current page to favorites",
                "Reload page",
                mouseMode ? "Mouse mode: turn OFF" : "Mouse mode: turn ON",
                "Go to Downloads",
                "Go to Favorites",
                "Go to Settings",
                "Scroll to top",
                "Scroll to bottom"
        };
        new AlertDialog.Builder(this)
                .setTitle(title.isEmpty() ? "Fetch menu" : title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: addFavorite(title.isEmpty() ? "Page" : title, url); break;
                        case 1: webView.reload(); break;
                        case 2: setMouseMode(!mouseMode); break;
                        case 3: showPane(1); break;
                        case 4: showPane(2); break;
                        case 5: showPane(3); break;
                        case 6: webView.pageUp(true); break;
                        case 7: webView.pageDown(true); break;
                        default: break;
                    }
                })
                .show();
    }

    private void navigate(String input) {
        if (input == null) return;
        loadUrl(resolveInput(input.trim()));
    }

    static String resolveInput(String s) {
        if (s.isEmpty()) return HOME_URL;
        try {
            if (s.matches("\\d+")) {
                // Downloader-style numeric short codes live on aftv.news.
                return "https://aftv.news/" + s;
            }
            if (s.contains(" ")) {
                return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8");
            }
            if (!s.contains("://")) {
                if (s.contains(".")) return "https://" + s;
                return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8");
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private void loadUrl(String url) {
        setMouseMode(false);
        urlBar.setText(url);
        webView.loadUrl(url);
        webView.requestFocus();
    }

    static String filenameFrom(String url, String contentDisposition) {
        if (contentDisposition != null) {
            for (String part : contentDisposition.split(";")) {
                String p = part.trim();
                if (p.startsWith("filename=")) {
                    String n = p.substring("filename=".length())
                            .replace("\"", "").trim();
                    if (!n.isEmpty()) return sanitize(n);
                }
            }
        }
        try {
            String path = new URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (!last.isEmpty()) return sanitize(URLDecoder.decode(last, "UTF-8"));
        } catch (Exception ignored) {
        }
        return "download.apk";
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ---------------------------------------------------------- mouse cursor (A)

    private void setMouseMode(boolean on) {
        mouseMode = on;
        btnMouse.setText(on ? "Mouse: ON" : "Mouse: off");
        cursorView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            cursorX = getResources().getDisplayMetrics().widthPixels / 2f;
            cursorY = getResources().getDisplayMetrics().heightPixels / 2f;
            cursorView.invalidate();
            Toast.makeText(this,
                    "Mouse: D-pad moves (push past page edge to scroll), "
                            + "tap = click, HOLD center = long-press menu, Back exits",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean center = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;

        if (mouseMode) {
            if (center) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        centerDownAt = SystemClock.uptimeMillis();
                    }
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    long held = SystemClock.uptimeMillis() - centerDownAt;
                    if (held >= 500) {
                        dispatchLongTap(cursorX, cursorY);
                    } else {
                        dispatchTap(cursorX, cursorY);
                    }
                    return true;
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int step = prefs.getInt(KEY_CURSOR_STEP, 52);
                if (e.isShiftPressed()) step = 12;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP: moveCursor(0, -step); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: moveCursor(0, step); return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT: moveCursor(-step, 0); return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: moveCursor(step, 0); return true;
                    case KeyEvent.KEYCODE_PAGE_UP: webView.pageUp(false); return true;
                    case KeyEvent.KEYCODE_PAGE_DOWN: webView.pageDown(false); return true;
                    case KeyEvent.KEYCODE_MENU: showPageMenu(); return true;
                    case KeyEvent.KEYCODE_BACK: setMouseMode(false); return true;
                    default: break;
                }
            }
            // Swallow the matching UP events for keys we handle on DOWN.
            if (e.getAction() == KeyEvent.ACTION_UP
                    && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                            || code == KeyEvent.KEYCODE_DPAD_LEFT
                            || code == KeyEvent.KEYCODE_DPAD_RIGHT
                            || code == KeyEvent.KEYCODE_BACK
                            || code == KeyEvent.KEYCODE_MENU)) {
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // Not in mouse mode: page-scroll keys + Menu key while browsing.
        if (e.getAction() == KeyEvent.ACTION_DOWN
                && browserPane.getVisibility() == View.VISIBLE) {
            switch (code) {
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    webView.pageUp(false);
                    return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    webView.pageDown(false);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    showPageMenu();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * Moves the cursor; when the cursor sits over the WebView and is
     * pushed past the page edge, the page scrolls instead — this is how
     * you reach the bottom of long pages (e.g. GitHub) in mouse mode.
     */
    private void moveCursor(float dx, float dy) {
        int[] loc = new int[2];
        webView.getLocationInWindow(loc);
        int wx = loc[0], wy = loc[1];
        int wr = wx + webView.getWidth(), wb = wy + webView.getHeight();
        boolean overWeb = browserPane.getVisibility() == View.VISIBLE
                && cursorX >= wx && cursorX <= wr && cursorY >= wy && cursorY <= wb;
        if (overWeb) {
            if (dy > 0 && cursorY + dy > wb) webView.scrollBy(0, dp(140));
            if (dy < 0 && cursorY + dy < wy) webView.scrollBy(0, -dp(140));
            if (dx > 0 && cursorX + dx > wr) webView.scrollBy(dp(80), 0);
            if (dx < 0 && cursorX + dx < wx) webView.scrollBy(-dp(80), 0);
        }
        int maxX = windowRoot.getWidth() > 0 ? windowRoot.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int maxY = windowRoot.getHeight() > 0 ? windowRoot.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        cursorX = clamp(cursorX + dx, 0, maxX);
        cursorY = clamp(cursorY + dy, 0, maxY);
        cursorView.invalidate();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Tap anywhere in the app UI: the window root routes the touch to
     *  whichever view is under the cursor (toolbar button, tab, page…). */
    private void dispatchTap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        windowRoot.dispatchTouchEvent(down);
        windowRoot.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    /** Long-press: holds the touch ~650 ms so the WebView fires its
     *  long-click (link menu on links, app menu elsewhere). If nothing
     *  handled it (e.g. cursor over the toolbar), open the app menu. */
    private void dispatchLongTap(final float x, final float y) {
        long now = SystemClock.uptimeMillis();
        final long pressStart = System.currentTimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        windowRoot.dispatchTouchEvent(down);
        down.recycle();
        ui.postDelayed(() -> {
            long upNow = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(upNow, upNow, MotionEvent.ACTION_UP, x, y, 0);
            windowRoot.dispatchTouchEvent(up);
            up.recycle();
            ui.postDelayed(() -> {
                if (lastMenuShownAt < pressStart) showPageMenu();
            }, 200);
        }, 650);
    }

    /** Ring + dot cursor drawn over the whole window in mouse mode. */
    private class CursorView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorView(Context ctx) {
            super(ctx);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3));
            ring.setColor(ACCENT);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(Color.WHITE);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!mouseMode) return;
            canvas.drawCircle(cursorX, cursorY, dp(16), ring);
            canvas.drawCircle(cursorX, cursorY, dp(3), dot);
        }
    }

    // ---------------------------------------------------------- downloads

    private void confirmDownload(final String url, final String filename, final long size) {
        final CheckBox deleteBox = new CheckBox(this);
        deleteBox.setText("Delete APK after install (J)");
        deleteBox.setTextColor(TEXT);
        deleteBox.setChecked(prefs.getBoolean(KEY_DELETE_AFTER, false));
        deleteBox.setPadding(dp(16), dp(8), dp(16), dp(4));

        String msg = filename + (size > 0
                ? "\n" + Formatter.formatFileSize(this, size) : "")
                + "\n\nSave to Downloads folder?";
        new AlertDialog.Builder(this)
                .setTitle("Download file?")
                .setMessage(msg)
                .setView(isApk(filename) ? deleteBox : null)
                .setPositiveButton("Download", (d, w) -> {
                    if (isApk(filename)) {
                        prefs.edit().putBoolean(KEY_DELETE_AFTER,
                                deleteBox.isChecked()).apply();
                    }
                    startDownload(url, filename);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startDownload(String url, String filename) {
        if (currentTask != null && currentTask.isAlive()) {
            Toast.makeText(this, "A download is already running",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = downloadDir();
        dir.mkdirs();
        File target = uniqueFile(dir, filename);
        dlRow.setVisibility(View.VISIBLE);
        dlProgress.setIndeterminate(true);
        dlText.setText(filename);
        currentTask = new DownloadTask(url, target);
        currentTask.start();
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private class DownloadTask extends Thread {
        private final String url;
        private final File target;
        volatile boolean cancelled = false;

        DownloadTask(String url, File target) {
            this.url = url;
            this.target = target;
        }

        @Override
        public void run() {
            File part = new File(target.getParentFile(), target.getName() + ".part");
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 7.1) Fetch/1.0");
                long done = part.isFile() ? part.length() : 0;
                if (done > 0) conn.setRequestProperty("Range", "bytes=" + done + "-");
                conn.connect();
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK
                        && code != HttpURLConnection.HTTP_PARTIAL) {
                    fail("Server returned " + code);
                    return;
                }
                if (code == HttpURLConnection.HTTP_OK && done > 0) {
                    done = 0; // server ignored Range; restart
                    part.delete();
                }
                long len = conn.getContentLength();
                final long total = len > 0 ? done + len : -1;
                InputStream in = conn.getInputStream();
                RandomAccessFile out = new RandomAccessFile(part, "rw");
                out.seek(done);
                byte[] buf = new byte[16384];
                int n;
                long lastPost = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) break;
                    out.write(buf, 0, n);
                    done += n;
                    if (done - lastPost > 65536) {
                        lastPost = done;
                        final long d = done;
                        ui.post(() -> updateProgress(target.getName(), d, total));
                    }
                }
                out.close();
                in.close();
                if (cancelled) {
                    ui.post(() -> {
                        dlRow.setVisibility(View.GONE);
                        Toast.makeText(MainActivity.this,
                                "Download cancelled (partial kept for resume)",
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                if (!part.renameTo(target)) {
                    fail("Could not finish the file");
                    return;
                }
                ui.post(() -> {
                    dlRow.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            "Saved " + target.getName(), Toast.LENGTH_LONG).show();
                    refreshDownloads();
                    if (isApk(target.getName())) {
                        installApk(target,
                                prefs.getBoolean(KEY_DELETE_AFTER, false));
                    }
                });
            } catch (Exception e) {
                fail(e.getMessage() != null ? e.getMessage() : "network error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private void fail(final String why) {
            ui.post(() -> {
                dlRow.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Download failed: " + why,
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void updateProgress(String name, long done, long total) {
        dlText.setText(name);
        if (total > 0) {
            dlProgress.setIndeterminate(false);
            dlProgress.setProgress((int) (done * 100 / total));
            dlText.setText(String.format(Locale.US, "%s  %s / %s (%d%%)", name,
                    Formatter.formatFileSize(this, done),
                    Formatter.formatFileSize(this, total),
                    done * 100 / total));
        } else {
            dlProgress.setIndeterminate(true);
            dlText.setText(name + "  " + Formatter.formatFileSize(this, done));
        }
    }

    // ---------------------------------------------------------- install

    void installApk(File file, boolean deleteAfter) {
        prefs.edit()
                .putString(KEY_LAST_FILE, file.getName())
                .putLong(KEY_LAUNCH_TIME, System.currentTimeMillis())
                .putBoolean(KEY_DELETE_AFTER, deleteAfter)
                .apply();
        try {
            Uri uri = FetchFileProvider.getUriForFile(this, file);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open installer: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------- downloads pane

    private void refreshDownloads() {
        if (dlAdapter == null) return;
        dlFiles.clear();
        File[] fs = downloadDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && !f.getName().endsWith(".part")) dlFiles.add(f);
            }
        }
        Collections.sort(dlFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        dlAdapter.notifyDataSetChanged();
    }

    private void showFileOptions(final File f) {
        List<String> items = new ArrayList<>();
        if (isApk(f.getName())) items.add("Install");
        items.add("Rename");
        items.add("Delete");
        items.add("Details");
        final String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(arr, (d, which) -> {
                    String pick = arr[which];
                    if ("Install".equals(pick)) {
                        installApk(f, prefs.getBoolean(KEY_DELETE_AFTER, false));
                    } else if ("Rename".equals(pick)) {
                        showRenameDialog(f);
                    } else if ("Delete".equals(pick)) {
                        new AlertDialog.Builder(this)
                                .setTitle("Delete file?")
                                .setMessage(f.getName())
                                .setPositiveButton("Delete", (d2, w2) -> {
                                    if (f.delete()) refreshDownloads();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else if ("Details".equals(pick)) {
                        String info = "Path: " + f.getAbsolutePath()
                                + "\nSize: " + Formatter.formatFileSize(this, f.length())
                                + "\nModified: " + new SimpleDateFormat(
                                        "yyyy-MM-dd HH:mm", Locale.US)
                                        .format(new Date(f.lastModified()));
                        new AlertDialog.Builder(this)
                                .setTitle(f.getName())
                                .setMessage(info)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                })
                .show();
    }

    private void showRenameDialog(final File f) {
        final EditText input = new EditText(this);
        input.setText(f.getName());
        input.setTextColor(TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String nn = sanitize(input.getText().toString().trim());
                    if (nn.isEmpty()) return;
                    File to = new File(f.getParentFile(), nn);
                    if (to.exists()) {
                        Toast.makeText(this, "That name is taken",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (f.renameTo(to)) refreshDownloads();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private class DownloadsAdapter extends BaseAdapter {
        @Override public int getCount() { return dlFiles.size(); }
        @Override public Object getItem(int pos) { return dlFiles.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView name, meta;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                name = (TextView) row.getChildAt(0);
                meta = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                name = new TextView(MainActivity.this);
                name.setTextSize(17);
                meta = new TextView(MainActivity.this);
                meta.setTextSize(13);
                row.addView(name);
                row.addView(meta);
            }
            File f = dlFiles.get(pos);
            boolean apk = isApk(f.getName());
            name.setText((apk ? "\u25B6 " : "") + f.getName());
            name.setTextColor(apk ? ACCENT : TEXT);
            meta.setText(Formatter.formatFileSize(MainActivity.this, f.length())
                    + "  \u2022  " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(new Date(f.lastModified()))
                    + (apk ? "  \u2022  tap to install" : ""));
            meta.setTextColor(DIM);
            return row;
        }
    }

    // ---------------------------------------------------------- favorites (B)

    private void loadFavs() {
        favs.clear();
        String raw = prefs.getString(KEY_FAVS, null);
        if (raw == null || raw.isEmpty()) {
            favs.add(new String[]{"GitHub", "https://github.com"});
            favs.add(new String[]{"APKMirror", "https://www.apkmirror.com"});
            favs.add(new String[]{"Aptoide TV", "https://www.aptoide.com"});
            saveFavs();
            return;
        }
        for (String line : raw.split("\n")) {
            String[] kv = line.split("\\|\\|", 2);
            if (kv.length == 2) favs.add(new String[]{kv[0], kv[1]});
        }
    }

    private void saveFavs() {
        StringBuilder sb = new StringBuilder();
        for (String[] f : favs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f[0]).append("||").append(f[1]);
        }
        prefs.edit().putString(KEY_FAVS, sb.toString()).apply();
    }

    private void addFavorite(String name, String url) {
        if (url == null || url.isEmpty() || HOME_URL.equals(url)) {
            Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show();
            return;
        }
        favs.add(new String[]{name != null ? name : url, url});
        saveFavs();
        if (favAdapter != null) favAdapter.notifyDataSetChanged();
        Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show();
    }

    private void showAddFavoriteDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText nameIn = new EditText(this);
        nameIn.setHint("Name");
        nameIn.setTextColor(TEXT);
        nameIn.setHintTextColor(DIM);
        final EditText urlIn = new EditText(this);
        urlIn.setHint("URL (example.com)");
        urlIn.setTextColor(TEXT);
        urlIn.setHintTextColor(DIM);
        box.addView(nameIn);
        box.addView(urlIn);
        new AlertDialog.Builder(this)
                .setTitle("Add favorite")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String u = urlIn.getText().toString().trim();
                    if (u.isEmpty()) return;
                    String n = nameIn.getText().toString().trim();
                    addFavorite(n.isEmpty() ? u : n, resolveInput(u));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private class FavsAdapter extends BaseAdapter {
        @Override public int getCount() { return favs.size() + 1; }
        @Override public Object getItem(int pos) { return pos < favs.size() ? favs.get(pos) : null; }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : new TextView(MainActivity.this);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(18);
            t.setPadding(dp(10), dp(26), dp(10), dp(26));
            if (pos == favs.size()) {
                t.setText("\uFF0B Add favorite");
                t.setTextColor(DIM);
            } else {
                t.setText(favs.get(pos)[0]);
                t.setTextColor(TEXT);
            }
            return t;
        }
    }

    // ---------------------------------------------------------- ad block (H)

    static class AdBlock {
        static volatile boolean enabled = true;

        private static final String[] HOSTS = {
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "adservice.google.com", "ads.google.com", "pagead2.googlesyndication.com",
                "amazon-adsystem.com", "ads.yahoo.com", "advertising.com",
                "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
                "outbrain.com", "taboola.com", "mgid.com", "revcontent.com",
                "smartadserver.com", "pubmatic.com", "rubiconproject.com",
                "openx.net", "casalemedia.com", "contextweb.com", "bidswitch.net",
                "moatads.com", "scorecardresearch.com", "quantserve.com",
                "zedo.com", "adform.net", "flashtalking.com", "sizmek.com",
                "unityads.unity3d.com", "applovin.com", "mopub.com",
                "chartboost.com", "inmobi.com", "vungle.com", "adcolony.com",
                "supersonicads.com", "ironsrc.com", "tapjoy.com",
                "adsafeprotected.com", "2mdn.net", "gstatic.com/ads",
                "imasdk.googleapis.com", "an.facebook.com", "ads-twitter.com",
                "analytics.twitter.com", "ads.pinterest.com", "ads.linkedin.com",
                "bat.bing.com", "ads.bing.com"
        };

        static boolean isAd(String url) {
            if (!enabled || url == null) return false;
            try {
                String host = new URL(url).getHost();
                if (host == null) return false;
                host = host.toLowerCase(Locale.US);
                for (String h : HOSTS) {
                    if (h.contains("/")) {
                        if (url.contains(h)) return true;
                    } else if (host.equals(h) || host.endsWith("." + h)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
            return false;
        }

        static WebResourceResponse empty() {
            return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                    null, new ByteArrayInputStream(new byte[0]));
        }
    }
}
