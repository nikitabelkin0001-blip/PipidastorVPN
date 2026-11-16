package com.pipidastr.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One-time, encrypted transfer of an HTTPS subscription over the local Wi-Fi network. */
final class SubscriptionPairing {
    private static final int MAX_FRAME = 8192;
    private static final int SESSION_SECONDS = 120;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SubscriptionPairing() { }

    static final class Session implements AutoCloseable {
        private final ServerSocket listener;
        private final byte[] key;
        private final String address;

        Session(Context context) throws Exception {
            Network wifi = wifi(context);
            Inet4Address ip = address(context, wifi);
            listener = new ServerSocket(0, 1, ip);
            listener.setSoTimeout(SESSION_SECONDS * 1000);
            key = new byte[32];
            RANDOM.nextBytes(key);
            address = "pipidastr://pair?host=" + ip.getHostAddress() + "&port=" + listener.getLocalPort()
                    + "&key=" + Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        }

        String qrContent() { return address; }

        void waitForSubscription(Consumer<String> received, Consumer<Exception> failed) {
            Thread thread = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(10_000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    int size = input.readInt();
                    if (size < 29 || size > MAX_FRAME) throw new IllegalArgumentException("Неверные данные сопряжения.");
                    byte[] frame = new byte[size];
                    input.readFully(frame);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                            new GCMParameterSpec(128, Arrays.copyOfRange(frame, 0, 12)));
                    String url = new String(cipher.doFinal(frame, 12, size - 12),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!validSubscription(url)) throw new IllegalArgumentException("Нужна HTTPS-ссылка на подписку.");
                    new DataOutputStream(socket.getOutputStream()).writeByte(1);
                    received.accept(url);
                } catch (Exception ex) { failed.accept(ex); }
                finally { close(); }
            }, "pipidastr-pair-receiver");
            thread.setDaemon(true);
            thread.start();
        }

        @Override public void close() {
            try { listener.close(); } catch (Exception ignored) { }
            Arrays.fill(key, (byte) 0);
        }
    }

    static boolean isPairingQr(String text) {
        Uri uri = Uri.parse(text);
        return "pipidastr".equals(uri.getScheme()) && "pair".equals(uri.getHost());
    }

    static void send(Context context, String qrContent, String subscriptionUrl) throws Exception {
        if (!validSubscription(subscriptionUrl))
            throw new IllegalArgumentException("Сначала добавьте HTTPS-подписку на телефоне.");
        Uri uri = Uri.parse(qrContent);
        if (!isPairingQr(qrContent)) throw new IllegalArgumentException("Это не QR-код сопряжения Pipidastr.");
        String ipText = uri.getQueryParameter("host");
        String portText = uri.getQueryParameter("port");
        String keyText = uri.getQueryParameter("key");
        if (ipText == null || portText == null || keyText == null)
            throw new IllegalArgumentException("Неполный QR-код сопряжения.");
        if (!ipText.matches("(\\d{1,3}\\.){3}\\d{1,3}"))
            throw new IllegalArgumentException("Неверный адрес телевизора.");
        InetAddress destination = InetAddress.getByName(ipText);
        if (!(destination instanceof Inet4Address) || !destination.isSiteLocalAddress())
            throw new IllegalArgumentException("Телевизор должен быть в домашней сети.");
        int port = Integer.parseInt(portText);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Неверный порт сопряжения.");
        byte[] key = Base64.decode(keyText, Base64.URL_SAFE | Base64.NO_WRAP);
        if (key.length != 32) throw new IllegalArgumentException("Неверный ключ сопряжения.");
        Network wifi = wifi(context);
        Inet4Address localAddress = address(context, wifi);
        if (!sameSubnet(context, wifi, localAddress, (Inet4Address) destination))
            throw new IllegalArgumentException("Телефон и телевизор должны быть в одной Wi-Fi сети.");
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(subscriptionUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (iv.length + encrypted.length > MAX_FRAME)
            throw new IllegalArgumentException("Ссылка подписки слишком длинная.");
        try (Socket socket = new Socket()) {
            wifi.bindSocket(socket);
            socket.connect(new InetSocketAddress(destination, port), 5_000);
            socket.setSoTimeout(10_000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(iv.length + encrypted.length);
            output.write(iv);
            output.write(encrypted);
            output.flush();
            if (new DataInputStream(socket.getInputStream()).readUnsignedByte() != 1)
                throw new IllegalStateException("Телевизор не принял подписку.");
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private static boolean validSubscription(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null &&
                    uri.getUserInfo() == null && value.length() <= 4096;
        } catch (Exception ex) { return false; }
    }

    private static Network wifi(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return network;
        }
        throw new IllegalStateException("Подключите оба устройства к одной Wi-Fi сети.");
    }

    private static Inet4Address address(Context context, Network network) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties = manager.getLinkProperties(network);
        if (properties != null) for (LinkAddress link : properties.getLinkAddresses()) {
            if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress())
                return (Inet4Address) link.getAddress();
        }
        throw new IllegalStateException("У Wi-Fi нет IPv4-адреса.");
    }

    private static boolean sameSubnet(Context context, Network network, Inet4Address local, Inet4Address remote) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties = manager.getLinkProperties(network);
        if (properties == null) return false;
        byte[] a = local.getAddress();
        byte[] b = remote.getAddress();
        for (LinkAddress link : properties.getLinkAddresses()) {
            if (!local.equals(link.getAddress())) continue;
            int bits = link.getPrefixLength();
            for (int i = 0; i < 4; i++) {
                int mask = bits >= 8 ? 255 : bits <= 0 ? 0 : 255 << (8 - bits) & 255;
                if ((a[i] & mask) != (b[i] & mask)) return false;
                bits -= 8;
            }
            return true;
        }
        return false;
    }
}
