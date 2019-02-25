/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAdjustments;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

import libcore.io.IoUtils;

final class IntentReceiverLeaked extends AndroidRuntimeException {
    public IntentReceiverLeaked(String msg) {
        super(msg);
    }
}

final class ServiceConnectionLeaked extends AndroidRuntimeException {
    public ServiceConnectionLeaked(String msg) {
        super(msg);
    }
}

/**
 * Local state maintained about a currently loaded .apk.
 */
public final class LoadedApk {

    private static final String TAG = "LoadedApk";

    private final ActivityThread mActivityThread;
    final String mPackageName;
    private ApplicationInfo mApplicationInfo;
    private String mAppDir;
    private String mResDir;
    private String[] mSplitAppDirs;
    private String[] mSplitResDirs;
    private String[] mOverlayDirs;
    private String[] mSharedLibraries;
    private String mDataDir;
    private String mLibDir;
    private File mDataDirFile;
    private File mDeviceProtectedDataDirFile;
    private File mCredentialProtectedDataDirFile;
    private final ClassLoader mBaseClassLoader;
    private final boolean mSecurityViolation;
    private final boolean mIncludeCode;
    private final boolean mRegisterPackage;
    private final DisplayAdjustments mDisplayAdjustments = new DisplayAdjustments();
    /**
     * WARNING: This may change. Don't hold external references to it.
     */
    Resources mResources;
    private ClassLoader mClassLoader;
    private Application mApplication;

    private final ArrayMap<Context, ArrayMap<BroadcastReceiver, ReceiverDispatcher>> mReceivers
            = new ArrayMap<Context, ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>>();
    private final ArrayMap<Context, ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>> mUnregisteredReceivers
            = new ArrayMap<Context, ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>>();
    private final ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mServices
            = new ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>>();
    private final ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mUnboundServices
            = new ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>>();

    int mClientCount = 0;

    Application getApplication() {
        return mApplication;
    }

    /**
     * Create information about a new .apk
     * <p>
     * NOTE: This constructor is called with ActivityThread's lock held,
     * so MUST NOT call back out to the activity manager.
     */
    public LoadedApk(ActivityThread activityThread, ApplicationInfo aInfo,
                     CompatibilityInfo compatInfo, ClassLoader baseLoader,
                     boolean securityViolation, boolean includeCode, boolean registerPackage) {

        mActivityThread = activityThread;
        setApplicationInfo(aInfo);
        mPackageName = aInfo.packageName;
        mBaseClassLoader = baseLoader;
        mSecurityViolation = securityViolation;
        mIncludeCode = includeCode;
        mRegisterPackage = registerPackage;
        mDisplayAdjustments.setCompatibilityInfo(compatInfo);
    }

    private static ApplicationInfo adjustNativeLibraryPaths(ApplicationInfo info) {
        // If we're dealing with a multi-arch application that has both
        // 32 and 64 bit shared libraries, we might need to choose the secondary
        // depending on what the current runtime's instruction set is.
        if (info.primaryCpuAbi != null && info.secondaryCpuAbi != null) {
            final String runtimeIsa = VMRuntime.getRuntime().vmInstructionSet();

            // Get the instruction set that the libraries of secondary Abi is supported.
            // In presence of a native bridge this might be different than the one secondary Abi used.
            String secondaryIsa = VMRuntime.getInstructionSet(info.secondaryCpuAbi);
            final String secondaryDexCodeIsa = SystemProperties.get("ro.dalvik.vm.isa." + secondaryIsa);
            secondaryIsa = secondaryDexCodeIsa.isEmpty() ? secondaryIsa : secondaryDexCodeIsa;

            // If the runtimeIsa is the same as the primary isa, then we do nothing.
            // Everything will be set up correctly because info.nativeLibraryDir will
            // correspond to the right ISA.
            if (runtimeIsa.equals(secondaryIsa)) {
                final ApplicationInfo modified = new ApplicationInfo(info);
                modified.nativeLibraryDir = modified.secondaryNativeLibraryDir;
                modified.primaryCpuAbi = modified.secondaryCpuAbi;
                return modified;
            }
        }

        return info;
    }

    /**
     * Create information about the system package.
     * Must call {@link #installSystemApplicationInfo} later.
     */
    LoadedApk(ActivityThread activityThread) {
        mActivityThread = activityThread;
        mApplicationInfo = new ApplicationInfo();
        mApplicationInfo.packageName = "android";
        mPackageName = "android";
        mAppDir = null;
        mResDir = null;
        mSplitAppDirs = null;
        mSplitResDirs = null;
        mOverlayDirs = null;
        mSharedLibraries = null;
        mDataDir = null;
        mDataDirFile = null;
        mDeviceProtectedDataDirFile = null;
        mCredentialProtectedDataDirFile = null;
        mLibDir = null;
        mBaseClassLoader = null;
        mSecurityViolation = false;
        mIncludeCode = true;
        mRegisterPackage = false;
        mClassLoader = ClassLoader.getSystemClassLoader();
        mResources = Resources.getSystem();
    }

    /**
     * Sets application info about the system package.
     */
    void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
        assert info.packageName.equals("android");
        mApplicationInfo = info;
        mClassLoader = classLoader;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    public int getTargetSdkVersion() {
        return mApplicationInfo.targetSdkVersion;
    }

    public boolean isSecurityViolation() {
        return mSecurityViolation;
    }

    public CompatibilityInfo getCompatibilityInfo() {
        return mDisplayAdjustments.getCompatibilityInfo();
    }

    public void setCompatibilityInfo(CompatibilityInfo compatInfo) {
        mDisplayAdjustments.setCompatibilityInfo(compatInfo);
    }

