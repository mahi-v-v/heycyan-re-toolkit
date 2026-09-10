package expo.modules.glasssdk.glass.vendors.k900.cmds;

import com.xy.ksdk.api.cmd.IBluetooth;
import com.xy.ksdk.cmd.base.SCmd;

public class S_StartStream extends SCmd {
    public S_StartStream() {
        super("startStream", 1);
    }

    public void setSsid(String ssid)       { addBodyKV("ssid", ssid); }
    public void setPwd(String pwd)         { addBodyKV("pwd", pwd); }
    public void setUrl(String url)         { addBodyKV("url", url); }
    public void setStreamKey(String key)   { addBodyKV("streamKey", key); }
    public void setFps(int fps)            { addBodyKV("fps", fps); }
    public void setBitrate(int bitrate)    { addBodyKV("bitrate", bitrate); }

    public boolean send(IBluetooth bt) {
        this.setBody();
        return super.send(bt);
    }
}
