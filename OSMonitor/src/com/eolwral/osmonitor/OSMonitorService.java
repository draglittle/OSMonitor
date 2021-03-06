package com.eolwral.osmonitor;

import java.util.Locale;

import com.eolwral.osmonitor.core.OsInfo.osInfo;
import com.eolwral.osmonitor.core.ProcessInfo.processInfo;
import com.eolwral.osmonitor.ipc.IpcService;
import com.eolwral.osmonitor.ipc.IpcService.ipcClientListener;
import com.eolwral.osmonitor.ipc.IpcMessage.ipcAction;
import com.eolwral.osmonitor.ipc.IpcMessage.ipcData;
import com.eolwral.osmonitor.ipc.IpcMessage.ipcMessage;
import com.eolwral.osmonitor.util.CommonUtil;
import com.eolwral.osmonitor.util.ProcessUtil;
import com.eolwral.osmonitor.settings.Settings;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.widget.RemoteViews;

public class OSMonitorService extends Service 
                              implements ipcClientListener 
{
	private static final int NOTIFYID = 20100811;
	private IpcService ipcService = null;
	private int UpdateInterval = 2; 
	  
	private boolean isRegistered = false;
	private NotificationManager nManager = null;
	private NotificationCompat.Builder nBuilder = null; 
	
	// process   
	private int iconColor = 0;
	private int fontColor = 0;
	private boolean isSetTop = false;
	private float cpuUsage = 0;
	private float [] topUsage = new float[3];
	private String [] topProcess = new String[3];
	
	// memory
	private long memoryTotal = 0;
	private long memoryFree = 0;

	// battery
	private boolean useCelsius = false;
	private int battLevel = 0;  // percentage value or -1 for unknown
	private int temperature = 0;

	//private  
	private ProcessUtil infoHelper = null;
	private Settings settings = null;

	@Override
	public IBinder onBind(Intent intent) {
		return null; 
    }

    @Override
    public void onCreate() {
    	
    	super.onCreate();

    	settings = Settings.getInstance(this);

		IpcService.Initialize(this);
		ipcService = IpcService.getInstance();
  		
    	refreshSettings();
    	initializeNotification();

   		if(settings.isEnableCPUMeter()) {
   	    	infoHelper = ProcessUtil.getInstance(this, false);
    		initService();
   		}
    }

	private void refreshSettings() {

    	switch(settings.getCPUMeterColor()) {
    	case 1:
    		iconColor = R.drawable.ic_cpu_graph_green;
    		break;
    	case 2:
    		iconColor = R.drawable.ic_cpu_graph_blue;
    		break;
    	}
    	
    	fontColor = settings.getNotificationFontColor();
    	isSetTop = settings.isNotificationOnTop();
    	useCelsius = settings.isUseCelsius();
	}
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    	
    	if(isRegistered)
    		endService();

    	endNotification();
    } 

    private void endNotification() {
    	nManager.cancel(NOTIFYID);
    	stopForeground(true);
    }
    
	private void initializeNotification() { 
		
		Intent notificationIntent = new Intent(this, OSMonitor.class);
		notificationIntent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent contentIntent = PendingIntent.getActivity(this.getBaseContext(), 0, notificationIntent, 0);

		nManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		
		nBuilder = new NotificationCompat.Builder(this);
    	nBuilder.setContentTitle(getResources().getText(R.string.ui_appname));
    	nBuilder.setContentText(getResources().getText(R.string.ui_shortcut_detail));
		nBuilder.setOnlyAlertOnce(true);
		nBuilder.setOngoing(true);
		nBuilder.setContentIntent(contentIntent);
		nBuilder.setSmallIcon(R.drawable.ic_launcher);
		
		if(isSetTop)
			nBuilder.setPriority(1000);
		
		Notification osNotification = nBuilder.build();
		nManager.notify(NOTIFYID, osNotification);
		
		// set foreground to avoid recycling
		startForeground(NOTIFYID, osNotification);

	}

    private BroadcastReceiver mReceiver = new BroadcastReceiver() 
    {
    	public void onReceive(Context context, Intent intent) {
    		
    		if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) 
    			goSleep();
    		
    		
    		if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) 
    			wakeUp();
    	}
    };
    
    private void registerScreenEvent() {
		IntentFilter filterScreenON = new IntentFilter(Intent.ACTION_SCREEN_ON);
		registerReceiver(mReceiver, filterScreenON);

		IntentFilter filterScreenOFF = new IntentFilter(Intent.ACTION_SCREEN_OFF);
		registerReceiver(mReceiver, filterScreenOFF);
	}
    
    private void initService()
    {
    	if(!isRegistered)
    	{
			registerScreenEvent();
    		isRegistered = true;
    	}

    	wakeUp();
    }

    private void endService()
    {
    	if(isRegistered)
    	{
    		unregisterReceiver(mReceiver);
    		isRegistered = false;
    	}
    	
    	goSleep();

    	ipcService.disconnect();
    }

    private void wakeUp() {
		UpdateInterval = settings.getInterval();
       	ipcAction newCommand[] = { ipcAction.PROCESS, ipcAction.OS };
		ipcService.removeRequest(this);
    	ipcService.addRequest(newCommand, 0, this);
    	startBatteryMonitor();
	} 
	
	private void goSleep() {
		ipcService.removeRequest(this);
    	stopBatteryMonitor();
	}
    
	
    private void startBatteryMonitor()
    {
    	if(!isRegisterBattery) {
    		IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    		registerReceiver(battReceiver, battFilter);
    		isRegisterBattery = true;
    	}
    }
    
    private void stopBatteryMonitor()
    {
    	if(isRegisterBattery) {
    		unregisterReceiver(battReceiver);
    		isRegisterBattery = false;
    	}
    }

	private boolean isRegisterBattery = false;

    private BroadcastReceiver battReceiver = new BroadcastReceiver() 
	{
		public void onReceive(Context context, Intent intent) {
			
			int rawlevel = intent.getIntExtra("level", -1);
			int scale = intent.getIntExtra("scale", -1);
			
			temperature = intent.getIntExtra("temperature", -1);

			if (rawlevel >= 0 && scale > 0) {
				battLevel = (rawlevel * 100) / scale;
			}
		}
	};

	@Override
	public void onRecvData(ipcMessage result) {
		
		if(result == null) {
			ipcAction newCommand[] = { ipcAction.PROCESS, ipcAction.OS };
			ipcService.addRequest(newCommand, UpdateInterval, this);
			return;
		}
		
		// gather data
		cpuUsage = 0;
		
		// empty
		for (int index = 0; index < 3; index++) {
			topUsage[index] = 0;
			topProcess[index] = "";
		}
		
		for (int index = 0; index < result.getDataCount(); index++) {
			try {
				ipcData rawData = result.getData(index);
				
				if (rawData.getAction() == ipcAction.OS){
					osInfo info = osInfo.parseFrom(rawData.getPayload(0));
					memoryFree = info.getFreeMemory()+info.getBufferedMemory()+info.getCachedMemory();
					memoryTotal =  info.getTotalMemory();
				}
				
				if (rawData.getAction() != ipcAction.PROCESS)
					continue;
				
				for (int count = 0; count < rawData.getPayloadCount(); count++) {
					processInfo item = processInfo.parseFrom(rawData.getPayload(count));
					cpuUsage += item.getCpuUsage();
					for(int check = 0; check < 3; check++) {
						if(topUsage[check] < item.getCpuUsage()) {
							
							for(int push = 2; push > check; push--) {
								topUsage[push] = topUsage[push-1];
								topProcess[push] = topProcess[push-1];
							}
							
							// check cached status
							if (!infoHelper.checkPackageInformation(item.getName())) {
								if(item.getName().toLowerCase(Locale.getDefault()).contains("osmcore")) 
									infoHelper.doCacheInfo(android.os.Process.myUid(), item.getOwner(), item.getName());
								else
									infoHelper.doCacheInfo(item.getUid(), item.getOwner(), item.getName());
							}
							
							topUsage[check] = item.getCpuUsage();
							topProcess[check] = infoHelper.getPackageName(item.getName());
							break;
						}
					}
				}
				
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		refreshNotification();
		
		// send command again
		ipcAction newCommand[] = { ipcAction.PROCESS, ipcAction.OS };
		ipcService.addRequest(newCommand, UpdateInterval, this);
	}

	private void refreshNotification() {

		Notification osNotification = nBuilder.build();
   	    osNotification.contentView = new RemoteViews(getPackageName(),  R.layout.ui_notification);

		if (useCelsius) 
			osNotification.contentView.setTextViewText(R.id.notification_bat, "BAT: "+battLevel+"% ("+temperature/10+"\u2103)" );
		else 
			osNotification.contentView.setTextViewText(R.id.notification_bat,  "BAT: "+battLevel+"% ("+((int)temperature/10*9/5+32)+"\u2109)");

		osNotification.contentView.setTextViewText(R.id.notification_mem, "MEM: "+CommonUtil.convertToSize(memoryFree, true));

		osNotification.contentView.setTextViewText(R.id.notification_cpu,"CPU: "+CommonUtil.convertToUsage(cpuUsage) + "%");

		osNotification.contentView.setTextViewText(R.id.notification_top1st,  CommonUtil.convertToUsage(topUsage[0]) + "% "  + topProcess[0] );
		osNotification.contentView.setTextViewText(R.id.notification_top2nd,  CommonUtil.convertToUsage(topUsage[1]) + "% "  + topProcess[1]);
		osNotification.contentView.setTextViewText(R.id.notification_top3nd, CommonUtil.convertToUsage(topUsage[2]) + "% "  + topProcess[2]);
		
		// use custom color
		if(fontColor != -1) {
			osNotification.contentView.setTextColor(R.id.notification_bat, fontColor);
			osNotification.contentView.setTextColor(R.id.notification_mem,  fontColor);
			osNotification.contentView.setTextColor(R.id.notification_cpu, fontColor);
			osNotification.contentView.setTextColor(R.id.notification_top1st, fontColor);
			osNotification.contentView.setTextColor(R.id.notification_top2nd, fontColor);
			osNotification.contentView.setTextColor(R.id.notification_top3nd, fontColor);
		}

		osNotification.contentView.setProgressBar(R.id.notification_cpu_bar, 100, (int) cpuUsage, false);
		osNotification.contentView.setProgressBar(R.id.notification_mem_bar, (int) memoryTotal, (int) (memoryTotal - memoryFree), false);
		osNotification.contentView.setProgressBar(R.id.notification_bat_bar, 100, (int) battLevel, false);
		
		osNotification.icon = iconColor;
		if (cpuUsage < 20)
			osNotification.iconLevel = 1;
		else if (cpuUsage < 40)
			osNotification.iconLevel = 2;
		else if (cpuUsage < 60)
			osNotification.iconLevel = 3;
		else if (cpuUsage < 80)
			osNotification.iconLevel = 4;
		else if (cpuUsage < 100)
			osNotification.iconLevel = 5;
		else
			osNotification.iconLevel = 6;
		
		nManager.notify(NOTIFYID, osNotification);
	}
	
}
