package com.google.android.diskusage;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import android.app.Activity;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.os.Build;
import android.os.RemoteException;
import android.os.StatFs;
import android.util.Log;

public class Apps2SDLoader {

  private Activity activity;
  private int numLookups;

  public Apps2SDLoader(Activity activity) {
    this.activity = activity;
  }
  
  private synchronized void changeNumLookups(int change) {
    numLookups += change;
  }
  
  private Map<String, Long> getDfSizes() {
    TreeMap<String, Long> map = new TreeMap<String, Long>();
    try {
      // FIXME: debug
      BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"));
//      BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(file.getBytes())));
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(" +");
        if (parts.length < 3) continue;
        String mountPoint = parts[1];
        String fsType = parts[2];
        if (fsType.equals("tmpfs")) {
          continue;
        }
        if (!mountPoint.startsWith("/mnt/asec/")) {
          continue;
        }
        String packageNameNum = mountPoint.substring(mountPoint.lastIndexOf('/') + 1);
        String packageName = packageNameNum.substring(0, packageNameNum.indexOf('-'));
        StatFs stat = new StatFs(mountPoint);
        long size = (stat.getBlockCount() - stat.getAvailableBlocks()) * stat.getBlockSize();
        map.put(packageName, size);
        Log.d("diskusage", "external size (" + packageName + ") = " + size / 1024 + " kb");
      }
    } catch (Throwable t) {
      Log.e("disksusage", "failed to parse /proc/mounts", t);
    }
    return map;
  }
  
  public FileSystemEntry[] load(boolean sdOnly, final AppFilter appFilter, final int blockSize) throws Throwable {
    final Map<String, Long> dfSizes = getDfSizes();
    final ArrayList<FileSystemEntry> entries = new ArrayList<FileSystemEntry>();

    PackageManager pm = activity.getPackageManager();
    Method getPackageSizeInfo = pm.getClass().getMethod(
        "getPackageSizeInfo", String.class, IPackageStatsObserver.class);
    for (final PackageInfo info : pm.getInstalledPackages(
        PackageManager.GET_META_DATA | PackageManager.GET_UNINSTALLED_PACKAGES)) {
//      Log.d("diskusage", "Got package: " + info.packageName);
      if (info.applicationInfo == null) {
        Log.d("diskusage", "No applicationInfo");
        continue;
      }
      int flag = 0x40000; // ApplicationInfo.FLAG_EXTERNAL_STORAGE
      boolean on_sdcard = (info.applicationInfo.flags & flag) != 0;
      if (on_sdcard || !sdOnly) {
        changeNumLookups(1);
        final String pkg = info.packageName;
        final String name = pm.getApplicationLabel(info.applicationInfo).toString();
        getPackageSizeInfo.invoke(pm, pkg, new IPackageStatsObserver.Stub() {

          @Override
          public void onGetStatsCompleted(PackageStats pStats, boolean succeeded)
          throws RemoteException {
            synchronized (Apps2SDLoader.this) {
              changeNumLookups(-1);
              if (succeeded) {
                FileSystemPackage p = new FileSystemPackage(
                    name, pkg, pStats, info.applicationInfo.flags, dfSizes.get(pkg), blockSize);
                p.applyFilter(appFilter, blockSize);
                entries.add(p);
//                Log.i("diskusage", "codeSize: " + pStats.codeSize);
              }
              Apps2SDLoader.this.notify();
            }
          }
        });

      }
    }
    while (true) {
      synchronized (this) {
        if (numLookups != 0) {
          wait();
        } else {
          break;
        }
      }
    }
    if (entries.size() == 0) return null;
    FileSystemEntry[] result = entries.toArray(new FileSystemEntry[] {});
    Arrays.sort(result, FileSystemEntry.COMPARE);
    return result;
  }
}