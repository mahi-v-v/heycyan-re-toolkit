#!/bin/sh
# postinstall_backdoor.sh - installed by the owner's firmware_backdoor.swu
# Gives the OWNER a root shell over Wi-Fi via adb-over-TCP on port 5555.
# swupdate invokes this as:  postinstall_backdoor.sh <phase>   (preinstall|postinstall)
# Idempotent, writes NO partitions, and ALWAYS exits 0 (never fails the update).

PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH

PORT=5555
ADBD_INIT=/etc/init.d/adbd
BOOT_HOOK=/etc/init.d/S99zbackdoor
CRONTABS=/etc/crontabs
CRONFILE=/etc/crontabs/root
LOG=/mnt/UDISK/backdoor.log

log() { echo "[backdoor] $(date 2>/dev/null) $*" >> "$LOG" 2>/dev/null; }

# Run the install only in the post-install phase; succeed as a no-op otherwise
# (swupdate may call the handler in the pre-install phase too).
[ "$1" = "preinstall" ] && exit 0

install_backdoor() {
    log "postinstall start (phase=$1)"

    # (1) Persistence A: force the stock adbd service into TCP mode every boot.
    if [ -f "$ADBD_INIT" ]; then
        if ! grep -q '^ADB_TRANSPORT_PORT=5555' "$ADBD_INIT"; then
            sed -i "s|^#\\?ADB_TRANSPORT_PORT=.*|ADB_TRANSPORT_PORT=$PORT|" "$ADBD_INIT" 2>/dev/null
            grep -q '^ADB_TRANSPORT_PORT=5555' "$ADBD_INIT" || \
                sed -i "/^PROG=/a ADB_TRANSPORT_PORT=$PORT" "$ADBD_INIT" 2>/dev/null
            log "patched $ADBD_INIT (ADB_TRANSPORT_PORT=$PORT)"
        fi
    fi

    # (2) Persistence B (independent): boot hook run by rc.final as "S99zbackdoor start".
    #     Passes the TCP env explicitly, so it works even if (1) did not take.
    cat > "$BOOT_HOOK" <<'EOS'
#!/bin/sh
# Owner root backdoor boot hook (rc.final runs: S99zbackdoor start)
[ "$1" = start ] || exit 0
( sleep 8
  ADB_TRANSPORT_PORT=5555 /etc/init.d/adbd restart >/dev/null 2>&1
) &
exit 0
EOS
    chmod 0755 "$BOOT_HOOK" 2>/dev/null
    log "wrote boot hook $BOOT_HOOK"

    # (3) Second daemon foothold: crond watchdog respawns a TCP adbd if it dies.
    mkdir -p "$CRONTABS" 2>/dev/null
    if ! grep -q 'ADB_TRANSPORT_PORT' "$CRONFILE" 2>/dev/null; then
        echo "* * * * * pidof adbd >/dev/null 2>&1 || ADB_TRANSPORT_PORT=$PORT /etc/init.d/adbd start >/dev/null 2>&1" >> "$CRONFILE"
        log "added crond watchdog to $CRONFILE"
    fi

    # (4) Optional: pre-authorize the owner's adb key (covers auth-enabled builds).
    :  # no adb pubkey embedded at build time; relying on stock auth-off default

    # (5) Immediate, no-reboot activation. Detached (setsid) so it is NOT tied to
    #     swupdate's lifetime; adbd is owned by procd/init and survives the OTA exit.
    setsid sh -c "ADB_TRANSPORT_PORT=$PORT $ADBD_INIT restart" </dev/null >/dev/null 2>&1 &
    setsid sh -c "/etc/init.d/cron restart" </dev/null >/dev/null 2>&1 &
    sync 2>/dev/null
    log "activation kicked (adb tcp :$PORT); done"
}

install_backdoor "$@" 2>/dev/null
exit 0
