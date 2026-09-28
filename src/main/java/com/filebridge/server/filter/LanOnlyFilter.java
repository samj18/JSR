package com.filebridge.server.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LanOnlyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LanOnlyFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String remoteAddr = request.getRemoteAddr();
        try {
            InetAddress remote = InetAddress.getByName(remoteAddr);
            if (!isLanAddress(remote)) {
                log.warn("Rejected non-LAN connection from {}", remoteAddr);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("LAN access only");
                return;
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isLanAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) return true;
        if (addr.isSiteLocalAddress()) return true;
        if (addr instanceof Inet6Address) {
            byte first = addr.getAddress()[0];
            return (first & (byte) 0xfe) == (byte) 0xfc;
        }
        return false;
    }
}
