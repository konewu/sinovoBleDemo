package com.sinovotec.sinovoble.callback;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.util.Log;
import android.util.SparseArray;

import com.alibaba.fastjson.JSONObject;
import com.sinovotec.sinovoble.SinovoBle;
import com.sinovotec.sinovoble.common.BleConnectLock;
import com.sinovotec.sinovoble.common.BleScanDevice;

import java.util.LinkedHashMap;

import static com.sinovotec.sinovoble.common.ComTool.byte2hex;
import static com.sinovotec.sinovoble.common.ComTool.calTimeDiff;
import static com.sinovotec.sinovoble.common.ComTool.getNowTime;

public class BleScanCallBack extends ScanCallback {
    private static BleScanCallBack instance;                //入口操作管理
    private static final String TAG = "SinovoBle";
    private boolean isScanning  = false;          //是否正在扫描
    IScanCallBack iScanCallBack;          //扫描结果回调

    private BleScanCallBack(IScanCallBack scanCallBack){
        this.iScanCallBack = scanCallBack;
        if (iScanCallBack == null){
            throw new NullPointerException("this scanCallback is null!");
        }
    }

    public boolean isScanning() {
        return isScanning;
    }

    public void setScanning(boolean scanning) {
        isScanning = scanning;
    }

    /**
     * 单例方式获取蓝牙通信入口
     */
    public static BleScanCallBack getInstance(IScanCallBack scanCallBack) {
        if (instance != null){
            return  instance;
        }

        synchronized (BleScanCallBack.class) {      //同步锁,一个线程访问一个对象中的synchronized(this)同步代码块时，其他试图访问该对象的线程将被阻塞
            if (instance == null) {
                instance = new BleScanCallBack(scanCallBack){
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        BleScanDevice bleScanDevice = analyzeScanResult(result);

                        for (int i = 0; i< SinovoBle.getInstance().getScanLockList().size(); i++){
                            if (SinovoBle.getInstance().getScanLockList().get(i).GetDevice().getAddress().equals(bleScanDevice.GetDevice().getAddress())){
                                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                                map.put("scanResult", "1");
                                map.put("lockMac", bleScanDevice.GetDevice().getAddress());
                                map.put("lockType", bleScanDevice.GetDevice().getName());

                                iScanCallBack.onDeviceFound(JSONObject.toJSONString(map));

                                //尝试进行自动连接
                                if (SinovoBle.getInstance().isBindMode()) {
                                    Log.w(TAG, "绑定模式下，自动进行连接："+bleScanDevice.GetDevice().getAddress());
                                    Log.w(TAG, "开始连接之前，先停止扫描");
                                    SinovoBle.getInstance().setScanAgain(false);
                                    BleScanCallBack.getInstance(iScanCallBack).stopScan();
                                    SinovoBle.getInstance().connectBle(bleScanDevice.GetDevice());
                                }else {
                                    Log.w(TAG, "非绑定模式下，getToConnectLockList size："+ SinovoBle.getInstance().getToConnectLockList().size());
                                    for (BleConnectLock bleConnectLock : SinovoBle.getInstance().getToConnectLockList()){
                                        String mac = bleConnectLock.getLockMac();
                                        Log.w(TAG,"lockmac:"+ mac + ",ble mac:"+ bleScanDevice.GetDevice().getAddress());
                                        if (bleScanDevice.GetDevice().getAddress().equals(mac)){

                                            Log.w(TAG, "开始进行之前，先停止扫描");
                                            SinovoBle.getInstance().setScanAgain(false);
                                            BleScanCallBack.getInstance(iScanCallBack).stopScan();

                                            Log.w(TAG, "开始进行自动连接:"+ bleScanDevice.GetDevice().getAddress());
                                            SinovoBle.getInstance().connectBle(bleScanDevice.GetDevice());
                                            break;
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }

                    @Override
                    public  void  onScanFailed(int errorCode){
                        Log.e(TAG, "Failed to scan");
                    }
                };
            }
        }
        return instance;
    }

    /**
     *  处理蓝牙扫描得到的解析结果
     */
    BleScanDevice analyzeScanResult(ScanResult result) {
        ScanRecord mScanRecord = result.getScanRecord();
        byte[] manufacturerData = new byte[0];
        int mRssi = result.getRssi();
        BluetoothDevice scanLock = result.getDevice();
        String scanLockMac  = scanLock.getAddress();
        String scanLockName = scanLock.getName();

        if (mScanRecord != null) {
            SparseArray<byte[]> mandufacturerDatas = mScanRecord.getManufacturerSpecificData();
            for (int i=0; i<mandufacturerDatas.size(); i++){
                manufacturerData = mandufacturerDatas.get(i);
            }
        }
        Log.d(TAG, "Scan result：{ Mac address:" +scanLockMac + " Lock name："+scanLockName + " Rssi:"+mRssi + " Adv_data:"+byte2hex(manufacturerData)+"}");

        //将扫描到的设备 放入到 list中
        boolean deviceExist = false;

        //判断扫描到的锁 是不是已经存在扫描列表中，如果已经存在，则更新其信息
        for (int i = 0; i < SinovoBle.getInstance().getScanLockList().size(); i++) {
            BleScanDevice bleScanDevice = SinovoBle.getInstance().getScanLockList().get(i);
            if (bleScanDevice.GetDevice().getAddress().compareTo(scanLockMac)== 0){
                String nowtime = getNowTime();
                bleScanDevice.ReflashInf(scanLock, mRssi, manufacturerData, nowtime);
                deviceExist = true;
            }
        }

        //判断扫描到的锁 是不是已经存在扫描列表中，如果不存在，则添加此锁
        //增加判断条件，针对绑定的时候，如果该锁已经尝试过绑定了，不能再进入扫描结果中 再次来绑定了
        boolean deviceIn = true;

        if (!deviceExist){
            if (SinovoBle.getInstance().isBindMode()){
                //已经绑定列表中包含此锁，则不再添加
                if (SinovoBle.getInstance().getBondBleMacList().contains(scanLockMac)){
                    Log.i(TAG, "锁" +scanLockMac +" 已经存在 bondBleMacList中，已经绑定过,不是要连接的锁，不需要再添加");
                    deviceIn = false;
                }

                //如果广播包内容为空，旧的lock,需要兼容
                if (manufacturerData == null || manufacturerData.length ==0){
                    Log.i(TAG, "锁" +scanLockMac +" 广播包内容为空，或是长度为0，兼容旧版本，允许连接它");
                    deviceIn = true;
                }else {
                    String advData = byte2hex(manufacturerData);
                    //兼容第二批 旧锁
                    if (advData.length() >= 12){
                        String adLockid = advData.substring(0, 12);
                        if (adLockid.equals(SinovoBle.getInstance().getLockID())) {
                            Log.w(TAG, "Adv_data's lockID:" + adLockid + " is matches the lockID(" + SinovoBle.getInstance().getLockID() + ") entered by user,connect and stop scan");
                            SinovoBle.getInstance().setScanAgain(false);
                            BleScanCallBack.getInstance(iScanCallBack).stopScan();
                        }else {
                            String advtype = advData.substring(0, 2);

                            if (advtype.equals("02")){        //兼容第二批， 02 表示是广播的日志
                                Log.w(TAG, "兼容第二批就锁，广播包为02开头，运行加入" + advData );
                            }else if (advtype.equals("01")) {
                                if (advData.length() >= 14){
                                    adLockid = advData.substring(2, 14);
                                    if (adLockid.equals(SinovoBle.getInstance().getLockID())) {
                                        Log.w(TAG, "Adv_data's lockID:" + adLockid + " is matches the lockID(" + SinovoBle.getInstance().getLockID() + ") entered by user,connect and stop scan");
                                        SinovoBle.getInstance().setScanAgain(false);
                                        BleScanCallBack.getInstance(iScanCallBack).stopScan();
                                    }else {
                                        deviceIn = false;
                                        Log.w(TAG, "Adv_data's lockID:" + adLockid + " is different from the lockID(" + SinovoBle.getInstance().getLockID() + ") entered by user,ignore");
                                    }
                                }else {
                                    deviceIn = false;
                                    Log.w(TAG, "Adv_data's lockID:" + adLockid + " is different from the lockID(" + SinovoBle.getInstance().getLockID() + ") entered by user,ignore");
                                }
                            }else {
                                deviceIn = false;
                                Log.w(TAG, "Adv_data's lockID:" + adLockid + " is different from the lockID(" + SinovoBle.getInstance().getLockID() + ") entered by user,ignore");
                            }
                        }
                    }else {
                        Log.i(TAG, "锁" +scanLockMac +" 存在广播包，但长度小于14，不合法，不连接他");
                        deviceIn = false;
                    }
                }
            }else {     //非绑定模式下，对比mac地址即可
                deviceIn = false;   //默认不符合加入
                for (int i = 0; i< SinovoBle.getInstance().getToConnectLockList().size(); i++){
                    BleConnectLock myConnectLock = SinovoBle.getInstance().getToConnectLockList().get(i);
                    if (myConnectLock.getLockMac().equals(scanLockMac)){
                        Log.d(TAG,"该设备是需要自动连接的设备："+ scanLockMac);
                        deviceIn = true;
                        break;
                    }
                }
            }

            if (deviceIn) {
                SinovoBle.getInstance().getScanLockList().add(new BleScanDevice(scanLock, mRssi, manufacturerData, getNowTime()));
                Log.i(TAG, "Get a new lock:" + scanLockMac + " time:" + getNowTime());
            }
        }

        //过滤 ，删除掉时间早于 10s之前的锁
        for (int i = 0; i < SinovoBle.getInstance().getScanLockList().size(); ){
            BleScanDevice bleScanDevice = SinovoBle.getInstance().getScanLockList().get(i);
            if (calTimeDiff(bleScanDevice.getJoinTime(),getNowTime())>10){
                SinovoBle.getInstance().getScanLockList().remove(i);
            }else {
                i++;
            }
        }

        return new BleScanDevice(scanLock, mRssi, manufacturerData, getNowTime());
    }


    //停止蓝牙扫描
    //参数 ，是否立即停止（自动扫描的话，停止之后会判断 是否还需要重新扫描）
    public void stopScan() {
        setScanning(false);
        SinovoBle.getInstance().removeScanHandlerMsg();

        if (SinovoBle.getInstance().getBluetoothAdapter() == null) {
            Log.d(TAG, "Bluetooth Adapter is null");
            return;
        }

        if (!SinovoBle.getInstance().getBluetoothAdapter().isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled");
            return ;
        }

        SinovoBle.getInstance().getBluetoothAdapter().getBluetoothLeScanner().stopScan(instance);
        if (SinovoBle.getInstance().isScanAgain()) {
            SinovoBle.getInstance().getScanBleHandler().postDelayed(() -> {
                Log.d(TAG, "start to scan again after 1s");
                SinovoBle.getInstance().bleScan(iScanCallBack);
            }, 1000);
        }
    }
}
