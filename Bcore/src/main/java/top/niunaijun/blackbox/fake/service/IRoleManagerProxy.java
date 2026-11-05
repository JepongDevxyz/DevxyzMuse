package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.app.role.BRIRoleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;

/**
 * DevxyzMuse 1.0.2 fix (engine-level, applies to every cloned app):
 *
 * A cloned app that opens a screen querying role state (e.g. Muse's
 * Settings screen composing its "Default assistant" card, which calls
 * RoleManager.isRoleHeld) talks to the REAL RoleService. RoleService
 * verifies the caller with AppOpsManager.checkPackage(guestPackage,
 * callingUid) - but the calling uid is the HOST app's uid, which does
 * not own the guest package on the real system, so the call dies with
 * SecurityException "Package <guest> does not belong to <host uid>".
 * Thrown inside UI composition, that exception kills the whole guest
 * process instantly (Muse clone: gear tap -> black screen -> back to
 * the clone list, Settings never renders).
 *
 * This proxy answers the role queries at the guest boundary with the
 * answers that are TRUE for a clone: a clone never holds a system role
 * (isRoleHeld = false), the roles themselves exist on the device
 * (isRoleAvailable = true), and no package is reported as a role
 * holder. Unhooked methods pass through to the real service.
 */
public class IRoleManagerProxy extends BinderInvocationStub {

    public IRoleManagerProxy() {
        super(BRServiceManager.get().getService(Context.ROLE_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIRoleManagerStub.get().asInterface(BRServiceManager.get().getService(Context.ROLE_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.ROLE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("isRoleHeld", false));
        addMethodHook(new ValueMethodProxy("isRoleHeldAsUser", false));
        addMethodHook(new ValueMethodProxy("isRoleAvailable", true));
        addMethodHook(new ValueMethodProxy("isRoleAvailableAsUser", true));
        addMethodHook(new EmptyRoleHoldersHook("getRoleHolders"));
        addMethodHook(new EmptyRoleHoldersHook("getRoleHoldersAsUser"));
    }

    /**
     * Returns an empty ParceledListSlice. The class is not in the public
     * SDK surface this module compiles against, so it is built
     * reflectively at runtime (it always exists on device - it is how
     * the real RoleService returns holder lists).
     */
    private static class EmptyRoleHoldersHook extends MethodHook {
        private final String mName;
        private Object mCached;

        EmptyRoleHoldersHook(String name) {
            mName = name;
        }

        @Override
        protected String getMethodName() {
            return mName;
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (mCached == null) {
                Class<?> slice = Class.forName("android.content.pm.ParceledListSlice");
                Constructor<?> ctor = slice.getConstructor(java.util.List.class);
                mCached = ctor.newInstance(new ArrayList<>());
            }
            return mCached;
        }
    }
}
