package com.pipidastr.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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
    private static final int MAX_SUBSCRIPTION_BYTES = 2_000_000;
    private static final int BACKGROUND = Color.rgb(10, 15, 32);
    private static final int CARD = Color.rgb(21, 30, 51);
    private static final int FIELD = Color.rgb(33, 45, 72);
    private static final int SELECTED = Color.rgb(44, 63, 99);
    private static final int ACCENT = Color.rgb(126, 119, 249);
    private static final int MUTED = Color.rgb(149, 174, 221);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<SubscriptionParser.Server> servers = new ArrayList<>();
    private ArrayAdapter<SubscriptionParser.Server> adapter;
    private EditText urlInput;
    private TextView status;
    private TextView serverTitle;
    private TextView serverDetails;
    private TextView serverCount;
    private Button refresh;
    private ListView serverList;
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
    private final BroadcastReceiver vpnStatus = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateConnectionUi(intent.getStringExtra(PipidastrVpnService.EXTRA_STATE),
                    intent.getStringExtra(PipidastrVpnService.EXTRA_MESSAGE));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        wideLayout = wide;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(16), dp(22), dp(16));
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        if (wide) {
            setContentView(root);
        } else {
            mobileScroll = new ScrollView(this);
            mobileScroll.setFillViewport(true);
            mobileScroll.setBackgroundColor(BACKGROUND);
            mobileScroll.addView(root);
            setContentView(mobileScroll);
        }
        root.requestFocus();

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
            menuButton.setBackground(round(FIELD));
            header.addView(menuButton, new LinearLayout.LayoutParams(dp(46), dp(42)));
        }
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        mobileBody = body;
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, wide ? 0 : -2, wide ? 1 : 0);
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
        urlInput.setBackground(round(FIELD));
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(getPreferences(MODE_PRIVATE).getString("subscription_url", ""));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(48));
        inputParams.topMargin = dp(12);
        sidebar.addView(urlInput, inputParams);

        refresh = new Button(this);
        refresh.setText("Обновить серверы");
        refresh.setTextSize(14);
        refresh.setTextColor(Color.WHITE);
        refresh.setAllCaps(false);
        refresh.setBackground(round(FIELD));
        refresh.setOnClickListener(view -> refreshServers());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, dp(48));
        refreshParams.topMargin = dp(10);
        sidebar.addView(refresh, refreshParams);

        ListView list = new ListView(this);
        serverList = list;
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        adapter = new ArrayAdapter<SubscriptionParser.Server>(this, android.R.layout.simple_list_item_1, servers) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, recycled, parent);
                SubscriptionParser.Server server = getItem(position);
                item.setText(server.name + "\n" + server.host + ":" + server.port + " · " + server.protocol);
                item.setTextSize(15);
                item.setTextColor(Color.WHITE);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setMinHeight(dp(68));
                item.setPadding(dp(14), dp(9), dp(10), dp(9));
                item.setBackground(round(position == selectedServer ? SELECTED : FIELD));
                return item;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> selectServer(position));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, wide ? 1 : 0);
        listParams.topMargin = dp(10);
        sidebar.addView(list, listParams);

        LinearLayout detailsColumn = new LinearLayout(this);
        detailsColumn.setOrientation(LinearLayout.VERTICAL);
        body.addView(detailsColumn, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : -2, wide ? 1 : 0));

        LinearLayout connection = card();
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2);
        if (!wide) connectionParams.topMargin = dp(14);
        detailsColumn.addView(connection, connectionParams);
        connectionBadge = text("НЕ ПОДКЛЮЧЕНО", 13, MUTED, true);
        connectionHeading = text("Не подключено", 30, Color.WHITE, true);
        connection.addView(connectionBadge);
        connection.addView(connectionHeading);
        serverTitle = text("Сервер не выбран", 18, Color.WHITE, false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(10);
        connection.addView(serverTitle, titleParams);
        serverDetails = text("Импортируйте подписку и выберите сервер", 14, MUTED, false);
        connection.addView(serverDetails);
        connectButton = new Button(this);
        connectButton.setText("Подключить");
        connectButton.setTextSize(17);
        connectButton.setAllCaps(false);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setBackground(round(ACCENT));
        connectButton.setOnClickListener(view -> toggleVpn());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, dp(46));
        connectParams.topMargin = dp(12);
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
            sidebar.addView(list, listParams);

            body.addView(connection, new LinearLayout.LayoutParams(-1, -2));
            sidebarParams.topMargin = dp(14);
            body.addView(sidebar, sidebarParams);

            mobileMenu = new LinearLayout(this);
            mobileMenu.setOrientation(LinearLayout.VERTICAL);
            mobileMenu.setVisibility(View.GONE);
            LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(-1, -2);
            menuParams.topMargin = dp(16);
            root.addView(mobileMenu, menuParams);

            LinearLayout subscriptionCard = card();
            subscriptionCard.addView(text("Подписка", 21, Color.WHITE, true));
            subscriptionCard.addView(urlInput, inputParams);
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
            chooseApps.setText("Выбрать приложения");
            chooseApps.setTextSize(14);
            chooseApps.setTextColor(Color.WHITE);
            chooseApps.setAllCaps(false);
            chooseApps.setBackground(round(FIELD));
            LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(48));
            chooseParams.topMargin = dp(10);
            appsCard.addView(chooseApps, chooseParams);
            chooseApps.setOnClickListener(view -> chooseApps());

            mobileMenu.addView(profileCard, profileCardParams);
            mobileMenu.addView(infoCard, infoParams);
            updateSelectedApps();
            menuButton.setOnClickListener(view -> showMenu(mobileMenu.getVisibility() != View.VISIBLE));
        } else {
            body.removeView(sidebar);
            detailsColumn.removeView(profileCard);
            LinearLayout leftColumn = new LinearLayout(this);
            leftColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(dp(330), -1);
            leftParams.rightMargin = dp(16);
            body.addView(leftColumn, 0, leftParams);
            leftColumn.addView(sidebar, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams leftProfileParams = new LinearLayout.LayoutParams(-1, -2);
            leftProfileParams.topMargin = dp(10);
            leftColumn.addView(profileCard, leftProfileParams);
        }
        restoreServer();
        refreshConnectButton();
        updateConnectionUi(PipidastrVpnService.currentState, PipidastrVpnService.currentMessage);
        acceptSharedServer(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptSharedServer(intent);
    }

    private void selectServer(int index) {
        if (index < 0 || index >= servers.size()) return;
        selectedServer = index;
        SubscriptionParser.Server server = servers.get(index);
        serverTitle.setText(server.name);
        serverDetails.setText(server.host + ":" + server.port + " · " + server.protocol);
        getPreferences(MODE_PRIVATE).edit().putString("selected_link", server.link).apply();
        adapter.notifyDataSetChanged();
        refreshConnectButton();
    }

    private void restoreServer() {
        String link = getPreferences(MODE_PRIVATE).getString("selected_link", "");
        if (link.isEmpty()) return;
        try {
            SubscriptionParser.Result parsed = SubscriptionParser.parse(link);
            servers.add(parsed.servers.get(0));
            adapter.notifyDataSetChanged();
            serverCount.setText("СЕРВЕРЫ · " + servers.size());
            resizeServerList();
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
        try {
            SubscriptionParser.Result parsed = SubscriptionParser.parse(link.trim());
            SubscriptionParser.Server server = parsed.servers.get(0);
            String config = SingBoxConfig.build(server);
            if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                io.nekohasekai.libbox.Libbox.checkConfig(config);
            servers.add(server);
            adapter.notifyDataSetChanged();
            serverCount.setText("СЕРВЕРЫ · " + servers.size());
            resizeServerList();
            selectServer(servers.size() - 1);
            if (!wideLayout) showMenu(false);
        } catch (Exception ex) {
            connectionMessage.setText("Не удалось импортировать сервер: " + ex.getMessage());
        }
    }

    private void resizeServerList() {
        if (wideLayout) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) serverList.getLayoutParams();
        params.height = dp(Math.min(servers.size() * 72, 252));
        serverList.setLayoutParams(params);
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

    private void showMenu(boolean open) {
        if (mobileMenu == null) return;
        mobileBody.setVisibility(open ? View.GONE : View.VISIBLE);
        mobileMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        menuButton.setImageResource(open ? R.drawable.ic_back : R.drawable.ic_menu);
        menuButton.setContentDescription(open ? "Закрыть меню" : "Открыть меню");
        mobileScroll.scrollTo(0, 0);
    }

    private void refreshConnectButton() {
        if (connectButton == null) return;
        String state = displayedVpnState;
        boolean busy = "connecting".equals(state);
        boolean connected = "connected".equals(state);
        boolean enabled = !busy && (connected || selectedServer >= 0);
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
        if (requestCode != VPN_PERMISSION_REQUEST) return;
        if (resultCode == RESULT_OK) startVpn();
        else connectionMessage.setText("Разрешение VPN не предоставлено.");
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
        if (mobileMenu != null && mobileMenu.getVisibility() == View.VISIBLE) {
            showMenu(false);
            return;
        }
        super.onBackPressed();
    }

    private void updateSelectedApps() {
        if (selectedAppsSummary == null) return;
        int count = getPreferences(MODE_PRIVATE).getStringSet("selected_apps", new HashSet<>()).size();
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
            new AlertDialog.Builder(this).setMessage("Установленные приложения не найдены.")
                    .setPositiveButton("Закрыть", null).show();
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
        new AlertDialog.Builder(this)
                .setTitle("Приложения через VPN")
                .setMultiChoiceItems(labels, checked, (dialog, index, selected) -> checked[index] = selected)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    Set<String> selection = new HashSet<>();
                    for (int i = 0; i < packages.size(); i++)
                        if (checked[i]) selection.add(packages.get(i));
                    getPreferences(MODE_PRIVATE).edit().putStringSet("selected_apps", selection).apply();
                    updateSelectedApps();
                }).show();
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
                List<SubscriptionParser.Server> compatible = new ArrayList<>();
                int unsupported = 0;
                for (SubscriptionParser.Server server : result.servers) {
                    try { SingBoxConfig.build(server); compatible.add(server); }
                    catch (Exception ex) { unsupported++; }
                }
                if (compatible.isEmpty()) throw new IOException("В подписке нет серверов, совместимых с Android-ядром.");
                int skipped = result.skipped + unsupported;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    String previousLink = getPreferences(MODE_PRIVATE).getString("selected_link", "");
                    servers.clear();
                    servers.addAll(compatible);
                    selectedServer = -1;
                    serverTitle.setText("Сервер не выбран");
                    serverDetails.setText("Выберите сервер из списка");
                    adapter.notifyDataSetChanged();
                    resizeServerList();
                    serverCount.setText("СЕРВЕРЫ · " + servers.size());
                    status.setText("Серверов: " + servers.size() + "; пропущено: " + skipped);
                    int selected = 0;
                    for (int i = 0; i < servers.size(); i++)
                        if (servers.get(i).link.equals(previousLink)) { selected = i; break; }
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
        worker.shutdownNow();
        super.onDestroy();
    }
}
