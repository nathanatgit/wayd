# Runs inside ONE mount namespace (global or a zygote): shows the system trust store plus WAYD's CA there.
# $TMP holds the original certificates and WAYD's CA. Nothing persistent is changed.
umount /apex/com.android.conscrypt/cacerts 2>/dev/null
umount /system/etc/security/cacerts 2>/dev/null
mount -t tmpfs tmpfs /system/etc/security/cacerts || exit 1
cp "$TMP"/* /system/etc/security/cacerts/ || exit 1
chown root:root /system/etc/security/cacerts/*
chmod 644 /system/etc/security/cacerts/*
chcon u:object_r:system_file:s0 /system/etc/security/cacerts /system/etc/security/cacerts/* 2>/dev/null
if [ -d /apex/com.android.conscrypt/cacerts ]; then
  mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts || exit 1
fi
exit 0
