package com.tencent.soter.core.sotercore;


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.tencent.soter.soterserver.ISoterService;
import com.tencent.soter.soterserver.SoterExportResult;
import com.tencent.soter.soterserver.SoterSessionResult;
import com.tencent.soter.soterserver.SoterSignResult;
import com.tencent.soter.core.model.ConstantsSoter;
import com.tencent.soter.core.model.SLogger;
import com.tencent.soter.core.model.SoterCoreResult;
import com.tencent.soter.core.model.SoterDelegate;
import com.tencent.soter.core.model.SoterPubKeyModel;
import com.tencent.soter.core.model.SoterErrCode;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Signature;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * The SOTER Core APIs Treble project
 */
public class SoterCoreTreble extends SoterCoreBase implements ConstantsSoter, SoterErrCode{

    public static final String TAG = "Soter.SoterCoreTreble";


    private Context mContext;

    protected ISoterService mSoterService;

    private boolean connected = false;

    private final Object lock = new Object();

    private SyncJob syncJob = new SyncJob();

    protected static final int DEFAULT_BLOCK_TIME = 3 * 1000; // Default synchronize block time

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {

        @Override
        public void binderDied() {
            // TODO Auto-generated method stub
            SLogger.i(TAG, "soter: binder died");
            if (mSoterService == null)
                return;

            synchronized (lock) {
                connected = false;
                lock.notifyAll();
            }

            mSoterService.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mSoterService = null;

            bindService();
        }
    };

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(
                ComponentName className, IBinder service) {
            SLogger.i(TAG, "soter: onServiceConnected");
            synchronized (lock) {
                connected = true;
                lock.notifyAll();
            }

            try {
                service.linkToDeath(mDeathRecipient, 0);
                mSoterService = ISoterService.Stub.asInterface(service);
            } catch (RemoteException e) {
                SLogger.e(TAG, "soter: Binding deathRecipient is error - RemoteException"+ e.toString());
            }


            SLogger.i(TAG, "soter: Binding is done - Service connected");

            syncJob.countDown();
        }

