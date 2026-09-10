const run = async () => {
  const versionsToTest = [
    "AM01CY_1.00.00_260411",
    "AM01CY_2.00.00_260411",
    "AM01CY_2.00.09_260411",
    "AM01CY_1.00.00_000000",
    "V1.0.0",
    "0.0.0"
  ];

  for (const ver of versionsToTest) {
    const res = await fetch('https://www.qlifesnap.com/glasses/app-update/last-ota', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'token': 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxNzgzMDc4MTIyMTk0NDk2OTYiLCJleHAiOjE3ODYxODYyODd9.5Ou5VpOCna1qgLq0peDF7PgQ3gWfFFHGjlznwn_D31Y',
      },
      body: JSON.stringify({
        appId: 1, // Cyan app uses 1
        uid: 1,
        hardwareVersion: "AM01CY_V2.0",
        romVersion: ver,
        os: 1, // Android
        mac: "66:C6:66:D9:03:DC", 
        country: "IN",
        dev: 2
      }),
    });
    console.log("Testing:", ver);
    console.log("Response:", await res.text());
  }
}
run();
