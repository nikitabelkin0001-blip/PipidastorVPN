package com.pipidastr.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.net.VpnService;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 491;
    private static final int QR_CAMERA_REQUEST = 492;
    private static final int QR_IMAGE_REQUEST = 493;
    private static final int MAX_SUBSCRIPTION_BYTES = 2_000_000;
    private static final int BACKGROUND = Color.rgb(10, 15, 32);
    private static final int CARD = Color.rgb(21, 30, 51);
    private static final int FIELD = Color.rgb(33, 45, 72);
    private static final int SELECTED = Color.rgb(44, 63, 99);
    private static final int ACCENT = Color.rgb(126, 119, 249);
    private static final int MUTED = Color.rgb(149, 174, 221);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<SubscriptionParser.Server> servers = new ArrayList<>();
    private final List<SubscriptionParser.Server> visibleServers = new ArrayList<>();
    private final Set<String> compatibleLinks = new HashSet<>();
    private ArrayAdapter<SubscriptionParser.Server> adapter;
    private EditText urlInput;
    private TextView status;
    private TextView serverTitle;
    private TextView serverDetails;
    private TextView serverCount;
    private Button refresh;
    private ListView serverList;
    private EditText serverSearch;
    private boolean wideLayout;
    private LinearLayout mobileBody;
    private LinearLayout mobileMenu;
    private ScrollView mobileScroll;
    private ImageButton menuButton;
    private TextView selectedAppsSummary;
    private TextView profileValue;
    private TextView profileName;
    private TextView connectionBadge;
    private TextView connectionHeading;
    private TextView connectionMessage;
    private Button connectButton;
    private int selectedServer = -1;
    private String displayedVpnState = "disconnected";
    private Uri qrCameraImage;
    private SubscriptionPairing.Session pairingSession;
    private final BroadcastReceiver vpnStatus = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateConnectionUi(intent.getStringExtra(PipidastrVpnService.EXTRA_STATE),
                    intent.getStringExtra(PipidastrVpnService.EXTRA_MESSAGE));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getString("qr_camera_image") != null)
            qrCameraImage = Uri.parse(state.getString("qr_camera_image"));
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        wideLayout = wide;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(16), dp(22), dp(16));
        root.setBackgroundColor(BACKGROUND);
        if (!wide) root.setFocusableInTouchMode(true);
        setContentView(root);
        if (!wide) root.requestFocus();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.pipidastr_logo);
        header.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView heading = text("PIPIDASTR VPN", 24, Color.WHITE, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-2, -2);
        headingParams.leftMargin = dp(13);
        header.addView(heading, headingParams);
        if (!wide) {
            header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            menuButton = new ImageButton(this);
            menuButton.setImageResource(R.drawable.ic_menu);
            menuButton.setContentDescription("Открыть меню");
            menuButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            menuButton.setPadding(dp(11), dp(9), dp(11), dp(9));
            menuButton.setBackground(interactive(FIELD));
            header.addView(menuButton, new LinearLayout.LayoutParams(dp(46), dp(42)));
        }
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        mobileBody = body;
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.topMargin = dp(16);
        root.addView(body, bodyParams);

        LinearLayout sidebar = card();
        LinearLayout.LayoutParams sidebarParams = new LinearLayout.LayoutParams(wide ? dp(330) : -1, wide ? -1 : -2);
        if (wide) sidebarParams.rightMargin = dp(16);
        body.addView(sidebar, sidebarParams);
        sidebar.addView(text("ПРОФИЛЬ", 12, MUTED, true));
        profileName = text("Весь трафик", 18, Color.WHITE, true);
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(-1, -2);
        profileParams.topMargin = dp(8);
        sidebar.addView(profileName, profileParams);

        serverCount = text("СЕРВЕРЫ · 0", 13, MUTED, true);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-1, -2);
        countParams.topMargin = dp(22);
        sidebar.addView(serverCount, countParams);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("HTTPS-ссылка на подписку");
        urlInput.setTextSize(15);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(MUTED);
        urlInput.setPadding(dp(12), 0, dp(12), 0);
        urlInput.setBackground(interactive(FIELD));
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(getPreferences(MODE_PRIVATE).getString("subscription_url", ""));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(48));
        inputParams.topMargin = dp(12);
        sidebar.addView(urlInput, inputParams);

        Button scanQr = new Button(this);
        scanQr.setText("Добавить по QR-коду");
        scanQr.setTextSize(14);
        scanQr.setTextColor(Color.WHITE);
        scanQr.setAllCaps(false);
        scanQr.setBackground(interactive(FIELD));
        scanQr.setOnClickListener(view -> chooseQrSource());
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-1, dp(48));
        qrParams.topMargin = dp(10);
        sidebar.addView(scanQr, qrParams);

        Button showQr = new Button(this);
        showQr.setText("Получить с телефона по QR");
        showQr.setTextSize(14);
        showQr.setTextColor(Color.WHITE);
        showQr.setAllCaps(false);
        showQr.setBackground(interactive(FIELD));
        showQr.setOnClickListener(view -> showPairingQr());
        LinearLayout.LayoutParams showQrParams = new LinearLayout.LayoutParams(-1, dp(48));
        showQrParams.topMargin = dp(10);
        if (wide) sidebar.addView(showQr, showQrParams);

        refresh = new Button(this);
        refresh.setText("Обновить серверы");
        refresh.setTextSize(14);
        refresh.setTextColor(Color.WHITE);
        refresh.setAllCaps(false);
        refresh.setBackground(interactive(FIELD));
        refresh.setOnClickListener(view -> refreshServers());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, dp(48));
        refreshParams.topMargin = dp(10);
        sidebar.addView(refresh, refreshParams);

        ListView list = new ListView(this);
        serverList = list;
        list.setId(View.generateViewId());
        if (wide) list.setFocusableInTouchMode(true);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(wide ? 7 : 10));
        list.setSelector(android.R.color.transparent);
        adapter = new ArrayAdapter<SubscriptionParser.Server>(this, android.R.layout.simple_list_item_1, visibleServers) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, recycled, parent);
                SubscriptionParser.Server server = getItem(position);
                boolean compatible = compatibleLinks.contains(server.link);
                item.setText(server.name + "\n" + server.host + ":" + server.port + " · " +
                        server.protocol + (compatible ? "" : " · нужен Xray"));
                item.setTextSize(wide ? 14 : 15);
                item.setTextColor(compatible ? Color.WHITE : MUTED);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setMinHeight(dp(wide ? 58 : 76));
                item.setPadding(dp(14), dp(wide ? 7 : 12), dp(10), dp(wide ? 7 : 12));
                boolean active = servers.indexOf(server) == selectedServer;
                item.setBackground(serverItemBackground(active));
                item.setActivated(wide && list.hasFocus() && position == list.getSelectedItemPosition());
                return item;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                selectServer(servers.indexOf(visibleServers.get(position))));
        if (wide) {
            list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                        int position, long id) {
                    updateServerFocus(list);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    updateServerFocus(list);
                }
            });
            list.setOnFocusChangeListener((view, focused) -> updateServerFocus(list));
        }
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1);
        listParams.topMargin = dp(10);
        sidebar.addView(list, listParams);

        LinearLayout detailsColumn = new LinearLayout(this);
        detailsColumn.setOrientation(LinearLayout.VERTICAL);
        body.addView(detailsColumn, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : -2, wide ? 1 : 0));

        LinearLayout connection = card();
        if (wide) connection.setPadding(dp(20), dp(8), dp(20), dp(8));
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2);
        if (!wide) connectionParams.topMargin = dp(14);
        detailsColumn.addView(connection, connectionParams);
        connectionBadge = text("НЕ ПОДКЛЮЧЕНО", wide ? 12 : 13, MUTED, true);
        if (wide) connectionBadge.setVisibility(View.GONE);
        connectionHeading = text("Не подключено", wide ? 26 : 30, Color.WHITE, true);
        connection.addView(connectionBadge);
        connection.addView(connectionHeading);
        serverTitle = text("Сервер не выбран", wide ? 17 : 18, Color.WHITE, false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(wide ? 5 : 10);
        connection.addView(serverTitle, titleParams);
        serverDetails = text("Импортируйте подписку и выберите сервер", wide ? 13 : 14, MUTED, false);
        if (wide) serverDetails.setVisibility(View.GONE);
        connection.addView(serverDetails);
        connectButton = new Button(this);
        connectButton.setId(View.generateViewId());
        connectButton.setText("Подключить");
        connectButton.setTextSize(17);
        connectButton.setAllCaps(false);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setBackground(interactive(ACCENT));
        connectButton.setOnClickListener(view -> toggleVpn());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, dp(wide ? 42 : 46));
        connectParams.topMargin = dp(wide ? 6 : 12);
        connection.addView(connectButton, connectParams);
        connectionMessage = text("", 14, MUTED, false);
        connection.addView(connectionMessage);

        LinearLayout profileCard = card();
        LinearLayout.LayoutParams profileCardParams = new LinearLayout.LayoutParams(-1, -2);
        profileCardParams.topMargin = dp(10);
        detailsColumn.addView(profileCard, profileCardParams);
        profileCard.addView(text("Текущий профиль", 21, Color.WHITE, true));
        profileValue = text("Весь трафик", 23, Color.WHITE, false);
        profileCard.addView(profileValue);

        LinearLayout infoCard = card();
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(10);
        detailsColumn.addView(infoCard, infoParams);
        infoCard.addView(text("Подписка и серверы", 21, Color.WHITE, true));
        status = text("Введите HTTPS-ссылку на подписку, чтобы загрузить серверы.", 15, MUTED, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(6);
        infoCard.addView(status, statusParams);

        if (!wide) {
            detailsColumn.removeView(connection);
            detailsColumn.removeView(profileCard);
            detailsColumn.removeView(infoCard);
            body.removeView(sidebar);
            body.removeView(detailsColumn);

            sidebar.removeAllViews();
            sidebar.addView(text("Подписка и серверы", 21, Color.WHITE, true));
            sidebar.addView(serverCount, countParams);
            serverSearch = new EditText(this);
            serverSearch.setSingleLine(true);
            serverSearch.setHint("Поиск серверов");
            serverSearch.setTextSize(15);
            serverSearch.setTextColor(Color.WHITE);
            serverSearch.setHintTextColor(MUTED);
            serverSearch.setPadding(dp(12), 0, dp(12), 0);
            serverSearch.setBackground(interactive(FIELD));
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(46));
            searchParams.topMargin = dp(10);
            sidebar.addView(serverSearch, searchParams);
            serverSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    applyServerFilter();
                }
                @Override public void afterTextChanged(Editable text) { }
            });
            sidebar.addView(list, listParams);

            body.addView(connection, new LinearLayout.LayoutParams(-1, -2));
            sidebarParams.topMargin = dp(14);
            sidebarParams.height = 0;
            sidebarParams.weight = 1;
            body.addView(sidebar, sidebarParams);

            mobileMenu = new LinearLayout(this);
            mobileMenu.setOrientation(LinearLayout.VERTICAL);
            mobileScroll = new ScrollView(this);
            mobileScroll.setFillViewport(true);
            mobileScroll.setVisibility(View.GONE);
            mobileScroll.addView(mobileMenu);
            LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(-1, 0, 1);
            menuParams.topMargin = dp(16);
            root.addView(mobileScroll, menuParams);

            LinearLayout subscriptionCard = card();
            subscriptionCard.addView(text("Подписка", 21, Color.WHITE, true));
            subscriptionCard.addView(urlInput, inputParams);
            subscriptionCard.addView(scanQr, qrParams);
            subscriptionCard.addView(refresh, refreshParams);
            mobileMenu.addView(subscriptionCard);

            LinearLayout appsCard = card();
            LinearLayout.LayoutParams appsParams = new LinearLayout.LayoutParams(-1, -2);
            appsParams.topMargin = dp(10);
            mobileMenu.addView(appsCard, appsParams);
            appsCard.addView(text("Приложения через VPN", 21, Color.WHITE, true));
            selectedAppsSummary = text("", 15, MUTED, false);
            appsCard.addView(selectedAppsSummary);
            Button chooseApps = new Button(this);
            chooseApps.setId(View.generateViewId());
            chooseApps.setText("Выбрать приложения");
            chooseApps.setTextSize(14);
            chooseApps.setTextColor(Color.WHITE);
            chooseApps.setAllCaps(false);
            chooseApps.setBackground(interactive(FIELD));
            LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(48));
            chooseParams.topMargin = dp(10);
            appsCard.addView(chooseApps, chooseParams);
            chooseApps.setOnClickListener(view -> chooseApps());

            mobileMenu.addView(profileCard, profileCardParams);
            mobileMenu.addView(infoCard, infoParams);
            updateSelectedApps();
            menuButton.setOnClickListener(view -> showMenu(mobileScroll.getVisibility() != View.VISIBLE));
        } else {
            body.removeView(sidebar);
            detailsColumn.removeView(profileCard);
            detailsColumn.removeView(infoCard);
            sidebar.removeAllViews();
            LinearLayout.LayoutParams tvCountParams = new LinearLayout.LayoutParams(-1, -2);
            sidebar.addView(serverCount, tvCountParams);
            sidebar.addView(list, listParams);
            LinearLayout.LayoutParams sidebarLayout = new LinearLayout.LayoutParams(dp(330), -1);
            sidebarLayout.rightMargin = dp(16);
            body.addView(sidebar, 0, sidebarLayout);
            list.setNextFocusRightId(connectButton.getId());
            connectButton.setNextFocusLeftId(list.getId());

            LinearLayout profileSettings = card();
            profileSettings.addView(text("ПРОФИЛЬ", 12, MUTED, true));
            profileSettings.addView(text("Текущий профиль", 17, Color.WHITE, true));
            profileCard.removeView(profileValue);
            profileValue.setTextSize(18);
            profileSettings.addView(profileValue);
            Button chooseApps = new Button(this);
            chooseApps.setId(View.generateViewId());
            chooseApps.setText("Выбрать приложения");
            chooseApps.setTextSize(14);
            chooseApps.setTextColor(Color.WHITE);
            chooseApps.setAllCaps(false);
            chooseApps.setBackground(interactive(FIELD));
            LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(40));
            chooseParams.topMargin = dp(6);
            profileSettings.addView(chooseApps, chooseParams);
            chooseApps.setOnClickListener(view -> chooseApps());
            LinearLayout.LayoutParams tvCardParams = new LinearLayout.LayoutParams(-1, -2);
            tvCardParams.topMargin = dp(10);
            detailsColumn.addView(profileSettings, tvCardParams);

            LinearLayout subscriptionCard = card();
            subscriptionCard.addView(text("Подписка", 18, Color.WHITE, true));
            Button addSubscription = new Button(this);
            addSubscription.setId(View.generateViewId());
            addSubscription.setText("Добавить подписку");
            addSubscription.setTextSize(14);
            addSubscription.setTextColor(Color.WHITE);
            addSubscription.setAllCaps(false);
            addSubscription.setBackground(interactive(FIELD));
            LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(42));
            addParams.topMargin = dp(6);
            subscriptionCard.addView(addSubscription, addParams);
            addSubscription.setOnClickListener(view -> showSubscriptionActions());
            status.setTextSize(13);
            infoCard.removeView(status);
            subscriptionCard.addView(status);
            connectButton.setNextFocusDownId(chooseApps.getId());
            chooseApps.setNextFocusUpId(connectButton.getId());
            chooseApps.setNextFocusDownId(addSubscription.getId());
            addSubscription.setNextFocusUpId(chooseApps.getId());
            chooseApps.setNextFocusLeftId(list.getId());
            addSubscription.setNextFocusLeftId(list.getId());
            LinearLayout.LayoutParams subscriptionParams = new LinearLayout.LayoutParams(-1, 0, 1);
            subscriptionParams.topMargin = dp(10);
            detailsColumn.addView(subscriptionCard, subscriptionParams);

            updateSelectedApps();
        }
        restoreServer();
        refreshConnectButton();
        updateConnectionUi(PipidastrVpnService.currentState, PipidastrVpnService.currentMessage);
        Intent incoming = getIntent();
        handleIncomingIntent(incoming);
        if (urlInput.length() > 0 &&
                (incoming == null || !Intent.ACTION_SEND.equals(incoming.getAction()))) refreshServers();
        if (wide) list.post(list::requestFocus);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null &&
                SubscriptionPairing.isPairingQr(intent.getDataString())) {
            sendSubscriptionToTv(intent.getDataString());
            return;
        }
        acceptSharedServer(intent);
    }

    private void selectServer(int index) {
        if (index < 0 || index >= servers.size()) return;
        selectedServer = index;
        SubscriptionParser.Server server = servers.get(index);
        serverTitle.setText(server.name);
        String details = server.host + ":" + server.port + " · " + server.protocol;
        if (!compatibleLinks.contains(server.link)) details += " · Этот сервер требует Xray-ядро";
        serverDetails.setText(details);
        getPreferences(MODE_PRIVATE).edit().putString("selected_link", server.link).apply();
        adapter.notifyDataSetChanged();
        refreshConnectButton();
    }

    private void restoreServer() {
        String link = getPreferences(MODE_PRIVATE).getString("selected_link", "");
        if (link.isEmpty()) return;
        try {
            SubscriptionParser.Result parsed = SubscriptionParser.parse(link);
            SubscriptionParser.Server server = parsed.servers.get(0);
            servers.add(server);
            try { SingBoxConfig.build(server); compatibleLinks.add(server.link); }
            catch (Exception ignored) { }
            applyServerFilter();
            selectServer(0);
        } catch (Exception ignored) { }
    }

    private void acceptSharedServer(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String link = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (link == null && (getApplicationInfo().flags &
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            String encoded = intent.getStringExtra("com.pipidastr.vpn.TEST_LINK_BASE64");
            if (encoded != null) link = new String(android.util.Base64.decode(encoded,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP), StandardCharsets.UTF_8);
        }
        if (link == null) return;
        if (link.trim().startsWith("https://")) {
            urlInput.setText(link.trim());
            refreshServers();
            return;
        }
        importServerLink(link);
    }

    private boolean importServerLink(String link) {
        try {
            SubscriptionParser.Result parsed = SubscriptionParser.parse(link.trim());
            SubscriptionParser.Server server = parsed.servers.get(0);
            String config = SingBoxConfig.build(server);
            if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                io.nekohasekai.libbox.Libbox.checkConfig(config);
            servers.add(server);
            compatibleLinks.add(server.link);
            applyServerFilter();
            selectServer(servers.size() - 1);
            if (!wideLayout) showMenu(false);
            return true;
        } catch (Exception ex) {
            connectionMessage.setText("Не удалось импортировать сервер: " + ex.getMessage());
            return false;
        }
    }

    private void applyServerFilter() {
        String query = serverSearch == null ? "" :
                serverSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        visibleServers.clear();
        for (SubscriptionParser.Server server : servers) {
            if (query.isEmpty() || server.name.toLowerCase(java.util.Locale.ROOT).contains(query) ||
                    server.host.toLowerCase(java.util.Locale.ROOT).contains(query) ||
                    server.protocol.toLowerCase(java.util.Locale.ROOT).contains(query))
                visibleServers.add(server);
        }
        adapter.notifyDataSetChanged();
        serverCount.setText("СЕРВЕРЫ · " + (query.isEmpty() ? servers.size() :
                visibleServers.size() + " / " + servers.size()));
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PipidastrVpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(vpnStatus, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(vpnStatus, filter);
        updateConnectionUi(PipidastrVpnService.currentState, PipidastrVpnService.currentMessage);
    }

    @Override protected void onStop() {
        unregisterReceiver(vpnStatus);
        super.onStop();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(16));
        return shape;
    }

    private Drawable interactive(int base) {
        int bright = base == ACCENT ? Color.rgb(151, 145, 255) : Color.rgb(61, 78, 119);
        return new AnimatedHighlightDrawable(base, bright, bright);
    }

    private Drawable serverItemBackground(boolean active) {
        return new AnimatedHighlightDrawable(active ? SELECTED : FIELD,
                SELECTED, Color.rgb(61, 78, 119));
    }

    private final class AnimatedHighlightDrawable extends Drawable {
        private final int base;
        private final int focused;
        private final int hovered;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArgbEvaluator colors = new ArgbEvaluator();
        private int fill;
        private int border = Color.argb(0, 255, 255, 255);
        private int opacity = 255;
        private boolean initialized;
        private ValueAnimator transition;

        AnimatedHighlightDrawable(int base, int focused, int hovered) {
            this.base = base;
            this.focused = focused;
            this.hovered = hovered;
            this.fill = base;
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            boolean strong = hasState(state, android.R.attr.state_pressed) ||
                    hasState(state, android.R.attr.state_focused) ||
                    hasState(state, android.R.attr.state_activated) ||
                    hasState(state, android.R.attr.state_selected);
            boolean hover = !strong && hasState(state, android.R.attr.state_hovered);
            int targetFill = strong ? focused : hover ? hovered : base;
            int targetBorder = strong ? Color.WHITE : hover ? ACCENT :
                    Color.argb(0, 255, 255, 255);
            if (transition != null) transition.cancel();
            if (!initialized) {
                initialized = true;
                fill = targetFill;
                border = targetBorder;
                invalidateSelf();
                return true;
            }
            if (fill == targetFill && border == targetBorder) return false;
            int fromFill = fill;
            int fromBorder = border;
            transition = ValueAnimator.ofFloat(0f, 1f);
            transition.setDuration(220);
            transition.setInterpolator(new DecelerateInterpolator());
            transition.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                fill = (int) colors.evaluate(progress, fromFill, targetFill);
                border = (int) colors.evaluate(progress, fromBorder, targetBorder);
                invalidateSelf();
            });
            transition.start();
            return true;
        }

        private boolean hasState(int[] states, int wanted) {
            for (int state : states) if (state == wanted) return true;
            return false;
        }

        @Override public void draw(Canvas canvas) {
            float inset = dp(1);
            RectF bounds = new RectF(getBounds().left + inset, getBounds().top + inset,
                    getBounds().right - inset, getBounds().bottom - inset);
            float radius = dp(16);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            paint.setAlpha(Color.alpha(fill) * opacity / 255);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            if (Color.alpha(border) > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(border);
                paint.setAlpha(Color.alpha(border) * opacity / 255);
                canvas.drawRoundRect(bounds, radius, radius, paint);
            }
        }

        @Override public void setAlpha(int value) { opacity = value; invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void updateServerFocus(ListView list) {
        int selected = list.hasFocus() ? list.getSelectedItemPosition() : -1;
        for (int index = 0; index < list.getChildCount(); index++) {
            list.getChildAt(index).setActivated(list.getFirstVisiblePosition() + index == selected);
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(13), dp(20), dp(13));
        card.setBackground(round(CARD));
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private LinearLayout dialogPanel(String title) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(20));
        panel.setBackground(round(CARD));
        TextView heading = text(title, wideLayout ? 21 : 19, Color.WHITE, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.bottomMargin = dp(16);
        panel.addView(heading, headingParams);
        return panel;
    }

    private Button dialogButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(wideLayout ? 16 : 14);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(interactive(color));
        return button;
    }

    private LinearLayout dialogActions(LinearLayout panel) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        panel.addView(actions, params);
        return actions;
    }

    private void showThemedDialog(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int available = getResources().getDisplayMetrics().widthPixels - dp(32);
            dialog.getWindow().setLayout(Math.min(dp(wideLayout ? 540 : 430), available),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showActionDialog(String title, String[] labels,
            java.util.function.IntConsumer selected) {
        LinearLayout panel = dialogPanel(title);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        for (int index = 0; index < labels.length; index++) {
            final int choice = index;
            Button action = dialogButton(labels[index], FIELD);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
            if (index > 0) params.topMargin = dp(9);
            panel.addView(action, params);
            action.setOnClickListener(view -> {
                dialog.dismiss();
                selected.accept(choice);
            });
        }
        showThemedDialog(dialog);
    }

    private void showMenu(boolean open) {
        if (mobileMenu == null) return;
        mobileBody.animate().cancel();
        mobileScroll.animate().cancel();
        mobileBody.setVisibility(open ? View.GONE : View.VISIBLE);
        mobileScroll.setVisibility(open ? View.VISIBLE : View.GONE);
        View incoming = open ? mobileScroll : mobileBody;
        incoming.setAlpha(0f);
        incoming.setTranslationY(dp(open ? -18 : 18));
        incoming.animate().alpha(1f).translationY(0f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator()).start();
        menuButton.setImageResource(open ? R.drawable.ic_back : R.drawable.ic_menu);
        menuButton.setContentDescription(open ? "Закрыть настройки" : "Открыть настройки");
        menuButton.animate().cancel();
        menuButton.animate().rotation(open ? 90f : 0f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator()).start();
        if (mobileScroll != null) mobileScroll.scrollTo(0, 0);
    }

    private void refreshConnectButton() {
        if (connectButton == null) return;
        String state = displayedVpnState;
        boolean busy = "connecting".equals(state);
        boolean connected = "connected".equals(state);
        boolean supported = selectedServer >= 0 && selectedServer < servers.size() &&
                compatibleLinks.contains(servers.get(selectedServer).link);
        boolean enabled = !busy && (connected || supported);
        connectButton.setEnabled(enabled);
        connectButton.setAlpha(enabled ? 1f : 0.65f);
        connectButton.setText(busy ? "Подключение…" : connected ? "Отключить" : "Подключить");
    }

    private void updateConnectionUi(String state, String message) {
        if (connectionHeading == null) return;
        displayedVpnState = state == null ? "disconnected" : state;
        boolean connected = "connected".equals(state);
        boolean connecting = "connecting".equals(state);
        boolean error = "error".equals(state);
        connectionBadge.setText(connected ? "ПОДКЛЮЧЕНО" : connecting ? "ПОДКЛЮЧЕНИЕ" :
                error ? "ОШИБКА" : "НЕ ПОДКЛЮЧЕНО");
        connectionHeading.setText(connected ? "Подключено" : connecting ? "Подключаю…" : "Не подключено");
        connectionMessage.setText(error && message != null ? message : "");
        refreshConnectButton();
    }

    private void toggleVpn() {
        if ("connected".equals(displayedVpnState)) {
            startService(new Intent(this, PipidastrVpnService.class)
                    .setAction(PipidastrVpnService.ACTION_STOP));
            updateConnectionUi("connecting", "Отключаю…");
            return;
        }
        if (selectedServer < 0 || selectedServer >= servers.size()) return;
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST);
        else startVpn();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) startVpn();
            else connectionMessage.setText("Разрешение VPN не предоставлено.");
        } else if (requestCode == QR_CAMERA_REQUEST) {
            Uri image = qrCameraImage;
            qrCameraImage = null;
            if (resultCode == RESULT_OK && image != null) decodeQrImage(image, true);
            else if (image != null) deleteQrPhoto(image);
        } else if (requestCode == QR_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            decodeQrImage(data.getData(), false);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (qrCameraImage != null) state.putString("qr_camera_image", qrCameraImage.toString());
        super.onSaveInstanceState(state);
    }

    private void chooseQrSource() {
        boolean camera = getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY);
        String[] choices = camera ? new String[] {"Сфотографировать QR", "Выбрать изображение"}
                : new String[] {"Выбрать изображение"};
        showActionDialog("Добавить по QR-коду", choices, index -> {
            if (camera && index == 0) captureQrImage();
            else pickQrImage();
        });
    }

    private void showSubscriptionActions() {
        String[] actions = {"Ввести ссылку", "Добавить по QR-коду",
                "Получить с телефона по QR", "Обновить серверы"};
        showActionDialog("Добавить подписку", actions, index -> {
            if (index == 0) showManualSubscriptionDialog();
            else if (index == 1) chooseQrSource();
            else if (index == 2) showPairingQr();
            else refreshServers();
        });
    }

    private void showManualSubscriptionDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("HTTPS-ссылка на подписку");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(urlInput.getText());
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(interactive(FIELD));
        LinearLayout panel = dialogPanel("Ссылка на подписку");
        panel.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout actions = dialogActions(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        Button cancel = dialogButton("Отмена", FIELD);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button save = dialogButton("Сохранить", ACCENT);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
        save.setOnClickListener(view -> {
                    String address = input.getText().toString().trim();
                    if (!address.startsWith("https://")) {
                        showQrError("Нужна HTTPS-ссылка на подписку.");
                        return;
                    }
                    dialog.dismiss();
                    urlInput.setText(address);
                    refreshServers();
                });
        showThemedDialog(dialog);
    }

    private void captureQrImage() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "pipidastr-qr-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        try {
            Uri image = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (image == null) throw new IllegalStateException("Не удалось создать снимок.");
            qrCameraImage = image;
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, image)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, QR_CAMERA_REQUEST);
        } catch (Exception ex) {
            if (qrCameraImage != null) deleteQrPhoto(qrCameraImage);
            qrCameraImage = null;
            showQrError("Камера недоступна. Выберите изображение с QR-кодом.");
        }
    }

    private void pickQrImage() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, QR_IMAGE_REQUEST);
        } catch (ActivityNotFoundException ex) {
            showQrError("На устройстве нет приложения для выбора изображения.");
        }
    }

    private void decodeQrImage(Uri image, boolean deleteAfterwards) {
        if (image == null) { showQrError("Изображение не выбрано."); return; }
        status.setText("Считываю QR-код…");
        worker.execute(() -> {
            try {
                String value = QrSubscriptionDecoder.decode(getContentResolver(), image);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (value.startsWith("https://")) {
                        urlInput.setText(value);
                        refreshServers();
                    } else if (SubscriptionPairing.isPairingQr(value)) {
                        sendSubscriptionToTv(value);
                    } else if (value.startsWith("vless://") || value.startsWith("hysteria2://")) {
                        if (importServerLink(value)) status.setText("Сервер добавлен из QR-кода.");
                    } else showQrError("QR-код не содержит HTTPS-подписку или ссылку сервера.");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) showQrError(ex.getMessage()); });
            } finally {
                if (deleteAfterwards) deleteQrPhoto(image);
            }
        });
    }

    private void showQrError(String message) {
        status.setText(message);
        showMessageDialog("QR-код", message);
    }

    private void showMessageDialog(String title, String message) {
        LinearLayout panel = dialogPanel(title);
        panel.addView(text(message, 15, MUTED, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        Button close = dialogButton("Закрыть", ACCENT);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.topMargin = dp(16);
        panel.addView(close, closeParams);
        close.setOnClickListener(view -> dialog.dismiss());
        showThemedDialog(dialog);
    }

    private void showPairingQr() {
        try {
            if (pairingSession != null) pairingSession.close();
            SubscriptionPairing.Session session = new SubscriptionPairing.Session(this);
            pairingSession = session;
            Bitmap bitmap = QrSubscriptionDecoder.encode(session.qrContent());
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setContentDescription("QR-код для передачи подписки с телефона");
            image.setBackgroundColor(Color.WHITE);
            int size = Math.min(dp(270), (int) (getResources().getDisplayMetrics().heightPixels * 0.52f));
            FrameLayout frame = new FrameLayout(this);
            frame.setPadding(dp(12), dp(12), dp(12), dp(12));
            frame.addView(image, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            LinearLayout panel = dialogPanel("Передать подписку с телефона");
            TextView hint = text("Откройте Pipidastr VPN на телефоне и выберите «Добавить по QR-коду». Код действует 2 минуты.",
                    14, MUTED, false);
            panel.addView(hint);
            panel.addView(frame, new LinearLayout.LayoutParams(-1, size + dp(24)));
            AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
            Button close = dialogButton("Закрыть", ACCENT);
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
            closeParams.topMargin = dp(10);
            panel.addView(close, closeParams);
            close.setOnClickListener(view -> dialog.dismiss());
            dialog.setOnDismissListener(ignored -> {
                if (pairingSession == session) { pairingSession = null; session.close(); }
                bitmap.recycle();
            });
            showThemedDialog(dialog);
            session.waitForSubscription(url -> runOnUiThread(() -> {
                if (pairingSession != session || isFinishing() || isDestroyed()) return;
                pairingSession = null;
                dialog.dismiss();
                urlInput.setText(url);
                refreshServers();
            }), error -> runOnUiThread(() -> {
                if (pairingSession != session || isFinishing() || isDestroyed()) return;
                pairingSession = null;
                dialog.dismiss();
                showQrError(error instanceof java.net.SocketTimeoutException ?
                        "Время ожидания истекло. Откройте новый QR-код." :
                        "Не удалось передать подписку. Проверьте Wi-Fi обоих устройств.");
            }));
        } catch (Exception ex) { showQrError(ex.getMessage()); }
    }

    private void sendSubscriptionToTv(String qr) {
        String subscription = urlInput.getText().toString().trim();
        if (subscription.isEmpty()) subscription = getPreferences(MODE_PRIVATE).getString("subscription_url", "");
        final String url = subscription;
        if (url.isEmpty()) { showQrError("Сначала добавьте HTTPS-подписку на телефоне."); return; }
        status.setText("Передаю подписку на телевизор…");
        worker.execute(() -> {
            try {
                SubscriptionPairing.send(this, qr, url);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText("Подписка передана на телевизор.");
                    showMessageDialog("Подписка", "Подписка передана на телевизор.");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed())
                    showQrError("Не удалось передать подписку: " + ex.getMessage()); });
            }
        });
    }

    private void deleteQrPhoto(Uri image) {
        try { getContentResolver().delete(image, null, null); }
        catch (Exception ignored) { }
    }

    private void startVpn() {
        try {
            String config = SingBoxConfig.build(servers.get(selectedServer));
            String[] packages = getPreferences(MODE_PRIVATE)
                    .getStringSet("selected_apps", new HashSet<>()).toArray(new String[0]);
            Intent intent = new Intent(this, PipidastrVpnService.class)
                    .setAction(PipidastrVpnService.ACTION_START)
                    .putExtra(PipidastrVpnService.EXTRA_CONFIG, config)
                    .putExtra(PipidastrVpnService.EXTRA_PACKAGES, packages);
            startForegroundService(intent);
            updateConnectionUi("connecting", "Подключаю сервер…");
        } catch (Exception ex) {
            connectionMessage.setText(ex.getMessage());
        }
    }

    @Override public void onBackPressed() {
        if (!wideLayout && mobileScroll != null && mobileScroll.getVisibility() == View.VISIBLE) {
            showMenu(false);
            return;
        }
        super.onBackPressed();
    }

    private void updateSelectedApps() {
        int count = getPreferences(MODE_PRIVATE).getStringSet("selected_apps", new HashSet<>()).size();
        if (selectedAppsSummary != null)
            selectedAppsSummary.setText(count == 0 ? "Весь трафик через VPN" : "Выбрано приложений: " + count);
        profileValue.setText(count == 0 ? "Весь трафик" : "Приложения · " + count);
        profileName.setText(count == 0 ? "Весь трафик" : "Приложения · " + count);
    }

    private void chooseApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> appNames = new HashMap<>();
        for (ResolveInfo app : getPackageManager().queryIntentActivities(launcher, 0)) {
            if (app.activityInfo == null) continue;
            String pkg = app.activityInfo.packageName;
            if (!getPackageName().equals(pkg))
                appNames.put(pkg, app.loadLabel(getPackageManager()).toString());
        }
        if (appNames.isEmpty()) {
            showMessageDialog("Приложения через VPN", "Установленные приложения не найдены.");
            return;
        }

        List<String> packages = new ArrayList<>(appNames.keySet());
        packages.sort((left, right) -> appNames.get(left).compareToIgnoreCase(appNames.get(right)));
        CharSequence[] labels = new CharSequence[packages.size()];
        boolean[] checked = new boolean[packages.size()];
        Set<String> saved = getPreferences(MODE_PRIVATE).getStringSet("selected_apps", new HashSet<>());
        for (int i = 0; i < packages.size(); i++) {
            String pkg = packages.get(i);
            labels[i] = appNames.get(pkg) + "\n" + pkg;
            checked[i] = saved.contains(pkg);
        }
        LinearLayout panel = dialogPanel("Приложения через VPN");
        TextView hint = text("Отметьте приложения, которые будут использовать VPN", 14, MUTED, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.bottomMargin = dp(12);
        panel.addView(hint, hintParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout appRows = new LinearLayout(this);
        appRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(appRows);
        int maxHeight = Math.min(dp(wideLayout ? 400 : 430),
                getResources().getDisplayMetrics().heightPixels - dp(220));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, Math.max(dp(120), maxHeight)));
        for (int index = 0; index < packages.size(); index++) {
            CheckBox option = new CheckBox(this);
            option.setText(labels[index]);
            Drawable icon;
            try {
                android.content.pm.ApplicationInfo info = getPackageManager()
                        .getApplicationInfo(packages.get(index), 0);
                icon = info.icon == 0 ? getDrawable(R.drawable.pipidastr_logo)
                        : getPackageManager().getApplicationIcon(info);
            }
            catch (Exception ignored) { icon = getDrawable(R.drawable.pipidastr_logo); }
            if (icon != null) {
                icon.setBounds(0, 0, dp(32), dp(32));
                option.setCompoundDrawables(icon, null, null, null);
                option.setCompoundDrawablePadding(dp(12));
            }
            option.setTextSize(wideLayout ? 15 : 13);
            option.setTextColor(Color.WHITE);
            option.setButtonTintList(ColorStateList.valueOf(ACCENT));
            option.setChecked(checked[index]);
            option.setPadding(dp(12), dp(9), dp(12), dp(9));
            option.setMinHeight(dp(58));
            option.setBackground(interactive(FIELD));
            final int choice = index;
            option.setOnCheckedChangeListener((view, selected) -> checked[choice] = selected);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(7);
            appRows.addView(option, rowParams);
        }
        LinearLayout actions = dialogActions(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        Button cancel = dialogButton("Отмена", FIELD);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button save = dialogButton("Сохранить", ACCENT);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
        save.setOnClickListener(view -> {
            Set<String> selection = new HashSet<>();
            for (int i = 0; i < packages.size(); i++)
                if (checked[i]) selection.add(packages.get(i));
            getPreferences(MODE_PRIVATE).edit().putStringSet("selected_apps", selection).apply();
            updateSelectedApps();
            dialog.dismiss();
        });
        showThemedDialog(dialog);
    }

    private void refreshServers() {
        String address = urlInput.getText().toString().trim();
        if (!address.startsWith("https://")) {
            status.setText("Нужна HTTPS-ссылка на подписку.");
            return;
        }
        refresh.setEnabled(false);
        status.setText("Загружаю подписку…");
        String previous = getPreferences(MODE_PRIVATE).getString("subscription_url", "");
        getPreferences(MODE_PRIVATE).edit().putString("subscription_url", address)
                .putBoolean("needs_device_id", address.equals(previous) &&
                        getPreferences(MODE_PRIVATE).getBoolean("needs_device_id", false)).apply();
        worker.execute(() -> {
            try {
                SubscriptionParser.Result result = fetch(address);
                Set<String> supported = new HashSet<>();
                int unsupported = 0;
                for (SubscriptionParser.Server server : result.servers) {
                    try { SingBoxConfig.build(server); supported.add(server.link); }
                    catch (Exception ex) { unsupported++; }
                }
                if (result.servers.isEmpty()) throw new IOException("В подписке нет серверов.");
                int supportedCount = result.servers.size() - unsupported;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    String previousLink = getPreferences(MODE_PRIVATE).getString("selected_link", "");
                    servers.clear();
                    servers.addAll(result.servers);
                    compatibleLinks.clear();
                    compatibleLinks.addAll(supported);
                    selectedServer = -1;
                    serverTitle.setText("Сервер не выбран");
                    serverDetails.setText("Выберите сервер из списка");
                    applyServerFilter();
                    status.setText("Серверов: " + servers.size() + "; совместимо с ядром: " +
                            supportedCount + (result.skipped > 0 ? "; повреждённых ссылок: " + result.skipped : ""));
                    int selected = -1;
                    for (int i = 0; i < servers.size(); i++)
                        if (servers.get(i).link.equals(previousLink) &&
                                supported.contains(servers.get(i).link)) { selected = i; break; }
                    if (selected < 0) for (int i = 0; i < servers.size(); i++)
                        if (supported.contains(servers.get(i).link)) { selected = i; break; }
                    if (selected < 0) selected = 0;
                    selectServer(selected);
                    refresh.setEnabled(true);
                    if (!wideLayout && !servers.isEmpty()) showMenu(false);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText("Не удалось загрузить подписку: " + ex.getMessage());
                    refresh.setEnabled(true);
                });
            }
        });
    }

    private SubscriptionParser.Result fetch(String address) throws IOException {
        boolean needsId = getPreferences(MODE_PRIVATE).getBoolean("needs_device_id", false);
        String deviceId = needsId ? deviceId() : null;
        try {
            return fetchOnce(address, deviceId);
        } catch (SubscriptionParser.DeviceIdRequiredException ex) {
            if (deviceId != null) throw new IOException("Сервис не принял ID устройства или достигнут лимит устройств.");
            SubscriptionParser.Result result = fetchOnce(address, deviceId());
            getPreferences(MODE_PRIVATE).edit().putBoolean("needs_device_id", true).apply();
            return result;
        }
    }

    private String deviceId() {
        String id = getPreferences(MODE_PRIVATE).getString("device_id", null);
        if (id != null) return id;
        id = UUID.randomUUID().toString().replace("-", "");
        getPreferences(MODE_PRIVATE).edit().putString("device_id", id).apply();
        return id;
    }

    private static SubscriptionParser.Result fetchOnce(String address, String deviceId) throws IOException {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Нужен HTTPS-адрес.");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "PipidastrVPN-Android/0.1");
            if (deviceId != null) connection.setRequestProperty("X-Hwid", deviceId);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                if ((code == 400 || code == 404) && body(connection.getErrorStream()).contains("HWID"))
                    throw new SubscriptionParser.DeviceIdRequiredException();
                if ("true".equalsIgnoreCase(connection.getHeaderField("x-hwid-max-devices-reached")))
                    throw new IOException("Достигнут лимит устройств подписки.");
                throw new IOException("Сервер подписки ответил кодом " + code + ".");
            }
            return SubscriptionParser.parse(body(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String body(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > MAX_SUBSCRIPTION_BYTES)
                    throw new IOException("Подписка слишком большая.");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override protected void onDestroy() {
        if (pairingSession != null) pairingSession.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
