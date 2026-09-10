const {
  withDangerousMod,
  withGradleProperties,
  withAndroidManifest,
  withInfoPlist,
} = require("expo/config-plugins");
const fs = require("fs");
const path = require("path");

const nativeConfig = {
  apiUrl: process.env.EXPO_PUBLIC_API_URL,
  "io.sentry.dsn":
    'https://bd4d8e25eed822280c1011cf01a8d390@o4511434365272064.ingest.us.sentry.io/4511434462461952"',
};

module.exports = function withAppPlugin(config) {
  config = withAndroidManifest(config, (config) => {
    const app = config.modResults.manifest.application[0];
    if (!app["meta-data"]) app["meta-data"] = [];
    app["meta-data"] = app["meta-data"].filter(
      (m) => !Object.keys(nativeConfig).includes(m.$["android:name"]),
    );
    for (const [key, value] of Object.entries(nativeConfig)) {
      app["meta-data"].push({
        $: { "android:name": key, "android:value": value },
      });
    }
    return config;
  });

  config = withInfoPlist(config, (config) => {
    for (const [key, value] of Object.entries(nativeConfig)) {
      config.modResults[key] = value;
    }
    return config;
  });

  // Inject use_modular_headers! into Podfile (required for Firebase Swift pods)
  config = withDangerousMod(config, [
    "ios",
    (config) => {
      const podfilePath = path.join(
        config.modRequest.platformProjectRoot,
        "Podfile",
      );
      let podfile = fs.readFileSync(podfilePath, "utf8");

      if (!podfile.includes("use_modular_headers!")) {
        podfile = podfile.replace(
          "platform :ios,",
          "use_modular_headers!\nplatform :ios,",
        );
      }

      // Force RNFB pods to build as static libraries to avoid Xcode 26+
      // -Werror=non-modular-include-in-framework-module errors.
      if (!podfile.includes("Pod::BuildType.static_library")) {
        podfile = podfile.replace(
          "\ntarget '",
          "\npre_install do |installer|\n  installer.pod_targets.each do |pod|\n    if pod.name.start_with?('RNFB')\n      def pod.build_type\n        Pod::BuildType.static_library\n      end\n    end\n  end\nend\n\ntarget '",
        );
      }

      fs.writeFileSync(podfilePath, podfile);

      return config;
    },
  ]);

  // Set JVM args in gradle.properties (survives prebuild)
  config = withGradleProperties(config, (config) => {
    config.modResults = config.modResults.filter(
      (item) =>
        !(item.type === "property" && item.key === "org.gradle.jvmargs"),
    );
    config.modResults.push({
      type: "property",
      key: "org.gradle.jvmargs",
      value:
        "-Xmx4096m -XX:MaxMetaspaceSize=1024m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8",
    });
    return config;
  });

  return config;
};
