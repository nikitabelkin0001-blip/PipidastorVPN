package com.pipidastr.vpn;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
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
    private int selectedServer = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(16), dp(22), dp(16));
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        if (wide) {
            setContentView(root);
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(BACKGROUND);
            scroll.addView(root);
            setContentView(scroll);
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
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, wide ? 0 : -2, wide ? 1 : 0);
        bodyParams.topMargin = dp(16);
        root.addView(body, bodyParams);

        LinearLayout sidebar = card();
        LinearLayout.LayoutParams sidebarParams = new LinearLayout.LayoutParams(wide ? dp(330) : -1, wide ? -1 : -2);
        if (wide) sidebarParams.rightMargin = dp(16);
        body.addView(sidebar, sidebarParams);
        sidebar.addView(text("ПРОФИЛЬ", 12, MUTED, true));
        TextView profile = text("Приложения", 18, Color.WHITE, true);
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(-1, -2);
        profileParams.topMargin = dp(8);
        sidebar.addView(profile, profileParams);

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
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedServer = position;
            SubscriptionParser.Server server = servers.get(position);
            serverTitle.setText(server.name);
            serverDetails.setText(server.host + ":" + server.port + " · " + server.protocol);
            adapter.notifyDataSetChanged();
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, wide ? 0 : dp(210), wide ? 1 : 0);
        listParams.topMargin = dp(10);
        sidebar.addView(list, listParams);

        LinearLayout detailsColumn = new LinearLayout(this);
        detailsColumn.setOrientation(LinearLayout.VERTICAL);
        body.addView(detailsColumn, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : -2, wide ? 1 : 0));

        LinearLayout connection = card();
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2);
        if (!wide) connectionParams.topMargin = dp(14);
        detailsColumn.addView(connection, connectionParams);
        connection.addView(text("НЕ ПОДКЛЮЧЕНО", 13, MUTED, true));
        connection.addView(text("Не подключено", 30, Color.WHITE, true));
        serverTitle = text("Сервер не выбран", 18, Color.WHITE, false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(10);
        connection.addView(serverTitle, titleParams);
        serverDetails = text("Импортируйте подписку и выберите сервер", 14, MUTED, false);
        connection.addView(serverDetails);
        Button connect = new Button(this);
        connect.setText("Подключить");
        connect.setTextSize(17);
        connect.setAllCaps(false);
        connect.setTextColor(Color.WHITE);
        connect.setBackground(round(ACCENT));
        connect.setEnabled(false);
        connect.setAlpha(0.65f);
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, dp(46));
        connectParams.topMargin = dp(12);
        connection.addView(connect, connectParams);

        LinearLayout profileCard = card();
        LinearLayout.LayoutParams profileCardParams = new LinearLayout.LayoutParams(-1, -2);
        profileCardParams.topMargin = dp(10);
        detailsColumn.addView(profileCard, profileCardParams);
        profileCard.addView(text("Текущий профиль", 21, Color.WHITE, true));
        profileCard.addView(text("Приложения", 23, Color.WHITE, false));

        LinearLayout infoCard = card();
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(10);
        detailsColumn.addView(infoCard, infoParams);
        infoCard.addView(text("Подписка и серверы", 21, Color.WHITE, true));
        status = text("Введите HTTPS-ссылку на подписку. Подключение станет доступно после обновления.", 15, MUTED, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(6);
        infoCard.addView(status, statusParams);
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
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    servers.clear();
                    servers.addAll(result.servers);
                    selectedServer = -1;
                    adapter.notifyDataSetChanged();
                    serverCount.setText("СЕРВЕРЫ · " + servers.size());
                    status.setText("Серверов: " + servers.size() + "; пропущено: " + result.skipped);
                    refresh.setEnabled(true);
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
