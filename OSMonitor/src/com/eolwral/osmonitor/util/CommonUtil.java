package com.eolwral.osmonitor.util;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.eolwral.osmonitor.OSMonitorService;
import com.eolwral.osmonitor.ipc.IpcService;
import com.eolwral.osmonitor.settings.Settings;

public class CommonUtil {
  
  /**
  * predefine location for osmcore
  */
  private final static String binaryName = "libosmcore.so";
  
  /**
   * bring up help activity
   * @param context  
   * @param url 
   */
  @SuppressLint("SetJavaScriptEnabled")
  public static void showHelp(Context context, String url)
  {
  	Intent intent = new Intent(Intent.ACTION_VIEW);
  	intent.setData(Uri.parse(url));
  	context.startActivity(intent);
  }
  
  /**
   * kill a process 
   * @param pid
   * @param context
   */
  public static void killProcess(int pid, Context context) {
	  final Settings settings = Settings.getInstance(context);
	  if (!settings.isRoot()) 
		  android.os.Process.killProcess(pid);
	  else
		  IpcService.getInstance().killProcess(pid);
  }


  /**
   * is ARMv7 base ?
   * @return true == yes , false == no
   */
  public static boolean isARMv7() {
    return (android.os.Build.CPU_ABI.toLowerCase().contains("armeabi-v7"));
  }
  
  /**
   * is ARM base ?
   * @return true == yes , false == no
   */
  public static boolean isARM() {
    return (android.os.Build.CPU_ABI.toLowerCase().contains("armeabi"));
  }
  
  /**
   * is MIPS base ?
   * @return true == yes, false == no
   */
  public static boolean isMIPS() {
    return (android.os.Build.CPU_ABI.toLowerCase().contains("mips"));	  
  }
  
  /**
   * is X86 base ?
   * @return true == yes, false == no
   */
  public static boolean isX86() {
	    return (android.os.Build.CPU_ABI.toLowerCase().contains("x86"));	  
	  }
  
  /**
   * check file status
   * @param file path
   * @return true == exist, false == not exist
   */
  @SuppressWarnings("unused")
  private static boolean fileExist(String localPath) {
	  File targetFile = new File(localPath);
	  return targetFile.exists();
  }
  
  /**
   * check SQLite DB status
   * @param context Context
   * @param dbName database name
   * @return true == exist, false == not exist 
   */
  public static boolean doesDatabaseExist(Context context, String dbName) {
	  File dbFile=context.getDatabasePath(dbName);
	  return dbFile.exists();
  }
  
  /**
   * execute osmcore as a binary execute
   * @param context
   * @throws InterruptedException 
   */
  public static boolean execCore(Context context) {

	if(context == null)
		return false;
	
	// lock file
	String lockfile = context.getFilesDir().getAbsolutePath()+"/"+binaryName+".lock";
	File file = new File(lockfile);
	FileChannel channel = null;
	FileLock lock = null;
	try {
		channel = new RandomAccessFile(file, "rw").getChannel();
		lock = channel.tryLock();
	} catch (Exception e) {
		return false;
	}
	
	// execute libosmcore.so
	ApplicationInfo info = context.getApplicationInfo();
	String binary = info.nativeLibraryDir+"/"+binaryName;	
	try {
		final Settings settings = Settings.getInstance(context);
		
		Process process = null;
		
		if (!settings.isRoot()) 
			process = Runtime.getRuntime().exec("sh");
		else
			process = Runtime.getRuntime().exec("su");
		
		DataOutputStream os = new DataOutputStream(process.getOutputStream());

		// !! CM11 will terminate orphan process with root permission
		if (isCyanogenMod() && isKitKat() && settings.isRoot()) { 
			os.writeBytes(binary + " " + settings.getToken().toString()+ ";exit\n");
			Thread.sleep(500);
			process.destroy();
		}
		else {
			os.writeBytes(binary + " " + settings.getToken().toString()+ " &\n");
			os.writeBytes("exit \n");
			process.waitFor();
		}

	} catch (Exception e) {
		return false;
	}
	
    // release the lock
    try {
		lock.release();
	    channel.close();
	} catch (Exception e) {}
       
	return true;
  }
  