    /**
     * Gets the array of shared libraries that are listed as
     * used by the given package.
     *
     * @param packageName the name of the package (note: not its
     *                    file name)
     *
     * @return null-ok; the array of shared libraries, each one
     * a fully-qualified path
     */
    private static String[] getLibrariesFor(String packageName) {
        ApplicationInfo ai = null;
        try {
            ai = ActivityThread.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_SHARED_LIBRARY_FILES, UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (ai == null) {
            return null;
        }

        return ai.sharedLibraryFiles;
    }

    public void updateApplicationInfo(ApplicationInfo aInfo, List<String> oldPaths) {
        setApplicationInfo(aInfo);

        final List<String> newPaths = new ArrayList<>();
        makePaths(mActivityThread, aInfo, newPaths, null /*libPaths*/);
        final List<String> addedPaths = new ArrayList<>(newPaths.size());

        if (oldPaths != null) {
            for (String path : newPaths) {
                final String apkName = path.substring(path.lastIndexOf(File.separator));
                boolean match = false;
                for (String oldPath : oldPaths) {
                    final String oldApkName = oldPath.substring(path.lastIndexOf(File.separator));
                    if (apkName.equals(oldApkName)) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    addedPaths.add(path);
                }
            }
        } else {
            addedPaths.addAll(newPaths);
        }
        synchronized (this) {
            createOrUpdateClassLoaderLocked(addedPaths);
            if (mResources != null) {
                mResources = mActivityThread.getTopLevelResources(mResDir, mSplitResDirs,
                        mOverlayDirs, mApplicationInfo.sharedLibraryFiles, Display.DEFAULT_DISPLAY,
                        this);
            }
        }
    }

    private void setApplicationInfo(ApplicationInfo aInfo) {
        final int myUid = Process.myUid();
        aInfo = adjustNativeLibraryPaths(aInfo);
        mApplicationInfo = aInfo;
        mAppDir = aInfo.sourceDir;
        mResDir = aInfo.uid == myUid ? aInfo.sourceDir : aInfo.publicSourceDir;
        mSplitAppDirs = aInfo.splitSourceDirs;
        mSplitResDirs = aInfo.uid == myUid ? aInfo.splitSourceDirs : aInfo.splitPublicSourceDirs;
        mOverlayDirs = aInfo.resourceDirs;
        mSharedLibraries = aInfo.sharedLibraryFiles;
        mDataDir = aInfo.dataDir;
        mLibDir = aInfo.nativeLibraryDir;
        mDataDirFile = FileUtils.newFileOrNull(aInfo.dataDir);
        mDeviceProtectedDataDirFile = FileUtils.newFileOrNull(aInfo.deviceProtectedDataDir);
        mCredentialProtectedDataDirFile = FileUtils.newFileOrNull(aInfo.credentialProtectedDataDir);
    }

    public static void makePaths(ActivityThread activityThread, ApplicationInfo aInfo,
                                 List<String> outZipPaths, List<String> outLibPaths) {
        final String appDir = aInfo.sourceDir;
        final String[] splitAppDirs = aInfo.splitSourceDirs;
        final String libDir = aInfo.nativeLibraryDir;
        final String[] sharedLibraries = aInfo.sharedLibraryFiles;

        outZipPaths.clear();
        outZipPaths.add(appDir);
        if (splitAppDirs != null) {
            Collections.addAll(outZipPaths, splitAppDirs);
        }

        if (outLibPaths != null) {
            outLibPaths.clear();
        }

        /*
         * The following is a bit of a hack to inject
         * instrumentation into the system: If the app
         * being started matches one of the instrumentation names,
         * then we combine both the "instrumentation" and
         * "instrumented" app into the path, along with the
         * concatenation of both apps' shared library lists.
         */

        String instrumentationPackageName = activityThread.mInstrumentationPackageName;
        String instrumentationAppDir = activityThread.mInstrumentationAppDir;
        String[] instrumentationSplitAppDirs = activityThread.mInstrumentationSplitAppDirs;
        String instrumentationLibDir = activityThread.mInstrumentationLibDir;

        String instrumentedAppDir = activityThread.mInstrumentedAppDir;
        String[] instrumentedSplitAppDirs = activityThread.mInstrumentedSplitAppDirs;
        String instrumentedLibDir = activityThread.mInstrumentedLibDir;
        String[] instrumentationLibs = null;

        if (appDir.equals(instrumentationAppDir)
                || appDir.equals(instrumentedAppDir)) {
            outZipPaths.clear();
            outZipPaths.add(instrumentationAppDir);
            if (instrumentationSplitAppDirs != null) {
                Collections.addAll(outZipPaths, instrumentationSplitAppDirs);
            }
            if (!instrumentationAppDir.equals(instrumentedAppDir)) {
                outZipPaths.add(instrumentedAppDir);
                if (instrumentedSplitAppDirs != null) {
                    Collections.addAll(outZipPaths, instrumentedSplitAppDirs);
                }
            }

            if (outLibPaths != null) {
                outLibPaths.add(instrumentationLibDir);
                if (!instrumentationLibDir.equals(instrumentedLibDir)) {
                    outLibPaths.add(instrumentedLibDir);
                }
            }

            if (!instrumentedAppDir.equals(instrumentationAppDir)) {
                instrumentationLibs = getLibrariesFor(instrumentationPackageName);
            }
        }

        if (outLibPaths != null) {
            if (outLibPaths.isEmpty()) {
                outLibPaths.add(libDir);
            }

            // Add path to libraries in apk for current abi. Do this now because more entries
            // will be added to zipPaths that shouldn't be part of the library path.
            if (aInfo.primaryCpuAbi != null) {
                // Add fake libs into the library search path if we target prior to N.
                if (aInfo.targetSdkVersion <= 23) {
                    outLibPaths.add("/system/fake-libs" +
                            (VMRuntime.is64BitAbi(aInfo.primaryCpuAbi) ? "64" : ""));
                }
                for (String apk : outZipPaths) {
                    outLibPaths.add(apk + "!/lib/" + aInfo.primaryCpuAbi);
                }
            }

            if (aInfo.isSystemApp() && !aInfo.isUpdatedSystemApp()) {
                // Add path to system libraries to libPaths;
                // Access to system libs should be limited
                // to bundled applications; this is why updated
                // system apps are not included.
                outLibPaths.add(System.getProperty("java.library.path"));
            }
        }

        if (sharedLibraries != null) {
            for (String lib : sharedLibraries) {
                if (!outZipPaths.contains(lib)) {
                    outZipPaths.add(0, lib);
                }
            }
        }

        if (instrumentationLibs != null) {
            for (String lib : instrumentationLibs) {
                if (!outZipPaths.contains(lib)) {
                    outZipPaths.add(0, lib);
                }
            }
        }
    }

    private void createOrUpdateClassLoaderLocked(List<String> addedPaths) {
        if (mPackageName.equals("android")) {
            // Note: This branch is taken for system server and we don't need to setup
            // jit profiling support.
            if (mClassLoader != null) {
                // nothing to update
                return;
            }

            if (mBaseClassLoader != null) {
                mClassLoader = mBaseClassLoader;
            } else {
                mClassLoader = ClassLoader.getSystemClassLoader();
            }

            return;
        }

        // Avoid the binder call when the package is the current application package.
        // The activity manager will perform ensure that dexopt is performed before
        // spinning up the process.
        if (!Objects.equals(mPackageName, ActivityThread.currentPackageName())) {
            VMRuntime.getRuntime().vmInstructionSet();
            try {
                ActivityThread.getPackageManager().notifyPackageUse(mPackageName,
                        PackageManager.NOTIFY_PACKAGE_USE_CROSS_PACKAGE);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        if (mRegisterPackage) {
            try {
                ActivityManagerNative.getDefault().addPackageDependency(mPackageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        // Lists for the elements of zip/code and native libraries.
        //
        // Both lists are usually not empty. We expect on average one APK for the zip component,
        // but shared libraries and splits are not uncommon. We expect at least three elements
        // for native libraries (app-based, system, vendor). As such, give both some breathing
        // space and initialize to a small value (instead of incurring growth code).
        final List<String> zipPaths = new ArrayList<>(10);
        final List<String> libPaths = new ArrayList<>(10);
        makePaths(mActivityThread, mApplicationInfo, zipPaths, libPaths);

        final boolean isBundledApp = mApplicationInfo.isSystemApp()
                && !mApplicationInfo.isUpdatedSystemApp();

        String libraryPermittedPath = mDataDir;
        if (isBundledApp) {
            // This is necessary to grant bundled apps access to
            // libraries located in subdirectories of /system/lib
            libraryPermittedPath += File.pathSeparator +
                    System.getProperty("java.library.path");
        }

        final String librarySearchPath = TextUtils.join(File.pathSeparator, libPaths);

        // If we're not asked to include code, we construct a classloader that has
        // no code path included. We still need to set up the library search paths
        // and permitted path because NativeActivity relies on it (it attempts to
        // call System.loadLibrary() on a classloader from a LoadedApk with
        // mIncludeCode == false).
        if (!mIncludeCode) {
            if (mClassLoader == null) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                mClassLoader = ApplicationLoaders.getDefault().getClassLoader(
                        "" /* codePath */, mApplicationInfo.targetSdkVersion, isBundledApp,
                        librarySearchPath, libraryPermittedPath, mBaseClassLoader);
                StrictMode.setThreadPolicy(oldPolicy);
            }

            return;
        }

        /*
         * With all the combination done (if necessary, actually create the java class
         * loader and set up JIT profiling support if necessary.
         *
         * In many cases this is a single APK, so try to avoid the StringBuilder in TextUtils.
         */
        final String zip = (zipPaths.size() == 1) ? zipPaths.get(0) :
                TextUtils.join(File.pathSeparator, zipPaths);

        if (ActivityThread.localLOGV)
            Slog.v(ActivityThread.TAG, "Class path: " + zip +
                    ", JNI path: " + librarySearchPath);

        boolean needToSetupJitProfiles = false;
        if (mClassLoader == null) {
            // Temporarily disable logging of disk reads on the Looper thread
            // as this is early and necessary.
            StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();

            mClassLoader = ApplicationLoaders.getDefault().getClassLoader(zip,
                    mApplicationInfo.targetSdkVersion, isBundledApp, librarySearchPath,
                    libraryPermittedPath, mBaseClassLoader);

            StrictMode.setThreadPolicy(oldPolicy);
            // Setup the class loader paths for profiling.
            needToSetupJitProfiles = true;
        }

        if (addedPaths != null && addedPaths.size() > 0) {
            final String add = TextUtils.join(File.pathSeparator, addedPaths);
            ApplicationLoaders.getDefault().addPath(mClassLoader, add);
            // Setup the new code paths for profiling.
            needToSetupJitProfiles = true;
        }

        // Setup jit profile support.
        //
        // It is ok to call this multiple times if the application gets updated with new splits.
        // The runtime only keeps track of unique code paths and can handle re-registration of
        // the same code path. There's no need to pass `addedPaths` since any new code paths
        // are already in `mApplicationInfo`.
        //
        // It is NOT ok to call this function from the system_server (for any of the packages it
        // loads code from) so we explicitly disallow it there.
        if (needToSetupJitProfiles && !ActivityThread.isSystem()) {
            setupJitProfileSupport();
        }
    }

    public ClassLoader getClassLoader() {
        synchronized (this) {
            if (mClassLoader == null) {
                createOrUpdateClassLoaderLocked(null /*addedPaths*/);
            }
            return mClassLoader;
        }
    }

    // Keep in sync with installd (frameworks/native/cmds/installd/commands.cpp).
    private static File getPrimaryProfileFile(String packageName) {
        File profileDir = Environment.getDataProfilesDePackageDirectory(
                UserHandle.myUserId(), packageName);
        return new File(profileDir, "primary.prof");
    }

    private void setupJitProfileSupport() {
        if (!SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false)) {
            return;
        }
        // Only set up profile support if the loaded apk has the same uid as the
        // current process.
        // Currently, we do not support profiling across different apps.
        // (e.g. application's uid might be different when the code is
        // loaded by another app via createApplicationContext)
        if (mApplicationInfo.uid != Process.myUid()) {
            return;
        }

        final List<String> codePaths = new ArrayList<>();
        if ((mApplicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            codePaths.add(mApplicationInfo.sourceDir);
        }
        if (mApplicationInfo.splitSourceDirs != null) {
            Collections.addAll(codePaths, mApplicationInfo.splitSourceDirs);
        }

        if (codePaths.isEmpty()) {
            // If there are no code paths there's no need to setup a profile file and register with
            // the runtime,
            return;
        }

        final File profileFile = getPrimaryProfileFile(mPackageName);
        final File foreignDexProfilesFile =
                Environment.getDataProfilesDeForeignDexDirectory(UserHandle.myUserId());

        VMRuntime.registerAppInfo(profileFile.getPath(), mApplicationInfo.dataDir,
                codePaths.toArray(new String[codePaths.size()]), foreignDexProfilesFile.getPath());
    }

    /**
     * Setup value for Thread.getContextClassLoader(). If the
     * package will not run in in a VM with other packages, we set
     * the Java context ClassLoader to the
     * PackageInfo.getClassLoader value. However, if this VM can
     * contain multiple packages, we intead set the Java context
     * ClassLoader to a proxy that will warn about the use of Java
     * context ClassLoaders and then fall through to use the
     * system ClassLoader.
     * <p>
     * <p> Note that this is similar to but not the same as the
     * android.content.Context.getClassLoader(). While both
     * context class loaders are typically set to the
     * PathClassLoader used to load the package archive in the
     * single application per VM case, a single Android process
     * may contain several Contexts executing on one thread with
     * their own logical ClassLoaders while the Java context
     * ClassLoader is a thread local. This is why in the case when
     * we have multiple packages per VM we do not set the Java
     * context ClassLoader to an arbitrary but instead warn the
     * user to set their own if we detect that they are using a
     * Java library that expects it to be set.
     */
    private void initializeJavaContextClassLoader() {
        IPackageManager pm = ActivityThread.getPackageManager();
        android.content.pm.PackageInfo pi;
        try {
            pi = pm.getPackageInfo(mPackageName, PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                    UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (pi == null) {
            throw new IllegalStateException("Unable to get package info for "
                    + mPackageName + "; is package not installed?");
        }
        /*
         * Two possible indications that this package could be
         * sharing its virtual machine with other packages:
         *
         * 1.) the sharedUserId attribute is set in the manifest,
         *     indicating a request to share a VM with other
         *     packages with the same sharedUserId.
         *
         * 2.) the application element of the manifest has an
         *     attribute specifying a non-default process name,
         *     indicating the desire to run in another packages VM.
         */
        boolean sharedUserIdSet = (pi.sharedUserId != null);
        boolean processNameNotDefault =
                (pi.applicationInfo != null &&
                        !mPackageName.equals(pi.applicationInfo.processName));
        boolean sharable = (sharedUserIdSet || processNameNotDefault);
        ClassLoader contextClassLoader =
                (sharable)
                        ? new WarningContextClassLoader()
                        : mClassLoader;
        Thread.currentThread().setContextClassLoader(contextClassLoader);
    }

    private static class WarningContextClassLoader extends ClassLoader {

        private static boolean warned = false;

        private void warn(String methodName) {
            if (warned) {
                return;
            }
            warned = true;
            Thread.currentThread().setContextClassLoader(getParent());
            Slog.w(ActivityThread.TAG, "ClassLoader." + methodName + ": " +
                    "The class loader returned by " +
                    "Thread.getContextClassLoader() may fail for processes " +
                    "that host multiple applications. You should explicitly " +
                    "specify a context class loader. For example: " +
                    "Thread.setContextClassLoader(getClass().getClassLoader());");
        }

        @Override
        public URL getResource(String resName) {
            warn("getResource");
            return getParent().getResource(resName);
        }

        @Override
        public Enumeration<URL> getResources(String resName) throws IOException {
            warn("getResources");
            return getParent().getResources(resName);
        }

        @Override
        public InputStream getResourceAsStream(String resName) {
            warn("getResourceAsStream");
            return getParent().getResourceAsStream(resName);
        }

        @Override
        public Class<?> loadClass(String className) throws ClassNotFoundException {
            warn("loadClass");
            return getParent().loadClass(className);
        }

        @Override
        public void setClassAssertionStatus(String cname, boolean enable) {
            warn("setClassAssertionStatus");
            getParent().setClassAssertionStatus(cname, enable);
        }

        @Override
        public void setPackageAssertionStatus(String pname, boolean enable) {
            warn("setPackageAssertionStatus");
            getParent().setPackageAssertionStatus(pname, enable);
        }

        @Override
        public void setDefaultAssertionStatus(boolean enable) {
            warn("setDefaultAssertionStatus");
            getParent().setDefaultAssertionStatus(enable);
        }

        @Override
        public void clearAssertionStatus() {
            warn("clearAssertionStatus");
            getParent().clearAssertionStatus();
        }
    }

    public String getAppDir() {
        return mAppDir;
    }

    public String getLibDir() {
        return mLibDir;
    }

    public String getResDir() {
        return mResDir;
    }

    public String[] getSplitAppDirs() {
        return mSplitAppDirs;
    }

    public String[] getSplitResDirs() {
        return mSplitResDirs;
    }

    public String[] getOverlayDirs() {
        return mOverlayDirs;
    }

    public String getDataDir() {
        return mDataDir;
    }

    public File getDataDirFile() {
        return mDataDirFile;
    }

    public File getDeviceProtectedDataDirFile() {
        return mDeviceProtectedDataDirFile;
    }

    public File getCredentialProtectedDataDirFile() {
        return mCredentialProtectedDataDirFile;
    }

    public AssetManager getAssets(ActivityThread mainThread) {
        return getResources(mainThread).getAssets();
    }

    public Resources getResources(ActivityThread mainThread) {
        if (mResources == null) {
            mResources = mainThread.getTopLevelResources(mResDir, mSplitResDirs, mOverlayDirs,
                    mApplicationInfo.sharedLibraryFiles, Display.DEFAULT_DISPLAY, this);
        }
        return mResources;
    }

    public Application makeApplication(boolean forceDefaultAppClass,
                                       Instrumentation instrumentation) {
        if (mApplication != null) {
            return mApplication;
        }

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "makeApplication");

        Application app = null;

        String appClass = mApplicationInfo.className;
        if (forceDefaultAppClass || (appClass == null)) {
            appClass = "android.app.Application";
        }

        try {
            java.lang.ClassLoader cl = getClassLoader();
            if (!mPackageName.equals("android")) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                        "initializeJavaContextClassLoader");
                initializeJavaContextClassLoader();
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
            ContextImpl appContext = ContextImpl.createAppContext(mActivityThread, this);
            app = mActivityThread.mInstrumentation.newApplication(
                    cl, appClass, appContext);
            appContext.setOuterContext(app);
        } catch (Exception e) {
            if (!mActivityThread.mInstrumentation.onException(app, e)) {
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                throw new RuntimeException(
                        "Unable to instantiate application " + appClass
                                + ": " + e.toString(), e);
            }
        }
        mActivityThread.mAllApplications.add(app);
        mApplication = app;

        if (instrumentation != null) {
            try {
                // 调用Application的onCreate方法
                instrumentation.callApplicationOnCreate(app);
            } catch (Exception e) {
                if (!instrumentation.onException(app, e)) {
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    throw new RuntimeException(
                            "Unable to create application " + app.getClass().getName()
                                    + ": " + e.toString(), e);
                }
            }
        }

        // Rewrite the R 'constants' for all library apks.
        SparseArray<String> packageIdentifiers = getAssets(mActivityThread)
                .getAssignedPackageIdentifiers();
        final int N = packageIdentifiers.size();
        for (int i = 0; i < N; i++) {
            final int id = packageIdentifiers.keyAt(i);
            if (id == 0x01 || id == 0x7f) {
                continue;
            }

            rewriteRValues(getClassLoader(), packageIdentifiers.valueAt(i), id);
        }

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        return app;
    }

    private void rewriteRValues(ClassLoader cl, String packageName, int id) {
        final Class<?> rClazz;
        try {
            rClazz = cl.loadClass(packageName + ".R");
        } catch (ClassNotFoundException e) {
            // This is not necessarily an error, as some packages do not ship with resources
            // (or they do not need rewriting).
            Log.i(TAG, "No resource references to update in package " + packageName);
            return;
        }

        final Method callback;
        try {
            callback = rClazz.getMethod("onResourcesLoaded", int.class);
        } catch (NoSuchMethodException e) {
            // No rewriting to be done.
            return;
        }

        Throwable cause;
        try {
            callback.invoke(null, id);
            return;
        } catch (IllegalAccessException e) {
            cause = e;
        } catch (InvocationTargetException e) {
            cause = e.getCause();
        }

        throw new RuntimeException("Failed to rewrite resource references for " + packageName,
                cause);
    }

    public void removeContextRegistrations(Context context,
                                           String who, String what) {
        final boolean reportRegistrationLeaks = StrictMode.vmRegistrationLeaksEnabled();
        synchronized (mReceivers) {
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> rmap =
                    mReceivers.remove(context);
            if (rmap != null) {
                for (int i = 0; i < rmap.size(); i++) {
                    LoadedApk.ReceiverDispatcher rd = rmap.valueAt(i);
                    IntentReceiverLeaked leak = new IntentReceiverLeaked(
                            what + " " + who + " has leaked IntentReceiver "
                                    + rd.getIntentReceiver() + " that was " +
                                    "originally registered here. Are you missing a " +
                                    "call to unregisterReceiver()?");
                    leak.setStackTrace(rd.getLocation().getStackTrace());
                    Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                    if (reportRegistrationLeaks) {
                        StrictMode.onIntentReceiverLeaked(leak);
                    }
                    try {
                        ActivityManagerNative.getDefault().unregisterReceiver(
                                rd.getIIntentReceiver());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
            mUnregisteredReceivers.remove(context);
        }

        synchronized (mServices) {
            //Slog.i(TAG, "Receiver registrations: " + mReceivers);
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> smap =
                    mServices.remove(context);
            if (smap != null) {
                for (int i = 0; i < smap.size(); i++) {
                    LoadedApk.ServiceDispatcher sd = smap.valueAt(i);
                    ServiceConnectionLeaked leak = new ServiceConnectionLeaked(
                            what + " " + who + " has leaked ServiceConnection "
                                    + sd.getServiceConnection() + " that was originally bound here");
                    leak.setStackTrace(sd.getLocation().getStackTrace());
                    Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                    if (reportRegistrationLeaks) {
                        StrictMode.onServiceConnectionLeaked(leak);
                    }
                    try {
                        ActivityManagerNative.getDefault().unbindService(
                                sd.getIServiceConnection());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    sd.doForget();
                }
            }
            mUnboundServices.remove(context);
            //Slog.i(TAG, "Service registrations: " + mServices);
        }
    }

    // 每一个注册过广播接收者的Activity组件在LoadApk类中都有一个对应的ReceiverDispatcher对象，它负责
    // 将这个被注册的广播接收者与注册它的Activity组件关联在一起。这些ReceiverDispatcher对象保存在一个
    // HashMap中，并且以它们所关联的广播接收者为关键字。最后用来保存这些ReceiverDispatcher对象的HashMap
    // 又以它们所关联的Activity组件的Context接口为关键字保存在LoadApk类的成员变量mReceivers中
    public IIntentReceiver getReceiverDispatcher(BroadcastReceiver r,
                                                 Context context, Handler handler,
                                                 Instrumentation instrumentation, boolean registered) {
        synchronized (mReceivers) {
            LoadedApk.ReceiverDispatcher rd = null;
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = null;
            // 是不是注册广播
            if (registered) {
                // 查找有没有对应的广播接收者对象列表
                map = mReceivers.get(context);
                if (map != null) {
                    // 查找是否存在该广播接收者对应的ReceiverDispatcher对象
                    rd = map.get(r);
                }
            }
            if (rd == null) {// 不存在
                // 初始化广播接收器调度员
                rd = new ReceiverDispatcher(r, context, handler,
                        instrumentation, registered);
                if (registered) {
                    if (map == null) {
                        map = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                        mReceivers.put(context, map);
                    }
                    // 缓存ReceiverDispatcher
                    map.put(r, rd);
                }
            } else {
                // 验证广播分发者的Context和Handler是否一致。
                rd.validate(context, handler);
            }
            rd.mForgotten = false;
            return rd.getIIntentReceiver();
        }
    }

    public IIntentReceiver forgetReceiverDispatcher(Context context,
                                                    BroadcastReceiver r) {
        synchronized (mReceivers) {
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = mReceivers.get(context);
            LoadedApk.ReceiverDispatcher rd = null;
            if (map != null) {
                rd = map.get(r);
                if (rd != null) {
                    map.remove(r);
                    if (map.size() == 0) {
                        mReceivers.remove(context);
                    }
                    if (r.getDebugUnregister()) {
                        ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                                = mUnregisteredReceivers.get(context);
                        if (holder == null) {
                            holder = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                            mUnregisteredReceivers.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unregistered here:");
                        ex.fillInStackTrace();
                        rd.setUnregisterLocation(ex);
                        holder.put(r, rd);
                    }
                    rd.mForgotten = true;
                    return rd.getIIntentReceiver();
                }
            }
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                    = mUnregisteredReceivers.get(context);
            if (holder != null) {
                rd = holder.get(r);
                if (rd != null) {
                    RuntimeException ex = rd.getUnregisterLocation();
                    throw new IllegalArgumentException(
                            "Unregistering Receiver " + r
                                    + " that was already unregistered", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Receiver " + r
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Receiver not registered: " + r);
            }

        }
    }

    static final class ReceiverDispatcher {

        final static class InnerReceiver extends IIntentReceiver.Stub {
            final WeakReference<LoadedApk.ReceiverDispatcher> mDispatcher;
            final LoadedApk.ReceiverDispatcher mStrongRef;

            InnerReceiver(LoadedApk.ReceiverDispatcher rd, boolean strong) {
                mDispatcher = new WeakReference<LoadedApk.ReceiverDispatcher>(rd);
                mStrongRef = strong ? rd : null;
            }

            @Override
            public void performReceive(Intent intent, int resultCode, String data,
                                       Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                final LoadedApk.ReceiverDispatcher rd;
                if (intent == null) {
                    Log.wtf(TAG, "Null intent received");
                    rd = null;
                } else {
                    rd = mDispatcher.get();
                }
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = intent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Receiving broadcast " + intent.getAction()
                            + " seq=" + seq + " to " + (rd != null ? rd.mReceiver : null));
                }
                if (rd != null) {
                    rd.performReceive(intent, resultCode, data, extras,
                            ordered, sticky, sendingUser);
                } else {
                    // The activity manager dispatched a broadcast to a registered
                    // receiver in this process, but before it could be delivered the
                    // receiver was unregistered.  Acknowledge the broadcast on its
                    // behalf so that the system's broadcast sequence can continue.
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing broadcast to unregistered receiver");
                    IActivityManager mgr = ActivityManagerNative.getDefault();
                    try {
                        if (extras != null) {
                            extras.setAllowFds(false);
                        }
                        mgr.finishReceiver(this, resultCode, data, extras, false, intent.getFlags());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }

        final IIntentReceiver.Stub mIIntentReceiver;// 指向实现了IIntentReceiver接口的Binder本地对象
        final BroadcastReceiver mReceiver;// 指向与该Activity组件相关的一个广播接收者
        final Context mContext;// 指向Activity
        final Handler mActivityThread;// 指向与该Activity组件相关的一个Handler对象
        final Instrumentation mInstrumentation;
        final boolean mRegistered;// 用来描述mReceiver所指向的广播接收者是否已经注册到AMS中。已注册：true，否则：false
        final IntentReceiverLeaked mLocation;
        RuntimeException mUnregisterLocation;
        boolean mForgotten;

        final class Args extends BroadcastReceiver.PendingResult implements Runnable {
            // 用来描述一个广播，它的目标广播接收者便是ReceiverDispatcher类的成员变量mReceiver所指向的广播接收者
            private Intent mCurIntent;
            private final boolean mOrdered;// 用来描述成员变量mCurIntent所描述的广播是否是一个有序广播
            private boolean mDispatched;

            // 动态广播：参数mRegistered为true，mType就是TYPE_REGISTERED
            // 非ordered广播：ordered变量是false，mOrderedHint为false，mType是TYPE_REGISTERED
            public Args(Intent intent, int resultCode, String resultData, Bundle resultExtras,
                        boolean ordered, boolean sticky, int sendingUser) {
                super(resultCode, resultData, resultExtras,
                        mRegistered ? TYPE_REGISTERED : TYPE_UNREGISTERED, ordered,
                        sticky, mIIntentReceiver.asBinder(), sendingUser, intent.getFlags());
                mCurIntent = intent;
                mOrdered = ordered;
            }

            public void run() {
                // mReceiver指向一个广播接收者
                final BroadcastReceiver receiver = mReceiver;
                final boolean ordered = mOrdered;

                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = mCurIntent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Dispatching broadcast " + mCurIntent.getAction()
                            + " seq=" + seq + " to " + mReceiver);
                    Slog.i(ActivityThread.TAG, "  mRegistered=" + mRegistered
                            + " mOrderedHint=" + ordered);
                }

                final IActivityManager mgr = ActivityManagerNative.getDefault();
                final Intent intent = mCurIntent;
                if (intent == null) {
                    Log.wtf(TAG, "Null intent being dispatched, mDispatched=" + mDispatched);
                }

                mCurIntent = null;
                mDispatched = true;
                if (receiver == null || intent == null || mForgotten) {
                    if (mRegistered && ordered) {
                        if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                "Finishing null broadcast to " + mReceiver);
                        sendFinished(mgr);
                    }
                    return;
                }

                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "broadcastReceiveReg");
                // 这里处理的是动态广播接收者，默认认为接收者BroadcastReceiver已经存在
                try {
                    ClassLoader cl = mReceiver.getClass().getClassLoader();
                    intent.setExtrasClassLoader(cl);
                    intent.prepareToEnterProcess();
                    setExtrasClassLoader(cl);
                    // 调用接收者的onReceive方法，这里还涉及到setPendingResult方法，相关具体内容请看
                    // BroadcastReceiver.goAsync方法
                    receiver.setPendingResult(this);
                    // 接受广播
                    receiver.onReceive(mContext, intent);
                    // 然后调用BroadcastReceiver.PendingResult.finish函数，也就是下面的finish函数
                } catch (Exception e) {
                    // 检查当前广播是否是有序广播，并且广播接收者是否已经注册到AMS中
                    if (mRegistered && ordered) {
                        if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                "Finishing failed broadcast to " + mReceiver);
                        // 通知AMS，它前面转发过来的有序广播已经处理完了，这时AMS就可以继续将这个有序广播
                        // 转发给下一个目标广播接收者了
                        sendFinished(mgr);
                    }
                    if (mInstrumentation == null ||
                            !mInstrumentation.onException(mReceiver, e)) {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                        throw new RuntimeException(
                                "Error receiving broadcast " + intent
                                        + " in " + mReceiver, e);
                    }
                }

                if (receiver.getPendingResult() != null) {
                    finish();
                }
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
        }

        ReceiverDispatcher(BroadcastReceiver receiver, Context context,
                           Handler activityThread, Instrumentation instrumentation,
                           boolean registered) {
            if (activityThread == null) {
                throw new NullPointerException("Handler must not be null");
            }

            mIIntentReceiver = new InnerReceiver(this, !registered);
            mReceiver = receiver;
            mContext = context;
            mActivityThread = activityThread;
            mInstrumentation = instrumentation;
            mRegistered = registered;
            mLocation = new IntentReceiverLeaked(null);
            mLocation.fillInStackTrace();
        }

        /**
         * 验证广播分发者的Context和Handler是否一致。
         *
         * @param context        广播分发者的Context
         * @param activityThread 广播分发者的Handler
         */
        void validate(Context context, Handler activityThread) {
            if (mContext != context) {
                throw new IllegalStateException(
                        "Receiver " + mReceiver +
                                " registered with differing Context (was " +
                                mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new IllegalStateException(
                        "Receiver " + mReceiver +
                                " registered with differing handler (was " +
                                mActivityThread + " now " + activityThread + ")");
            }
        }

        IntentReceiverLeaked getLocation() {
            return mLocation;
        }

        BroadcastReceiver getIntentReceiver() {
            return mReceiver;
        }

        IIntentReceiver getIIntentReceiver() {
            return mIIntentReceiver;
        }

        void setUnregisterLocation(RuntimeException ex) {
            mUnregisterLocation = ex;
        }

        RuntimeException getUnregisterLocation() {
            return mUnregisterLocation;
        }

        public void performReceive(Intent intent, int resultCode, String data,
                                   Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
            // 首先将参数Intent所描述的一个广播封装成一个Args对象，然后将这个Args对象封装成一个消息对象，
            // 然后将这个消息对象发送到应用程序主线程的消息队列中。
            final Args args = new Args(intent, resultCode, data, extras, ordered,
                    sticky, sendingUser);
            // 将当前广播信息放到主线程的Handler中进行处理，作为一个Runnable调度而不是在handleMessage中处理，
            // 而是在Handler内部机制中，处理的时候会对应run函数，因此这里不久后会调用Args.run函数
            if (intent == null) {
                Log.wtf(TAG, "Null intent received");
            } else {
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = intent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Enqueueing broadcast " + intent.getAction()
                            + " seq=" + seq + " to " + mReceiver);
                }
            }
            // 上面将广播的参数封装在一个Args对象里面，然后通过post到主线程的消息队列里面
            if (intent == null || !mActivityThread.post(args)) {
                if (mRegistered && ordered) {
                    IActivityManager mgr = ActivityManagerNative.getDefault();
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing sync broadcast to " + mReceiver);
                    args.sendFinished(mgr);
                }
            }
        }

    }

    // 每一个绑定过Service组件的Activity组件在LoadedApk类中都有一个对应的ServiceDispatcher对象，它负责将
    // 这个被绑定的Service组件与绑定它的Activity组件关联起来，这些ServiceDispatcher保存在map中
    public final IServiceConnection getServiceDispatcher(ServiceConnection c,
                                                         Context context, Handler handler, int flags) {
        synchronized (mServices) {
            LoadedApk.ServiceDispatcher sd = null;
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map = mServices.get(context);
            // 检查成员变量mServices中是否存在一个以ServiceConnection对象c为关键字的ServiceDispatcher
            // 对象sd，如果不存在，则创建一个并且以context为关键字保存到mServices中
            if (map != null) {
                sd = map.get(c);
            }
            if (sd == null) {
                sd = new ServiceDispatcher(c, context, handler, flags);
                if (map == null) {
                    map = new ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                    mServices.put(context, map);
                }
                map.put(c, sd);
            } else {
                sd.validate(context, handler);
            }
            // 调用前面获取到的ServiceDispatcher对象sd的成员函数getIServiceConnection来获取一个实现了
            // IServiceConnection接口的本地Binder对象
            return sd.getIServiceConnection();
        }
    }

    public final IServiceConnection forgetServiceDispatcher(Context context,
                                                            ServiceConnection c) {
        synchronized (mServices) {
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map
                    = mServices.get(context);
            LoadedApk.ServiceDispatcher sd = null;
            if (map != null) {
                sd = map.get(c);
                if (sd != null) {
                    map.remove(c);
                    sd.doForget();
                    if (map.size() == 0) {
                        mServices.remove(context);
                    }
                    if ((sd.getFlags() & Context.BIND_DEBUG_UNBIND) != 0) {
                        ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                                = mUnboundServices.get(context);
                        if (holder == null) {
                            holder = new ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                            mUnboundServices.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unbound here:");
                        ex.fillInStackTrace();
                        sd.setUnbindLocation(ex);
                        holder.put(c, sd);
                    }
                    return sd.getIServiceConnection();
                }
            }
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                    = mUnboundServices.get(context);
            if (holder != null) {
                sd = holder.get(c);
                if (sd != null) {
                    RuntimeException ex = sd.getUnbindLocation();
                    throw new IllegalArgumentException(
                            "Unbinding Service " + c
                                    + " that was already unbound", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Service " + c
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Service not registered: " + c);
            }
        }
    }

    static final class ServiceDispatcher {
        // mIServiceConnection对象指向一个实现了IServiceConnection接口的Binder本地对象
        private final ServiceDispatcher.InnerConnection mIServiceConnection;
        private final ServiceConnection mConnection;
        private final Context mContext;// 指向Activity组件
        private final Handler mActivityThread;// 指向与Activity组件关联的一个Handler对象
        private final ServiceConnectionLeaked mLocation;// 指向与上面Activity关联的一个ServiceConnection对象
        private final int mFlags;

        private RuntimeException mUnbindLocation;

        private boolean mForgotten;

        private static class ConnectionInfo {
            IBinder binder;
            IBinder.DeathRecipient deathMonitor;
        }

        private static class InnerConnection extends IServiceConnection.Stub {
            final WeakReference<LoadedApk.ServiceDispatcher> mDispatcher;

            InnerConnection(LoadedApk.ServiceDispatcher sd) {
                mDispatcher = new WeakReference<LoadedApk.ServiceDispatcher>(sd);
            }

            public void connected(ComponentName name, IBinder service) throws RemoteException {
                LoadedApk.ServiceDispatcher sd = mDispatcher.get();
                if (sd != null) {
                    sd.connected(name, service);
                }
            }
        }

        private final ArrayMap<ComponentName, ServiceDispatcher.ConnectionInfo> mActiveConnections
                = new ArrayMap<ComponentName, ServiceDispatcher.ConnectionInfo>();

        ServiceDispatcher(ServiceConnection conn,
                          Context context, Handler activityThread, int flags) {
            mIServiceConnection = new InnerConnection(this);
            mConnection = conn;
            mContext = context;
            mActivityThread = activityThread;
            mLocation = new ServiceConnectionLeaked(null);
            mLocation.fillInStackTrace();
            mFlags = flags;
        }

        void validate(Context context, Handler activityThread) {
            if (mContext != context) {
                throw new RuntimeException(
                        "ServiceConnection " + mConnection +
                                " registered with differing Context (was " +
                                mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new RuntimeException(
                        "ServiceConnection " + mConnection +
                                " registered with differing handler (was " +
                                mActivityThread + " now " + activityThread + ")");
            }
        }

        void doForget() {
            synchronized (this) {
                for (int i = 0; i < mActiveConnections.size(); i++) {
                    ServiceDispatcher.ConnectionInfo ci = mActiveConnections.valueAt(i);
                    ci.binder.unlinkToDeath(ci.deathMonitor, 0);
                }
                mActiveConnections.clear();
                mForgotten = true;
            }
        }

        ServiceConnectionLeaked getLocation() {
            return mLocation;
        }

        ServiceConnection getServiceConnection() {
            return mConnection;
        }

        // 将成员变量mIServiceConnection所指向的一个InnerConnection对象返回给调用者
        IServiceConnection getIServiceConnection() {
            return mIServiceConnection;
        }

        int getFlags() {
            return mFlags;
        }

        void setUnbindLocation(RuntimeException ex) {
            mUnbindLocation = ex;
        }

        RuntimeException getUnbindLocation() {
            return mUnbindLocation;
        }

        public void connected(ComponentName name, IBinder service) {
            // mActivityThread指向了ActivityThread类的成员变量mH，它是用来向应用程序的主线程的消息队列发送消息的
            if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 0));
            } else {
                // 执行连接
                doConnected(name, service);
            }
        }

        public void death(ComponentName name, IBinder service) {
            if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 1));
            } else {
                doDeath(name, service);
            }
        }

        public void doConnected(ComponentName name, IBinder service) {
            ServiceDispatcher.ConnectionInfo old;
            ServiceDispatcher.ConnectionInfo info;

            synchronized (this) {
                if (mForgotten) {
                    // We unbound before receiving the connection; ignore
                    // any connection received.
                    return;
                }
                old = mActiveConnections.get(name);
                if (old != null && old.binder == service) {
                    // Huh, already have this one.  Oh well!
                    return;
                }

                if (service != null) {
                    // A new service is being connected... set it all up.
                    info = new ConnectionInfo();
                    info.binder = service;
                    info.deathMonitor = new DeathMonitor(name, service);
                    try {
                        service.linkToDeath(info.deathMonitor, 0);
                        mActiveConnections.put(name, info);
                    } catch (RemoteException e) {
                        // This service was dead before we got it...  just
                        // don't do anything with it.
                        mActiveConnections.remove(name);
                        return;
                    }

                } else {
                    // The named service is being disconnected... clean up.
                    mActiveConnections.remove(name);
                }

                if (old != null) {
                    old.binder.unlinkToDeath(old.deathMonitor, 0);
                }
            }

            // If there was an old service, it is now disconnected.
            if (old != null) {
                mConnection.onServiceDisconnected(name);
            }
            // If there is a new service, it is now connected.
            if (service != null) {
                // 调用onServiceConnected函数以便将参数service所描述的Binder本地对象传递给你要绑定的Service组件
                mConnection.onServiceConnected(name, service);
            }
        }

        public void doDeath(ComponentName name, IBinder service) {
            synchronized (this) {
                ConnectionInfo old = mActiveConnections.get(name);
                if (old == null || old.binder != service) {
                    // Death for someone different than who we last
                    // reported...  just ignore it.
                    return;
                }
                mActiveConnections.remove(name);
                old.binder.unlinkToDeath(old.deathMonitor, 0);
            }

            mConnection.onServiceDisconnected(name);
        }

        private final class RunConnection implements Runnable {
            RunConnection(ComponentName name, IBinder service, int command) {
                mName = name;
                mService = service;
                mCommand = command;
            }

            public void run() {
                if (mCommand == 0) {
                    doConnected(mName, mService);
                } else if (mCommand == 1) {
                    doDeath(mName, mService);
                }
            }

            final ComponentName mName;
            final IBinder mService;
            final int mCommand;
        }

        private final class DeathMonitor implements IBinder.DeathRecipient {
            DeathMonitor(ComponentName name, IBinder service) {
                mName = name;
                mService = service;
            }

            public void binderDied() {
                death(mName, mService);
            }

            final ComponentName mName;
            final IBinder mService;
        }
    }
}