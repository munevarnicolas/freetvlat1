package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Whether Tailscale is connected on this TV. Read from Android's own list of networks (no Tailscale API, nothing over
 * the network): a VPN with a tailnet address (100.64.0.0/10), Tailscale's DNS (100.100.100.100) or a *.ts.net domain
 * is Tailscale. A default route through it means an exit node is in use (which one, Tailscale doesn't say). While it's
 * on screen it listens for VPN changes, so it follows Tailscale being turned on and off.
 */
final class TailscaleWidget extends Widget {
    static final String PKG = "com.tailscale.ipn";

    private volatile ConnectivityManager.NetworkCallback callback;
    private ConnectivityManager cm;

    @Override String id() { return "tailscale"; }

    @Override String name() { return "Tailscale"; }

    @Override String description() {
        return Ui.tr("Se o Tailscale está conectado, o IP da TV no tailnet e o nó de saída, lidos da lista de redes do Android. Sem acesso à rede.",
                "Whether Tailscale is connected, this TV's tailnet IP and the exit node, read from Android's network list. No network access.");
    }

    /** Without a callback (some TVs limit them), a look every minute while it's on screen keeps it right. */
    @Override long refreshEvery() { return callback == null ? 60_000 : 0; }

    @Override synchronized void watch(Context c, Runnable changed) {
        if (callback != null) return;
        cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        NetworkRequest vpns = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { changed.run(); }
            @Override public void onLost(Network n) { changed.run(); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { changed.run(); }
        };
        try {
            cm.registerNetworkCallback(vpns, cb);
            callback = cb;
        } catch (RuntimeException e) {   // some TVs limit network callbacks per app
            AppLog.w("Tailscale widget won't follow changes: " + e.getMessage());
        }
    }

    @Override synchronized void unwatch() {
        if (callback == null) return;
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
        }
        callback = null;
    }

    @Override Model load(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        String ip = "";
        boolean on = false, exit = false;
        try {
            for (Network n : cm == null ? new Network[0] : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                if (nc == null || lp == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                String addr = "";
                boolean tailnet = false;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (tailnet(la.getAddress())) {
                        tailnet = true;
                        if (la.getAddress() instanceof Inet4Address) addr = la.getAddress().getHostAddress();
                    }
                }
                for (InetAddress dns : lp.getDnsServers()) if ("100.100.100.100".equals(dns.getHostAddress())) tailnet = true;
                String domains = lp.getDomains();
                if (domains != null && domains.contains(".ts.net")) tailnet = true;
                if (!tailnet) continue;
                for (RouteInfo r : lp.getRoutes()) if (r.isDefaultRoute()) exit = true;
                on = true;
                ip = addr;
                break;
            }
        } catch (RuntimeException e) {
            AppLog.w("Tailscale state: " + e.getMessage());
        }
        boolean installed = on || HomeApps.launchIntent(c.getPackageManager(), PKG) != null;

        // "Tailscale · 100.68.180.6 · exit node", "Tailscale · off", "Tailscale · not installed".
        String line = "Tailscale · " + (!on ? (installed ? Ui.tr("desligado", "off") : Ui.tr("não instalado", "not installed"))
                : (ip.isEmpty() ? Ui.tr("ligado", "on") : ip) + (exit ? " · " + Ui.tr("nó de saída", "exit node") : ""));
        String status = on ? Ui.tr("Conectado", "Connected")
                : installed ? Ui.tr("Desconectado", "Disconnected") : Ui.tr("Não instalado", "Not installed");
        return new Model(line, on ? GOOD : IDLE, name(), "",
                new Row(Ui.tr("Estado", "Status"), status, on ? GOOD : IDLE),
                new Row(Ui.tr("IP no Tailscale", "Tailscale IP"), on && !ip.isEmpty() ? ip : "—", PLAIN),
                new Row(Ui.tr("Nó de saída", "Exit node"), !on ? "—" : exit ? Ui.tr("Em uso", "In use") : Ui.tr("Não", "No"), PLAIN));
    }

    /** 100.64.0.0/10, the shared address space Tailscale gives each device. */
    private static boolean tailnet(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        byte[] b = a.getAddress();
        return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }
}