        public void onServiceDisconnected(ComponentName className) {
            synchronized (lock) {
                connected = false;
                lock.notifyAll();
            }

            mSoterService = null;

            SLogger.i(TAG, "soter: unBinding is done - Service disconnected");

            syncJob.countDown();

            bindService();
        }
    };

    @Override
    public boolean initSoter(Context context) {
        mContext = context;

        SLogger.i(TAG, "soter: initSoter in");
        syncJob.doAsSyncJob(DEFAULT_BLOCK_TIME, new Runnable() {
            @Override
            public void run() {
                bindServiceIfNeeded();
                SLogger.i(TAG, "soter: initSoter binding");
            }
        });

        if(connected){
            SLogger.i(TAG, "soter: initSoter finish");
            return true;
        }else {
            SLogger.e(TAG, "soter: initSoter error");
            return false;
        }

    }

    public void bindServiceIfNeeded() {
        if (!connected) {
            SLogger.i(TAG, "soter: bindServiceIfNeeded try to bind");
            bindService();
        }
    }

    public void bindService(){
        Intent intent = new Intent();
        intent.setAction("com.tencent.soter.soterserver.ISoterService");
        intent.setPackage("com.tencent.soter.soterserver");

        if(mContext == null) {
            SLogger.e(TAG, "soter: bindService context is null ");
            return;
        }
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        SLogger.i(TAG, "soter: bindService binding is start ");
    }

    public void unbindService(Context context){
        context.unbindService(mServiceConnection);
    }

    public boolean isNativeSupportSoter() {

        if(SoterDelegate.isTriggeredOOM()) {
            SLogger.w(TAG, "soter: the device has already triggered OOM. mark as not support");
            return false;
        }

        return true;

    }

    @Override
    public SoterCoreResult generateAppGlobalSecureKey() {
        SLogger.i(TAG,"soter: generateAppSecureKey in");

        if(!isNativeSupportSoter()){
            return new SoterCoreResult(ERR_ASK_GEN_FAILED);
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(ERR_ASK_GEN_FAILED);
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return new SoterCoreResult(ERR_ASK_GEN_FAILED);
        }

        try {
            if(mSoterService.generateAppSecureKey(0) == ERR_OK) {
                return new SoterCoreResult(ERR_OK);
            }
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: generateAppSecureKey fail: ");
        }
        return new SoterCoreResult(ERR_ASK_GEN_FAILED);
    }

    @Override
    public SoterCoreResult removeAppGlobalSecureKey() {
        SLogger.i(TAG, "soter: removeAppGlobalSecureKey in");

        if(!isNativeSupportSoter()){
            return new SoterCoreResult(ERR_REMOVE_ASK);
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(ERR_REMOVE_ASK);
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return new SoterCoreResult(ERR_REMOVE_ASK);
        }

        try {
            if(mSoterService.removeAllAuthKey(0) == ERR_OK) {
                return new SoterCoreResult(ERR_OK);
            }
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: removeAppGlobalSecureKey fail: ");
        }
        return new SoterCoreResult(ERR_REMOVE_ASK);

    }

    @Override
    public boolean hasAppGlobalSecureKey() {
        SLogger.i(TAG, "soter: hasAppGlobalSecureKey in");

        if(!isNativeSupportSoter()){
            return false;
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return false;
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return false;
        }

        try {
            return mSoterService.hasAskAlready(0);
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: hasAppGlobalSecureKey fail: ");
            return false;
        }


    }

    @Override
    public boolean isAppGlobalSecureKeyValid() {
        SLogger.i(TAG,"soter: isAppGlobalSecureKeyValid in");
        return hasAppGlobalSecureKey() && getAppGlobalSecureKeyModel() != null;
    }

    @Override
    public SoterPubKeyModel getAppGlobalSecureKeyModel() {
        SLogger.i(TAG,"soter: getAppGlobalSecureKeyModel in");


        if(!isNativeSupportSoter()){
            return null;
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterExportResult soterExportResult;

        try {
            soterExportResult =  mSoterService.getAppSecureKey(0);
            byte[] rawBytes = soterExportResult.exportData;

            if (rawBytes != null && rawBytes.length > 0) {
                return retrieveJsonFromExportedData(rawBytes);
            }else {
                SLogger.e(TAG, "soter: soter: key can not be retrieved");
                return null;
            }
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: getAppGlobalSecureKeyModel fail: ");
        }
        return null;

    }

    @Override
    public SoterCoreResult generateAuthKey(String authKeyName) {
        SLogger.i(TAG,"soter: generateAuthKey in");

        if(!isNativeSupportSoter()){
            return new SoterCoreResult(ERR_AUTH_KEY_GEN_FAILED);
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(ERR_AUTH_KEY_GEN_FAILED);
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return new SoterCoreResult(ERR_AUTH_KEY_GEN_FAILED);
        }

        try {
            if(mSoterService.generateAuthKey(0, authKeyName) == ERR_OK) {
                return new SoterCoreResult(ERR_OK);
            }
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: generateAuthKey fail: ");
        }

        return new SoterCoreResult(ERR_AUTH_KEY_GEN_FAILED);
    }

    @Override
    public SoterCoreResult removeAuthKey(String authKeyName, boolean isAutoDeleteASK) {
        SLogger.i(TAG,"soter: removeAuthKey in");

        if(!isNativeSupportSoter()){
            return new SoterCoreResult(ERR_REMOVE_AUTH_KEY);
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(ERR_REMOVE_AUTH_KEY);
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return new SoterCoreResult(ERR_REMOVE_AUTH_KEY);
        }

        try {
            if(mSoterService.removeAuthKey(0, authKeyName) == ERR_OK) {
                if (isAutoDeleteASK) {
                    if (mSoterService.removeAllAuthKey(0) == ERR_OK) {
                        return new SoterCoreResult(ERR_OK);
                    } else {
                        return new SoterCoreResult(ERR_REMOVE_ASK);
                    }
                }
                return new SoterCoreResult(ERR_OK);
            }
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: removeAuthKey fail: ");
        }
        return new SoterCoreResult(ERR_REMOVE_AUTH_KEY);
    }

    @Override
    public Signature initAuthKeySignature(String useKeyAlias) throws InvalidKeyException, NoSuchProviderException,
            NoSuchAlgorithmException, KeyStoreException, IOException,
            CertificateException, UnrecoverableEntryException {
        return null;
    }

    @Override
    public boolean isAuthKeyValid(String authKeyName, boolean autoDelIfNotValid) {
        SLogger.i(TAG,"soter: isAuthKeyValid in");
        //todo
        return hasAuthKey(authKeyName) && getAuthKeyModel(authKeyName) != null;
    }

    @Override
    public SoterPubKeyModel getAuthKeyModel(String authKeyName) {
        SLogger.i(TAG,"soter: getAuthKeyModel in");

        if(!isNativeSupportSoter()){
            return null;
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterExportResult soterExportResult;

        try {
            soterExportResult =  mSoterService.getAuthKey(0, authKeyName);
            byte[] rawBytes = soterExportResult.exportData;
            if (rawBytes != null && rawBytes.length > 0) {
                return retrieveJsonFromExportedData(rawBytes);
            }else {
                SLogger.e(TAG, "soter: key can not be retrieved");
                return null;
            }
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: getAuthKeyModel fail: ");
        }
        return null;

    }

    @Override
    public Signature getAuthInitAndSign(String useKeyAlias) {
        return null;
    }

    @Override
    public boolean hasAuthKey(String authKeyName) {

        SLogger.i(TAG, "soter: hasAuthKey in");

        if(!isNativeSupportSoter()){
            return false;
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return false;
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return false;
        }

        try {
            return mSoterService.hasAuthKey(0,authKeyName);
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: hasAuthKey fail: ");
            return false;
        }

    }

    @Override
    public SoterSessionResult initSigh(String kname, String challenge) {

        SLogger.i(TAG, "soter: initSigh in");

        if(!isNativeSupportSoter()){
            return null;
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterSessionResult result;

        try {
            result =  mSoterService.initSigh(0, kname, challenge);
            return result;
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: initSigh fail: ");
        }
        return null;

    }

    @Override
    public byte[] finishSign(long signSession) throws Exception{

        SLogger.i(TAG, "soter: finishSign in");

        if(!isNativeSupportSoter()){
            return null;
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterSignResult soterSignResult;
        byte[] rawBytes = new byte[0];
        try {
            soterSignResult =  mSoterService.finishSign(signSession);
            rawBytes = soterSignResult.exportData;
            if(soterSignResult.resultCode != ERR_OK ){
                throw new Exception("finishSign error");
            }

        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: finishSign fail: ");
        }
        return rawBytes;

    }

    public int getVersion() {
        SLogger.i(TAG,"soter: getVersion in");

        if(!isNativeSupportSoter()){
            return 0;
        }

        if(mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return 0;
        }

        bindServiceIfNeeded();

        if(mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            return 0;
        }

        try {
            return mSoterService.getVersion() ;
        } catch (RemoteException e) {
            SLogger.printErrStackTrace(TAG, e, "soter: getVersion fail: ");
        }
        return 0;
    }

}
