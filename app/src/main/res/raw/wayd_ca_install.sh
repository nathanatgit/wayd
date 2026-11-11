# Driver (runs as root). Placeholders: @PEM@ @NAME@ @APPLY@
# Makes the system trust WAYD's CA in the GLOBAL mount namespace and in every zygote namespace (new apps fork from those),
# then verifies each namespace. If anything is off it rolls everything back, so the trust store is never left empty or half-done.
PEM='@PEM@'
NAME='@NAME@'
APPLY='@APPLY@'
TMP=/data/local/tmp/wayd_ca_tmp
APEX=/apex/com.android.conscrypt/cacerts
rm -rf "$TMP"; mkdir -p "$TMP" || exit 1
SRC=$APEX; [ -d "$SRC" ] || SRC=/system/etc/security/cacerts
cp "$SRC"/* "$TMP"/ || { echo fail-copy; exit 1; }
cp "$PEM" "$TMP/$NAME.0" || { echo fail-copy-ca; exit 1; }
EXPECT=$(ls "$TMP" | wc -l)
NS="1 $(pidof zygote zygote64)"
ROLL=
for p in $NS; do
  nsenter --mount=/proc/$p/ns/mnt -- env TMP="$TMP" sh "$APPLY" || { echo "fail-apply:$p"; ROLL=1; break; }
done
if [ -z "$ROLL" ]; then
  for p in $NS; do
    nsenter --mount=/proc/$p/ns/mnt -- sh -c "D=$APEX; [ -d \$D ] || D=/system/etc/security/cacerts; [ -f \$D/$NAME.0 ] && [ \$(ls \$D | wc -l) -ge $EXPECT ]" || { echo "fail-verify:$p"; ROLL=1; }
  done
fi
if [ -n "$ROLL" ]; then
  for p in $NS; do
    nsenter --mount=/proc/$p/ns/mnt -- sh -c 'umount /apex/com.android.conscrypt/cacerts 2>/dev/null; umount /system/etc/security/cacerts 2>/dev/null'
  done
  rm -rf "$TMP"
  echo rolled-back
  exit 1
fi
rm -rf "$TMP"
echo done
