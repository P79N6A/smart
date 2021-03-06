package com.vm.shadowsocks.core;

import android.util.SparseArray;

import com.vm.shadowsocks.dns.DnsPacket;
import com.vm.shadowsocks.dns.Question;
import com.vm.shadowsocks.dns.Resource;
import com.vm.shadowsocks.dns.ResourcePointer;
import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tcpip.IPHeader;
import com.vm.shadowsocks.tcpip.UDPHeader;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;


public class DnsProxy implements Runnable {

    private class QueryState {
        public short ClientQueryID;
        public long QueryNanoTime;
        public int ClientIP;
        public short ClientPort;
        public int RemoteIP;
        public short RemotePort;
    }

    public boolean Stopped;
    private static final ConcurrentHashMap<Integer, String> IPDomainMaps = new ConcurrentHashMap<Integer, String>();
    private static final ConcurrentHashMap<String, Integer> DomainIPMaps = new ConcurrentHashMap<String, Integer>();
    private final long QUERY_TIMEOUT_NS = 10 * 1000000000L;
    private DatagramSocket m_Client;
    private Thread m_ReceivedThread;
    private short m_QueryID;
    private SparseArray<QueryState> m_QueryArray;

    public DnsProxy() throws IOException {
        m_QueryArray = new SparseArray<QueryState>();
        m_Client = new DatagramSocket(0);
    }

    public static String reverseLookup(int ip) {
        return IPDomainMaps.get(ip);
    }

    public void start() {
        m_ReceivedThread = new Thread(this);
        m_ReceivedThread.setName("DnsProxyThread");
        m_ReceivedThread.start();
    }

    public void stop() {
        Stopped = true;
        if (m_Client != null) {
            m_Client.close();
            m_Client = null;
        }
    }

