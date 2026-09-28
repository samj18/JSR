package com.filebridge.client.service;

import com.filebridge.shared.model.NetworkInterfaceInfo;
import com.filebridge.shared.model.NetworkInterfaceInfo.Type;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

@Service
public class NetworkUtils {

    public List<NetworkInterfaceInfo> listInterfaces() {
        List<NetworkInterfaceInfo> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;
                Type type = classify(ni);
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress ip = addr.getAddress();
                    if (ip.getAddress().length == 4 && ip.isSiteLocalAddress()) {
                        result.add(new NetworkInterfaceInfo(
                                ni.getName(),
                                ni.getDisplayName() == null ? ni.getName() : ni.getDisplayName(),
                                ip.getHostAddress(),
                                type));
                    }
                }
            }
        } catch (Exception ignored) {}

        result.sort(Comparator.comparing(NetworkInterfaceInfo::type));
        if (result.isEmpty()) {
            result.add(new NetworkInterfaceInfo("lo", "Loopback", "127.0.0.1", Type.OTHER));
        }
        return Collections.unmodifiableList(result);
    }

    public NetworkInterfaceInfo pickDefault(List<NetworkInterfaceInfo> all, String preferredIp) {
        if (preferredIp != null) {
            for (NetworkInterfaceInfo n : all) if (preferredIp.equals(n.ipAddress())) return n;
        }
        for (NetworkInterfaceInfo n : all) if (n.type() == Type.WIFI) return n;
        for (NetworkInterfaceInfo n : all) if (n.type() == Type.ETHERNET) return n;
        return all.get(0);
    }

    private Type classify(NetworkInterface ni) {
        String name = ni.getName().toLowerCase();
        String display = ni.getDisplayName() == null ? "" : ni.getDisplayName().toLowerCase();
        if (display.contains("wi-fi") || display.contains("wifi")
                || display.contains("wireless") || display.contains("airport")
                || name.startsWith("wlan") || name.startsWith("wlp")
                || name.startsWith("wifi") || name.equals("en0")) return Type.WIFI;
        if (display.contains("ethernet") || display.contains("lan")
                || name.startsWith("eth") || name.startsWith("enp") || name.startsWith("eno")
                || name.startsWith("en")) return Type.ETHERNET;
        return Type.OTHER;
    }
}
