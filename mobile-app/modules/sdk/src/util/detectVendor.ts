import type { GlassAdvertisement } from "../native/Glass";

export type GlassVendor = "k900" | "cyan" | "cyan_sports" | "unknown";

/**
 * Vendor classification lives on the JS side so the rule can change and ship via an
 * OTA JS update — no native rebuild. The native scanner is generic and surfaces the
 * full advertisement; this is the single place that decides which vendor a device is.
 *
 * Returned strings are the contract with native — used verbatim as the adapter-pool
 * key ("k900" / "cyan" / "mock").
 *
 * Classification is by ADVERTISEMENT MARKERS only (no device-name matching — names
 * are user-mutable). Different platforms surface different parts of the advertisement
 * (iOS drops the scan-response; Android merges it), so each vendor has several markers
 * and any one classifies the device:
 *
 *  - K900: BLE Appearance 14667 (0x394B; the marker iOS exposes), manufacturer
 *    company 0xB822 (Android), or service UUID 0x4860 (GATT service, if ever advertised).
 *  - Cyan: service-data UUID 0x3802 or manufacturer company 0x1234 (Android), or
 *    manufacturer company 0x000E (the marker iOS exposes; also present on Android).
 *    Note: 0x000E is Broadcom's company id — a small false-positive risk on unrelated
 *    Broadcom devices, accepted as the price of not matching on the name.
 *  - Cyan Sports (Moyoung CRP SDK): manufacturer company 0xF0EF.
 */
const K900_APPEARANCE = 14667; // 0x394B ("K9")

export function classifyVendor(
  adv: GlassAdvertisement | undefined,
  name?: string,
): GlassVendor {
  const uuids = (adv?.serviceUuids ?? []).map((u) => u.toLowerCase());
  const svcData = Object.keys(adv?.serviceData ?? {}).map((k) =>
    k.toLowerCase(),
  );
  const mfg = Object.keys(adv?.manufacturerData ?? {}).map((k) =>
    k.toLowerCase(),
  );
  const hasUuid = (needle: string) => uuids.some((u) => u.includes(needle));

  // K900 — appearance 14667 (iOS), mfg company 0xB822 (Android), or service UUID 0x4860.
  if (
    adv?.appearance === K900_APPEARANCE ||
    mfg.includes("b822") ||
    hasUuid("4860")
  ) {
    return "k900";
  }

  // Cyan Sports (Moyoung CRP SDK) — manufacturer company 0xF0EF.
  if (mfg.includes("f0ef") || name?.toLowerCase().includes("v06")) {
    return "cyan_sports";
  }

  // Cyan — service data 0x3802 / mfg 0x1234 (Android), or mfg 0x000E (iOS + Android).
  if (
    svcData.some((k) => k.includes("3802")) ||
    mfg.includes("1234") ||
    mfg.includes("000e") ||
    name?.toLowerCase().includes("cy")
  ) {
    return "cyan";
  }

  return "unknown";
}
