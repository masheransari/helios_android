package com.Helios;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.client.ClientProtocolException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationServices;

public class BluetoothMonitorService extends Service 
	implements GenericBeaconUpdateReceiver, ConnectionCallbacks, OnConnectionFailedListener {

	private int NOTIFICATION_ID = 112;
	private final String TAG = "Helios_" + getClass().getSimpleName();
	private final String title = "Helios Bluetooth Monitor";
	
	// used to access UI thread for toasts
	private Handler handler = new Handler(Looper.getMainLooper());
	private Context con;

	private String mEmail;
	private String token;

	// used for location services using new Location API
	private GoogleApiClient mGoogleApiClient;
	private Location mLocation;

	KontaktBeaconManagerBridge kontaktBeaconManager;
	private Map<String, BeaconInfo> discoveredBeacons = new HashMap<String, BeaconInfo>();
	private Map<String, BeaconInfo> monitoredBeacons = new HashMap<String, BeaconInfo>();
	private Map<String, BeaconInfo> staticBeacons = new HashMap<String, BeaconInfo>();

	private GenericRangingListener mRangingListener = new GenericRangingListener(this, monitoredBeacons);
	private Map<String, Boolean> UUIDMap = Helpers.getUUIDMap();
	
	// used to log in to Amazon Web Services and create client object
	// to upload to S3 and send messages to SQS
	CognitoHelper cognitoHelperObj;

	// define BroadCastReceiver to shut the service down if Bluetooth
	// gets switched off while the service is running
    private final IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
	    @Override
	    public void onReceive(Context context, Intent intent) {
	        final String action = intent.getAction();

	        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
	            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
	                                                 BluetoothAdapter.ERROR);
	            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF)	{ 
	            	Helpers.displayToast(handler, context, "Shutting down Helios monitor", Toast.LENGTH_SHORT);
	            	Log.i(TAG, "Killing service since Bluetooth was turned off");
	            	BluetoothMonitorService.this.stopSelf();
	            }
	        }
	    }
	};

	public void onCreate(){
		con = this;
		
		mGoogleApiClient = new GoogleApiClient.Builder(this)
		.addConnectionCallbacks(this)
		.addOnConnectionFailedListener(this)
		.addApi(LocationServices.API).build();
	    // Register for broadcasts on BluetoothAdapter state change
	    registerReceiver(mReceiver, filter);
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	public int onStartCommand(Intent intent, int flags, int startID) {
		// gets email and access token from calling activity
		mEmail = intent.getStringExtra(LoginActivity.EMAIL_MSG);
		token = intent.getStringExtra(LoginActivity.TOKEN_MSG);
		String requestType = intent.getStringExtra(TokenFetcherTask.REQUEST_TYPE);
		Log.v(TAG, "onStartCommand received request " + requestType);		

		if (requestType.equals(TokenFetcherTask.REQUEST_TYPE_START)) {
			Log.i(TAG, "Started service for user " + mEmail);
			Helpers.createStopPauseNotification(title, "Stop", "Pause",
					this, BluetoothMonitorService.class, token, mEmail, NOTIFICATION_ID);
			mGoogleApiClient.connect();

	        cognitoHelperObj = new CognitoHelper(this, token);
	        cognitoHelperObj.doCognitoLogin();
			if(!Config.WiFiUploadOnly || Helpers.isWifiConnected(this))
				cognitoHelperObj.sendCognitoMessage(mEmail);
			
			new Thread(new BeaconListDownloader(this, mEmail, token)).start();
		}

		if (requestType.equals(TokenFetcherTask.REQUEST_TYPE_PAUSE)) {
			Log.i(TAG, "Paused recording for user " + mEmail);
			disconnectBeaconManager();
			Helpers.createStopPlayNotification(title, "Stop", "Monitor",
					this, BluetoothMonitorService.class, token, mEmail, NOTIFICATION_ID);
		}

		if (requestType.equals(TokenFetcherTask.REQUEST_TYPE_PLAY)) {
			Log.i(TAG, "Restarted recording for user " + mEmail);
			Helpers.createStopPauseNotification(title, "Stop", "Pause",
					this, BluetoothMonitorService.class, token, mEmail, NOTIFICATION_ID);
			// initializeBeaconManager();
			kontaktBeaconManager = new KontaktBeaconManagerBridge(this, mRangingListener, UUIDMap);
			connectBeaconManager();
		}

		if (requestType.equals(TokenFetcherTask.REQUEST_TYPE_STOP)) {
			Log.i(TAG, "Stopped service for user " + mEmail);
			stopSelf();
		}

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		disconnectBeaconManager();
		kontaktBeaconManager = null;
		if (mGoogleApiClient.isConnected())
			mGoogleApiClient.disconnect();
		unregisterReceiver(mReceiver);
	}

	
	private void disconnectBeaconManager() {
		if(kontaktBeaconManager != null)
			kontaktBeaconManager.disconnectBeaconManager();
	}

	private void connectBeaconManager() {
		try {
			kontaktBeaconManager.connectBeaconManager();
		} catch (RemoteException e) {
			showRemoteException(e);			
		}
	}

	private void showRemoteException(RemoteException e) {
		Log.e(TAG, "Unrecoverable error when connecting beacon manager " + e.getMessage());
		Helpers.showAlert(con, "Beacon error", "Unrecoverable error when connecting beacon manager");
		stopSelf();
	}
	
	// methods to implement interface GenericBeaconUpdateReceiver
	public void processStaticBeacon(BeaconInfo beaconInfo){
		updateStaticBeaconList(beaconInfo);		
	}
	
	public void processMonitoredBeacon(BeaconInfo beaconInfo) {
		String beaconID, beaconUniqueId;
		String prox, text;

		if (mGoogleApiClient.isConnected())
			mLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
		else
			mLocation = null;

		beaconInfo.setLocation(mLocation);
		beaconID = beaconInfo.getBeaconUniqueKey();
		prox = beaconInfo.getProximity();
		beaconUniqueId = beaconInfo.getBeaconUniqueId();

		text = "Discovered beacon name " + beaconUniqueId;
		Log.v(TAG, text + " " + beaconID + " " + prox);

		String friendlyName = monitoredBeacons.get(beaconID).friendlyName;

		if (isBeaconUploadable(beaconInfo)) {
			beaconInfo.friendlyName = friendlyName;
			Log.i(TAG, "Inserted " + beaconUniqueId + " " + beaconID + " " + prox);
			discoveredBeacons.put(beaconID, beaconInfo);
			new BeaconUploader(this, mEmail, token, cognitoHelperObj, beaconInfo, staticBeacons,
					System.currentTimeMillis(), Config.WiFiUploadOnly).execute();
		}
	}

	private Map<String, BeaconInfo> updateStaticBeaconList(BeaconInfo beacon){
		String uniqueKey = beacon.getBeaconUniqueKey();
		
		Log.v(TAG, beacon.getBeaconUniqueId() + " is a static beacon");
		staticBeacons.put(uniqueKey, beacon);
		// if there are now more than 3 beacons in the map, we throw out 
		// the oldest beacon observation in the map and put this one in
		Long minTimestamp = Long.MAX_VALUE;
		Long currentTimestamp;
		String minKey = "";
		
		if(staticBeacons.size() > 3){
			for(String observedStaticBeacon: staticBeacons.keySet()){
				currentTimestamp = staticBeacons.get(observedStaticBeacon).getTimestamp();
				if(currentTimestamp < minTimestamp){
					minTimestamp = currentTimestamp;
					minKey = observedStaticBeacon;
				}
			} // for
			Log.v(TAG, "Removed " + minKey + " from static beacon map");
			staticBeacons.remove(minKey);
		}
		return staticBeacons;
	}
	
	private boolean isBeaconUploadable(BeaconInfo newBeaconInfo) {
		// decides whether a beacon observation should be uploaded to S3 or not
		// depending on when it was last seen
		String beaconID = newBeaconInfo.getBeaconUniqueKey();
		BeaconInfo beaconObs = discoveredBeacons.get(beaconID);

		// if we haven't seen this beacon before, upload it
		if (beaconObs == null)
			return true;

		double dist = beaconObs.calcDistance(newBeaconInfo);

		if (newBeaconInfo.matchProximity(beaconObs)) {
			// if we have moved more than 10 metres and the proximity is unch,
			// object might have moved
			if (dist > 10)
				return true;
			else
				return false;
		} else {
			// proximity indicator has changed
			// we assume if proximity indicator changes and the distance also changed
			// we have moved but object has not so no need to upload its location
			if (dist > 10)
				return false;
			else {
				// proximity indicator has changed but our location has not
				// either object has moved or there is some noise in the
				// proximity indicator
				// TODO: this should be some moving avg of proximity indicator
				// rather than a timestamp
				if (beaconObs.calcTimeDiff(newBeaconInfo) > 30000)
					return true;
				else
					return false;
			}
		}
	}
	/*
	 * Callback methods for Google Location Services
	 */

	@Override
	public void onConnected(Bundle connectionHint) {
		Log.v(TAG, "Location services connected");
	}

	/*
	 * Called by Google Play services if the connection to GoogleApiClient drops
	 * because of an error.
	 */
	public void onDisconnected() {
		Log.v(TAG, "Disconnected. Location information no longer available.");
	}

	@Override
	public void onConnectionSuspended(int cause) {
		// The connection to Google Play services was lost for some reason. We
		// call connect() to
		// attempt to re-establish the connection.
		Log.i(TAG, "Location services connection suspended");
		mGoogleApiClient.connect();
	}

	@Override
	public void onConnectionFailed(ConnectionResult connectionResult) {
		/*
		 * we simply notify user that Locations will not be recorded
		 */
		Log.e(TAG, "Error when connecting to Location Services "
				+ connectionResult.getErrorCode()
				+ " Location services not available");
		Helpers.displayToast(handler, con, "Location services not available",
				Toast.LENGTH_LONG);
	}

	// Inner class to download list of beacons for a given user
	private class BeaconListDownloader implements Runnable {
		// must be called after logging in via Cognito

		private final String TAG_DOWNLOAD = "Helios_" + getClass().getSimpleName();
		private final String mEmail;
		private final String mToken;
		private Context parent;
		
		BeaconListDownloader(Context parentActivity, String email, String token) {
			mEmail = email;
			mToken = token;
			parent = parentActivity;
		}

		public void run() {
			String KEY_PREFIX = "";
			try{
				KEY_PREFIX = cognitoHelperObj.getIdentityID();
			}
			catch(Exception e){
				Helpers.displayToast(handler, con, "Unrecoverable error when logging into Amazon cognito", Toast.LENGTH_LONG);
				Log.d(TAG, "Cognito Login error - " + e.getMessage());
				BluetoothMonitorService.this.stopSelf();
			}
			
			try{
				downloadListFromRDS();
			} catch(JSONException jse){
				Log.d(TAG, "Error parsing list of beacons to monitor - " + jse.getMessage());
				BluetoothMonitorService.this.stopSelf();				
			}

			// initialize beacon manager on UI thread because Kontakt.io SDK requires this
			handler.post(new Runnable() {
				public void run() {
					kontaktBeaconManager = new KontaktBeaconManagerBridge(parent, mRangingListener, UUIDMap);
					connectBeaconManager();
				}
			});
		}
		
		private void downloadListFromRDS() throws JSONException{
			String uniqueID, friendlyName;
			int major, minor;
			
			JSONObject serverResponse = getBeaconList();
			
			JSONArray beaconArray = serverResponse.getJSONArray("Beacons");
			for(int i = 0; i < beaconArray.length(); i++){
				JSONObject beacon = beaconArray.getJSONObject(i);
				
				uniqueID = beacon.getString("Unique_id");
				friendlyName = beacon.getString("Friendly_name");;
				major = beacon.getInt("Major_id");
				minor = beacon.getInt("Minor_id");
				
				BeaconInfo beac = new BeaconInfo(uniqueID, major, minor, friendlyName);
				monitoredBeacons.put(beac.getBeaconUniqueKey(), beac);

				Log.i(TAG_DOWNLOAD,
						"Monitoring beacon " + uniqueID + " " + major + " "
								+ minor + " " + friendlyName);
				Log.i(TAG_DOWNLOAD, "Added beacon " + beac.getBeaconUniqueKey());				
			}			
		}
		
		private JSONObject getBeaconList(){
			URL url;
			HttpURLConnection conn;
			try {
				url = new URL(Config.BEACON_LIST_DOWNLOAD_POST_TARGET);
				conn = (HttpURLConnection) url.openConnection();
			} catch (MalformedURLException mue) {
				Log.w(TAG, "Malformed URL Exception " + mue.getMessage());
				return null;
			} catch (IOException e) {
				Log.w(TAG, "Network Exception when POSTing beacon data " + e.getMessage());
				return null;
			}
			// connection was opened successfully if we got here

			try {			
				JSONObject requestObj = getRequestObj();
				String payloadObj = requestObj.toString();

				conn.setDoOutput(true);
				conn.setRequestMethod("POST");  
				conn.setFixedLengthStreamingMode(payloadObj.getBytes().length);
				conn.setRequestProperty("Content-Type","application/json");   

				OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream());
				Log.v(TAG, "Sending data now");
				osw.write(payloadObj.toString());
				osw.flush();
				osw.close();
				Log.v(TAG, "Response status code is " + conn.getResponseCode());
		    	StringBuffer jb = new StringBuffer();
		    	  String line = null;
		    	  try {
		    	    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
		    	    while ((line = reader.readLine()) != null)
		    	      jb.append(line);
					reader.close();
		    	  } catch (Exception e) { 
		      	    Log.w(TAG, "Error reading request string" + e.toString());
		    	  }
		    	JSONObject response = new JSONObject(jb.toString());	    	
				Log.i(TAG, "Response string from DBServlet is " + response.getString("Beacons"));
				return response;

			} catch (ClientProtocolException e) {
				Log.w(TAG, "Protocol Exception when downloading beacon data " + e.getMessage());
				return null;
			} catch (IOException e) {
				Log.w(TAG, "Network Exception when downloading beacon data " + e.getMessage());
				return null;
			} catch (Exception e) {
				Log.w(TAG, "Exception when downloading beacon data " + e.getMessage());
				return null;
			} finally {
				conn.disconnect();
			}	
		}

		private JSONObject getRequestObj() throws JSONException{
			// create request object to ask servlet to send list of beacons
			JSONObject requestObj = new JSONObject();
			
			requestObj.put("Email", mEmail);
			requestObj.put("Token", token);
			requestObj.put("query", "get_beacons");
			
			return requestObj;
		}
	}

}
