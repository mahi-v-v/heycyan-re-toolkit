# Firmware and Operating System

> **RETRACTION / CORRECTION (2026-07-11):** An earlier revision of this doc claimed the
> E907 `riscv` coprocessor "listens for the wake word using a built-in DSP acoustic model"
> and framed `FUN_ram_80882ddc` as an "audio wakeup-buffer / acoustic detection" routine.
> **This is wrong and has been retracted.** Verified against the primary artifacts, the
> E907 image is a camera-ISP + audio-clock + RPBUF/FreeRTOS AMP firmware with **no neural
> network and no TFL3 / KWS model** (`grep -c TFL3` = 0 across `riscv`, `rootfs`, `user`,
> and the non-stripped `bin/ai_glass_audio`; no NN/MFCC/tensor symbols; the density regions
> are camera ISP gamma/DRC LUTs). The "Hey Cyan" keyword spotter is **not** on the E907
> image and **not** on the V821 Linux side; its host is UNVERIFIED (most plausibly the
> encrypted AM01CY BLE MCU — stated as inference, not confirmed). Other residual errors
> (address mislabel, wpa_supplicant/hostapd, "securely maps") are corrected inline below.

The firmware for the Hey Cyan smart glasses is primarily located in the ``firmware`` directory. It operates on an Asymmetric Multi-Processing (AMP) architecture, utilizing both a Linux environment and an RTOS.

## 1. Partitions and Firmware Images
- **Kernel (`kernel` / `kernel_sdnand`)**: Android Boot Image containing the main Linux kernel (5.4.220). Page size is 2048 bytes.
- **Bootloader (`uboot` / `uboot_sdnand`)**: U-Boot image.
- **Root Filesystem (`rootfs` / `rootfs_sdnand`)**: Squashfs filesystem containing the main Allwinner Tina Linux OS. It holds essential utilities and system binaries. Note: there are **no** `wpa_supplicant` / `hostapd` daemons on this image — only their `.conf` files are present. The Wi-Fi stack is provided by `bin/wifi_daemon` plus `libwifimg`.
- **User Filesystem (`user`)**: Squashfs filesystem containing user-specific configurations, networking scripts (`/lib/netifd`), and kernel modules (like `/lib/modules/5.4.220/v821_smac.ko`).

## 2. Key Executables & Daemons
- **``ai_glass_audio``**: Located in the Linux rootfs partition. This is a RISC-V 32-bit ELF executable that acts as the main audio processing daemon on the glasses. It exchanges audio buffers with the coprocessor over the `rpbuf` IPC link. (It contains no NN/KWS code — `grep -c TFL3` on the non-stripped binary is 0.)
- **``riscv` / `riscv_sdnand``**: A 32-bit RISC-V ELF firmware for the Xuantie-900 (E907) coprocessor. It is FreeRTOS-based (built with Xuantie-900 bare-metal newlib GCC 10.4.0, executing from reserved DDR at `0x80860000`, entry `0x80862e00`). It handles low-level always-on tasks such as buffering microphone audio, the audio clock, camera-ISP support, monitoring touch IO, and RPBUF IPC. It does **not** run a wake-word / acoustic model — there is no neural network and no TFL3 / MFCC / tensor code on this image.
  - **Memory Offsets Identified**:
    - `0x8088a4da` (Function `FUN_ram_8088a498`): Configures hardware IO wakeups (like physical buttons) via register `DAT_ram_809f28d4`.
    - `0x80882ddc` (Function entry `FUN_ram_80882ddc`): A **generic `rpbuf` notify / service wrapper** — it issues Inter-Processor Communication (IPC) via `rpbuf_notify_by_link`. It is **not** a wake-word or acoustic-detection handler. (The previously cited address `0x808e5958` was a mislabel; the function entry is `0x80882ddc`.)
- **`ai_glass_video`**: The camera/video daemon. It drives the V821 **hardware H.264 encoder (VENC, via CedarX / `librt_media.so`)** and writes recordings to **files** (`stream_file`, `record`, on `/mnt/UDISK`). Its only socket is **UDP** (`bind`/`recvfrom`/`sendto`, **no** `listen`/`accept`) — local control, **not** a network video stream.
- **`ai_glass_download`**: A minimal **HTTP/1.1 file server** (`socket`/`bind`/`listen`/`accept`; emits `GET` responses with `Content-Length` / `200 OK`) that serves **recorded** `.mp4`/`.jpg` from `/mnt/UDISK/` to the phone over Wi-Fi — the media-sync path.

### 2.1. Daemon network-role summary (ELF `--dyn-syms`, 2026-07-17)
Imported socket symbols reveal each daemon's role without running it (`listen`+`accept` ⇒ server, `connect` ⇒ client, `bind`+`recvfrom`+`sendto` only ⇒ UDP/local). Result: the camera pipeline is **record-to-file → HTTP download** — there is **no live A/V streaming server** anywhere in the rootfs. The only binaries importing `listen`+`accept` are `adbd`, `ai_glass_download`, `wifi_daemon`, and `swupdate`; there are **no `rtsp` / `8554` / `rtmp` / `ffmpeg` / `librtmp` strings** in the firmware. Consequently **live video streaming / RTMP is not available on stock firmware** (it would require rooting the V821 and adding a streamer that taps the VENC output). Full methodology + evidence: [`cyan_reverse_engineering.md`](06_Reference_Materials/cyan_reverse_engineering.md) §11.

## 3. Update Mechanism
Firmware updates are packaged as `.swu` files (e.g., `firmware.swu`), which use the `swupdate` framework. The ``sw-description`` file maps the partitions for Over-The-Air (OTA) updates. **There is no secure-boot and no image signing** — the only on-device integrity gate is a per-CPIO-item MD5 (`cpio_item_md5`).
