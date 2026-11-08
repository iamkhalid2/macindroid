package xyz.iamkhalid.macindroid;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Finds Macs running Macindroid on the local network (Bonjour / DNS-SD). */
final class Discovery {
    static final class Mac {
        final String name;
        final InetSocketAddress address;

        Mac(String name, InetSocketAddress address) {
            this.name = name;
            this.address = address;
        }
    }

    interface Callback {
        void onFound(Mac mac); // called on a binder thread
    }

    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicast;
    private final List<NsdManager.ServiceInfoCallback> resolvers = new ArrayList<>();
    private NsdManager.DiscoveryListener listener;

    Discovery(Context context) {
        nsd = context.getSystemService(NsdManager.class);
        multicast = context.getSystemService(WifiManager.class).createMulticastLock("macindroid");
        multicast.setReferenceCounted(false);
    }

    void start(Callback callback) {
        multicast.acquire();
        listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int error) {}
            @Override public void onStopDiscoveryFailed(String type, int error) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                NsdManager.ServiceInfoCallback resolver = new NsdManager.ServiceInfoCallback() {
                    @Override public void onServiceInfoCallbackRegistrationFailed(int error) {}
                    @Override public void onServiceLost() {}
                    @Override public void onServiceInfoCallbackUnregistered() {}

                    @Override
                    public void onServiceUpdated(NsdServiceInfo resolved) {
                        InetAddress pick = null;
                        for (InetAddress a : resolved.getHostAddresses()) {
                            if (a instanceof Inet4Address) { pick = a; break; }
                            if (pick == null) pick = a;
                        }
                        if (pick != null) callback.onFound(new Mac(resolved.getServiceName(), new InetSocketAddress(pick, resolved.getPort())));
                    }
                };
                synchronized (resolvers) {
                    resolvers.add(resolver);
                }
                try {
                    nsd.registerServiceInfoCallback(info, Runnable::run, resolver);
                } catch (RuntimeException ignored) {
                }
            }
        };
        try {
            nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException ignored) {
        }
    }

    void stop() {
        synchronized (resolvers) {
            for (NsdManager.ServiceInfoCallback r : resolvers) {
                try {
                    nsd.unregisterServiceInfoCallback(r);
                } catch (RuntimeException ignored) {
                }
            }
            resolvers.clear();
        }
        if (listener != null) {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
            listener = null;
        }
        multicast.release();
    }

    /** Blocks up to timeoutMs for a Mac, preferring the one called `preferred`. */
    static InetSocketAddress findOne(Context context, String preferred, long timeoutMs) {
        Discovery d = new Discovery(context);
        AtomicReference<InetSocketAddress> any = new AtomicReference<>();
        AtomicReference<InetSocketAddress> named = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        d.start(mac -> {
            any.compareAndSet(null, mac.address);
            if (mac.name.equals(preferred)) {
                named.compareAndSet(null, mac.address);
                done.countDown();
            }
        });
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        d.stop();
        return named.get() != null ? named.get() : any.get();
    }
}
