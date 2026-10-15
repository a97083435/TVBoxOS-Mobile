package com.kukuqi.tvbox.osc.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.util.List;

/** Retains the host's binary DNS endpoint using the core resolver and its DoH settings. */
public final class CoreDnsBridge {
    private CoreDnsBridge() { }
    public static byte[] message(String host) throws Exception {
        String name = IDN.toASCII(host);
        if (name.endsWith(".")) name = name.substring(0, name.length()-1);
        if (name.isEmpty() || name.length() > 253) throw new IllegalArgumentException("Invalid DNS name");
        List<InetAddress> addresses = com.github.catvod.net.OkHttp.dns().lookup(name);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0); out.writeShort(0x8180); out.writeShort(1);
        out.writeShort(addresses.size()); out.writeShort(0); out.writeShort(0);
        for (String label : name.split("\\.")) {
            byte[] value = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (value.length == 0 || value.length > 63) throw new IllegalArgumentException("Invalid DNS label");
            out.writeByte(value.length); out.write(value);
        }
        out.writeByte(0); out.writeShort(1); out.writeShort(1);
        for (InetAddress address : addresses) {
            byte[] value = address.getAddress();
            out.writeShort(0xc00c); out.writeShort(value.length == 16 ? 28 : 1);
            out.writeShort(1); out.writeInt(60); out.writeShort(value.length); out.write(value);
        }
        return bytes.toByteArray();
    }
}
