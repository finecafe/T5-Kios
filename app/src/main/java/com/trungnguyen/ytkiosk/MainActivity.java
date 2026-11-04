package com.trungnguyen.ytkiosk;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // Các tên miền được mở ngay trong app (toàn bộ link của YouTube)
    private static final String[] INTERNAL_HOSTS = {
            "youtube.com", "youtu.be", "youtube-nocookie.com",
            "google.com", "googleusercontent.com", "gstatic.com",
            "ytimg.com", "googlevideo.com", "ggpht.com"
    };

    // Ép YouTube phát chất lượng cao nhất (1080p) thay vì tự hạ xuống 360p/480p
    private static final String FORCE_QUALITY_JS =
            "(function(){try{"
            + "var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');"
            + "if(p){"
            + "if(p.setPlaybackQualityRange){p.setPlaybackQualityRange('hd1080','hd1080');}"
            + "if(p.setPlaybackQuality){p.setPlaybackQuality('hd1080');}"
            + "}}catch(e){}})();";

    // Lấy danh sách ID playlist trên trang /playlists (cuộn xuống để tải hết)
    private static final String DISCOVER_JS =
            "(function(){try{window.scrollTo(0,document.documentElement.scrollHeight);}catch(e){}"
            + "var ids=[],seen={};var a=document.querySelectorAll('a[href*=\"list=\"]');"
            + "for(var i=0;i<a.length;i++){var m=a[i].href.match(/[?&]list=([A-Za-z0-9_-]+)/);"
            + "if(m&&!seen[m[1]]&&m[1]!=='WL'&&m[1]!=='LL'&&m[1].indexOf('RD')!==0)"
            + "{seen[m[1]]=1;ids.push(m[1]);}}"
            + "return ids.join(',');})();";

    // Trạng thái video: ended|videoId|listId (đồng thời tự bấm phát nếu đang bị dừng)
    private static final String STATE_JS =
            "(function(){var v=document.querySelector('video');if(!v)return '0||';"
            + "var m=location.search.match(/[?&]v=([^&]+)/);var l=location.search.match(/[?&]list=([^&]+)/);"
            + "if(v.paused&&!v.ended){try{v.play();}catch(e){}}"
            + "return (v.ended?'1':'0')+'|'+(m?m[1]:'')+'|'+(l?l[1]:'');})();";

    private enum Mode { BROWSE, DISCOVER, PLAY }

    private static final long TICK_MS = 2000;
    private static final int ENDED_TICKS_TO_NEXT = 4;   // ~8 giây đứng ở cuối => sang playlist kế
    private static final int NO_VIDEO_TICKS_TO_RELOAD = 15; // ~30 giây không có video => tải lại

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Mode mode = Mode.BROWSE;
    private final List<String> playlistIds = new ArrayList<>();
    private int currentIndex = -1;

    private int lastCount = -1;
    private int stableTicks = 0;
    private int discoverTicks = 0;

    private String lastVid = "";
    private int endedTicks = 0;
    private int wrongListTicks = 0;
    private int noVideoTicks = 0;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Tăng tốc phần cứng + giữ tỉ lệ chữ/hình chuẩn, tránh bị phóng to làm mờ
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        s.setTextZoom(100);

        // Tuỳ chọn: giao diện máy tính (YouTube cho phép chất lượng cao hơn). Bật trong strings.xml
        if (getResources().getBoolean(R.bool.desktop_mode)) {
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                hideSystemBars();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                webView.setVisibility(View.VISIBLE);
            }
        });

        if (getResources().getBoolean(R.bool.auto_play_playlists)) {
            startDiscover();
        } else {
            mode = Mode.BROWSE;
            webView.loadUrl(getString(R.string.channel_url));
        }
    }

    // ---------- Tự động phát tuần tự các playlist ----------

    /** Mở trang /playlists của kênh để lấy danh sách playlist. */
    private void startDiscover() {
        mode = Mode.DISCOVER;
        lastCount = -1;
        stableTicks = 0;
        discoverTicks = 0;
        webView.loadUrl(getString(R.string.channel_url));
    }

    private void startPlaylist(int index) {
        currentIndex = index;
        mode = Mode.PLAY;
        lastVid = "";
        endedTicks = 0;
        wrongListTicks = 0;
        noVideoTicks = 0;
        webView.loadUrl("https://www.youtube.com/watch?list=" + playlistIds.get(index));
    }

    /** Sang playlist kế tiếp; hết danh sách thì quét lại từ đầu (lặp vô hạn). */
    private void nextPlaylist() {
        int next = currentIndex + 1;
        if (next >= playlistIds.size()) {
            startDiscover();
        } else {
            startPlaylist(next);
        }
    }

    private static String unquote(String raw) {
        if (raw == null || raw.equals("null")) return "";
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private void tick() {
        if (webView == null) return;
        webView.evaluateJavascript(FORCE_QUALITY_JS, null);

        if (mode == Mode.DISCOVER) {
            discoverTicks++;
            webView.evaluateJavascript(DISCOVER_JS, value -> {
                if (mode != Mode.DISCOVER) return;
                String res = unquote(value);
                int count = res.isEmpty() ? 0 : res.split(",").length;
                if (count > 0 && count == lastCount) {
                    stableTicks++;
                } else {
                    stableTicks = 0;
                }
                lastCount = count;

                boolean ready = count > 0 && (stableTicks >= 2 || discoverTicks > 20);
                if (ready) {
                    playlistIds.clear();
                    for (String id : res.split(",")) {
                        if (!id.isEmpty()) playlistIds.add(id);
                    }
                    startPlaylist(0);
                } else if (count == 0 && discoverTicks > 30) {
                    startDiscover(); // không thấy playlist nào, tải lại trang
                }
            });
        } else if (mode == Mode.PLAY) {
            webView.evaluateJavascript(STATE_JS, value -> {
                if (mode != Mode.PLAY) return;
                String[] p = unquote(value).split("\\|", -1);
                String ended = p.length > 0 ? p[0] : "0";
                String vid = p.length > 1 ? p[1] : "";
                String list = p.length > 2 ? p[2] : "";
                String expected = playlistIds.get(currentIndex);

                if (vid.isEmpty()) {
                    // Chưa có video (đang tải / video lỗi)
                    noVideoTicks++;
                    if (noVideoTicks >= NO_VIDEO_TICKS_TO_RELOAD) {
                        startPlaylist(currentIndex);
                    }
                    return;
                }
                noVideoTicks = 0;

                // YouTube tự nhảy sang video gợi ý ngoài playlist => coi như playlist đã hết
                if (!list.equals(expected)) {
                    wrongListTicks++;
                    if (wrongListTicks >= 2) {
                        nextPlaylist();
                    }
                    return;
                }
                wrongListTicks = 0;

                if (ended.equals("1") && vid.equals(lastVid)) {
                    endedTicks++;
                } else {
                    endedTicks = ended.equals("1") ? 1 : 0;
                }
                lastVid = vid;

                if (endedTicks >= ENDED_TICKS_TO_NEXT) {
                    nextPlaylist();
                }
            });
        }
    }

    // ---------- Phần còn lại ----------

    /** true = đã xử lý (mở ngoài app); false = để WebView tự mở trong app. */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme != null && (scheme.equals("http") || scheme.equals("https")) && host != null) {
            for (String h : INTERNAL_HOSTS) {
                if (host.equals(h) || host.endsWith("." + h)) {
                    return false;
                }
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.getWebChromeClient().onHideCustomView();
                return true;
            }
            // Chế độ tự phát: không cho lùi ra khỏi vòng lặp bằng nút Back
            if (mode == Mode.BROWSE && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(loop);
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        handler.removeCallbacks(loop);
        handler.postDelayed(loop, TICK_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(loop);
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
