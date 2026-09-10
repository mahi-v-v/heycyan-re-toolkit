package expo.modules.glasssdk.glass.vendors.k900.cmds;

import com.xy.ksdk.api.cmd.IBluetooth;
import com.xy.ksdk.cmd.base.SCmd;

public class S_QrScan extends SCmd {
    public S_QrScan() {
        super("cs_qr_scan", 1);
    }

    public boolean send(IBluetooth bt) {
        this.setBody();
        return super.send(bt);
    }
}
