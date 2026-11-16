package com.pipidastr.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import libXray.DialerController;
import libXray.LibXray;

/** Android VpnService bridge for the official libXray core. */
public final class PipidastrVpnService extends VpnService {
    static final String ACTION_START = "com.pipidastr.vpn.START";
    static final String ACTION_STOP = "com.pipidastr.vpn.STOP";
    static final String ACTION_STATUS = "com.pipidastr.vpn.STATUS";
    static final String EXTRA_CONFIG = "config";
    static final String EXTRA_PACKAGES = "packages";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_MESSAGE = "message";
    static volatile String currentState = "disconnected";
    static volatile String currentMessage = "Не подключено";
    private static final String CHANNEL = "pipidastr_vpn";
    private static final String TAG = "PipidastrVPN";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private ConnectivityManager connectivity;
    private ParcelFileDescriptor tun;
    private boolean coreStarted;
    private boolean coreStartAttempted;
    private boolean dnsConfigured;

    @Override public void onCreate() {
        super.onCreate();
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            worker.execute(this::stopCore);
            return START_NOT_STICKY;
        }
        showForeground("Подключение…");
        String config = intent.getStringExtra(EXTRA_CONFIG);
        String[] packages = intent.getStringArrayExtra(EXTRA_PACKAGES);
        if (config == null || packages == null) {
            updateStatus("error", "Выберите сервер.");
            worker.execute(this::stopCore);
            return START_NOT_STICKY;
        }
        worker.execute(() -> startCore(config, packages));
        return START_NOT_STICKY;
    }

    private void startCore(String config, String[] packages) {
        try {
            if (coreStarted) return;
            closing.set(false);
            updateStatus("connecting", "Подключаю сервер…");
            if (!hasUnderlyingNetwork())
                throw new IllegalStateException("Подключите Wi-Fi или мобильный интернет.");

            Builder builder = new Builder().setSession("Pipidastr VPN").setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1");
            if (packages.length > 0) {
                List<String> apps = new ArrayList<>(Arrays.asList(packages));
                if (!apps.contains(getPackageName())) apps.add(getPackageName());
                for (String name : apps) {
                    try { builder.addAllowedApplication(name); }
                    catch (PackageManager.NameNotFoundException ex) { Log.w(TAG, "App removed: " + name); }
                }
            }
            tun = builder.establish();
            if (tun == null) throw new IllegalStateException("Android не создал VPN-интерфейс.");

            DialerController controller = fd -> protect((int) fd);
            LibXray.registerDialerController(controller);
            LibXray.setDNS(controller, "1.1.1.1:53");
            dnsConfigured = true;
            coreStartAttempted = true;
            invoke("runXray", new JSONObject()
                    .put("xrayJson", XrayConfig.withTunFd(config, tun.getFd())));
            coreStarted = true;

            showForeground("Проверка соединения…");
            updateStatus("connecting", "Проверяю доступ к сайтам…");
            if (!verifyConnectivity())
                throw new IllegalStateException("Сервер не передаёт трафик. Выберите другой сервер.");
            showForeground("Подключено");
            updateStatus("connected", "VPN подключён");
        } catch (Exception ex) {
            Log.e(TAG, "VPN start failed", ex);
            updateStatus("error", "Ошибка подключения: " + ex.getMessage());
            stopCore();
        }
    }

    private void stopCore() {
        if (!closing.compareAndSet(false, true)) return;
        if (coreStartAttempted) {
            try { invoke("stopXray", new JSONObject()); }
            catch (Exception ex) { Log.w(TAG, "stop Xray", ex); }
            coreStarted = false;
            coreStartAttempted = false;
        }
        if (dnsConfigured) {
            LibXray.resetDNS();
            dnsConfigured = false;
        }
        ParcelFileDescriptor descriptor = tun;
        tun = null;
        if (descriptor != null) try { descriptor.close(); }
        catch (Exception ex) { Log.w(TAG, "close TUN", ex); }
        if (!"error".equals(currentState)) updateStatus("disconnected", "Не подключено");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private static JSONObject invoke(String method, JSONObject payload) throws Exception {
        JSONObject request = new JSONObject().put("apiVersion", 3)
                .put("method", method).put("payload", payload);
        JSONObject response = new JSONObject(LibXray.invoke(request.toString()));
        if (!response.optBoolean("success"))
            throw new IllegalStateException(response.optString("error", "Ошибка Xray"));
        return response;
    }

    @Override public void onRevoke() {
        worker.execute(this::stopCore);
        super.onRevoke();
    }

    @Override public void onDestroy() {
        if (!closing.get()) worker.execute(this::stopCore);
        worker.shutdown();
        super.onDestroy();
    }

    private void showForeground(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Pipidastr VPN",
                    NotificationManager.IMPORTANCE_LOW));
        PendingIntent pending = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle("Pipidastr VPN")
                .setContentText(message)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        startForeground(7, notification);
    }

    private void updateStatus(String state, String message) {
        currentState = state;
        currentMessage = message;
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state).putExtra(EXTRA_MESSAGE, message));
    }

    private boolean hasUnderlyingNetwork() {
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true;
        }
        return false;
    }

    private boolean verifyConnectivity() {
        Network vpnNetwork = null;
        for (int attempt = 0; attempt < 12 && vpnNetwork == null; attempt++) {
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnNetwork = network;
                    break;
                }
            }
            if (vpnNetwork == null) try { Thread.sleep(250); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
        }
        if (vpnNetwork == null) return false;
        for (int attempt = 0; attempt < 3; attempt++) {
            for (String address : new String[] {"https://example.com", "https://yandex.ru"}) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) vpnNetwork.openConnection(new URL(address));
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(6000);
                    connection.setInstanceFollowRedirects(false);
                    int response = connection.getResponseCode();
                    if (response >= 200 && response < 400) return true;
                    Log.w(TAG, "VPN connectivity check returned HTTP " + response);
                } catch (Exception ex) {
                    Log.w(TAG, "VPN connectivity check failed: " + ex.getClass().getSimpleName());
                } finally { if (connection != null) connection.disconnect(); }
            }
            try { Thread.sleep(750); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
