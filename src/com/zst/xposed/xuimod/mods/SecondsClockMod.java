/*
 * Copyright (C) 2013 XuiMod
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

package com.zst.xposed.xuimod.mods;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import com.zst.xposed.xuimod.Common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.Html;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class SecondsClockMod {
    public static TextView thix; //Reference to StatusBar clock
	public static boolean enabled = false; //Clock Enabled?
	public static boolean bold = false; //clock bold?
	public static CharSequence format = null; // Format of Clock
	public static boolean stopForever = false; // stop until systemui restarts
	private static boolean allowHtml = false; //Allow HTML tags to be used?
	private static int clockSizePercentage = -1;
	private static float clockSizeFromSystem = -1;
	
	private static final int LETTER_DEFAULT = 0;
	private static final int LETTER_LOWERCASE = 1;
	private static final int LETTER_UPPERCASE = 2;
	private static int letterCaseType = LETTER_DEFAULT;
	
	static XSharedPreferences pref; 
	static Calendar mCalendar; 
	static Handler mHandler; 
	static Runnable mTicker ;

	public static void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
    	if (!lpparam.packageName.equals("com.android.systemui")) return;
    	
    	pref = new XSharedPreferences(Common.MY_PACKAGE_NAME);
    	if (!pref.getBoolean(Common.KEY_SECONDS_MASTER_SWITCH, Common.DEFAULT_SECONDS_MASTER_SWITCH)) {
    		return;
    	}
    	
		try{
			hookClock(lpparam);
		}catch(Throwable t){
		}
	}
	private static void hookClock(final LoadPackageParam lpparam){
		findAndHookMethod("com.android.systemui.statusbar.policy.Clock", lpparam.classLoader, "updateClock", new XC_MethodHook() {
    		@Override
    		protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    			//only the last Clock TextView will be set(statusbar). 
    			//The notification panel and lockscreen also use Clock class. But they are not visible and handler will screw up 
    			if (thix == null){
    			thix = (TextView)param.thisObject; 
    			clockSizeFromSystem = thix.getTextSize();
    			if(init()){ //init() will return TRUE when setting is enabled.
    				start(); //Start the seconds handler
    			}
    			}
    			if (!enabled){
    				customSettingWhenDisabled();
    			}else{
    				tick(false);
    			}
    			IntentFilter filter = new IntentFilter();
    			filter.addAction(Common.ACTION_SETTINGS_CHANGED);
    			thix.getContext().registerReceiver(broadcastReceiver, filter);
    			//else tick() is to apply our format IMMEDIATELY when the clock refreshes every second
    			//fixes the skipping bug from 59sec to 01 sec
    		}
    	});
	}
	
	private static boolean init(){ // get all the values
		if (stopForever) return false; //Don't continue
		pref.reload();
		enabled = pref.getBoolean(Common.KEY_SECONDS_ENABLE,Common.DEFAULT_SECONDS_ENABLE);
		bold = pref.getBoolean(Common.KEY_SECONDS_BOLD,Common.DEFAULT_SECONDS_BOLD);
		allowHtml = pref.getBoolean(Common.KEY_SECONDS_USE_HTML,Common.DEFAULT_SECONDS_USE_HTML);
		letterCaseType = Integer.parseInt( pref.getString(Common.KEY_SECONDS_LETTER_CASE, 
				Common.DEFAULT_SECONDS_LETTER_CASE) );
		clockSizePercentage = pref.getInt(Common.KEY_SECONDS_SIZE, Common.DEFAULT_SECONDS_SIZE);
		thix.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
		if(!enabled){ 
			stopForever = true; //Stop forever until systemUI reboots. Prevents reading too much off the disk(every minute which is bad)
			return false; 
		}
		setFormat(true);
		return enabled;
	}
	
	private static void start() { // start handlers
		mHandler = new Handler(thix.getContext().getMainLooper());
	    mTicker = new Runnable() {
	        public void run() {
	        	if (enabled){ // must check if enabled before you update seconds
	        		tickOnThread(); 
	        		waitOneSecond();
	        	}
	        }
	    };
	    mHandler.postDelayed(mTicker, 800); // Initial wait only. This will never be called again.
	}
	
	private static void setFormat(boolean seconds_enabled){
		format = pref.getString(Common.KEY_SECONDS_CUSTOM,"");
		if (!seconds_enabled)return;
		if (format.equals("")){
			boolean is24hr =  DateFormat.is24HourFormat(thix.getContext()) ;
			format = (is24hr ? "kk:mm:ss" /*24 hr*/ : "hh:mm:ss a" /*12 hr*/);
		}	
	}
	private static void waitOneSecond() { 
		mHandler.postDelayed(mTicker, 990);//wait 1 sec (slightly less to overlap the lag)	
	}
	
	private static void tickOnThread() { // A new second, get the time
		final Thread thread = new Thread(new Runnable(){
			@Override
			public void run() {
				tick(true);
			}
		});
		thread.start();	
	}
	
	private static void tick(boolean changeTextWithHandler) { // A new second, get the time
		mCalendar = Calendar.getInstance(TimeZone.getDefault());
		if (format == null) return;
		CharSequence time = DateFormat.format(format, mCalendar);
		if (letterCaseType == LETTER_LOWERCASE) {
			time = time.toString().toLowerCase(Locale.ENGLISH);
		} else if (letterCaseType == LETTER_UPPERCASE) {
			time = time.toString().toUpperCase(Locale.ENGLISH);
		}
		CharSequence clockText = allowHtml ? Html.fromHtml(time.toString().replace("\\n", "<br>")) : time;
		if (changeTextWithHandler){
			setClockTextOnHandler(clockText);
		}else{
			thix.setText(clockText);
		}
		
		final float newClockSize = clockSizeFromSystem * (clockSizePercentage * 0.01f);
		if (changeTextWithHandler) {
			setClockSizeOnHandler(newClockSize);
		} else {
			thix.setTextSize(TypedValue.COMPLEX_UNIT_PX, newClockSize);
			thix.setSingleLine(false);
			thix.setLines(4);
			// Increase Max Lines from default 1
		}
	}
	
	private static void setClockTextOnHandler(final CharSequence time) {
		if (mHandler == null) {
			mHandler = new Handler(thix.getContext().getMainLooper());
		} 
		mHandler.post(new Runnable() {
	        public void run() {
	    		thix.setText(time);
	        }
	    });
	}
	
	private static void setClockSizeOnHandler(final float newClockSize) {
		if (mHandler == null) {
			mHandler = new Handler(thix.getContext().getMainLooper());
		} 
		mHandler.post(new Runnable() {
	        public void run() {
	        	thix.setTextSize(TypedValue.COMPLEX_UNIT_PX, newClockSize);
	        }
	    });
	}
	
	private static void customSettingWhenDisabled(){ //Change Clock Format even when seconds disabled
		if (format == null) setFormat(false);
		if (!format.equals("")) tick(false); // If the setting is not empty, then use our custom format
		}
	
	private final static BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (Common.ACTION_SETTINGS_CHANGED.equals(action)){
				Context ctx = thix.getContext();
				mHandler = null;
				mTicker = null;
				enabled = false;
				stopForever = false;
				format = null;
				//Reset all the variables
				Intent i = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
				ctx.sendBroadcast(i);
				if(init()){ //init() will return TRUE when setting is enabled.
    				start(); //Start the seconds handler
    			}
				/* 
				 * Broadcast to system that time changed.
				 */
			}
		}
	};
}
