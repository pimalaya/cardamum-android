# cardamum client: JNI-facing symbols R8 must not rename or remove.

# Native.listAddressbooks is matched by its fully-qualified name from Rust.
-keep class org.pimalaya.cardamum.client.Native { *; }

# Transport.connect / read / write are called only from native code,
# so R8 sees them as unused without this rule.
-keep class org.pimalaya.cardamum.client.Transport {
    boolean connect(java.lang.String, int);
    byte[] read();
    void write(byte[]);
}
