/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.am;

import com.android.internal.os.BatteryStatsImpl;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * A running application service.
 */
class ServiceRecord extends Binder {
    final ActivityManagerService ams;
    final BatteryStatsImpl.Uid.Pkg.Serv stats;
    final ComponentName name; // service component.
    final String shortName; // name.flattenToShortString().
    final Intent.FilterComparison intent;
                            // original intent used to find service.
    final ServiceInfo serviceInfo;
                            // all information about the service.
    final ApplicationInfo appInfo;
                            // information about service's app.
    final String packageName; // the package implementing intent's component
    final String processName; // process where this component wants to run
    final String permission;// permission needed to access service
    final String baseDir;   // where activity source (resources etc) located
    final String resDir;   // where public activity source (public resources etc) located
    final String dataDir;   // where activity data should go
    final boolean exported; // from ServiceInfo.exported
    final Runnable restarter; // used to schedule retries of starting the service
    final long createTime;  // when this service was created
    final HashMap<Intent.FilterComparison, IntentBindRecord> bindings
            = new HashMap<Intent.FilterComparison, IntentBindRecord>();
                            // All active bindings to the service.
    final HashMap<IBinder, ConnectionRecord> connections
            = new HashMap<IBinder, ConnectionRecord>();
                            // IBinder -> ConnectionRecord of all bound clients
    
    // Maximum number of delivery attempts before giving up.
    static final int MAX_DELIVERY_COUNT = 3;
    
    // Maximum number of times it can fail during execution before giving up.
    static final int MAX_DONE_EXECUTING_COUNT = 6;
    
    static class StartItem {
        final int id;
        final Intent intent;
        long deliveredTime;
        int deliveryCount;
        int doneExecutingCount;
        
        StartItem(int _id, Intent _intent) {
            id = _id;
            intent = _intent;
        }
    }
    final ArrayList<StartItem> deliveredStarts = new ArrayList<StartItem>();
                            // start() arguments which been delivered.
    final ArrayList<StartItem> pendingStarts = new ArrayList<StartItem>();
                            // start() arguments that haven't yet been delivered.

    ProcessRecord app;      // where this service is running or null.
    boolean isForeground;   // is service currently in foreground mode?
    int foregroundId;       // Notification ID of last foreground req.
    Notification foregroundNoti; // Notification record of foreground state.
    long lastActivity;      // last time there was some activity on the service.
    boolean startRequested; // someone explicitly called start?
    boolean stopIfKilled;   // last onStart() said to stop if service killed?
    boolean callStart;      // last onStart() has asked to alway be called on restart.
    int lastStartId;        // identifier of most recent start request.
    int executeNesting;     // number of outstanding operations keeping foreground.
    long executingStart;    // start time of last execute request.
    int crashCount;         // number of times proc has crashed with service running
    int totalRestartCount;  // number of times we have had to restart.
    int restartCount;       // number of restarts performed in a row.
    long restartDelay;      // delay until next restart attempt.
    long restartTime;       // time of last restart.
    long nextRestartTime;   // time when restartDelay will expire.

    String stringName;      // caching of toString
    
