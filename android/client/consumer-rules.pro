# cardamum client: JNI-facing symbols R8 must not rename or remove.

# Native.listAddressbooks is matched by its fully-qualified name from Rust.
-keep class org.pimalaya.cardamum.client.Native { *; }

# Transport.read / write are called only from native code, so R8 sees
# them as unused without this rule. The member specs must match the Java
# signatures exactly: a stale spec matches nothing, R8 strips the real
# methods, and every release-build network call dies at runtime with a
# JNI "method not found".
-keep class org.pimalaya.cardamum.client.Transport {
    byte[] read(java.lang.String);
    void write(java.lang.String, byte[]);
}

# OfflineDriver.serve is upcalled by the native offline engine on every
# coroutine yield; keeping the interface member keeps each live
# implementor's override with it, and the wildcard rule pins those
# overrides against removal.
-keep interface org.pimalaya.cardamum.client.OfflineDriver { *; }
-keep class * implements org.pimalaya.cardamum.client.OfflineDriver {
    java.lang.String serve(java.lang.String);
}
