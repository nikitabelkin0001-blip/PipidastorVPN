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
import java.net.InetSocketAddress;
import java.net.Socket;
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
    static final String EXTRA_CANDIDATES = "reserve_candidates";
    static final String EXTRA_FIND_RESERVE = "find_reserve";
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
            closing.set(true);
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
        String[] candidates = intent.getStringArrayExtra(EXTRA_CANDIDATES);
        boolean findReserve = intent.getBooleanExtra(EXTRA_FIND_RESERVE, false);
        worker.execute(() -> startCore(config, packages, candidates, findReserve));
        return START_NOT_STICKY;
    }

    private void startCore(String config, String[] packages, String[] candidates, boolean findReserve) {
        try {
            if (coreStarted && !findReserve) return;
            releaseCore();
            closing.set(false);
            updateStatus("connecting", "Подключаю сервер…");
            if (!hasUnderlyingNetwork())
                throw new IllegalStateException("Подключите Wi-Fi или мобильный интернет.");

            Builder builder = new Builder().setSession("Pipidastr VPN").setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1");
            Network physicalNetwork = underlyingNetwork();
            if (physicalNetwork != null) {
                builder.setUnderlyingNetworks(new Network[] {physicalNetwork});
                if (Build.VERSION.SDK_INT >= 33) {
                    android.net.LinkProperties properties = connectivity.getLinkProperties(physicalNetwork);
                    if (properties != null) for (android.net.LinkAddress address : properties.getLinkAddresses())
                        if (address.getAddress() instanceof java.net.Inet4Address && address.getPrefixLength() > 0)
                            builder.excludeRoute(new android.net.IpPrefix(address.getAddress(), address.getPrefixLength()));
                }
            }
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
            boolean verified = false;
            String reserveUsed = null;
            if (!findReserve) {
                updateStatus("connecting", "Проверяю прямое подключение…");
                verified = runAndVerify(config);
            }
            if (!verified && !closing.get() && candidates != null) {
                Network physical = underlyingNetwork();
                for (int index = 0; index < candidates.length && !closing.get(); index++) {
                    SubscriptionParser.Server candidate;
                    try { candidate = SubscriptionParser.parse(candidates[index]).servers.get(0); }
                    catch (Exception ignored) { continue; }
                    if (!canReach(physical, candidate)) continue;
                    updateStatus("connecting", "Проверяю резерв " + (index + 1) + "/" + candidates.length + ": " + candidate.name);
                    try { verified = runAndVerify(XrayConfig.throughReserve(config, candidate)); }
                    catch (Exception ex) { Log.w(TAG, "Reserve core failed: " + ex.getClass().getSimpleName()); }
                    if (verified) {
                        reserveUsed = candidate.name;
                        getSharedPreferences("MainActivity", MODE_PRIVATE).edit()
                                .putString("reserve_link", candidate.link).putString("reserve_name", candidate.name).apply();
                        Log.i(TAG, "HTTPS verified through reserve");
                        break;
                    }
                }
            }
            if (closing.get()) { stopCore(); return; }
            if (!verified) throw new IllegalStateException("HTTPS через сервер не отвечает. Прямое подключение и доступные резервы проверены.");
            showForeground("Подключено");
            updateStatus("connected", "VPN подключён" + (reserveUsed == null ? "" : " · резерв: " + reserveUsed));
        } catch (Exception ex) {
            Log.e(TAG, "VPN start failed", ex);
            updateStatus("error", "Ошибка подключения: " + ex.getMessage());
            stopCore();
        }
    }

    private void stopCore() {
        closing.set(true);
        releaseCore();
        if (!"error".equals(currentState)) updateStatus("disconnected", "Не подключено");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseCore() {
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
    }

    private boolean runAndVerify(String config) throws Exception {
        if (coreStartAttempted) {
            invoke("stopXray", new JSONObject());
            coreStartAttempted = false;
            coreStarted = false;
        }
        if (closing.get()) return false;
        coreStartAttempted = true;
        invoke("runXray", new JSONObject().put("xrayJson", XrayConfig.withTunFd(config, tun.getFd())));
        coreStarted = true;
        showForeground("Проверка соединения…");
        boolean result = verifyConnectivity();
        Log.i(TAG, "HTTPS connectivity: " + (result ? "OK" : "failed"));
        return result;
    }

    private Network underlyingNetwork() {
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return network;
        }
        return null;
    }

    private boolean canReach(Network network, SubscriptionParser.Server server) {
        if (network == null || !"VLESS".equalsIgnoreCase(server.protocol)) return false;
        try (Socket socket = network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(network.getByName(server.host), server.port), 1200);
            return true;
        } catch (Exception ignored) { return false; }
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
        Log.i(TAG, state + ": " + message);
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
        for (int attempt = 0; attempt < 1 && !closing.get(); attempt++) {
            for (String address : new String[] {"https://www.cloudflare.com/cdn-cgi/trace", "https://api.ipify.org"}) {
                if (closing.get()) return false;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) vpnNetwork.openConnection(new URL(address));
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setInstanceFollowRedirects(false);
                    int response = connection.getResponseCode();
                    if (response == 200) {
                        byte[] body = new byte[4096];
                        int length = connection.getInputStream().read(body);
                        String text = length < 0 ? "" : new String(body, 0, length, java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (text.contains("ip=") || text.matches("[0-9a-fA-F:.]+")) return true;
                    }
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
