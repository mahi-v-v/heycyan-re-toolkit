import os

path = './hardware_research/decompilers_and_extracts/apk_extract/sources/com/oudmon/ble/base/communication/bigData/bean/WifiInfoReq.java'
if not os.path.exists(path):
    path = 'D:\\Mahi\\_workspace\\heycyan-re-toolkit\\hardware_research\\decompilers_and_extracts\\apk_extract\\sources\\com\\oudmon\\ble\\base\\communication\\bigData\\bean\\WifiInfoReq.java'

with open(path, 'rb') as f:
    d = f.read()

try:
    print(d.decode('utf-16le'))
except Exception:
    print(d.decode('utf-8'))