    void dumpStartList(PrintWriter pw, String prefix, List<StartItem> list, long now) {
        final int N = list.size();
        for (int i=0; i<N; i++) {
            StartItem si = list.get(i);
            pw.print(prefix); pw.print("#"); pw.print(i);
                    pw.print(" id="); pw.print(si.id);
                    if (now != 0) pw.print(" dur="); pw.print(now-si.deliveredTime);
                    if (si.deliveryCount != 0) {
                        pw.print(" dc="); pw.print(si.deliveryCount);
                    }
                    if (si.doneExecutingCount != 0) {
                        pw.print(" dxc="); pw.print(si.doneExecutingCount);
                    }
                    pw.print(" ");
                    if (si.intent != null) pw.println(si.intent.toString());
                    else pw.println("null");
        }
    }
    
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("intent={");
                pw.print(intent.getIntent().toShortString(true, false));
                pw.println('}');
        pw.print(prefix); pw.print("packageName="); pw.println(packageName);
        pw.print(prefix); pw.print("processName="); pw.println(processName);
        if (permission != null) {
            pw.print(prefix); pw.print("permission="); pw.println(permission);
        }
        long now = SystemClock.uptimeMillis();
        pw.print(prefix); pw.print("baseDir="); pw.print(baseDir);
                if (!resDir.equals(baseDir)) pw.print(" resDir="); pw.print(resDir);
                pw.print(" dataDir="); pw.println(dataDir);
        pw.print(prefix); pw.print("app="); pw.println(app);
        if (isForeground || foregroundId != 0) {
            pw.print(prefix); pw.print("isForeground="); pw.print(isForeground);
                    pw.print(" foregroundId="); pw.print(foregroundId);
                    pw.print(" foregroundNoti="); pw.println(foregroundNoti);
        }
        pw.print(prefix); pw.print("lastActivity="); pw.print(lastActivity-now);
                pw.print(" executingStart="); pw.print(executingStart-now);
                pw.print(" restartTime="); pw.println(restartTime);
        if (startRequested || lastStartId != 0) {
            pw.print(prefix); pw.print("startRequested="); pw.print(startRequested);
                    pw.print(" stopIfKilled="); pw.print(stopIfKilled);
                    pw.print(" callStart="); pw.print(callStart);
                    pw.print(" lastStartId="); pw.println(lastStartId);
        }
        if (executeNesting != 0 || crashCount != 0 || restartCount != 0
                || restartDelay != 0 || nextRestartTime != 0) {
            pw.print(prefix); pw.print("executeNesting="); pw.print(executeNesting);
                    pw.print(" restartCount="); pw.print(restartCount);
                    pw.print(" restartDelay="); pw.print(restartDelay-now);
                    pw.print(" nextRestartTime="); pw.print(nextRestartTime-now);
                    pw.print(" crashCount="); pw.println(crashCount);
        }
        if (deliveredStarts.size() > 0) {
            pw.print(prefix); pw.println("Delivered Starts:");
            dumpStartList(pw, prefix, deliveredStarts, SystemClock.uptimeMillis());
        }
        if (pendingStarts.size() > 0) {
            pw.print(prefix); pw.println("Pending Starts:");
            dumpStartList(pw, prefix, pendingStarts, 0);
        }
        if (bindings.size() > 0) {
            Iterator<IntentBindRecord> it = bindings.values().iterator();
            pw.print(prefix); pw.println("Bindings:");
            while (it.hasNext()) {
                IntentBindRecord b = it.next();
                pw.print(prefix); pw.print("* IntentBindRecord{");
                        pw.print(Integer.toHexString(System.identityHashCode(b)));
                        pw.println("}:");
                b.dumpInService(pw, prefix + "  ");
            }
        }
        if (connections.size() > 0) {
            pw.print(prefix); pw.println("All Connections:");
            Iterator<ConnectionRecord> it = connections.values().iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                pw.print(prefix); pw.print("  "); pw.println(c);
            }
        }
    }

    ServiceRecord(ActivityManagerService ams,
            BatteryStatsImpl.Uid.Pkg.Serv servStats, ComponentName name,
            Intent.FilterComparison intent, ServiceInfo sInfo, Runnable restarter) {
        this.ams = ams;
        this.stats = servStats;
        this.name = name;
        shortName = name.flattenToShortString();
        this.intent = intent;
        serviceInfo = sInfo;
        appInfo = sInfo.applicationInfo;
        packageName = sInfo.applicationInfo.packageName;
        processName = sInfo.processName;
        permission = sInfo.permission;
        baseDir = sInfo.applicationInfo.sourceDir;
        resDir = sInfo.applicationInfo.publicSourceDir;
        dataDir = sInfo.applicationInfo.dataDir;
        exported = sInfo.exported;
        this.restarter = restarter;
        createTime = lastActivity = SystemClock.uptimeMillis();
    }

    public AppBindRecord retrieveAppBindingLocked(Intent intent,
            ProcessRecord app) {
        Intent.FilterComparison filter = new Intent.FilterComparison(intent);
        IntentBindRecord i = bindings.get(filter);
        if (i == null) {
            i = new IntentBindRecord(this, filter);
            bindings.put(filter, i);
        }
        AppBindRecord a = i.apps.get(app);
        if (a != null) {
            return a;
        }
        a = new AppBindRecord(this, i, app);
        i.apps.put(app, a);
        return a;
    }

    public void resetRestartCounter() {
        restartCount = 0;
        restartDelay = 0;
        restartTime = 0;
    }
    
    public StartItem findDeliveredStart(int id, boolean remove) {
        final int N = deliveredStarts.size();
        for (int i=0; i<N; i++) {
            StartItem si = deliveredStarts.get(i);
            if (si.id == id) {
                if (remove) deliveredStarts.remove(i);
                return si;
            }
        }
        
        return null;
    }
    
    public void postNotification() {
        if (foregroundId != 0 && foregroundNoti != null) {
            // Do asynchronous communication with notification manager to
            // avoid deadlocks.
            final String localPackageName = packageName;
            final int localForegroundId = foregroundId;
            final Notification localForegroundNoti = foregroundNoti;
            ams.mHandler.post(new Runnable() {
                public void run() {
                    INotificationManager inm = NotificationManager.getService();
                    if (inm == null) {
                        return;
                    }
                    try {
                        int[] outId = new int[1];
                        inm.enqueueNotification(localPackageName, localForegroundId,
                                localForegroundNoti, outId);
                    } catch (RuntimeException e) {
                        Slog.w(ActivityManagerService.TAG,
                                "Error showing notification for service", e);
                        // If it gave us a garbage notification, it doesn't
                        // get to be foreground.
                        ams.setServiceForeground(name, ServiceRecord.this,
                                localForegroundId, null, true);
                    } catch (RemoteException e) {
                    }
                }
            });
        }
    }
    
    public void cancelNotification() {
        if (foregroundId != 0) {
            // Do asynchronous communication with notification manager to
            // avoid deadlocks.
            final String localPackageName = packageName;
            final int localForegroundId = foregroundId;
            ams.mHandler.post(new Runnable() {
                public void run() {
                    INotificationManager inm = NotificationManager.getService();
                    if (inm == null) {
                        return;
                    }
                    try {
                        inm.cancelNotification(localPackageName, localForegroundId);
                    } catch (RuntimeException e) {
                        Slog.w(ActivityManagerService.TAG,
                                "Error canceling notification for service", e);
                    } catch (RemoteException e) {
                    }
                }
            });
        }
    }
    
    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ServiceRecord{")
            .append(Integer.toHexString(System.identityHashCode(this)))
            .append(' ').append(shortName).append('}');
        return stringName = sb.toString();
    }
}