  /**
   * detect CyanogenMod
   * @return true == CM, false == non-CM
   */
  public static boolean isCyanogenMod() {
	  String version = System.getProperty("os.version");
	  if (version.contains("cyanogenmod")) 
	      return true;
	  return false;
  }
  
  /**
   * detect KitKat
   * @return true == KitKat, false == non-KitKat
   */
  public static boolean isKitKat() {
	  final int KITKAT = 19;
	  if (Build.VERSION.SDK_INT == KITKAT)
		  return true;
	  return false;
  }
  
  /**
   * detect that Device is rooted or not
   * @return true == rooted, false == non-rooted
   */
  public static boolean preCheckRoot() {
	boolean flag = false; 
	
	try {
		Process process = Runtime.getRuntime().exec("su");
		DataOutputStream os = new DataOutputStream(process.getOutputStream());
		os.writeBytes("exit \n");
		process.waitFor();
		
		if(process.exitValue() == 0)
			flag = true;
		
	} catch (Exception e) { }
	
	return flag;
  }
  
  
  /**
   * convert data as memory 
   * @param data
   * @return a string with correct format
   *
   * Reference:
   * http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
   */
  @SuppressLint("DefaultLocale")
  public static String convertToSize(long data, boolean si) {
    int unit = si ? 1000 : 1024;
	if (data < unit) return data + " B";
	int exp = (int) (Math.log(data) / Math.log(unit));
	String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (si ? "" : "i");
	return String.format("%.1f %sB", data / Math.pow(unit, exp), pre);
  }
  
  /**
   * convert data as Usage
   * @param data
   * @return a string of float value
   */
  @SuppressLint("DefaultLocale")
  public static String convertToUsage(float data) {
	return String.format("%.1f", data); 	  
  }
  
  /**
   * convert string as Integer
   * @param string
   * @return int
   */
  public static int convertToInt(String value) {
	try {
		return Integer.parseInt(value);
	} catch(Exception e) {}
	return 0;
  }
  
  /**
   * remove string from array , if it can't be converted to int
   * @param string []
   * @return string []
   */
  public static String[] eraseNonIntegarString(String[] data) {
	  ArrayList<String> checked = new ArrayList<String>();
	  for (int index = 0; index < data.length; index++) {
		  if (convertToInt(data[index]) != 0)
			  checked.add(data[index]);
	  }
	  return checked.toArray(new String[checked.size()]);
  }
  
  /**
   * remove empty string from array
   * @param string []
   * @return string []
   */
  public static String[] eraseEmptyString(String[] data) {
	  ArrayList<String> checked = new ArrayList<String>();
	  for (int index = 0; index < data.length; index++) {
		  if(!data[index].trim().isEmpty())
			  checked.add(data[index]);
	  }
	  return checked.toArray(new String[checked.size()]);
  }
  
  /**
   * detect background service is running or not
   * @param context
   * @return running == ture, none == false
   */
  public static boolean isServiceRunning(Context context){
	final ActivityManager actMgr = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
	final List<RunningServiceInfo> services = actMgr.getRunningServices(Integer.MAX_VALUE);
	  
    for (RunningServiceInfo serviceInfo : services) {
      if (serviceInfo.service.getClassName().equals(OSMonitorService.class.getName()))
        return true;
    }
    return false;
  }
  
  /**
	 * For custom purposes. Not used by ColorPickerPreference
	 * @param color
	 * @author Charles Rosaaen
	 * @return A string representing the hex value of color,
	 * without the alpha value
	 */
   public static String convertToRGB(int color) {
       String red = Integer.toHexString(Color.red(color));
       String green = Integer.toHexString(Color.green(color));
       String blue = Integer.toHexString(Color.blue(color));

       if (red.length() == 1) {
           red = "0" + red;
       }

       if (green.length() == 1) {
           green = "0" + green;
       }

       if (blue.length() == 1) {
           blue = "0" + blue;
       }

       return "#" + red + green + blue;
   }
}
