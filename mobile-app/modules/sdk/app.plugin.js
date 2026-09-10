const {
  withProjectBuildGradle,
  withAppBuildGradle,
  withDangerousMod,
  withMainApplication,
} = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

module.exports = function withMyModule(config) {
  config = withProjectBuildGradle(config, (config) => {
    const gradle = config.modResults.contents;
    const classpath = `classpath("com.google.protobuf:protobuf-gradle-plugin:0.9.4")`;

    if (!gradle.includes(classpath)) {
      config.modResults.contents = gradle.replace(
        /dependencies\s?{/,
        `dependencies {
    ${classpath}`,
      );
    }

    return config;
  });

  config = withAppBuildGradle(config, (config) => {
    let gradle = config.modResults.contents;

    if (!gradle.includes("resolutionStrategy")) {
      gradle = gradle.replace(
        /dependencies\s*{/,
        `
configurations.all {
    resolutionStrategy {
        force "androidx.core:core:1.16.0"
    }
    exclude group: "com.android.support", module: "support-compat"
    exclude group: "com.android.support", module: "support-annotations"
    exclude group: "com.android.support", module: "support-core-utils"
    exclude group: "com.android.support", module: "support-core-ui"
    exclude group: "com.android.support", module: "support-fragment"
    exclude group: "com.android.support", module: "support-v4"
    exclude group: "com.android.support", module: "appcompat-v7"
    exclude group: "com.android.support", module: "versionedparcelable"
}

dependencies {
`,
      );
    }

    if (!gradle.includes("splits {")) {
      gradle = gradle.replace(
        /androidResources\s*\{[^}]*\}/,
        (match) =>
          match +
          `
    splits {
        abi {
            reset()
            // enable true
            universalApk true
            include "armeabi-v7a", "arm64-v8a", "x86", "x86_64"
        }
    }`,
      );
    }

    // Resolve duplicate META-INF resources (e.g. INDEX.LIST from netty-all + objenesis via LiveKit)
    if (!gradle.includes("META-INF/INDEX.LIST")) {
      gradle = gradle.replace(
        /packagingOptions\s*\{/,
        `packagingOptions {
        resources {
            excludes += [
                'META-INF/INDEX.LIST',
                'META-INF/DEPENDENCIES',
                'META-INF/LICENSE',
                'META-INF/LICENSE.txt',
                'META-INF/license.txt',
                'META-INF/NOTICE',
                'META-INF/NOTICE.txt',
                'META-INF/notice.txt',
                'META-INF/io.netty.versions.properties',
            ]
        }`,
      );
    }

    config.modResults.contents = gradle;

    return config;
  });

  // onnxruntime-react-native is a legacy bridge module (no codegenConfig) and its Android
  // platform is dropped by both Expo and community autolinking, so OnnxruntimePackage never
  // lands in the generated PackageList -> NativeModules.Onnxruntime is null at runtime.
  // Register it manually in MainApplication so the native ONNX module is available.
  config = withMainApplication(config, (config) => {
    let src = config.modResults.contents;
    const pkgCall = "add(ai.onnxruntime.reactnative.OnnxruntimePackage())";

    if (!src.includes(pkgCall)) {
      src = src.replace(
        /(PackageList\(this\)\.packages\.apply\s*\{)/,
        `$1\n              ${pkgCall}`,
      );
    }

    config.modResults.contents = src;
    return config;
  });

  config = withDangerousMod(config, [
    "ios",
    (config) => {
      const podfilePath = path.join(
        config.modRequest.platformProjectRoot,
        "Podfile",
      );
      let podfile = fs.readFileSync(podfilePath, "utf8");

      if (!podfile.includes("livekit/podspecs")) {
        podfile =
          `source "https://cdn.cocoapods.org/"\nsource "https://github.com/livekit/podspecs.git"\n\n` +
          podfile;
      }

      // Inject umbrella patching INTO the existing post_install block (CocoaPods only runs the last one)
      if (!podfile.includes("GlassSdk-umbrella.h")) {
        const umbrellaCode = `
  umbrella_path = File.join(
    installer.sandbox.root,
    'Headers/Public/GlassSdk/GlassSdk-umbrella.h'
  )
  if File.exist?(umbrella_path)
    content = File.read(umbrella_path)
    content.gsub!('#import "BesBaseViewController.h"', '#import <XingYiGlassesSDK/BesBaseViewController.h>')
    content.gsub!('#import "OTAViewController.h"', '#import <XingYiGlassesSDK/OTAViewController.h>')
    content.gsub!('#import "XingYiGlassesSDK.h"', '#import <XingYiGlassesSDK/XingYiGlassesSDK.h>')
    content.gsub!('#import "XYBleManager.h"', '#import <XingYiGlassesSDK/XYBleManager.h>')
    content.gsub!('#import "XYBleRespondModel.h"', '#import <XingYiGlassesSDK/XYBleRespondModel.h>')
    content.gsub!('#import "XYDataUtil.h"', '#import <XingYiGlassesSDK/XYDataUtil.h>')
    content.gsub!('#import "XYFileRespondModel.h"', '#import <XingYiGlassesSDK/XYFileRespondModel.h>')
    content.gsub!('#import "XYSocketManager.h"', '#import <XingYiGlassesSDK/XYSocketManager.h>')
    File.write(umbrella_path, content)
    puts "\\u2705 Patched GlassSdk-umbrella.h"
  end`;

        // Inject after the react_native_post_install closing paren inside the post_install block
        const marker =
          ":ccache_enabled => ccache_enabled?(podfile_properties),\n  )";
        if (podfile.includes(marker)) {
          podfile = podfile.replace(marker, marker + "\n" + umbrellaCode);
        }
      }

      // onnxruntime-react-native enables ORT extensions (-DORT_ENABLE_EXTENSIONS=1) but never adds
      // the onnxruntime-extensions-c public headers to its search path, so SessionUtils.cpp's
      // `#include <onnxruntime_extensions.h>` fails to compile. Add the header path in post_install.
      if (!podfile.includes("Headers/Public/onnxruntime-extensions-c")) {
        const ortExtCode =
          `
    installer.pods_project.targets.each do |target|
      next unless target.name == 'onnxruntime-react-native'
      target.build_configurations.each do |bc|
        hsp = bc.build_settings['HEADER_SEARCH_PATHS'] || '$(inherited)'
        hsp = hsp.join(' ') if hsp.is_a?(Array)
        unless hsp.include?('onnxruntime-extensions-c')
          bc.build_settings['HEADER_SEARCH_PATHS'] = "#{hsp} \\"` +
          "${PODS_ROOT}" +
          `/Headers/Public/onnxruntime-extensions-c\\""
        end
      end
    end`;

        const ortMarker =
          ":ccache_enabled => ccache_enabled?(podfile_properties),\n    )";
        if (podfile.includes(ortMarker)) {
          podfile = podfile.replace(ortMarker, ortMarker + "\n" + ortExtCode);
        }
      }

      fs.writeFileSync(podfilePath, podfile);

      return config;
    },
  ]);

  return config;
};
