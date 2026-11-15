package com.pipidastr.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.OsConstants;
import android.util.Log;

import java.net.InterfaceAddress;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.SystemProxyStatus;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/** Android VpnService bridge for the official sing-box libbox core. */
public final class PipidastrVpnService extends VpnService implements PlatformInterface, CommandServerHandler {
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
    private volatile CommandServer core;
    private volatile ParcelFileDescriptor tun;
    private volatile InterfaceUpdateListener interfaceListener;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String tunName;

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
            if (core != null) return;
            closing.set(false);
            updateStatus("connecting", "Подключаю сервер…");
            if (!hasUnderlyingNetwork())
                throw new IllegalStateException("Подключите Wi-Fi или мобильный интернет.");
            SetupOptions setup = new SetupOptions();
            setup.setBasePath(getFilesDir().getAbsolutePath());
            setup.setWorkingPath(getFilesDir().getAbsolutePath());
            setup.setTempPath(getCacheDir().getAbsolutePath());
            setup.setCrashReportSource("android");
            setup.setAppVersion("0.2.0");
            setup.setAppMarketingVersion("0.2.0");
            setup.setLogMaxLines(200);
            Libbox.setup(setup);
            Libbox.checkConfig(config);
            CommandServer server = Libbox.newCommandServer(this, this);
            core = server;
            server.start();
            OverrideOptions overrides = new OverrideOptions();
            if (packages.length > 0) {
                List<String> apps = new ArrayList<>(Arrays.asList(packages));
                if (!apps.contains(getPackageName())) apps.add(getPackageName());
                overrides.setIncludePackage(new Strings(apps));
            }
            server.startOrReloadService(config, overrides);
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
        CommandServer server = core;
        core = null;
        if (server != null) {
            try { server.closeService(); } catch (Exception ex) { Log.w(TAG, "close service", ex); }
            server.close();
        }
        ParcelFileDescriptor descriptor = tun;
        tun = null;
        if (descriptor != null) try { descriptor.close(); } catch (Exception ex) { Log.w(TAG, "close tun", ex); }
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
            networkCallback = null;
        }
        if (!"error".equals(currentState)) updateStatus("disconnected", "Не подключено");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
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
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Pipidastr VPN", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
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
        Intent event = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state).putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(event);
    }

    private static final class Strings implements StringIterator {
        private final List<String> values;
        private int index;
        Strings(List<String> values) { this.values = values; }
        @Override public boolean hasNext() { return index < values.size(); }
        @Override public String next() { return values.get(index++); }
        @Override public int len() { return values.size(); }
    }

    private static int addRoutes(Builder builder, RoutePrefixIterator routes) {
        int count = 0;
        while (routes != null && routes.hasNext()) {
            RoutePrefix route = routes.next();
            builder.addRoute(route.address(), route.prefix());
            count++;
        }
        return count;
    }

    @Override public int openTun(TunOptions options) throws Exception {
        if (VpnService.prepare(this) != null) throw new IllegalStateException("Разрешение VPN не предоставлено.");
        Builder builder = new Builder().setSession("Pipidastr VPN").setMtu(options.getMTU());
        RoutePrefixIterator addresses = options.getInet4Address();
        while (addresses.hasNext()) {
            RoutePrefix address = addresses.next();
            builder.addAddress(address.address(), address.prefix());
        }
        addresses = options.getInet6Address();
        while (addresses.hasNext()) {
            RoutePrefix address = addresses.next();
            builder.addAddress(address.address(), address.prefix());
        }
        if (options.getAutoRoute()) {
            if (addRoutes(builder, options.getInet4RouteRange()) == 0)
                builder.addRoute("0.0.0.0", 0);
            addRoutes(builder, options.getInet6RouteRange());
            if (options.getDNSMode() != null && !Libbox.DNSModeDisabled.equals(options.getDNSMode().getValue())) {
                StringIterator dns = options.getDNSServerAddress();
                while (dns.hasNext()) builder.addDnsServer(dns.next());
            }
            StringIterator included = options.getIncludePackage();
            while (included.hasNext()) {
                String name = included.next();
                try { builder.addAllowedApplication(name); }
                catch (PackageManager.NameNotFoundException ex) { Log.w(TAG, "App removed: " + name); }
            }
        }
        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) throw new IllegalStateException("Android не создал VPN-интерфейс.");
        tun = descriptor;
        return descriptor.getFd();
    }

    @Override public boolean usePlatformAutoDetectInterfaceControl() { return true; }
    @Override public void autoDetectInterfaceControl(int fd) { protect(fd); }

    @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        interfaceListener = listener;
        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { notifyDefaultNetwork(); }
                @Override public void onLost(Network network) { notifyDefaultNetwork(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { notifyDefaultNetwork(); }
            };
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            connectivity.registerNetworkCallback(request, networkCallback);
        }
        notifyDefaultNetwork();
    }

    private void notifyDefaultNetwork() {
        InterfaceUpdateListener listener = interfaceListener;
        if (listener == null) return;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null || properties.getInterfaceName() == null ||
                    properties.getInterfaceName().equals(tunName)) continue;
            try {
                java.net.NetworkInterface net = java.net.NetworkInterface.getByName(properties.getInterfaceName());
                if (net != null) {
                    listener.updateDefaultInterface(net.getName(), net.getIndex(),
                            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), false);
                    return;
                }
            } catch (Exception ex) { Log.w(TAG, "network monitor", ex); }
        }
        listener.updateDefaultInterface("", -1, false, false);
    }

    private boolean hasUnderlyingNetwork() {
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                return true;
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
            if (vpnNetwork == null) {
                try { Thread.sleep(250); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
            }
        }
        if (vpnNetwork == null) return false;
        for (String address : new String[] {"https://example.com", "https://yandex.ru"}) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) vpnNetwork.openConnection(new URL(address));
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setInstanceFollowRedirects(false);
                int response = connection.getResponseCode();
                if (response >= 200 && response < 400) return true;
            } catch (Exception ex) { Log.w(TAG, "VPN connectivity check failed: " + ex.getClass().getSimpleName()); }
            finally { if (connection != null) connection.disconnect(); }
        }
        return false;
    }

    @Override public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        interfaceListener = null;
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
            networkCallback = null;
        }
    }

    @Override public NetworkInterfaceIterator getInterfaces() throws Exception {
        List<io.nekohasekai.libbox.NetworkInterface> values = new ArrayList<>();
        Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            java.net.NetworkInterface net = interfaces.nextElement();
            io.nekohasekai.libbox.NetworkInterface item = new io.nekohasekai.libbox.NetworkInterface();
            item.setName(net.getName());
            item.setIndex(net.getIndex());
            item.setMTU(net.getMTU());
            int flags = 0;
            if (net.isUp()) flags |= OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
            if (net.isLoopback()) flags |= OsConstants.IFF_LOOPBACK;
            if (net.isPointToPoint()) flags |= OsConstants.IFF_POINTOPOINT;
            if (net.supportsMulticast()) flags |= OsConstants.IFF_MULTICAST;
            item.setFlags(flags);
            List<String> addresses = new ArrayList<>();
            for (InterfaceAddress address : net.getInterfaceAddresses()) {
                String ip = address.getAddress().getHostAddress().split("%", 2)[0];
                addresses.add(ip + "/" + address.getNetworkPrefixLength());
            }
            item.setAddresses(new Strings(addresses));
            item.setDNSServer(new Strings(Collections.emptyList()));
            item.setGateway(new Strings(Collections.emptyList()));
            item.setType(Libbox.InterfaceTypeOther);
            values.add(item);
        }
        return new NetworkInterfaceIterator() {
            int index;
            @Override public boolean hasNext() { return index < values.size(); }
            @Override public io.nekohasekai.libbox.NetworkInterface next() { return values.get(index++); }
        };
    }

    @Override public void registerMyInterface(String name) { tunName = name; }
    @Override public boolean underNetworkExtension() { return false; }
    @Override public boolean includeAllNetworks() { return false; }
    @Override public LocalDNSTransport localDNSTransport() { return null; }
    @Override public ConnectionOwner findConnectionOwner(int protocol, String source, int sourcePort,
                                                        String destination, int destinationPort) {
        ConnectionOwner owner = new ConnectionOwner();
        try {
            if (Build.VERSION.SDK_INT < 29)
                return Libbox.findConnectionOwner(protocol, source, sourcePort, destination, destinationPort);
            int uid = connectivity.getConnectionOwnerUid(protocol,
                    new InetSocketAddress(source, sourcePort),
                    new InetSocketAddress(destination, destinationPort));
            if (uid != Process.INVALID_UID) {
                owner.setUserId(uid);
                String[] packages = getPackageManager().getPackagesForUid(uid);
                if (packages != null && packages.length > 0) {
                    owner.setUserName(packages[0]);
                    owner.setAndroidPackageNames(new Strings(Arrays.asList(packages)));
                }
            }
        } catch (Exception ex) { Log.w(TAG, "connection owner lookup", ex); }
        return owner;
    }
    @Override public WIFIState readWIFIState() { return null; }
    @Override public void clearDNSCache() { }
    @Override public void sendNotification(io.nekohasekai.libbox.Notification notification) { }
    @Override public void cancelNotification(String identifier, int type) { }
    @Override public void startNeighborMonitor(NeighborUpdateListener listener) { }
    @Override public void closeNeighborMonitor(NeighborUpdateListener listener) { }
    @Override public boolean usePlatformShell() { return false; }
    @Override public void checkPlatformShell() { throw new UnsupportedOperationException(); }
    @Override public ShellSession openShellSession(PlatformUser user, String command, StringIterator environ,
                                                   String term, int rows, int cols) { throw new UnsupportedOperationException(); }
    @Override public PlatformUser lookupUser(String username) { return null; }
    @Override public String lookupSFTPServer() { return ""; }
    @Override public String readSystemSSHHostKey() { return ""; }
    @Override public String tailscaleHostname() { return ""; }
    @Override public boolean usePlatformBridge() { return false; }
    @Override public BridgeSession createBridge(BridgeOptions options) { throw new UnsupportedOperationException(); }

    @Override public void serviceStop() { worker.execute(this::stopCore); }
    @Override public void serviceReload() { }
    @Override public SystemProxyStatus getSystemProxyStatus() {
        SystemProxyStatus status = new SystemProxyStatus();
        status.setAvailable(false);
        status.setEnabled(false);
        return status;
    }
    @Override public void setSystemProxyEnabled(boolean enabled) { }
    @Override public void triggerNativeCrash() { }
    @Override public void writeDebugMessage(String message) { Log.d(TAG, message); }
    @Override public int connectSSHAgent() { return -1; }
}
