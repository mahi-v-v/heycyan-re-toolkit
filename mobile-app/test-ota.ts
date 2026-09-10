import { checkFirmwareUpdate } from './utils/otaApi';

async function runTest() {
  console.log("Running OTA test...");
  const hardwareVersion = "AM01CY_V2.0";
  const romVersion = "AM01CY_2.00.10_260411";
  
  const result = await checkFirmwareUpdate(hardwareVersion, romVersion);
  console.log("Result:");
  console.log(JSON.stringify(result, null, 2));
}

runTest();
