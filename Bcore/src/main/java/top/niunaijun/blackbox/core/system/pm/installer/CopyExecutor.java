package top.niunaijun.blackbox.core.system.pm.installer;


import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.utils.Slog;


public class CopyExecutor implements Executor {

    public static final String TAG = "CopyExecutor";

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;



        List<String> hostSplits = resolveHostSplits(packageName, option);

        try {
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                File libDir = BEnvironment.getAppLibDir(packageName);
                NativeUtils.copyNativeLib(new File(ps.pkg.baseCodePath), libDir);



                for (String split : hostSplits) {
                    try {
                        NativeUtils.copyNativeLib(new File(split), libDir);
                    } catch (Exception e) {
                        Slog.w(TAG, "failed extracting native libs from split " + split + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
        if (option.isFlag(InstallOption.FLAG_STORAGE)) {

            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(packageName);
            try {
                if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                    boolean b = FileUtils.renameTo(origFile, newFile);
                    if (!b) {
                        FileUtils.copyFile(origFile, newFile);
                    }
                } else {
                    FileUtils.copyFile(origFile, newFile);
                }
                newFile.setReadOnly();

                ps.pkg.baseCodePath = newFile.getAbsolutePath();


                if (copySplits(packageName, hostSplits) != 0) {
                    return -1;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        } else if (option.isFlag(InstallOption.FLAG_SYSTEM)) {

        }
        return 0;
    }



    private List<String> resolveHostSplits(String packageName, InstallOption option) {
        List<String> result = new ArrayList<>();
        if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
            return result;
        }
        try {
            ApplicationInfo hostInfo = BlackBoxCore.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            String[] splits = hostInfo.splitSourceDirs;
            if (splits != null) {
                for (String split : splits) {
                    if (split != null && new File(split).isFile()) {
                        result.add(split);
                    }
                }
            }
        } catch (Throwable ignored) {

        }
        if (!result.isEmpty()) {
            Slog.d(TAG, "found " + result.size() + " split apk(s) for " + packageName);
        }
        return result;
    }

    private int copySplits(String packageName, List<String> hostSplits) {
        File splitDir = BEnvironment.getSplitApkDir(packageName);

        FileUtils.deleteDir(splitDir);
        if (hostSplits.isEmpty()) {
            return 0;
        }
        FileUtils.mkdirs(splitDir);
        for (String split : hostSplits) {
            File src = new File(split);
            File dst = BEnvironment.getSplitApkFile(packageName, src.getName());
            try {
                FileUtils.copyFile(src, dst);
                dst.setReadOnly();
            } catch (IOException e) {
                Slog.e(TAG, "failed copying split " + src.getName() + ": " + e.getMessage());
                return -1;
            }
        }
        Slog.d(TAG, "copied " + hostSplits.size() + " split apk(s) for " + packageName);
        return 0;
    }
}
