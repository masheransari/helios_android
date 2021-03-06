
package com.Helios;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.GooglePlayServicesAvailabilityException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

public class LoginActivity extends Activity {
    private final String TAG = "Helios_" + getClass().getSimpleName(); 
			
    private TextView mOut;
    
    private static final int REQUEST_CODE_PICK_ACCOUNT = 1000;
    private static final int REQUEST_CODE_RECOVER_FROM_AUTH_ERROR = 1001;
    private static final int REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR = 1002;
	private static final int REQUEST_CODE_ENABLE_BLUETOOTH = 1003;

	public static final String TOKEN_MSG = "ACCESS_TOKEN";
	public static final String EMAIL_MSG = "USERNAME";

	private String mEmail;
    private String mToken;
    
    private Helpers.ActivityType activityType;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.main);
        mOut = (TextView) findViewById(R.id.message);        
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_ACCOUNT) {
            if (resultCode == RESULT_OK) {
                mEmail = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                getUsername();
            } else if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, "You must pick an account", Toast.LENGTH_SHORT).show();
            }
            return;
        } 
        
        if ((requestCode == REQUEST_CODE_RECOVER_FROM_AUTH_ERROR ||
                requestCode == REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR)
                && resultCode == RESULT_OK) {
            handleAuthorizeResult(resultCode, data);
            return;
        }
        
        if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH){
			if (resultCode == Activity.RESULT_OK) {
				Log.i(TAG, "Bluetooth was enabled. Getting username");
		    	if(isGooglePlayServicesConnected())
		    		getUsername();
			} else {
				Helpers.showAlert(this, "Bluetooth error", "Bluetooth must be enabled");
				return;
			}
        }
    }

    private void handleAuthorizeResult(int resultCode, Intent data) {
        if (data == null) {
            show("Unknown error, click the button again");
            return;
        }
        if (resultCode == RESULT_OK) {
            Log.i(TAG, "Retrying");
            new TokenFetcherTask(LoginActivity.this, mEmail, Config.SCOPE).execute();
            return;
        }
        if (resultCode == RESULT_CANCELED) {
            show("User rejected authorization.");
            return;
        }
        show("Unknown error, click the button again");
    }

    private boolean isGooglePlayServicesConnected(){
        int statusCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if (statusCode == ConnectionResult.SUCCESS) {
            return true;
        } else if (GooglePlayServicesUtil.isUserRecoverableError(statusCode)) {
            Dialog dialog = GooglePlayServicesUtil.getErrorDialog(
                    statusCode, this, 0 /* request code not used */);
            if (dialog != null)
            	dialog.show();
            return false;
        } else {
            Toast.makeText(this, R.string.unrecoverable_error, Toast.LENGTH_SHORT).show();
            return false;
        }   	
    }

    /** Attempt to get the user name. If the email address isn't known yet,
     * then call pickUserAccount() method so the user can pick an account.
     */
    private void getUsername() {
        if (mEmail == null) {
            pickUserAccount();
        } else {
            if (isDeviceOnline()) {
                TokenFetcherTask task = new TokenFetcherTask(LoginActivity.this, mEmail, Config.SCOPE);
                task.execute();
            } else {
                Toast.makeText(this, "No network connection available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Starts an activity in Google Play Services so the user can pick an account */
    private void pickUserAccount() {
        String[] accountTypes = new String[]{"com.google"};
        Intent intent = AccountPicker.newChooseAccountIntent(null, null,
                accountTypes, false, null, null, null, null);
        startActivityForResult(intent, REQUEST_CODE_PICK_ACCOUNT);
    }

    /** Called by button in the layout */
	public void captureBackgroundVideo(View view) {
		activityType = Helpers.ActivityType.RECORD_VIDEO;
		if (isServiceRunning(BackgroundVideoRecorder.class)) {
			Helpers.showAlert(this, "Duplicate Video Recorder Alert",
					"Video is already being recorded in background. Please stop recording before attempting to log in again");
			return;
		}
		if (isGooglePlayServicesConnected())
			getUsername();
	}

    /** Called by button in the layout */
    public void AddNewBeacons(View view) {
    	activityType = Helpers.ActivityType.ADD_NEW_BEACONS;
    	
    	checkBluetooth();
    }

    /** Called by button in the layout */
    public void ModifyBeacons(View view) {
    	activityType = Helpers.ActivityType.MODIFY_BEACONS;
    	
    	checkBluetooth();
    }

    /** Called by button in the layout */
    public void monitorBluetooth(View view) {
    	activityType = Helpers.ActivityType.FOREGROUND_BLUETOOTH_MONITOR;
    	
    	checkBluetooth();
    }

    /** Called by button in the layout */
    public void monitorBluetoothService(View view) {
    	activityType = Helpers.ActivityType.BACKGROUND_BLUETOOTH_MONITOR;
    	
		if (isServiceRunning(BackgroundVideoRecorder.class)) {
			Helpers.showAlert(this, "Duplicate Bluetooth Monitor Service Alert",
					"Helios is already monitoring beacons in background. Please stop monitoring before attempting to log in again");
			return;
		}
    	checkBluetooth();
    }

    private void checkBluetooth() {
		// checks if Bluetooth is available and enabled
		// requests Bluetooth permission if it not enabled
		// gets a username if it is enabled
		
		BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    	if (mBluetoothAdapter == null) {
    	    // Device does not support Bluetooth
    		Helpers.showAlert(this, "Bluetooth Adapter Error", "Device does not have a working Bluetooth adapter");
    		return;
    	}
    	
	    if (!mBluetoothAdapter.isEnabled()) {
	        // Bluetooth is not enabled
			final Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(intent, REQUEST_CODE_ENABLE_BLUETOOTH);
			return;
	    }
	    
	    // Bluetooth is enabled
    	if(isGooglePlayServicesConnected())
    		getUsername();
	}

    private boolean isServiceRunning(Class<?> serviceClass) {
    	/* from http://stackoverflow.com/a/5921190
    	 * used to check if BackgroundVideoRecorder Service is already running
    	 * when user tries to log in.
    	 */
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
        
    /** Called by button in the layout */
    public void LogoutUser(View view) {
    	if(isGooglePlayServicesConnected() && mToken != null)
    		new TokenClearerTask(this, mToken).execute();
    	Toast.makeText(this, "Logging out", Toast.LENGTH_SHORT).show();
    	finish();
    }


    /** Checks whether the device currently has a network connection */
    private boolean isDeviceOnline() {
        ConnectivityManager connMgr = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        if (networkInfo != null && networkInfo.isConnected()) {
            return true;
        }
        return false;
    }

    public void resetEmail(){
    	/*
    	 * called by TokenFetcherTask to reset username to null after 
    	 * successfully retrieving token. This ensures that the user has to explicitly 
    	 * log in each time.  
    	 */
    	mEmail = null;
    }
    public void setToken(String token){
    	// called by TokenFetcherTask to set the token after it has been retrieved
    	mToken = token;
    }
    
    /**
     * This method is a hook for background threads and async tasks that need to update the UI.
     * It does this by launching a runnable under the UI thread.
     */
    public void show(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mOut.setText(message);
            }
        });
    }

    public void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_SHORT).show();        	    			            	
            }
        });
    }

    /**
     * This method is a hook for background threads and async tasks that need to provide the
     * user a response UI when an exception occurs.
     */
    public void handleException(final Exception e) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (e instanceof GooglePlayServicesAvailabilityException) {
                    // The Google Play services APK is old, disabled, or not present.
                    // Show a dialog created by Google Play services that allows
                    // the user to update the APK
                    int statusCode = ((GooglePlayServicesAvailabilityException)e)
                            .getConnectionStatusCode();
                    Dialog dialog = GooglePlayServicesUtil.getErrorDialog(statusCode,
                            LoginActivity.this,
                            REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR);
                    dialog.show();
                } else if (e instanceof UserRecoverableAuthException) {
                    // Unable to authenticate, such as when the user has not yet granted
                    // the app access to the account, but the user can fix this.
                    // Forward the user to an activity in Google Play services.
                    Intent intent = ((UserRecoverableAuthException)e).getIntent();
                    startActivityForResult(intent,
                            REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR);
                }
            }
        });
    }    
    
    public Helpers.ActivityType getActivityType(){
    	return activityType;
    }
}