    @Override
    public void run() {
        try {
            byte[] RECEIVE_BUFFER = new byte[2000];
            IPHeader ipHeader = new IPHeader(RECEIVE_BUFFER, 0);
            ipHeader.Default();
            UDPHeader udpHeader = new UDPHeader(RECEIVE_BUFFER, 20);

            ByteBuffer dnsBuffer = ByteBuffer.wrap(RECEIVE_BUFFER);
            dnsBuffer.position(28);
            dnsBuffer = dnsBuffer.slice();

            DatagramPacket packet = new DatagramPacket(RECEIVE_BUFFER, 28, RECEIVE_BUFFER.length - 28);

            while (m_Client != null && !m_Client.isClosed()) {
                packet.setLength(RECEIVE_BUFFER.length - 28);
                m_Client.receive(packet);

                dnsBuffer.clear();
                dnsBuffer.limit(packet.getLength());
                try {
                    DnsPacket dnsPacket = DnsPacket.FromBytes(dnsBuffer);
                    if (dnsPacket != null) {
                        onDnsResponseReceived(ipHeader, udpHeader, dnsPacket);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    LocalVpnService.Instance.writeLog("Parse dns error: %s", e);
                }
            }
        } catch (Exception e) {
            LocalVpnService.Instance.writeLog(e.getLocalizedMessage());
            e.printStackTrace();
        } finally {
            LocalVpnService.Instance.writeLog("DnsResolver Thread Exited.");
            this.stop();
        }
    }

    private int getFirstIP(DnsPacket dnsPacket) {
        for (int i = 0; i < dnsPacket.Header.ResourceCount; i++) {
            Resource resource = dnsPacket.Resources[i];
            if (resource.Type == 1) {
                return CommonMethods.readInt(resource.Data, 0);
            }
        }
        return 0;
    }

    private void tamperDnsResponse(byte[] rawPacket, DnsPacket dnsPacket, int newIP) {
        Question question = dnsPacket.Questions[0];

        dnsPacket.Header.setResourceCount((short) 1);
        dnsPacket.Header.setAResourceCount((short) 0);
        dnsPacket.Header.setEResourceCount((short) 0);

        ResourcePointer rPointer = new ResourcePointer(rawPacket, question.Offset() + question.Length());
        rPointer.setDomain((short) 0xC00C);
        rPointer.setType(question.Type);
        rPointer.setClass(question.Class);
        rPointer.setTTL(ProxyConfig.Instance.getDnsTTL());
        rPointer.setDataLength((short) 4);
        rPointer.setIP(newIP);

        dnsPacket.Size = 12 + question.Length() + 16;
    }

    private int getOrCreateFakeIP(String domainString) {
        Integer fakeIP = DomainIPMaps.get(domainString);
        if (fakeIP == null) {
            int hashIP = domainString.hashCode();
            do {
                fakeIP = ProxyConfig.FAKE_NETWORK_IP | (hashIP & 0x0000FFFF);
                hashIP++;
            } while (IPDomainMaps.containsKey(fakeIP));

            DomainIPMaps.put(domainString, fakeIP);
            IPDomainMaps.put(fakeIP, domainString);
        }
        return fakeIP;
    }

    private boolean dnsPollution(byte[] rawPacket, DnsPacket dnsPacket) {
        if (dnsPacket.Header.QuestionCount > 0) {
            Question question = dnsPacket.Questions[0];
            if (question.Type == 1) {
                int realIP = getFirstIP(dnsPacket);

                // 此时needProxy域名和对应的ip解析都存在
                if (ProxyConfig.Instance.needProxy(question.Domain, realIP).equals("proxy")) {
                    int fakeIP = getOrCreateFakeIP(question.Domain);
                    tamperDnsResponse(rawPacket, dnsPacket, fakeIP);
                    if (ProxyConfig.IS_DEBUG)
                        LocalVpnService.Instance.writeLog("FakeDns: %s=>%s(%s)\n", question.Domain, CommonMethods.ipIntToString(realIP), CommonMethods.ipIntToString(fakeIP));
                    return true;
                }
            }
        }
        return false;
    }

    private void onDnsResponseReceived(IPHeader ipHeader, UDPHeader udpHeader, DnsPacket dnsPacket) {
        QueryState state = null;
        synchronized (m_QueryArray) {
            state = m_QueryArray.get(dnsPacket.Header.ID);
            if (state != null) {
                m_QueryArray.remove(dnsPacket.Header.ID);
            }
        }

        if (state != null) {
            // DNS污染，默认污染海外网站
            if (ProxyConfig.IS_DEBUG)
                LocalVpnService.Instance.writeLog("onDnsResponseReceived: " + dnsPacket.Questions[0].Domain + " " + CommonMethods.ipIntToString(state.RemoteIP) + ":" + state.RemotePort + "<->" + CommonMethods.ipIntToString(state.ClientIP) + ":" + state.ClientPort);
            dnsPollution(udpHeader.m_Data, dnsPacket);

            dnsPacket.Header.setID(state.ClientQueryID);
            ipHeader.setSourceIP(state.RemoteIP);
            ipHeader.setDestinationIP(state.ClientIP);
            ipHeader.setProtocol(IPHeader.UDP);
            ipHeader.setTotalLength(20 + 8 + dnsPacket.Size);
            udpHeader.setSourcePort(state.RemotePort);
            udpHeader.setDestinationPort(state.ClientPort);
            udpHeader.setTotalLength(8 + dnsPacket.Size);

            LocalVpnService.Instance.sendUDPPacket(ipHeader, udpHeader);
        }
    }

    private int getIPFromCache(String domain) {
        Integer ip = DomainIPMaps.get(domain);
        if (ip == null) {
            return 0;
        } else {
            return ip;
        }
    }

    private boolean interceptDns(IPHeader ipHeader, UDPHeader udpHeader, DnsPacket dnsPacket) {
        Question question = dnsPacket.Questions[0];
        // Requests the A record for the domain name
        if (question.Type == 1) {
            // 此时needProxy只有域名存在，如果cache里面有，那么都存在
            String action = ProxyConfig.Instance.needProxy(question.Domain, getIPFromCache(question.Domain));
            if (action.equals("proxy")) {
                int fakeIP = getOrCreateFakeIP(question.Domain);
                tamperDnsResponse(ipHeader.m_Data, dnsPacket, fakeIP);

                if (ProxyConfig.IS_DEBUG)
                    LocalVpnService.Instance.writeLog("interceptDns FakeDns: %s=>%s\n", question.Domain, CommonMethods.ipIntToString(fakeIP));

                // 返回fake ip
                int sourceIP = ipHeader.getSourceIP();
                short sourcePort = udpHeader.getSourcePort();
                ipHeader.setSourceIP(ipHeader.getDestinationIP());
                ipHeader.setDestinationIP(sourceIP);
                ipHeader.setTotalLength(20 + 8 + dnsPacket.Size);
                udpHeader.setSourcePort(udpHeader.getDestinationPort());
                udpHeader.setDestinationPort(sourcePort);
                udpHeader.setTotalLength(8 + dnsPacket.Size);

                // write to tun
                LocalVpnService.Instance.sendUDPPacket(ipHeader, udpHeader);
                return true;
            }
        }
        return false;
    }

    private void clearExpiredQueries() {
        long now = System.nanoTime();
        for (int i = m_QueryArray.size() - 1; i >= 0; i--) {
            QueryState state = m_QueryArray.valueAt(i);
            if ((now - state.QueryNanoTime) > QUERY_TIMEOUT_NS) {
                m_QueryArray.removeAt(i);
            }
        }
    }

    public void onDnsRequestReceived(IPHeader ipHeader, UDPHeader udpHeader, DnsPacket dnsPacket) {
        // 不拦截，转发dns数据包
        if (ProxyConfig.IS_DEBUG)
            LocalVpnService.Instance.writeLog("onDnsRequestReceived: " + dnsPacket.Questions[0].Domain + " " + CommonMethods.ipIntToString(ipHeader.getSourceIP()) + ":" + udpHeader.getSourcePort() + "<->" + CommonMethods.ipIntToString(ipHeader.getDestinationIP()) + ":" + udpHeader.getDestinationPort());
        if (!interceptDns(ipHeader, udpHeader, dnsPacket)) {
            // 转发DNS
            QueryState state = new QueryState();
            state.ClientQueryID = dnsPacket.Header.ID;
            state.QueryNanoTime = System.nanoTime();
            state.ClientIP = ipHeader.getSourceIP();
            state.ClientPort = udpHeader.getSourcePort();
            state.RemoteIP = ipHeader.getDestinationIP();
            state.RemotePort = udpHeader.getDestinationPort();

            // 转换QueryID
            m_QueryID++; // 增加ID
            dnsPacket.Header.setID(m_QueryID);

            synchronized (m_QueryArray) {
                clearExpiredQueries(); // 清空过期的查询，减少内存开销。
                m_QueryArray.put(m_QueryID, state); // 关联数据
            }

            InetSocketAddress remoteAddress = new InetSocketAddress(CommonMethods.ipIntToInet4Address(state.RemoteIP), state.RemotePort);
            DatagramPacket packet = new DatagramPacket(udpHeader.m_Data, udpHeader.m_Offset + 8, dnsPacket.Size);
            packet.setSocketAddress(remoteAddress);

            try {
                /**
                 * Protect a socket from VPN connections. After protecting, data sent
                 * through this socket will go directly to the underlying network,
                 * so its traffic will not be forwarded through the VPN.
                 * This method is useful if some connections need to be kept
                 * outside of VPN. For example, a VPN tunnel should protect itself if its
                 * destination is covered by VPN routes. Otherwise its outgoing packets
                 * will be sent back to the VPN interface and cause an infinite loop. This
                 * method will fail if the application is not prepared or is revoked.
                 *
                 * <p class="note">The socket is NOT closed by this method.
                 *
                 * @return {@code true} on success.
                 */
                if (LocalVpnService.Instance.protect(m_Client)) {
                    m_Client.send(packet);
                } else {
                    LocalVpnService.Instance.writeLog("VPN protect udp socket failed.");
                }
            } catch (IOException e) {
                LocalVpnService.Instance.writeLog(e.getLocalizedMessage());
                e.printStackTrace();
            }
        }
    }
}
