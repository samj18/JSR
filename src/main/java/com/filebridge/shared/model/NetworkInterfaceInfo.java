package com.filebridge.shared.model;

/**
 * One network interface address - shown in the IP dropdown.
 */
public record NetworkInterfaceInfo(
        String interfaceName,
        String displayName,
        String ipAddress,
        Type type
) {
    public enum Type { WIFI, ETHERNET, OTHER }

    @Override
    public String toString() {
        String icon = switch (type) {
            case WIFI -> "📶";
            case ETHERNET -> "🔌";
            case OTHER -> "📡";
        };
        return icon + "  " + ipAddress + "   (" + displayName + ")";
    }
}
