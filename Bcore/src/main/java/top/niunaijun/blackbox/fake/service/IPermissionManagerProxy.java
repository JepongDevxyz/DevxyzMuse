package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.DiagLog;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * The virtual package name / uid does not exist in the real PMS, so a
     * permission query in guest identity is misjudged as DENIED. WebView
     * initialization reads checkSelfPermission(INTERNET); a DENIED answer
     * sets blockNetworkLoads=true and EVERY page load inside the guest
     * fails with net::ERR_CACHE_MISS (seen 2026-11-09: Meta Account /
     * accountscenter.meta.com inside Muse clones, while the real Muse
     * app loaded it fine). Rewrite the query to the host identity, whose
     * real permissions the PMS can actually answer. Fix ported from
     * ZiTanIOI's fork, which documents this exact error.
     */
    @ProxyMethod("checkPermission")
    public static class CheckPermission extends MethodHook {
        private static boolean sLoggedInternetFix;

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            boolean isInternet = args != null && args.length > 0
                    && "android.permission.INTERNET".equals(args[0]);
            String origPkg = MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceAppUid(args);
            Object result = method.invoke(who, args);
            if (isInternet && !sLoggedInternetFix) {
                sLoggedInternetFix = true;
                DiagLog.log("perm-fix: INTERNET checkPermission (pkg=" + origPkg
                        + ") answered as host -> " + result);
            }
            return result;
        }
    }

    @ProxyMethod("checkUidPermission")
    public static class CheckUidPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceAppUid(args);
            return method.invoke(who, args);
        }
    }

}
